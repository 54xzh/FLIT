package me.rerere.rikkahub.data.ai.mcp

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getMcpToolCallTimeoutSeconds
import me.rerere.rikkahub.data.db.entity.SandboxRootfsStatus
import me.rerere.rikkahub.data.db.entity.WorkspaceType
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.workspace.SandboxProcessContext
import me.rerere.rikkahub.workspace.SandboxProcessCoordinator
import me.rerere.rikkahub.workspace.SandboxProcessLauncher
import me.rerere.rikkahub.workspace.SandboxProcessOwner
import me.rerere.rikkahub.workspace.SandboxRawProcess
import me.rerere.rikkahub.workspace.SandboxWorkspaceManager
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private const val STDERR_TAIL_LIMIT = 16 * 1024
private const val STDIO_IDLE_TIMEOUT_MILLIS = 10 * 60 * 1000L
private const val PROCESS_OWNER_ID = "stdio-mcp"
private const val STDERR_DRAIN_TIMEOUT_MILLIS = 1_000L

private data class StdioSessionKey(val serverId: Uuid, val workspaceId: String)

internal class StderrTail(private val maxChars: Int = STDERR_TAIL_LIMIT) {
    private val text = StringBuilder()

    @Synchronized
    fun append(value: String) {
        text.append(value)
        if (text.length > maxChars) text.delete(0, text.length - maxChars)
    }

    @Synchronized
    fun value(): String = text.toString()
}

internal class StdioCallActivity(private val idleTimeoutMillis: Long = STDIO_IDLE_TIMEOUT_MILLIS) {
    var activeCalls: Int = 0
        private set
    var lastUsedAt: Long = 0L
        private set

    fun begin(now: Long) {
        activeCalls++
        lastUsedAt = now
    }

    fun finish(now: Long) {
        activeCalls = (activeCalls - 1).coerceAtLeast(0)
        lastUsedAt = now
    }

    fun canClose(now: Long): Boolean = activeCalls == 0 && now - lastUsedAt >= idleTimeoutMillis
}

internal suspend fun <T> withSingleStdioInitializationRetry(
    cleanup: suspend () -> Unit,
    block: suspend (attempt: Int) -> T,
): T {
    var lastError: Throwable? = null
    repeat(2) { attempt ->
        try {
            return block(attempt)
        } catch (error: CancellationException) {
            cleanup()
            if (error is TimeoutCancellationException && attempt == 0) {
                lastError = error
            } else {
                throw error
            }
        } catch (error: Throwable) {
            cleanup()
            lastError = error
            if (attempt == 1) throw error
        }
    }
    throw lastError ?: IllegalStateException("STDIO MCP initialization failed")
}

/** Side-effecting tools/call requests are intentionally executed exactly once. */
internal suspend fun <T> callStdioToolOnce(block: suspend () -> T): T = block()

internal fun enabledStdioConfigs(configs: List<McpServerConfig>): Map<Uuid, McpServerConfig.StdioServer> =
    configs.filterIsInstance<McpServerConfig.StdioServer>()
        .filter { it.commonOptions.enable }
        .associateBy { it.id }

internal suspend fun drainStderrReader(job: Job?, timeoutMillis: Long = STDERR_DRAIN_TIMEOUT_MILLIS) {
    if (job == null) return
    withTimeoutOrNull(timeoutMillis) { job.join() }
    if (!job.isCompleted) job.cancelAndJoin()
}

private class StdioSession(initialConfig: McpServerConfig.StdioServer) {
    @Volatile
    var config = initialConfig
    val lifecycleMutex = Mutex()
    var client: Client? = null
    var process: SandboxRawProcess? = null
    var stderrJob: Job? = null
    var stderrTail = StderrTail()
    var idleJob: Job? = null
    val activity = StdioCallActivity()
}

internal class StdioMcpSessionRegistry(
    private val settingsStore: SettingsStore,
    private val appScope: AppScope,
    private val workspaceRepository: WorkspaceRepository,
    private val sandboxWorkspaceManager: SandboxWorkspaceManager,
    private val processLauncher: SandboxProcessLauncher,
    private val processCoordinator: SandboxProcessCoordinator,
    private val statusStore: McpStatusStore,
) : SandboxProcessOwner {
    private val sessions = ConcurrentHashMap<StdioSessionKey, StdioSession>()
    private val temporarySessions = ConcurrentHashMap.newKeySet<StdioSession>()

    init {
        processCoordinator.register(PROCESS_OWNER_ID, this)
    }

    fun getClient(configId: Uuid): Client? = sessions.entries
        .firstOrNull { it.key.serverId == configId }
        ?.value
        ?.client

    fun reconcile(configs: List<McpServerConfig>) {
        val stdioConfigs = enabledStdioConfigs(configs)
        stdioConfigs.values.forEach { config ->
            if (config.commonOptions.tools.isNotEmpty() && statusStore.status.value[config.id] == null) {
                statusStore.update(config.id, McpStatus.Ready)
            }
        }
        sessions.entries.toList().forEach { (key, session) ->
            val latest = stdioConfigs[key.serverId]
            if (latest == null || latest.stdioConnectionKey() != session.config.stdioConnectionKey()) {
                sessions.remove(key, session)
                statusStore.update(key.serverId, McpStatus.Idle)
                appScope.launch { closeSession(session) }
            } else {
                session.config = latest
            }
        }
        (statusStore.status.value.keys - configs.mapTo(mutableSetOf()) { it.id }).forEach(statusStore::remove)
    }

    suspend fun testAndSync(configInput: McpServerConfig.StdioServer) {
        val config = currentConfig(configInput.id) ?: return
        val session = StdioSession(config)
        temporarySessions.add(session)
        var terminalError: Throwable? = null
        try {
            validateWorkspace(config)
            val client = connectWithInitializationRetry(session, config)
            val serverTools = withTimeout(config.startupTimeoutSeconds.seconds) {
                client.listTools()?.tools.orEmpty()
            }
            if (updateCachedTools(config, serverTools)) {
                updateStatusIfCurrent(config, McpStatus.Ready)
            }
        } catch (error: TimeoutCancellationException) {
            terminalError = error
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            terminalError = error
            throw error
        } finally {
            temporarySessions.remove(session)
            closeSession(session)
            terminalError?.let { error ->
                updateErrorIfCurrent(
                    config,
                    stdioErrorStatus(error, session.stderrTail.value(), config),
                )
            }
        }
    }

    suspend fun callTool(
        configInput: McpServerConfig.StdioServer,
        expectedRuntimeScope: McpRuntimeScope,
        toolName: String,
        args: JsonObject,
    ): CallToolResult {
        val config = currentConfig(configInput.id)
            ?: throw McpClientUnavailableException("STDIO MCP configuration no longer exists")
        if (config.runtimeScope() != expectedRuntimeScope) {
            throw McpClientUnavailableException("STDIO MCP runtime scope changed before invocation")
        }
        try {
            validateWorkspace(config)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            updateErrorIfCurrent(config, stdioErrorStatus(error, "", config))
            throw error
        }
        val session = sessionFor(config)
        val client = try {
            session.lifecycleMutex.withLock {
                session.idleJob?.cancel()
                session.idleJob = null
                val connected = session.client ?: connectWithInitializationRetryLocked(session, config)
                session.activity.begin(System.currentTimeMillis())
                connected
            }
        } catch (error: TimeoutCancellationException) {
            updateErrorIfCurrent(config, stdioErrorStatus(error, session.stderrTail.value(), config))
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            updateErrorIfCurrent(config, stdioErrorStatus(error, session.stderrTail.value(), config))
            throw error
        }

        var closeAfterCall = false
        var terminalError: Throwable? = null
        val timeoutSeconds = settingsStore.settingsFlow.value.getMcpToolCallTimeoutSeconds()
        try {
            return callStdioToolOnce {
                client.callTool(
                    request = CallToolRequest(
                        params = CallToolRequestParams(name = toolName, arguments = args),
                    ),
                    options = RequestOptions(timeout = timeoutSeconds.seconds),
                )
            }
        } catch (error: TimeoutCancellationException) {
            closeAfterCall = true
            terminalError = error
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // tools/call 绝不重放。会话是否仍可靠未知，交给下一次调用重新初始化。
            closeAfterCall = true
            terminalError = error
            throw error
        } finally {
            withContext(NonCancellable) {
                session.lifecycleMutex.withLock {
                    session.activity.finish(System.currentTimeMillis())
                    if (closeAfterCall) {
                        closeSessionLocked(session)
                    } else {
                        scheduleIdleCloseLocked(session)
                    }
                }
                terminalError?.let { error ->
                    updateErrorIfCurrent(
                        config,
                        stdioErrorStatus(
                            error = error,
                            stderr = session.stderrTail.value(),
                            config = config,
                            phase = StdioFailurePhase.TOOL_CALL,
                            timeoutSeconds = timeoutSeconds,
                        ),
                    )
                }
            }
        }
    }

    suspend fun syncAll(configs: List<McpServerConfig.StdioServer>) {
        configs.filter { it.commonOptions.enable }.forEach { config ->
            runCatching { testAndSync(config) }
        }
    }

    suspend fun remove(configId: Uuid) {
        sessions.entries.toList()
            .filter { it.key.serverId == configId }
            .forEach { (key, session) ->
                sessions.remove(key, session)
                closeSession(session)
                updateStatusIfCurrent(session.config, McpStatus.Ready)
            }
        statusStore.remove(configId)
    }

    override suspend fun stopWorkspace(workspaceId: String) {
        sessions.entries.toList()
            .filter { it.key.workspaceId == workspaceId }
            .forEach { (key, session) ->
                sessions.remove(key, session)
                closeSession(session)
                updateStatusIfCurrent(session.config, McpStatus.Ready)
            }
        temporarySessions.toList()
            .filter { it.config.workspaceId == workspaceId }
            .forEach { session ->
                closeSession(session)
                updateStatusIfCurrent(session.config, McpStatus.Ready)
            }
    }

    override suspend fun stopAll() {
        val detached = sessions.values.toList()
        sessions.clear()
        (detached + temporarySessions.toList()).distinct().forEach { session ->
            closeSession(session)
            updateStatusIfCurrent(session.config, McpStatus.Ready)
        }
    }

    private suspend fun currentConfig(id: Uuid): McpServerConfig.StdioServer? =
        settingsStore.settingsFlow.value.mcpServers
            .filterIsInstance<McpServerConfig.StdioServer>()
            .firstOrNull { it.id == id && it.commonOptions.enable && it.commonOptions.name.isNotBlank() }

    private suspend fun sessionFor(config: McpServerConfig.StdioServer): StdioSession {
        val key = StdioSessionKey(config.id, config.workspaceId)
        val existing = sessions[key]
        if (existing != null && existing.config.stdioConnectionKey() != config.stdioConnectionKey()) {
            if (sessions.remove(key, existing)) closeSession(existing)
        }
        return sessions.computeIfAbsent(key) { StdioSession(config) }
            .apply { this.config = config }
    }

    private suspend fun connectWithInitializationRetry(
        session: StdioSession,
        config: McpServerConfig.StdioServer,
    ): Client = session.lifecycleMutex.withLock {
        connectWithInitializationRetryLocked(session, config)
    }

    private suspend fun connectWithInitializationRetryLocked(
        session: StdioSession,
        config: McpServerConfig.StdioServer,
    ): Client {
        session.client?.takeIf { session.process?.isAlive == true }?.let { return it }
        return withSingleStdioInitializationRetry(
            cleanup = { withContext(NonCancellable) { closeSessionLocked(session) } },
        ) {
            withTimeout(config.startupTimeoutSeconds.seconds) {
                connectOnceLocked(session, config)
            }
        }
    }

    private suspend fun connectOnceLocked(
        session: StdioSession,
        config: McpServerConfig.StdioServer,
    ): Client = withContext(Dispatchers.IO) {
        validateWorkspace(config)
        updateStatusIfCurrent(config, McpStatus.Connecting)
        val bindMounts = workspaceRepository.getSandboxBindMounts(config.workspaceId)
        val process = processLauncher.start(
            SandboxProcessContext(
                workspaceId = config.workspaceId,
                command = config.command,
                args = config.args,
                workingDirectory = config.workingDirectory,
                environment = config.environment,
                filesDir = sandboxWorkspaceManager.filesDir(config.workspaceId),
                linuxDir = sandboxWorkspaceManager.linuxDir(config.workspaceId),
                tempDir = sandboxWorkspaceManager.tempDir(config.workspaceId),
                workspaceBindMounts = bindMounts,
            )
        )
        session.process = process
        val tail = StderrTail()
        session.stderrTail = tail
        val stderrJob = appScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(2048)
            while (true) {
                val count = process.stderr.read(buffer)
                if (count < 0) break
                if (count > 0) tail.append(String(buffer, 0, count, StandardCharsets.UTF_8))
            }
        }
        session.stderrJob = stderrJob
        val transport = StdioClientTransport(
            process.stdout.asSource().buffered(),
            process.stdin.asSink().buffered(),
        )
        val client = Client(Implementation(name = config.commonOptions.name, version = "1.0"))
        transport.onClose {
            appScope.launch {
                invalidateClosedProcess(
                    session,
                    process,
                ) { exitCode, stderr, phase ->
                    stdioProcessExitStatus(config, exitCode, stderr, phase)
                }
            }
        }
        transport.onError { error ->
            appScope.launch {
                invalidateClosedProcess(session, process) { _, stderr, phase ->
                    stdioErrorStatus(error, stderr, config, phase)
                }
            }
        }
        client.connect(transport)
        session.client = client
        session.config = config
        if (session.activity.lastUsedAt == 0L) {
            session.activity.begin(System.currentTimeMillis())
            session.activity.finish(System.currentTimeMillis())
        }
        updateStatusIfCurrent(config, McpStatus.Connected)
        client
    }

    private suspend fun invalidateClosedProcess(
        session: StdioSession,
        process: SandboxRawProcess,
        status: (exitCode: Int?, stderr: String, phase: StdioFailurePhase) -> McpStatus,
    ) {
        session.lifecycleMutex.withLock {
            if (session.process !== process) return
            val exitCode = process.exitCodeOrNull()
            val phase = if (session.client == null) {
                StdioFailurePhase.STARTUP
            } else {
                StdioFailurePhase.TOOL_CALL
            }
            closeSessionLocked(session)
            updateStatusIfCurrent(session.config, status(exitCode, session.stderrTail.value(), phase))
        }
    }

    private suspend fun validateWorkspace(config: McpServerConfig.StdioServer) {
        require(config.workspaceId.isNotBlank()) { "A sandbox workspace must be selected" }
        require(config.command.isNotBlank()) { "Command is required" }
        if (!processCoordinator.isStartAllowed(config.workspaceId)) {
            throw McpClientUnavailableException("Sandbox workspace maintenance is in progress")
        }
        val workspace = workspaceRepository.getById(config.workspaceId)
            ?: throw McpClientUnavailableException("Sandbox workspace is missing")
        if (workspace.type != WorkspaceType.SANDBOX) {
            throw McpClientUnavailableException("Selected workspace is not a sandbox")
        }
        val rootfsAvailable = withContext(Dispatchers.IO) {
            sandboxWorkspaceManager.hasRootfs(config.workspaceId)
        }
        if (workspace.sandboxStatus != SandboxRootfsStatus.READY || !rootfsAvailable) {
            throw McpClientUnavailableException("Sandbox Rootfs is not ready")
        }
    }

    private suspend fun updateCachedTools(
        config: McpServerConfig.StdioServer,
        serverTools: List<io.modelcontextprotocol.kotlin.sdk.types.Tool>,
    ): Boolean {
        var updated = false
        settingsStore.update { old ->
            old.copy(
                mcpServers = old.mcpServers.map { stored ->
                    if (stored !is McpServerConfig.StdioServer ||
                        stored.id != config.id ||
                        stored.stdioConnectionKey() != config.stdioConnectionKey()
                    ) {
                        stored
                    } else {
                        updated = true
                        stored.copy(
                            commonOptions = stored.commonOptions.copy(
                            tools = mergeMcpTools(stored.commonOptions.tools, serverTools),
                        )
                        )
                    }
                }
            )
        }
        return updated
    }

    private fun scheduleIdleCloseLocked(session: StdioSession) {
        if (session.activity.activeCalls != 0 || session.client == null) return
        session.idleJob?.cancel()
        session.idleJob = appScope.launch {
            delay(STDIO_IDLE_TIMEOUT_MILLIS)
            session.lifecycleMutex.withLock {
                if (session.activity.canClose(System.currentTimeMillis())) {
                    closeSessionLocked(session)
                    statusStore.update(session.config.id, McpStatus.Ready)
                }
            }
        }
    }

    private suspend fun closeSession(session: StdioSession) {
        withContext(NonCancellable) {
            session.lifecycleMutex.withLock { closeSessionLocked(session) }
        }
    }

    private suspend fun closeSessionLocked(session: StdioSession) {
        session.idleJob?.cancel()
        session.idleJob = null
        val client = session.client
        val process = session.process
        val stderrJob = session.stderrJob
        session.client = null
        session.process = null
        session.stderrJob = null
        runCatching { client?.close() }
        withContext(Dispatchers.IO) { runCatching { process?.close() } }
        drainStderrReader(stderrJob)
    }

    private fun updateStatusIfCurrent(config: McpServerConfig.StdioServer, status: McpStatus) {
        val current = settingsStore.settingsFlow.value.mcpServers
            .filterIsInstance<McpServerConfig.StdioServer>()
            .firstOrNull { it.id == config.id }
        if (current?.stdioConnectionKey() == config.stdioConnectionKey()) {
            statusStore.update(config.id, status)
        }
    }

    private fun updateErrorIfCurrent(config: McpServerConfig.StdioServer, status: McpStatus.Error) {
        if (processCoordinator.isStartAllowed(config.workspaceId)) {
            updateStatusIfCurrent(config, status)
        }
    }
}

internal enum class StdioFailureKind {
    COMMAND_NOT_FOUND,
    PERMISSION_DENIED,
    NODE_VERSION_UNSUPPORTED,
    TLS_ERROR,
    NETWORK_ERROR,
    STARTUP_TIMEOUT,
    TOOL_CALL_TIMEOUT,
    CONNECTION_CLOSED,
    CONNECTION_LOST,
}

internal enum class StdioFailurePhase {
    STARTUP,
    TOOL_CALL,
}

internal fun classifyStdioFailure(
    error: Throwable?,
    stderr: String,
    command: String,
    phase: StdioFailurePhase = StdioFailurePhase.STARTUP,
): StdioFailureKind? {
    val commandName = command.substringAfterLast('/')
    val commandPattern = Regex(
        pattern = "(?:env|exec(?:vp)?)[^\\n]*\\b${Regex.escape(commandName)}\\b[^\\n]*" +
            "(?:no such file|not found)",
        option = RegexOption.IGNORE_CASE,
    )
    val permissionPattern = Regex(
        pattern = "(?:env|exec(?:vp)?)[^\\n]*\\b${Regex.escape(commandName)}\\b[^\\n]*permission denied",
        option = RegexOption.IGNORE_CASE,
    )
    val combined = buildString {
        append(stderr)
        error?.message?.let {
            append('\n')
            append(it)
        }
    }

    return when {
        commandName.isNotBlank() && commandPattern.containsMatchIn(stderr) ->
            StdioFailureKind.COMMAND_NOT_FOUND
        commandName.isNotBlank() && permissionPattern.containsMatchIn(stderr) ->
            StdioFailureKind.PERMISSION_DENIED
        combined.contains("EBADENGINE", ignoreCase = true) ||
            combined.contains("Unsupported engine", ignoreCase = true) ||
            combined.contains("requires node", ignoreCase = true) ->
            StdioFailureKind.NODE_VERSION_UNSUPPORTED
        listOf(
            "UNABLE_TO_GET_ISSUER_CERT_LOCALLY",
            "SELF_SIGNED_CERT_IN_CHAIN",
            "CERT_HAS_EXPIRED",
            "certificate verify failed",
        ).any { combined.contains(it, ignoreCase = true) } -> StdioFailureKind.TLS_ERROR
        listOf("EAI_AGAIN", "ENOTFOUND", "ECONNREFUSED", "ECONNRESET", "ETIMEDOUT").any {
            combined.contains(it, ignoreCase = true)
        } -> StdioFailureKind.NETWORK_ERROR
        error is TimeoutCancellationException -> when (phase) {
            StdioFailurePhase.STARTUP -> StdioFailureKind.STARTUP_TIMEOUT
            StdioFailurePhase.TOOL_CALL -> StdioFailureKind.TOOL_CALL_TIMEOUT
        }
        error?.message?.contains("Connection closed", ignoreCase = true) == true ||
            error?.message?.contains("process closed", ignoreCase = true) == true -> when (phase) {
            StdioFailurePhase.STARTUP -> StdioFailureKind.CONNECTION_CLOSED
            StdioFailurePhase.TOOL_CALL -> StdioFailureKind.CONNECTION_LOST
        }
        else -> null
    }
}

internal fun stdioErrorStatus(
    error: Throwable,
    stderr: String,
    config: McpServerConfig.StdioServer,
    phase: StdioFailurePhase = StdioFailurePhase.STARTUP,
    timeoutSeconds: Int = config.startupTimeoutSeconds,
): McpStatus.Error = buildStdioErrorStatus(
    error = error,
    stderr = stderr,
    config = config,
    fallbackMessage = error.message ?: error.javaClass.simpleName,
    phase = phase,
    timeoutSeconds = timeoutSeconds,
)

internal fun stdioProcessExitStatus(
    config: McpServerConfig.StdioServer,
    exitCode: Int?,
    stderr: String,
    phase: StdioFailurePhase,
): McpStatus.Error = buildStdioErrorStatus(
    error = null,
    stderr = stderr,
    config = config,
    fallbackMessage = if (exitCode == null) {
        "STDIO MCP process closed"
    } else {
        "STDIO MCP process exited with code $exitCode"
    },
    phase = phase,
    timeoutSeconds = config.startupTimeoutSeconds,
    defaultFailureKind = when (phase) {
        StdioFailurePhase.STARTUP -> StdioFailureKind.CONNECTION_CLOSED
        StdioFailurePhase.TOOL_CALL -> StdioFailureKind.CONNECTION_LOST
    },
)

private fun buildStdioErrorStatus(
    error: Throwable?,
    stderr: String,
    config: McpServerConfig.StdioServer,
    fallbackMessage: String,
    phase: StdioFailurePhase,
    timeoutSeconds: Int,
    defaultFailureKind: StdioFailureKind? = null,
): McpStatus.Error {
    val diagnostic = stderr.trim().takeIf { it.isNotEmpty() }
    val failureKind = classifyStdioFailure(error, stderr, config.command, phase) ?: defaultFailureKind
    val (messageResId, messageArgs) = when (failureKind) {
        StdioFailureKind.COMMAND_NOT_FOUND ->
            R.string.mcp_error_stdio_command_not_found to listOf(config.command)
        StdioFailureKind.PERMISSION_DENIED ->
            R.string.mcp_error_stdio_permission_denied to listOf(config.command)
        StdioFailureKind.NODE_VERSION_UNSUPPORTED ->
            R.string.mcp_error_stdio_node_version_unsupported to emptyList()
        StdioFailureKind.TLS_ERROR -> R.string.mcp_error_stdio_tls to emptyList()
        StdioFailureKind.NETWORK_ERROR -> R.string.mcp_error_stdio_network to emptyList()
        StdioFailureKind.STARTUP_TIMEOUT ->
            R.string.mcp_error_stdio_startup_timeout to listOf(timeoutSeconds.toString())
        StdioFailureKind.TOOL_CALL_TIMEOUT ->
            R.string.mcp_error_stdio_tool_call_timeout to listOf(timeoutSeconds.toString())
        StdioFailureKind.CONNECTION_CLOSED ->
            R.string.mcp_error_stdio_connection_closed to emptyList()
        StdioFailureKind.CONNECTION_LOST ->
            R.string.mcp_error_stdio_connection_lost to emptyList()
        null -> null to emptyList()
    }
    return McpStatus.Error(
        message = fallbackMessage,
        messageResId = messageResId,
        messageArgs = messageArgs,
        detail = buildString {
            if (diagnostic != null) {
                append("STDERR (tail):\n")
                append(diagnostic)
            }
            if (error != null) {
                if (isNotEmpty()) append("\n\n")
                append("Exception:\n")
                append(error.stackTraceToString())
            } else {
                if (isNotEmpty()) append("\n\n")
                append("Process:\n")
                append(fallbackMessage)
            }
        },
    )
}

private data class StdioConnectionKey(
    val clientName: String,
    val workspaceId: String,
    val command: String,
    val args: List<String>,
    val environment: Map<String, String>,
    val workingDirectory: String,
    val startupTimeoutSeconds: Int,
)

private fun McpServerConfig.StdioServer.stdioConnectionKey() = StdioConnectionKey(
    clientName = commonOptions.name,
    workspaceId = workspaceId,
    command = command,
    args = args,
    environment = environment,
    workingDirectory = workingDirectory,
    startupTimeoutSeconds = startupTimeoutSeconds,
)
