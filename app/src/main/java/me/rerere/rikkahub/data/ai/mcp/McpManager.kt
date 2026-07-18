package me.rerere.rikkahub.data.ai.mcp

import android.content.Context
import android.util.Log
import io.modelcontextprotocol.kotlin.sdk.client.Client
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.model.Assistant
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
 * 对外保留旧版 API（callTool 按工具名调用、返回 JsonElement；getAllAvailableTools 返回 List<McpTool>），
 * 不改动 ChatService / ScheduledTaskWorker 等调用方。
 */
class McpManager(
    private val settingsStore: SettingsStore,
    private val appScope: AppScope,
    appEventBus: AppEventBus,
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

    init {
        appScope.launch {
            settingsStore.settingsFlow
                .map { settings -> settings.mcpServers }
                .distinctUntilChanged()
                .collect(sessionRegistry::reconcile)
        }
    }

    val syncingStatus: StateFlow<Map<Uuid, McpStatus>>
        get() = statusStore.status

    fun getClient(config: McpServerConfig): Client? = sessionRegistry.getClient(config.id)

    fun getStatus(config: McpServerConfig): Flow<McpStatus> = sessionRegistry.getStatus(config.id)

    fun getAllAvailableTools(): List<McpTool> {
        val settings = settingsStore.settingsFlow.value
        val assistant = settings.getCurrentAssistant()
        return getAvailableToolsForAssistant(assistant)
    }

    fun getAvailableToolsForAssistant(assistant: Assistant): List<McpTool> {
        val settings = settingsStore.settingsFlow.value
        return settings.mcpServers
            .filter { it.commonOptions.enable && it.id in assistant.mcpServers }
            .flatMap { it.commonOptions.tools.filter { tool -> tool.enable } }
    }

    suspend fun callTool(toolName: String, args: JsonObject): JsonElement {
        val assistant = settingsStore.settingsFlow.value.getCurrentAssistant()
        return callToolForAssistant(assistant, toolName, args)
    }

    suspend fun callToolForAssistant(assistant: Assistant, toolName: String, args: JsonObject): JsonElement {
        val settings = settingsStore.settingsFlow.value
        // 找到该助手启用、且包含此工具名的 server
        val server = settings.mcpServers.firstOrNull { config ->
            config.commonOptions.enable &&
                config.id in assistant.mcpServers &&
                config.commonOptions.tools.any { it.name == toolName && it.enable }
        } ?: return JsonPrimitive("Failed to execute tool, because no such tool")

        return try {
            val result = sessionRegistry.callTool(server.id, toolName, args)
            McpJson.encodeToJsonElement(result.content)
        } catch (e: CancellationException) {
            throw e
        } catch (e: McpClientUnavailableException) {
            Log.w(TAG, "callTool unavailable: ${e.message}")
            JsonPrimitive("Failed to execute tool, because no such mcp client for the tool")
        }
    }

    suspend fun addClient(config: McpServerConfig) = sessionRegistry.addClient(config)

    suspend fun removeClient(config: McpServerConfig) = sessionRegistry.removeClient(config)

    suspend fun syncAll() = sessionRegistry.syncAll()

    fun startAuthorization(config: McpServerConfig, context: Context) {
        oauthCoordinator.startAuthorization(config, context)
    }

    fun cancelAuthorization(config: McpServerConfig) {
        oauthCoordinator.cancelAuthorization(config.id)
    }

    suspend fun clearAuthorization(config: McpServerConfig) {
        val freshConfig = oauthCoordinator.clearAuthorization(config)
        sessionRegistry.addClient(freshConfig)
    }
}

internal val McpJson: kotlinx.serialization.json.Json by lazy {
    kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        classDiscriminatorMode = kotlinx.serialization.json.ClassDiscriminatorMode.NONE
        explicitNulls = false
    }
}