package me.rerere.rikkahub.data.ai.mcp

import android.content.Context
import android.util.Log
import io.modelcontextprotocol.kotlin.sdk.client.Client
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.db.entity.SandboxRootfsStatus
import me.rerere.rikkahub.data.db.entity.WorkspaceType
import me.rerere.rikkahub.workspace.SandboxProcessCoordinator
import me.rerere.rikkahub.workspace.SandboxProcessLauncher
import me.rerere.rikkahub.workspace.SandboxWorkspaceManager
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

private const val TAG = "McpManager"

/**
 * MCP 子系统的公共入口。
 *
 * 这里仅协调配置、OAuth、连接注册表；单个服务器的连接状态机由
 * [McpSessionRegistry] 管理，OAuth 协议细节由 [McpOAuthCoordinator] 管理。
 *
 * 所有工具查询和调用都显式接收有效工作区，并用 serverId + 原始工具名精确路由。
 */
class McpManager(
    private val settingsStore: SettingsStore,
    private val appScope: AppScope,
    appEventBus: AppEventBus,
    private val workspaceRepository: WorkspaceRepository,
    private val sandboxWorkspaceManager: SandboxWorkspaceManager,
    sandboxProcessLauncher: SandboxProcessLauncher,
    sandboxProcessCoordinator: SandboxProcessCoordinator,
) {
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(120, TimeUnit.SECONDS)
        .followSslRedirects(true)
        .followRedirects(true)
        .build()

    private val statusStore = McpStatusStore()
    private val oauthCoordinator = McpOAuthCoordinator(
        settingsStore = settingsStore,
        appScope = appScope,
        appEventBus = appEventBus,
        oauthClient = McpOAuthClient(okHttpClient),
        updateStatus = statusStore::update,
    )
    private val sessionRegistry = McpSessionRegistry(
        settingsStore = settingsStore,
        appScope = appScope,
        okHttpClient = okHttpClient,
        oauthCoordinator = oauthCoordinator,
        statusStore = statusStore,
    )
    private val stdioSessionRegistry = StdioMcpSessionRegistry(
        settingsStore = settingsStore,
        appScope = appScope,
        workspaceRepository = workspaceRepository,
        sandboxWorkspaceManager = sandboxWorkspaceManager,
        processLauncher = sandboxProcessLauncher,
        processCoordinator = sandboxProcessCoordinator,
        statusStore = statusStore,
    )

    init {
        appScope.launch {
            settingsStore.settingsFlow
                .map { settings -> settings.mcpServers }
                .distinctUntilChanged()
                .collect { configs ->
                    sessionRegistry.reconcile(configs)
                    stdioSessionRegistry.reconcile(configs)
                }
        }
        // 进程启动即恢复进行中的 OAuth 授权，确保 deep link 到达前订阅已就绪。
        oauthCoordinator.resumePendingAuthorization()
    }

    val syncingStatus: StateFlow<Map<Uuid, McpStatus>>
        get() = statusStore.status

    fun getClient(config: McpServerConfig): Client? = when (config) {
        is McpServerConfig.StdioServer -> stdioSessionRegistry.getClient(config.id)
        else -> sessionRegistry.getClient(config.id)
    }

    fun getStatus(config: McpServerConfig): Flow<McpStatus> = statusStore.get(config.id)

    suspend fun getAvailableToolsForAssistant(
        assistant: Assistant,
        effectiveWorkspaceId: String?,
    ): List<McpResolvedTool> {
        val settings = settingsStore.settingsFlow.value
        val validWorkspaceId = resolveRunnableSandboxId(effectiveWorkspaceId)
        return resolveMcpTools(settings.mcpServers, assistant.mcpServers, validWorkspaceId)
    }

    suspend fun callToolForAssistant(
        selectedServerIds: Set<Uuid>,
        effectiveWorkspaceId: String?,
        serverId: Uuid,
        originalToolName: String,
        expectedRuntimeScope: McpRuntimeScope,
        args: JsonObject,
    ): JsonElement {
        val settings = settingsStore.settingsFlow.value
        val validWorkspaceId = resolveRunnableSandboxId(effectiveWorkspaceId)
        val server = settings.mcpServers.firstOrNull { it.id == serverId }
            ?: return JsonPrimitive("Failed to execute tool, because no such tool")
        val tool = server.commonOptions.tools.firstOrNull { it.name == originalToolName }
        if (tool == null || !isMcpInvocationAvailable(
                server = server,
                tool = tool,
                selectedServerIds = selectedServerIds,
                effectiveWorkspaceId = validWorkspaceId,
                expectedRuntimeScope = expectedRuntimeScope,
            )
        ) {
            return JsonPrimitive("Failed to execute tool, because it is unavailable in the active workspace")
        }

        return try {
            val result = when (server) {
                is McpServerConfig.StdioServer -> stdioSessionRegistry.callTool(
                    configInput = server,
                    expectedRuntimeScope = expectedRuntimeScope,
                    toolName = originalToolName,
                    args = args,
                )
                else -> sessionRegistry.callTool(server.id, originalToolName, args)
            }
            McpJson.encodeToJsonElement(result.content)
        } catch (e: CancellationException) {
            throw e
        } catch (e: McpClientUnavailableException) {
            Log.w(TAG, "callTool unavailable: ${e.message}")
            JsonPrimitive("Failed to execute tool: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "callTool failed for server $serverId: ${e.message}")
            JsonPrimitive("Failed to execute tool: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    suspend fun addClient(config: McpServerConfig) {
        when (config) {
            is McpServerConfig.StdioServer -> {
                sessionRegistry.removeClient(config)
                if (config.commonOptions.enable) {
                    stdioSessionRegistry.remove(config.id)
                    stdioSessionRegistry.testAndSync(config)
                } else {
                    stdioSessionRegistry.remove(config.id)
                }
            }
            else -> {
                stdioSessionRegistry.remove(config.id)
                sessionRegistry.addClient(config)
            }
        }
    }

    suspend fun removeClient(config: McpServerConfig) {
        sessionRegistry.removeClient(config)
        stdioSessionRegistry.remove(config.id)
    }

    suspend fun syncAll() {
        sessionRegistry.syncAll()
        val stdioConfigs = settingsStore.settingsFlow.value.mcpServers
            .filterIsInstance<McpServerConfig.StdioServer>()
        stdioSessionRegistry.syncAll(stdioConfigs)
    }

    fun startAuthorization(config: McpServerConfig, context: Context) {
        if (config is McpServerConfig.StdioServer) return
        oauthCoordinator.startAuthorization(config, context)
    }

    fun cancelAuthorization(config: McpServerConfig) {
        oauthCoordinator.cancelAuthorization(config.id)
    }

    suspend fun clearAuthorization(config: McpServerConfig) {
        if (config is McpServerConfig.StdioServer) return
        val freshConfig = oauthCoordinator.clearAuthorization(config)
        sessionRegistry.addClient(freshConfig)
    }

    private suspend fun resolveRunnableSandboxId(workspaceId: String?): String? {
        val workspace = workspaceId?.let { workspaceRepository.getById(it) } ?: return null
        val rootfsAvailable = withContext(Dispatchers.IO) {
            sandboxWorkspaceManager.hasRootfs(workspace.id)
        }
        return workspace.id.takeIf {
            workspace.type == WorkspaceType.SANDBOX &&
                workspace.sandboxStatus == SandboxRootfsStatus.READY &&
                rootfsAvailable
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
internal val McpJson: kotlinx.serialization.json.Json by lazy {
    kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        classDiscriminatorMode = kotlinx.serialization.json.ClassDiscriminatorMode.NONE
        explicitNulls = false
    }
}
