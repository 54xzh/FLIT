package me.rerere.rikkahub.data.ai.mcp

import kotlin.uuid.Uuid

/** MCP 的工作区可见性与运行可用性都从这里判断，避免界面和调用链各自解释规则。 */
fun McpServerConfig.isVisibleForWorkspace(effectiveWorkspaceId: String?): Boolean =
    when (this) {
        is McpServerConfig.StdioServer -> workspaceId.isNotBlank() && workspaceId == effectiveWorkspaceId
        else -> true
    }

fun isMcpServerAvailable(
    server: McpServerConfig,
    selectedServerIds: Set<Uuid>,
    effectiveWorkspaceId: String?,
): Boolean = server.commonOptions.enable &&
    server.id in selectedServerIds &&
    server.isVisibleForWorkspace(effectiveWorkspaceId)

fun isMcpServerEffective(
    server: McpServerConfig,
    selectedServerIds: Set<Uuid>,
    effectiveWorkspaceId: String?,
): Boolean = isMcpServerAvailable(server, selectedServerIds, effectiveWorkspaceId) &&
    server.commonOptions.tools.any { it.enable }

fun isMcpToolAvailable(
    server: McpServerConfig,
    tool: McpTool,
    selectedServerIds: Set<Uuid>,
    effectiveWorkspaceId: String?,
): Boolean = tool.enable && isMcpServerAvailable(server, selectedServerIds, effectiveWorkspaceId)

fun isMcpInvocationAvailable(
    server: McpServerConfig,
    tool: McpTool,
    selectedServerIds: Set<Uuid>,
    effectiveWorkspaceId: String?,
    expectedRuntimeScope: McpRuntimeScope,
): Boolean = server.runtimeScope() == expectedRuntimeScope &&
    isMcpToolAvailable(server, tool, selectedServerIds, effectiveWorkspaceId)

data class McpResolvedTool(
    val serverId: Uuid,
    val originalName: String,
    val exposedName: String,
    val description: String?,
    val inputSchema: me.rerere.ai.core.InputSchema?,
    val requireApproval: Boolean,
    val runtimeScope: McpRuntimeScope,
)

data class McpRuntimeScope(
    val transport: String,
    val workspaceId: String? = null,
)

fun McpServerConfig.runtimeScope(): McpRuntimeScope = when (this) {
    is McpServerConfig.StdioServer -> McpRuntimeScope("stdio", workspaceId)
    is McpServerConfig.SseTransportServer -> McpRuntimeScope("sse")
    is McpServerConfig.StreamableHTTPServer -> McpRuntimeScope("streamable_http")
}

internal fun resolveMcpTools(
    servers: List<McpServerConfig>,
    selectedServerIds: Set<Uuid>,
    effectiveWorkspaceId: String?,
): List<McpResolvedTool> {
    val available = servers.flatMap { server ->
        server.commonOptions.tools.mapNotNull { tool ->
            if (isMcpToolAvailable(server, tool, selectedServerIds, effectiveWorkspaceId)) {
                server to tool
            } else {
                null
            }
        }
    }
    val duplicateNames = available.groupingBy { it.second.name }.eachCount().filterValues { it > 1 }.keys
    val usedNames = mutableSetOf<String>()
    return available.map { (server, tool) ->
        val preferredName = if (tool.name in duplicateNames) {
            "${server.commonOptions.name.toToolPrefix()}_${tool.name}"
        } else {
            tool.name
        }
        val exposedName = if (usedNames.add(preferredName)) {
            preferredName
        } else {
            "${preferredName}_${server.id.toString().take(8)}".also(usedNames::add)
        }
        McpResolvedTool(
            serverId = server.id,
            originalName = tool.name,
            exposedName = exposedName,
            description = tool.description,
            inputSchema = tool.inputSchema,
            requireApproval = tool.requireApproval,
            runtimeScope = server.runtimeScope(),
        )
    }
}

private fun String.toToolPrefix(): String =
    lowercase()
        .replace(Regex("[^a-z0-9_]+"), "_")
        .trim('_')
        .ifBlank { "mcp" }

internal fun hasMcpRuntimeScopeChanged(old: McpServerConfig, new: McpServerConfig): Boolean =
    old.runtimeScope() != new.runtimeScope()

internal fun hasStdioLaunchChanged(old: McpServerConfig, new: McpServerConfig): Boolean {
    old as? McpServerConfig.StdioServer ?: return false
    new as? McpServerConfig.StdioServer ?: return false
    return old.workspaceId != new.workspaceId ||
        old.command != new.command ||
        old.args != new.args ||
        old.environment != new.environment ||
        old.workingDirectory != new.workingDirectory
}

internal fun applyMcpSelectionDelta(
    latestSelection: Set<Uuid>,
    displayedSelection: Set<Uuid>,
    requestedSelection: Set<Uuid>,
): Set<Uuid> {
    val added = requestedSelection - displayedSelection
    val removed = displayedSelection - requestedSelection
    return (latestSelection + added) - removed
}
