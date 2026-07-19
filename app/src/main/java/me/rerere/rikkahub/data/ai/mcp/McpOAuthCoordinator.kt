package me.rerere.rikkahub.data.ai.mcp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private const val TAG = "McpOAuthCoordinator"
private const val TOKEN_REFRESH_LEEWAY_MS = 60_000L
private val OAUTH_CALLBACK_TIMEOUT = 5.minutes

private class McpOAuthConfigChangedException(
    val expectedConnectionKey: McpConnectionKey,
) : IllegalStateException("MCP 配置已在授权期间发生变化")

/**
 * 负责 MCP OAuth 的授权、令牌刷新与持久化。
 *
 * 连接生命周期由配置流的消费者管理；令牌持久化后，配置变化会自然触发连接替换。
 */
internal class McpOAuthCoordinator(
    private val settingsStore: SettingsStore,
    private val appScope: AppScope,
    private val appEventBus: AppEventBus,
    private val oauthClient: McpOAuthClient,
    private val updateStatus: (Uuid, McpStatus) -> Unit,
) {
    private val authorizationJobs = ConcurrentHashMap<Uuid, Job>()
    private val refreshLocks = ConcurrentHashMap<Uuid, Mutex>()

    fun startAuthorization(config: McpServerConfig, context: Context) {
        authorizationJobs.remove(config.id)?.cancel()
        val job = appScope.launch {
            updateStatus(config.id, McpStatus.Authorizing)
            try {
                authorize(config, context.applicationContext)
            } catch (e: McpOAuthConfigChangedException) {
                val current = settingsStore.settingsFlow.value.mcpServers.find { it.id == config.id }
                if (current?.connectionKey() == e.expectedConnectionKey) {
                    updateStatus(config.id, McpStatus.NeedsAuthorization)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "OAuth authorization failed for ${config.commonOptions.name}", e)
                updateStatus(
                    config.id,
                    McpStatus.Error.from(
                        throwable = e,
                        fallbackMessage = "OAuth authorization failed",
                        canRetryAuthorization = true,
                    ),
                )
            }
        }
        authorizationJobs[config.id] = job
        job.invokeOnCompletion { authorizationJobs.remove(config.id, job) }
    }

    fun cancelAuthorization(configId: Uuid) {
        authorizationJobs.remove(configId)?.cancel()
        updateStatus(configId, McpStatus.NeedsAuthorization)
    }

    /**
     * 进程启动时恢复所有进行中的授权。对每条未超时且配置仍匹配的待授权记录，启动一个
     * 与正常授权共享任务登记/取消机制的恢复任务：先查落盘回调（覆盖回调先于恢复到达的
     * 时序），再订阅事件继续等待。
     *
     * 在 [McpManager] 初始化时调用。不要求先于 deep link 到达——落盘回调兜底。
     */
    fun resumePendingAuthorization() {
        appScope.launch {
            val pending = settingsStore.readPendingMcpAuthorizations()
            if (pending.isEmpty()) return@launch
            pending.values.forEach { record -> launchResumeFor(record) }
        }
    }

    private fun launchResumeFor(pending: McpPendingAuthorization) {
        // 已有进行中的授权任务（正常路径）则跳过，避免重复。
        if (authorizationJobs[pending.configId]?.isActive == true) return
        val job = appScope.launch {
            updateStatus(pending.configId, McpStatus.Authorizing)
            try {
                val now = System.currentTimeMillis()
                val elapsed = now - pending.startedAt
                if (elapsed < 0 || elapsed >= OAUTH_CALLBACK_TIMEOUT.inWholeMilliseconds) {
                    // 已超时或时钟异常，按 NeedsAuthorization 提示重新授权。
                    updateStatus(pending.configId, McpStatus.NeedsAuthorization)
                    return@launch
                }
                // 配置已删除或 URL 已变，无需恢复。
                val current = settingsStore.settingsFlow.value.mcpServers.find { it.id == pending.configId }
                if (current == null || current.serverUrl != pending.serverUrl) {
                    updateStatus(pending.configId, McpStatus.NeedsAuthorization)
                    return@launch
                }
                val remaining = OAUTH_CALLBACK_TIMEOUT.inWholeMilliseconds - elapsed
                val callback = awaitCallback(remaining, pending.state)
                completeAuthorizationWithCallback(pending, callback)
            } catch (e: McpOAuthConfigChangedException) {
                updateStatus(pending.configId, McpStatus.NeedsAuthorization)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Resumed OAuth authorization failed for ${pending.configId}", e)
                updateStatus(
                    pending.configId,
                    McpStatus.Error.from(
                        throwable = e,
                        fallbackMessage = "OAuth authorization failed",
                        canRetryAuthorization = true,
                    ),
                )
            } finally {
                // 恢复路径同样在 NonCancellable 中按 state 比较清理自己的记录与落盘回调。
                withContext(NonCancellable) {
                    settingsStore.clearPendingMcpAuthorizationIfStateMatches(
                        pending.configId, pending.state,
                    )
                    settingsStore.writePendingMcpOAuthCallback(pending.state, null)
                }
            }
        }
        authorizationJobs[pending.configId] = job
        job.invokeOnCompletion { authorizationJobs.remove(pending.configId, job) }
    }

    fun forget(configId: Uuid) {
        authorizationJobs.remove(configId)?.cancel()
        // 保留 refreshLocks 中已有的 Mutex：同一 serverId 始终复用同一把锁，
        // 让禁用-重新启用之间、或在途刷新与新刷新自然串行，避免新 Mutex 与旧 Mutex
        // 不互斥导致并发刷新同一个 refresh_token。serverId 为 UUID 不复用，
        // 条目数量受限于 server 总数，不随时间无限增长。
    }

    suspend fun clearAuthorization(config: McpServerConfig): McpServerConfig {
        persistOAuthState(config.id, null)
        return settingsStore.settingsFlow.value.mcpServers.find { it.id == config.id }
            ?: config.clone(commonOptions = config.commonOptions.copy(oauth = null))
    }

    /**
     * 按 serverId 串行刷新。获得锁后重新读取配置，避免并发工具调用重复使用同一个 refresh token。
     */
    suspend fun ensureFreshToken(configInput: McpServerConfig): McpServerConfig {
        val lock = refreshLocks.computeIfAbsent(configInput.id) { Mutex() }
        return lock.withLock {
            val config = settingsStore.settingsFlow.value.mcpServers.find { it.id == configInput.id }
                ?: configInput
            val oauth = config.commonOptions.oauth ?: return@withLock config
            if (!oauth.enabled || oauth.refreshToken.isNullOrBlank()) return@withLock config
            val resource = McpOAuthClient.canonicalResource(config.serverUrl)
            if (oauth.resource != resource) return@withLock config

            val expired = oauth.expiresAt > 0 &&
                System.currentTimeMillis() >= oauth.expiresAt - TOKEN_REFRESH_LEEWAY_MS
            if (!oauth.accessToken.isNullOrBlank() && !expired) return@withLock config

            val tokenEndpoint = oauth.tokenEndpoint ?: return@withLock config
            val clientId = oauth.clientId ?: return@withLock config
            runCatching {
                val token = oauthClient.refreshToken(
                    tokenEndpoint = tokenEndpoint,
                    clientId = clientId,
                    clientSecret = oauth.clientSecret,
                    tokenEndpointAuthMethod = oauth.tokenEndpointAuthMethod
                        ?: McpOAuthClient.TOKEN_ENDPOINT_AUTH_NONE,
                    refreshToken = oauth.refreshToken,
                    resource = resource,
                    scope = oauth.scope,
                )
                val updated = oauth.copy(
                    accessToken = token.accessToken,
                    refreshToken = token.refreshToken ?: oauth.refreshToken,
                    expiresAt = computeExpiry(token.expiresIn),
                    scope = token.scope ?: oauth.scope,
                )
                if (replaceOAuthStateIfCurrent(config.id, config.serverUrl, oauth, updated)) {
                    config.clone(commonOptions = config.commonOptions.copy(oauth = updated))
                } else {
                    settingsStore.settingsFlow.value.mcpServers.find { it.id == config.id } ?: config
                }
            }.getOrElse { error ->
                Log.w(TAG, "Token refresh failed for ${config.commonOptions.name}: ${error.message}")
                when (tokenErrorKind(error)) {
                    TokenErrorKind.InvalidGrant -> {
                        // refresh_token 已被服务端吊销或失效，保留无意义、重试只会反复打满
                        // token 端点。清空令牌并切到 NeedsAuthorization，让用户重新走授权；
                        // 保留 clientId/secret/端点，重新授权可直接复用动态注册结果。
                        val cleared = oauth.copy(
                            accessToken = null,
                            refreshToken = null,
                            expiresAt = 0L,
                        )
                        if (replaceOAuthStateIfCurrent(config.id, config.serverUrl, oauth, cleared)) {
                            updateStatus(config.id, McpStatus.NeedsAuthorization)
                            config.clone(commonOptions = config.commonOptions.copy(oauth = cleared))
                        } else {
                            settingsStore.settingsFlow.value.mcpServers.find { it.id == config.id }
                                ?: config
                        }
                    }
                    TokenErrorKind.InvalidClient -> {
                        // client_secret 失效或认证失败。清令牌、切到 NeedsAuthorization 提示用户，
                        // 但不清 clientId/secret：清掉后重新授权会走动态注册拿一套全新凭证，
                        // 而用户手动填的静态客户端凭证被清掉就得重新输入。保留它们，
                        // 让用户重新授权时由授权服务器再次校验（仍失败则提示用户检查配置）。
                        val cleared = oauth.copy(
                            accessToken = null,
                            refreshToken = null,
                            expiresAt = 0L,
                        )
                        if (replaceOAuthStateIfCurrent(config.id, config.serverUrl, oauth, cleared)) {
                            updateStatus(config.id, McpStatus.NeedsAuthorization)
                            config.clone(commonOptions = config.commonOptions.copy(oauth = cleared))
                        } else {
                            settingsStore.settingsFlow.value.mcpServers.find { it.id == config.id }
                                ?: config
                        }
                    }
                    TokenErrorKind.Other -> config // 网络/超时/5xx 等临时性错误，保留 token 等下次重试。
                }
            }
        }
    }

    private enum class TokenErrorKind { InvalidGrant, InvalidClient, Other }

    /**
     * 按 OAuth 2.0 标准 token 端点 error 字段分类，不依赖错误文案字符串。
     */
    private fun tokenErrorKind(error: Throwable): TokenErrorKind {
        val tokenError = (error as? McpOAuthClient.TokenRequestException)?.error
        return when (tokenError) {
            "invalid_grant" -> TokenErrorKind.InvalidGrant
            "invalid_client" -> TokenErrorKind.InvalidClient
            else -> TokenErrorKind.Other
        }
    }

    suspend fun needsAuthorization(config: McpServerConfig, error: Throwable): Boolean {
        val oauth = config.commonOptions.oauth
        // OAuth 已启用但令牌被清空（invalid_grant/invalid_client 刷新失败后），无需依赖
        // 错误文案判断，直接认定需要授权，避免 transport 把 401 文案不含关键字时
        // 误判为普通连接错误而覆盖掉 NeedsAuthorization 状态。
        if (oauth?.enabled == true &&
            oauth.accessToken.isNullOrBlank() &&
            oauth.refreshToken.isNullOrBlank()
        ) {
            return true
        }
        if (!looksUnauthorized(error)) return false
        if (oauth?.enabled == true) return true
        if (config.commonOptions.headers.any { it.first.equals("Authorization", ignoreCase = true) }) {
            return false
        }
        return runCatching { oauthClient.discoverProtectedResource(config.serverUrl) }
            .onFailure {
                Log.i(TAG, "OAuth probe failed for ${config.commonOptions.name}: ${it.message}")
            }
            .isSuccess
    }

    private suspend fun authorize(config: McpServerConfig, context: Context) = withContext(Dispatchers.IO) {
        val serverUrl = config.serverUrl
        require(serverUrl.isNotBlank()) { "Server URL 为空，无法授权" }

        val protectedResource = oauthClient.discoverProtectedResource(serverUrl)
        val issuer = protectedResource.authorizationServers.firstOrNull()
            ?: error("受保护资源未声明授权服务器")
        val metadata = oauthClient.discoverAuthorizationServer(issuer)
        val authorizationEndpoint = metadata.authorizationEndpoint
            ?: error("授权服务器缺少 authorization_endpoint")
        val tokenEndpoint = metadata.tokenEndpoint
            ?: error("授权服务器缺少 token_endpoint")
        val resource = McpOAuthClient.canonicalResource(serverUrl)
        val storedOAuth = config.commonOptions.oauth
        val reusableOAuth = storedOAuth?.takeIf { oauth ->
            val matchesCurrentServer = oauth.resource == resource && oauth.issuer == issuer
            val isUnboundManualConfig = oauth.resource == null &&
                oauth.issuer == null &&
                oauth.authorizationEndpoint == null &&
                oauth.tokenEndpoint == null &&
                oauth.registrationEndpoint == null &&
                oauth.accessToken.isNullOrBlank() &&
                oauth.refreshToken.isNullOrBlank()
            matchesCurrentServer || isUnboundManualConfig
        }
        val scope = reusableOAuth?.scope
            ?: protectedResource.scopesSupported?.joinToString(" ")
            ?: metadata.scopesSupported?.joinToString(" ")

        var clientId = reusableOAuth?.clientId
        var clientSecret = reusableOAuth?.clientSecret
        var tokenEndpointAuthMethod = reusableOAuth?.tokenEndpointAuthMethod
        if (clientId.isNullOrBlank()) {
            val registrationEndpoint = metadata.registrationEndpoint
                ?: error("授权服务器不支持动态注册，且未预配置 client_id")
            val requestedAuthMethod = McpOAuthClient.selectDynamicRegistrationAuthMethod(
                metadata.tokenEndpointAuthMethodsSupported
            )
            val registration = oauthClient.registerClient(
                registrationEndpoint = registrationEndpoint,
                clientName = config.commonOptions.name,
                redirectUri = MCP_OAUTH_REDIRECT_URI,
                scope = scope,
                tokenEndpointAuthMethod = requestedAuthMethod,
            )
            clientId = registration.clientId
            clientSecret = registration.clientSecret
            tokenEndpointAuthMethod = registration.tokenEndpointAuthMethod ?: requestedAuthMethod
        }
        tokenEndpointAuthMethod = McpOAuthClient.selectTokenEndpointAuthMethod(
            clientSecret = clientSecret,
            registeredMethod = tokenEndpointAuthMethod,
            supportedMethods = metadata.tokenEndpointAuthMethodsSupported,
        )

        val pkce = oauthClient.generatePkce()
        val state = oauthClient.generateState()
        val pendingOAuth = (reusableOAuth ?: McpOAuthState()).copy(
            enabled = true,
            resource = resource,
            issuer = issuer,
            clientId = clientId,
            clientSecret = clientSecret,
            authorizationEndpoint = authorizationEndpoint,
            tokenEndpoint = tokenEndpoint,
            tokenEndpointAuthMethod = tokenEndpointAuthMethod,
            registrationEndpoint = metadata.registrationEndpoint,
            scope = scope,
        )
        if (!replaceOAuthStateIfCurrent(config.id, serverUrl, storedOAuth, pendingOAuth)) {
            throw McpOAuthConfigChangedException(config.connectionKey())
        }

        val authorizationUrl = oauthClient.buildAuthorizationUrl(
            authorizationEndpoint = authorizationEndpoint,
            clientId = clientId,
            redirectUri = MCP_OAUTH_REDIRECT_URI,
            pkce = pkce,
            state = state,
            scope = scope,
            resource = resource,
        )
        // 持久化进行中的授权记录：若授权期间进程被系统回收，浏览器返回的 deep link
        // 会因无订阅者丢失；进程重启后靠这份记录恢复等待。这里在打开浏览器前写入。
        val pending = McpPendingAuthorization(
            configId = config.id,
            serverUrl = serverUrl,
            state = state,
            pkceVerifier = pkce.verifier,
            pendingOAuth = pendingOAuth,
            startedAt = System.currentTimeMillis(),
        )
        settingsStore.writePendingMcpAuthorization(config.id, pending)
        try {
            val callback = awaitCallbackAndLaunchBrowser(context, authorizationUrl, state)
            completeAuthorizationWithCallback(pending, callback)
        } finally {
            // 取消后协程进入 finally，普通挂起写可能被略过；用 NonCancellable 保证清理执行。
            // 按 state 比较：仅当记录仍属于本次授权时才删，避免误删"取消后立即重新授权"
            // 时新任务已写入的同 configId 记录。
            withContext(NonCancellable) {
                settingsStore.clearPendingMcpAuthorizationIfStateMatches(config.id, state)
                settingsStore.writePendingMcpOAuthCallback(state, null)
            }
        }
    }

    /**
     * 用回调结果完成授权：校验 state、交换令牌、写回 authorized OAuth。
     * 进程重建后的恢复路径与正常路径共用此逻辑。
     *
     * 抛出异常表示授权失败（超时、被拒绝、token 交换失败、配置已变化等），由调用方处理状态。
     */
    private suspend fun completeAuthorizationWithCallback(
        pending: McpPendingAuthorization,
        callback: AppEvent.McpOAuthCallback?,
    ) {
        if (callback == null) error("OAuth 授权超时")
        callback.error?.let { error("授权失败: $it") }
        val code = callback.code ?: error("授权失败: 未返回授权码")
        // state 由 awaitCallbackAndLaunchBrowser 已过滤；恢复路径同样先匹配 state 再进入此函数。
        // 这里再做一次防御性校验。
        if (callback.state != pending.state) error("授权失败: state 不匹配")

        val pendingOAuth = pending.pendingOAuth
        val token = oauthClient.exchangeCode(
            tokenEndpoint = pendingOAuth.tokenEndpoint
                ?: error("授权失败: 缺少 token 端点"),
            clientId = pendingOAuth.clientId
                ?: error("授权失败: 缺少 client_id"),
            clientSecret = pendingOAuth.clientSecret,
            tokenEndpointAuthMethod = pendingOAuth.tokenEndpointAuthMethod
                ?: McpOAuthClient.TOKEN_ENDPOINT_AUTH_NONE,
            code = code,
            codeVerifier = pending.pkceVerifier,
            redirectUri = MCP_OAUTH_REDIRECT_URI,
            resource = pendingOAuth.resource ?: pending.serverUrl,
        )
        val authorizedOAuth = pendingOAuth.copy(
            scope = token.scope ?: pendingOAuth.scope,
            accessToken = token.accessToken,
            refreshToken = token.refreshToken,
            expiresAt = computeExpiry(token.expiresIn),
        )
        // 恢复路径下 server 可能已被用户改 URL：用记录的 serverUrl 作期望值，
        // 与当前配置不匹配则视为配置已变化，放弃写回。
        if (!replaceOAuthStateIfCurrent(pending.configId, pending.serverUrl, pendingOAuth, authorizedOAuth)) {
            throw McpOAuthConfigChangedException(
                settingsStore.settingsFlow.value.mcpServers
                    .find { it.id == pending.configId }?.connectionKey()
                    ?: McpConnectionKey("", "", "", emptyList()),
            )
        }
    }

    private suspend fun awaitCallbackAndLaunchBrowser(
        context: Context,
        authorizationUrl: String,
        state: String,
    ): AppEvent.McpOAuthCallback? = coroutineScope {
        // 先查落盘回调：若回调在订阅前已到达（被 Activity 落盘），直接消费，无需开浏览器等待。
        consumePendingCallback(state)?.let { return@coroutineScope it }
        // 先建立回调订阅，再打开浏览器，避免快速回调在订阅生效前 emit 而丢失
        // (AppEventBus 的 SharedFlow replay=0，无订阅者时的事件不会补发)
        val subscribed = CompletableDeferred<Unit>()
        val callback = async {
            withTimeoutOrNull(OAUTH_CALLBACK_TIMEOUT) {
                appEventBus.events
                    .onSubscription { subscribed.complete(Unit) }
                    .filterIsInstance<AppEvent.McpOAuthCallback>()
                    // 只接受 state 对得上的回调：正常授权、用户拒绝(error+state)、
                    // 以及外部打断都会带 state(RFC 6749 §4.1.2.1 要求授权服务器在错误响应里回传 state)。
                    // 不再单独认"无 state 的 error"分支——那是全局广播，会让并发的其它授权被误中断，
                    // 也给恶意 app 留了个发个 error 就打断授权的口子。
                    // 服务器真的不带 state 的拒绝极少见，会等满超时再提示，可接受。
                    .first { it.state == state }
            }
        }
        // 订阅生效后再查一次落盘，覆盖"订阅与回调落盘"之间的窗口。
        // 命中后必须取消并等待事件 async 结束：否则 coroutineScope 会等子协程到超时。
        subscribed.await()
        val persisted = consumePendingCallback(state)
        if (persisted != null) {
            callback.cancelAndJoin()
            return@coroutineScope persisted
        }
        withContext(Dispatchers.Main) {
            launchOAuthAuthorization(context, authorizationUrl)
        }
        callback.await()
    }

    /** 只等待回调（不打开浏览器），用于进程重建后的恢复路径。同样优先消费落盘回调。 */
    private suspend fun awaitCallback(
        timeoutMs: Long,
        state: String,
    ): AppEvent.McpOAuthCallback? = coroutineScope {
        consumePendingCallback(state)?.let { return@coroutineScope it }
        val subscribed = CompletableDeferred<Unit>()
        val callback = async {
            withTimeoutOrNull(timeoutMs.coerceAtLeast(0L)) {
                appEventBus.events
                    .onSubscription { subscribed.complete(Unit) }
                    .filterIsInstance<AppEvent.McpOAuthCallback>()
                    .first { it.state == state }
            }
        }
        subscribed.await()
        val persisted = consumePendingCallback(state)
        if (persisted != null) {
            callback.cancelAndJoin()
            return@coroutineScope persisted
        }
        callback.await()
    }

    /**
     * 读取并删除落盘回调（匹配 state）。回调 Activity 在无订阅者时会把回调落盘，
     * 这里消费后立即清掉，避免被重复处理。返回 AppEvent 形式以与事件路径统一后续处理。
     */
    private suspend fun consumePendingCallback(state: String): AppEvent.McpOAuthCallback? {
        val record = settingsStore.readPendingMcpOAuthCallbacks()[state] ?: return null
        settingsStore.writePendingMcpOAuthCallback(state, null)
        return AppEvent.McpOAuthCallback(
            state = record.state,
            code = record.code,
            error = record.error,
        )
    }

    private suspend fun persistOAuthState(configId: Uuid, oauth: McpOAuthState?) {
        settingsStore.update { old ->
            old.copy(
                mcpServers = old.mcpServers.map { server ->
                    if (server.id != configId) server
                    else server.clone(commonOptions = server.commonOptions.copy(oauth = oauth))
                }
            )
        }
    }

    private suspend fun replaceOAuthStateIfCurrent(
        configId: Uuid,
        expectedServerUrl: String,
        expectedOAuth: McpOAuthState?,
        oauth: McpOAuthState,
    ): Boolean {
        var replaced = false
        settingsStore.update { old ->
            val current = old.mcpServers.find { it.id == configId }
            if (current == null ||
                current.serverUrl != expectedServerUrl ||
                current.commonOptions.oauth != expectedOAuth
            ) {
                return@update old
            }
            replaced = true
            old.copy(
                mcpServers = old.mcpServers.map { server ->
                    if (server.id != configId) server
                    else server.clone(commonOptions = server.commonOptions.copy(oauth = oauth))
                }
            )
        }
        return replaced
    }

    private fun computeExpiry(expiresIn: Long?): Long =
        if (expiresIn != null && expiresIn > 0) {
            System.currentTimeMillis() + expiresIn * 1000
        } else {
            0L
        }

    private fun looksUnauthorized(error: Throwable): Boolean {
        val message = generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .lowercase()
        return message.contains("401") ||
            message.contains("unauthorized") ||
            message.contains("invalid_token") ||
            message.contains("invalid access token") ||
            message.contains("missing or invalid")
    }
}
