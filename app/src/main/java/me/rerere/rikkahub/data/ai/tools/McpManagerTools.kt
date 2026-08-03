package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.McpStatus
import me.rerere.rikkahub.data.ai.mcp.hasStdioLaunchChanged
import me.rerere.rikkahub.data.ai.mcp.withMcpSelectionRemoved
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import kotlin.uuid.Uuid

/**
 * MCP 管理工具组：让助手在对话中 CRUD MCP 服务器、查连接状态、重载。
 *
 * 产品规则：
 * - 单开关控整组（[me.rerere.rikkahub.data.ai.tools.LocalToolOption.McpManager]）。
 * - 无需用户审批（requiresUserApproval = false）。
 * - STDIO 自动绑定当前助手绑定的沙盒工作区，不暴露 workspaceId 参数；
 *   助手无可运行沙盒时返回报错并建议改用 HTTP/SSE，不创建。
 * - 新建即对当前助手默认开启（commonOptions.enable=true + 加入 assistant.mcpServers）。
 * - 快照版：创建后需用户发新消息，下一轮才可见新 MCP 工具。返回 note 提示助手。
 *
 * 工具依赖通过 [LocalTools] 构造注入，[createMcpManagerTools] 在 getTools 内分发时调用。
 * execute 内部自行解析 effectiveWorkspaceId（按助手绑定），不通过 getTools 传参，
 * 从而避免改动 ScheduledTaskWorker 等调用点。
 */
fun createMcpManagerTools(
    assistantId: Uuid,
    settingsStore: SettingsStore,
    mcpManager: McpManager,
    workspaceRepository: WorkspaceRepository,
    scheduledTaskDao: me.rerere.rikkahub.data.db.dao.ScheduledTaskDao,
): List<Tool> = listOf(
    buildListTool(assistantId, settingsStore, mcpManager),
    buildCreateTool(assistantId, settingsStore, mcpManager, workspaceRepository),
    buildEditTool(assistantId, settingsStore, mcpManager),
    buildDeleteTool(assistantId, settingsStore, mcpManager, scheduledTaskDao),
    buildStatusTool(assistantId, settingsStore, mcpManager),
    buildReloadTool(assistantId, settingsStore, mcpManager),
)

// ── 工具实现 ────────────────────────────────────────────────────────────────────

private fun buildListTool(
    assistantId: Uuid,
    settingsStore: SettingsStore,
    mcpManager: McpManager,
) = Tool(
    name = "mcp_list",
    description = "List MCP servers enabled for the current assistant, with transport type, connection status, and exposed tool names.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("scope", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add(JsonPrimitive("enabled"))
                        add(JsonPrimitive("all"))
                    })
                    put("description", "enabled: only servers enabled for the current assistant (default). all: all configured servers.")
                })
            },
        )
    },
    execute = { args ->
        val settings = settingsStore.settingsFlow.value
        val assistant = settings.assistants.firstOrNull { it.id == assistantId }
        val selected = assistant?.mcpServers ?: emptySet()
        val scope = args.jsonObject["scope"]?.jsonPrimitiveOrNull?.contentOrNull ?: "enabled"
        val servers = if (scope == "all") {
            settings.mcpServers
        } else {
            settings.mcpServers.filter { it.id in selected }
        }
        val statusMap = mcpManager.syncingStatus.value
        buildJsonObject {
            put("servers", buildJsonArray {
                servers.forEach { server ->
                    add(serverToJson(server, statusMap[server.id]))
                }
            })
        }
    }
)

private fun buildCreateTool(
    assistantId: Uuid,
    settingsStore: SettingsStore,
    mcpManager: McpManager,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "mcp_create",
    description = "Create an MCP server and auto-enable it for the current assistant.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "Server display name")
                })
                put("type", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add(JsonPrimitive("http"))
                        add(JsonPrimitive("sse"))
                        add(JsonPrimitive("stdio"))
                    })
                    put("description", "Transport type. stdio requires the assistant to bind a runnable sandbox workspace.")
                })
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "Server URL. Required for http/sse.")
                })
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Executable command. Required for stdio.")
                })
                put("args", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                    put("description", "Command arguments. stdio only.")
                })
                put("environment", buildJsonObject {
                    put("type", "object")
                    put("description", "Environment variables (key-value). stdio only.")
                })
                put("working_directory", buildJsonObject {
                    put("type", "string")
                    put("description", "Working directory inside the sandbox (default /workspace). stdio only.")
                })
                put("headers", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                    })
                    put("description", "HTTP headers as [[key, value], ...]. http/sse only.")
                })
            },
            required = listOf("name", "type"),
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        val name = obj["name"]?.jsonPrimitiveOrNull?.contentOrNull?.trim()
            ?: return@Tool buildJsonObject { put("error", "missing name") }
        val typeStr = obj["type"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: return@Tool buildJsonObject { put("error", "missing type") }

        // 解析 headers: [[k,v],...] -> List<Pair<String,String>>
        val headers = obj["headers"]?.let { h ->
            if (h !is JsonArray) return@let emptyList()
            h.mapNotNull { pair ->
                if (pair !is JsonArray || pair.size != 2) return@mapNotNull null
                val k = pair[0].jsonPrimitiveOrNull?.contentOrNull ?: return@mapNotNull null
                val v = pair[1].jsonPrimitiveOrNull?.contentOrNull ?: return@mapNotNull null
                k to v
            }
        } ?: emptyList()

        // 构造配置；STDIO 需先校验可运行沙盒
        val newConfig: McpServerConfig = when (typeStr) {
            "http" -> {
                val url = obj["url"]?.jsonPrimitiveOrNull?.contentOrNull
                    ?: return@Tool buildJsonObject { put("error", "missing url for http") }
                McpServerConfig.StreamableHTTPServer(
                    commonOptions = McpCommonOptions(enable = true, name = name, headers = headers),
                    url = url,
                )
            }
            "sse" -> {
                val url = obj["url"]?.jsonPrimitiveOrNull?.contentOrNull
                    ?: return@Tool buildJsonObject { put("error", "missing url for sse") }
                McpServerConfig.SseTransportServer(
                    commonOptions = McpCommonOptions(enable = true, name = name, headers = headers),
                    url = url,
                )
            }
            "stdio" -> {
                val command = obj["command"]?.jsonPrimitiveOrNull?.contentOrNull
                    ?: return@Tool buildJsonObject { put("error", "missing command for stdio") }
                val settings = settingsStore.settingsFlow.value
                val assistant = settings.assistants.firstOrNull { it.id == assistantId }
                val boundWorkspaceId = assistant?.workspaceId
                val runnableSandboxId = mcpManager.resolveRunnableSandboxId(boundWorkspaceId)
                if (runnableSandboxId == null) {
                    return@Tool buildJsonObject {
                        put("error", "no_runnable_sandbox")
                        put("reason", "当前助手未绑定可用的沙盒工作区，无法创建 STDIO MCP")
                        put("suggestion", "改用 HTTP/SSE 类型，或先在助手设置里绑定一个沙盒工作区")
                    }
                }
                val argsList = obj["args"]?.let { a ->
                    if (a !is JsonArray) emptyList() else a.mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull }
                } ?: emptyList()
                val env = obj["environment"]?.let { e ->
                    if (e !is JsonObject) emptyMap() else e.mapNotNull { (k, v) ->
                        v.jsonPrimitiveOrNull?.contentOrNull?.let { k to it }
                    }.toMap()
                } ?: emptyMap()
                val workingDir = obj["working_directory"]?.jsonPrimitiveOrNull?.contentOrNull
                    ?: "/workspace"
                McpServerConfig.StdioServer(
                    commonOptions = McpCommonOptions(enable = true, name = name),
                    workspaceId = runnableSandboxId,
                    command = command,
                    args = argsList,
                    environment = env,
                    workingDirectory = workingDir,
                )
            }
            else -> return@Tool buildJsonObject { put("error", "invalid type: $typeStr") }
        }

        // 原子写入：新增配置 + 对当前助手开启
        settingsStore.update { latest ->
            val updatedServers = latest.mcpServers + newConfig
            val updatedAssistants = latest.assistants.map { a ->
                if (a.id == assistantId) a.copy(mcpServers = a.mcpServers + newConfig.id) else a
            }
            latest.copy(mcpServers = updatedServers, assistants = updatedAssistants)
        }

        // 触发连接 + 工具同步（STDIO testAndSync 回写 tools；HTTP/SSE 同步工具）
        val syncError = runCatchingCancelSafe { mcpManager.addClient(newConfig) }.exceptionOrNull()
        if (syncError != null) {
            return@Tool buildJsonObject {
                put("ok", false)
                put("id", newConfig.id.toString())
                put("name", newConfig.commonOptions.name)
                put("type", typeStr)
                put("warning", "连接同步失败: ${syncError.message ?: syncError.javaClass.simpleName}")
                put("note", "配置已保存。可用 mcp_status 查看错误，或 mcp_reload 重试。")
            }
        }

        // 同步完成后读最新配置里的工具列表（testAndSync 已回写）
        val refreshed = settingsStore.settingsFlow.value.mcpServers
            .firstOrNull { it.id == newConfig.id }
        val statusMap = mcpManager.syncingStatus.value
        buildJsonObject {
            put("ok", true)
            put("id", newConfig.id.toString())
            put("name", newConfig.commonOptions.name)
            put("type", typeStr)
            if (newConfig is McpServerConfig.StdioServer) {
                put("workspace_id", newConfig.workspaceId)
            }
            put("status", statusString(statusMap[newConfig.id]))
            put("tools", buildJsonArray {
                refreshed?.commonOptions?.tools?.forEach { t ->
                    add(buildJsonObject {
                        put("name", t.name)
                        put("enabled", t.enable)
                    })
                }
            })
            put("note", "新工具将在下一条消息后可用")
        }
    }
)

private fun buildEditTool(
    assistantId: Uuid,
    settingsStore: SettingsStore,
    mcpManager: McpManager,
) = Tool(
    name = "mcp_edit",
    description = "Update an MCP server's config by id. Transport type cannot be changed.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject {
                    put("type", "string")
                    put("description", "ID of the MCP server to update")
                })
                put("name", buildJsonObject { put("type", "string") })
                put("enable", buildJsonObject { put("type", "boolean") })
                put("url", buildJsonObject { put("type", "string") })
                put("command", buildJsonObject { put("type", "string") })
                put("args", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                })
                put("environment", buildJsonObject { put("type", "object") })
                put("working_directory", buildJsonObject { put("type", "string") })
                put("headers", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                    })
                    put("description", "HTTP headers as [[key, value], ...]. http/sse only.")
                })
            },
            required = listOf("id"),
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        val idStr = obj["id"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: return@Tool buildJsonObject { put("error", "missing id") }
        val id = runCatching { Uuid.parse(idStr) }.getOrNull()
            ?: return@Tool buildJsonObject { put("error", "invalid id: $idStr") }

        // 仅做存在性预检；真正的 patch 在 update 闭包内基于 latest 完成，避免覆盖并发更新
        val exists = settingsStore.settingsFlow.value.mcpServers.any { it.id == id }
        if (!exists) return@Tool buildJsonObject { put("error", "server not found: $idStr") }

        // 解析可选字段（不依赖 settings，闭包外解析即可）
        val newName = obj["name"]?.jsonPrimitiveOrNull?.contentOrNull?.trim()
        val newEnable = obj["enable"]?.jsonPrimitiveOrNull?.booleanOrNull
        val newUrl = obj["url"]?.jsonPrimitiveOrNull?.contentOrNull
        val newCommand = obj["command"]?.jsonPrimitiveOrNull?.contentOrNull
        val newArgs = obj["args"]?.let { a ->
            if (a !is JsonArray) null else a.mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull }
        }
        val newEnv = obj["environment"]?.let { e ->
            if (e !is JsonObject) null else e.mapNotNull { (k, v) ->
                v.jsonPrimitiveOrNull?.contentOrNull?.let { k to it }
            }.toMap()
        }
        val newWorkingDir = obj["working_directory"]?.jsonPrimitiveOrNull?.contentOrNull
        val newHeaders = obj["headers"]?.let { h ->
            if (h !is JsonArray) null else h.mapNotNull { pair ->
                if (pair !is JsonArray || pair.size != 2) return@mapNotNull null
                val k = pair[0].jsonPrimitiveOrNull?.contentOrNull ?: return@mapNotNull null
                val v = pair[1].jsonPrimitiveOrNull?.contentOrNull ?: return@mapNotNull null
                k to v
            }
        }

        // 基于「当前最新」的 old 计算新配置，避免覆盖别处的并发写入（如 OAuth 令牌刷新、工具缓存回写）
        fun patch(old: McpServerConfig): McpServerConfig = when (old) {
            is McpServerConfig.StreamableHTTPServer -> old.copy(
                url = newUrl ?: old.url,
                commonOptions = old.commonOptions.copy(
                    name = newName ?: old.commonOptions.name,
                    enable = newEnable ?: old.commonOptions.enable,
                    headers = newHeaders ?: old.commonOptions.headers,
                ),
            )
            is McpServerConfig.SseTransportServer -> old.copy(
                url = newUrl ?: old.url,
                commonOptions = old.commonOptions.copy(
                    name = newName ?: old.commonOptions.name,
                    enable = newEnable ?: old.commonOptions.enable,
                    headers = newHeaders ?: old.commonOptions.headers,
                ),
            )
            is McpServerConfig.StdioServer -> {
                val draft = old.copy(
                    command = newCommand ?: old.command,
                    args = newArgs ?: old.args,
                    environment = newEnv ?: old.environment,
                    workingDirectory = newWorkingDir ?: old.workingDirectory,
                    commonOptions = old.commonOptions.copy(
                        name = newName ?: old.commonOptions.name,
                        enable = newEnable ?: old.commonOptions.enable,
                    ),
                )
                // launch 参数变化时清空缓存的工具列表（对齐 SettingVM.saveMcpConfig 行为）
                if (hasStdioLaunchChanged(old, draft)) {
                    draft.copy(commonOptions = draft.commonOptions.copy(tools = emptyList()))
                } else {
                    draft
                }
            }
        }

        // update 闭包内基于 latest 取 old 并就地 patch；若并发删除了该 id 则保留 latest 不动
        var updatedOrNull: McpServerConfig? = null
        settingsStore.update { latest ->
            val old = latest.mcpServers.firstOrNull { it.id == id }
            if (old == null) {
                latest
            } else {
                val patched = patch(old)
                updatedOrNull = patched
                latest.copy(mcpServers = latest.mcpServers.map { if (it.id == id) patched else it })
            }
        }
        val updated = updatedOrNull
            ?: return@Tool buildJsonObject { put("error", "server not found: $idStr") }

        runCatchingCancelSafe { mcpManager.addClient(updated) }
        val statusMap = mcpManager.syncingStatus.value
        buildJsonObject {
            put("ok", true)
            put("id", id.toString())
            put("name", updated.commonOptions.name)
            put("status", statusString(statusMap[id]))
        }
    }
)

private fun buildDeleteTool(
    assistantId: Uuid,
    settingsStore: SettingsStore,
    mcpManager: McpManager,
    scheduledTaskDao: me.rerere.rikkahub.data.db.dao.ScheduledTaskDao,
) = Tool(
    name = "mcp_delete",
    description = "Delete an MCP server by id. Removes it from all assistants' selections.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject {
                    put("type", "string")
                    put("description", "ID of the MCP server to delete")
                })
            },
            required = listOf("id"),
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        val idStr = obj["id"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: return@Tool buildJsonObject { put("error", "missing id") }
        val id = runCatching { Uuid.parse(idStr) }.getOrNull()
            ?: return@Tool buildJsonObject { put("error", "invalid id: $idStr") }

        val settings = settingsStore.settingsFlow.value
        val existing = settings.mcpServers.firstOrNull { it.id == id }
            ?: return@Tool buildJsonObject { put("error", "server not found: $idStr") }

        settingsStore.update { latest ->
            latest.copy(mcpServers = latest.mcpServers.filter { it.id != id })
                .withMcpSelectionRemoved(id)
        }
        // 清理定时任务里对该服务器的覆盖配置（对齐 SettingVM.deleteMcpConfig）
        withContext(Dispatchers.IO) { scheduledTaskDao.removeMcpServerOverride(id.toString()) }
        runCatchingCancelSafe { mcpManager.removeClient(existing) }

        buildJsonObject {
            put("ok", true)
            put("id", id.toString())
        }
    }
)

private fun buildStatusTool(
    assistantId: Uuid,
    settingsStore: SettingsStore,
    mcpManager: McpManager,
) = Tool(
    name = "mcp_status",
    description = "Query connection status and last error for one or all MCP servers.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject {
                    put("type", "string")
                    put("description", "Server ID. If omitted, query all servers enabled for the current assistant.")
                })
            },
        )
    },
    execute = { args ->
        val settings = settingsStore.settingsFlow.value
        val assistant = settings.assistants.firstOrNull { it.id == assistantId }
        val selected = assistant?.mcpServers ?: emptySet()
        val idStr = args.jsonObject["id"]?.jsonPrimitiveOrNull?.contentOrNull
        val statusMap = mcpManager.syncingStatus.value
        if (idStr != null) {
            val id = runCatching { Uuid.parse(idStr) }.getOrNull()
                ?: return@Tool buildJsonObject { put("error", "invalid id: $idStr") }
            val server = settings.mcpServers.firstOrNull { it.id == id }
                ?: return@Tool buildJsonObject { put("error", "server not found: $idStr") }
            return@Tool buildJsonObject {
                put("server", serverToJson(server, statusMap[id]))
            }
        }
        buildJsonObject {
            put("servers", buildJsonArray {
                settings.mcpServers.filter { it.id in selected }.forEach { server ->
                    add(serverToJson(server, statusMap[server.id]))
                }
            })
        }
    }
)

private fun buildReloadTool(
    assistantId: Uuid,
    settingsStore: SettingsStore,
    mcpManager: McpManager,
) = Tool(
    name = "mcp_reload",
    description = "Reconnect and refresh tool list for one or all MCP servers.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject {
                    put("type", "string")
                    put("description", "Server ID. If omitted, reload all servers.")
                })
            },
        )
    },
    execute = { args ->
        val idStr = args.jsonObject["id"]?.jsonPrimitiveOrNull?.contentOrNull
        if (idStr != null) {
            val id = runCatching { Uuid.parse(idStr) }.getOrNull()
                ?: return@Tool buildJsonObject { put("error", "invalid id: $idStr") }
            val settings = settingsStore.settingsFlow.value
            val server = settings.mcpServers.firstOrNull { it.id == id }
                ?: return@Tool buildJsonObject { put("error", "server not found: $idStr") }
            runCatchingCancelSafe { mcpManager.reloadClient(server) }
            return@Tool buildJsonObject {
                put("ok", true)
                put("id", id.toString())
            }
        }
        runCatchingCancelSafe { mcpManager.syncAll() }
        buildJsonObject { put("ok", true) }
    }
)

// ── Helpers ────────────────────────────────────────────────────────────────────

/**
 * 捕获挂起操作的非取消异常。CancellationException 一律重新抛出，避免用户停止生成后
 * 工具仍返回成功/警告而非随协程正确终止。返回结果以 Result 形式供调用方判断。
 */
private inline fun <T> runCatchingCancelSafe(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}

private fun transportString(server: McpServerConfig): String = when (server) {
    is McpServerConfig.StreamableHTTPServer -> "http"
    is McpServerConfig.SseTransportServer -> "sse"
    is McpServerConfig.StdioServer -> "stdio"
}

private fun statusString(status: McpStatus?): String = when (status) {
    null, is McpStatus.Idle -> "idle"
    is McpStatus.Connecting -> "connecting"
    is McpStatus.Connected -> "connected"
    is McpStatus.Ready -> "ready"
    is McpStatus.Reconnecting -> "reconnecting"
    is McpStatus.NeedsAuthorization -> "needs_auth"
    is McpStatus.Authorizing -> "authorizing"
    is McpStatus.Error -> "error"
}

private fun serverToJson(
    server: McpServerConfig,
    status: McpStatus?,
): JsonObject = buildJsonObject {
    put("id", server.id.toString())
    put("name", server.commonOptions.name)
    put("type", transportString(server))
    put("enabled", server.commonOptions.enable)
    if (server is McpServerConfig.StdioServer) {
        put("workspace_id", server.workspaceId)
    }
    put("status", statusString(status))
    if (status is McpStatus.Error) {
        put("error", buildJsonObject {
            put("message", status.message)
            if (status.detail != null) put("detail", status.detail)
        })
    }
    if (status is McpStatus.Reconnecting) {
        put("reconnect_attempt", status.attempt)
        put("reconnect_max", status.maxAttempts)
    }
    put("tools", buildJsonArray {
        server.commonOptions.tools.forEach { t ->
            add(buildJsonObject {
                put("name", t.name)
                put("enabled", t.enable)
            })
        }
    })
}