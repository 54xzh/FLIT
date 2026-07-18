package me.rerere.rikkahub.data.ai.mcp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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

    fun forget(configId: Uuid) {
        authorizationJobs.remove(configId)?.cancel()
        refreshLocks.remove(configId)
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
            }.getOrElse {
                Log.w(TAG, "Token refresh failed for ${config.commonOptions.name}: ${it.message}")
                config
            }
        }
    }

    suspend fun needsAuthorization(config: McpServerConfig, error: Throwable): Boolean {
        if (!looksUnauthorized(error)) return false
        if (config.commonOptions.oauth?.enabled == true) return true
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
        val callback = awaitCallbackAndLaunchBrowser(context, authorizationUrl, state)
            ?: error("OAuth 授权超时")
        callback.error?.let { error("授权失败: $it") }
        val code = callback.code ?: error("授权失败: 未返回授权码")

        val token = oauthClient.exchangeCode(
            tokenEndpoint = tokenEndpoint,
            clientId = clientId,
            clientSecret = clientSecret,
            tokenEndpointAuthMethod = tokenEndpointAuthMethod,
            code = code,
            codeVerifier = pkce.verifier,
            redirectUri = MCP_OAUTH_REDIRECT_URI,
            resource = resource,
        )
        val authorizedOAuth = pendingOAuth.copy(
            scope = token.scope ?: scope,
            accessToken = token.accessToken,
            refreshToken = token.refreshToken,
            expiresAt = computeExpiry(token.expiresIn),
        )
        if (!replaceOAuthStateIfCurrent(config.id, serverUrl, pendingOAuth, authorizedOAuth)) {
            throw McpOAuthConfigChangedException(config.connectionKey())
        }
    }

    private suspend fun awaitCallbackAndLaunchBrowser(
        context: Context,
        authorizationUrl: String,
        state: String,
    ): AppEvent.McpOAuthCallback? = coroutineScope {
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
        subscribed.await() // 确保订阅已注册
        withContext(Dispatchers.Main) {
            launchOAuthAuthorization(context, authorizationUrl)
        }
        callback.await()
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
