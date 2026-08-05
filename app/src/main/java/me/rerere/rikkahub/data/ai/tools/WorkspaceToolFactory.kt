package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.db.entity.SandboxRootfsStatus
import me.rerere.rikkahub.data.db.entity.WorkspaceType
import me.rerere.rikkahub.data.db.entity.toolDefaultNeedsApproval
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.WorkspaceFileReferenceContext
import me.rerere.rikkahub.data.repository.SafRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.SkillScriptPathUtils
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import me.rerere.rikkahub.workspace.SandboxWorkspaceManager
import java.util.Locale

/**
 * 轻量工作区工具的路径规整。除 workspace_list 外的工具共用：
 * 模型偶尔会把工作区根目录理解成 "/" 而发出 "/folder/file.txt" 这类路径，
 * 这里宽容地剥掉前导 "/" 按相对路径处理（与 workspace_list 行为一致）；
 * 但单独的 "/" 仍然拒绝，避免 workspace_delete 通过 "/" 命中根目录
 * （根目录只能用空字符串 "" 显式指定）。
 */
internal fun normalizeWorkspaceToolPath(rawPath: String?, allowBlank: Boolean): String? {
    var trimmed = rawPath?.trim().orEmpty()
    if (trimmed.isBlank()) return if (allowBlank) "" else null
    // 先统一分隔符，让 Windows 风格的 "\folder\file.txt" 也能享受同样的宽容
    trimmed = trimmed.replace('\\', '/')
    if (trimmed.startsWith("/")) {
        val stripped = trimmed.trimStart('/').trim()
        if (stripped.isBlank()) return null
        trimmed = stripped
    }
    return SkillScriptPathUtils.normalizeAndValidateWorkspaceFileRelPath(trimmed)
}

internal fun normalizeWorkspaceListToolPath(rawPath: String?): String? {
    val trimmed = rawPath?.trim().orEmpty().replace('\\', '/')
    if (trimmed.isBlank() || trimmed == "." || trimmed == "/") return ""
    var normalized = trimmed
    while (normalized.startsWith("/")) normalized = normalized.removePrefix("/")
    if (normalized.isBlank()) return ""
    return normalizeWorkspaceToolPath(normalized, allowBlank = true)
}

internal enum class WorkspaceToolExecutionMode {
    INTERACTIVE,
    SCHEDULED,
}

internal data class WorkspaceToolSet(
    val tools: List<Tool>,
    val referenceContext: WorkspaceFileReferenceContext?,
) {
    companion object {
        val EMPTY = WorkspaceToolSet(tools = emptyList(), referenceContext = null)
    }
}

internal fun shouldAttachWorkspaceTools(assistant: Assistant): Boolean =
    assistant.localTools.contains(LocalToolOption.WorkspaceFiles) &&
        !assistant.workspaceId.isNullOrBlank()

internal fun filterToolsForExecutionMode(
    tools: List<Tool>,
    mode: WorkspaceToolExecutionMode,
): List<Tool> = when (mode) {
    WorkspaceToolExecutionMode.INTERACTIVE -> tools
    WorkspaceToolExecutionMode.SCHEDULED -> tools.filterNot { it.requiresUserApproval }
}

class WorkspaceToolFactory(
    private val context: Context,
    private val workspaceRepository: WorkspaceRepository,
    private val safRepository: SafRepository,
    private val sandboxWorkspaceManager: SandboxWorkspaceManager,
) {
    internal suspend fun createForAssistant(
        assistant: Assistant,
        settingsSnapshot: Settings,
        mode: WorkspaceToolExecutionMode = WorkspaceToolExecutionMode.INTERACTIVE,
        conversationId: String? = null,
    ): WorkspaceToolSet {
        if (!shouldAttachWorkspaceTools(assistant)) {
            return WorkspaceToolSet.EMPTY
        }

        val workspaceId = assistant.workspaceId?.trim()?.takeIf { it.isNotBlank() }
            ?: return WorkspaceToolSet.EMPTY
        val workspace = withContext(Dispatchers.IO) {
            workspaceRepository.getById(workspaceId)
        } ?: return WorkspaceToolSet.EMPTY

        val workspaceAvailable = withContext(Dispatchers.IO) {
            when (workspace.type) {
                WorkspaceType.LIGHTWEIGHT -> isLightweightWorkspaceAvailable(workspace.treeUri)
                WorkspaceType.SANDBOX -> workspace.sandboxStatus == SandboxRootfsStatus.READY &&
                    runCatching { sandboxWorkspaceManager.hasRootfs(workspace.id) }.getOrDefault(false)
            }
        }
        if (!workspaceAvailable) return WorkspaceToolSet.EMPTY

        val workspaceAssistant = if (assistant.workspaceId == workspaceId) {
            assistant
        } else {
            assistant.copy(workspaceId = workspaceId)
        }

        val allTools = withContext(Dispatchers.IO) {
            when (workspace.type) {
                WorkspaceType.LIGHTWEIGHT -> createWorkspaceFileTools(
                    assistant = workspaceAssistant,
                    settingsSnapshot = settingsSnapshot,
                )
                WorkspaceType.SANDBOX -> createSandboxWorkspaceTools(workspace.id, workspaceRepository, conversationId)
            }
        }
        val tools = filterToolsForExecutionMode(allTools, mode)
        return WorkspaceToolSet(
            tools = tools,
            referenceContext = workspaceId
                .takeIf { allTools.isNotEmpty() }
                ?.let(::WorkspaceFileReferenceContext),
        )
    }

    private fun isLightweightWorkspaceAvailable(treeUri: String?): Boolean {
        val rootDoc = treeUri?.let { uri ->
            runCatching { DocumentFile.fromTreeUri(context, Uri.parse(uri)) }.getOrNull()
        }
        return rootDoc?.isDirectory == true
    }

    private fun guessMimeType(name: String): String {
        val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
        if (ext.isBlank()) return "application/octet-stream"
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        return mime ?: "application/octet-stream"
    }

    // SAF 文件操作统一委托给 SafRepository（见 di/RepositoryModule），
    // splitRelPath / resolve / resolveOrCreateDir / deleteDocument 见 SafRepository。

    /**
     * 解析助手绑定的工作区 SAF 根目录。
     *
     * 工作区 = 助手绑定的 WorkspaceEntity.treeUri（一个 SAF 授权目录），无子路径。
     * @throws IllegalStateException 助手未绑定工作区 / 工作区记录不存在 / SAF 目录不可访问
     */
    private suspend fun resolveAssistantWorkspaceDir(
        assistant: Assistant,
    ): DocumentFile = withContext(Dispatchers.IO) {
        val workspaceId = assistant.workspaceId
        if (workspaceId.isNullOrBlank()) {
            error("Assistant has no workspace bound")
        }
        val workspace = workspaceRepository.getById(workspaceId)
            ?: error("Workspace not found: $workspaceId")
        require(workspace.type == WorkspaceType.LIGHTWEIGHT) {
            "This operation is only available for a lightweight workspace"
        }
        val treeUri = workspace.treeUri ?: error("Workspace folder is missing")
        val rootDoc = runCatching {
            DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
        }.getOrNull()
        if (rootDoc?.isDirectory != true) {
            error("Workspace directory is not accessible")
        }
        rootDoc
    }

    /**
     * 取助手工作区的工具审批覆盖（toolName -> needsApproval）。
     * 未绑工作区返回空 map（走默认审批）。
     */
    private suspend fun assistantToolApprovalOverrides(
        assistant: Assistant,
    ): Map<String, Boolean> {
        val workspaceId = assistant.workspaceId ?: return emptyMap()
        return workspaceRepository.getById(workspaceId)?.toolApprovalOverrides() ?: emptyMap()
    }

    /**
     * 判断某工具在某助手上是否需要审批卡片。
     * 优先用工作区 toolApprovals 覆盖；未覆盖则走 [toolDefaultNeedsApproval] 的默认值
     * （仅写文件/删除/执行 python/执行脚本默认需审批，其余默认免审批）。
     */
    private suspend fun toolNeedsApproval(
        assistant: Assistant,
        toolName: String,
    ): Boolean {
        val overrides = assistantToolApprovalOverrides(assistant)
        return overrides[toolName] ?: toolDefaultNeedsApproval(toolName)
    }


    private suspend fun createWorkspaceFileTools(
        assistant: Assistant,
        settingsSnapshot: Settings,
    ): List<Tool> {
        return listOf(
            createWorkspaceListTool(assistant = assistant, settingsSnapshot = settingsSnapshot),
            createWorkspaceReadFileTool(assistant = assistant, settingsSnapshot = settingsSnapshot),
            createWorkspaceWriteFileTool(assistant = assistant, settingsSnapshot = settingsSnapshot),
            createWorkspaceMkdirTool(assistant = assistant, settingsSnapshot = settingsSnapshot),
            createWorkspaceDeleteTool(assistant = assistant, settingsSnapshot = settingsSnapshot),
            createWorkspaceRenameTool(assistant = assistant, settingsSnapshot = settingsSnapshot),
        )
    }

    private fun parseWorkspaceToolBool(
        obj: JsonObject,
        key: String,
        defaultValue: Boolean = false,
        vararg altKeys: String,
    ): Boolean {
        val keys = arrayOf(key, *altKeys)
        for (k in keys) {
            val parsed = obj[k]?.jsonPrimitiveOrNull?.contentOrNull
                ?.trim()
                ?.toBooleanStrictOrNull()
            if (parsed != null) return parsed
        }
        return defaultValue
    }

    private fun parseWorkspaceToolInt(
        obj: JsonObject,
        key: String,
        defaultValue: Int,
        min: Int,
        max: Int,
        vararg altKeys: String,
    ): Int {
        val keys = arrayOf(key, *altKeys)
        for (k in keys) {
            val parsed = obj[k]?.jsonPrimitiveOrNull?.contentOrNull
                ?.trim()
                ?.toIntOrNull()
            if (parsed != null) return parsed.coerceIn(min, max)
        }
        return defaultValue
    }

    private fun parseWorkspaceToolString(
        obj: JsonObject,
        key: String,
        vararg altKeys: String,
    ): String? {
        val keys = arrayOf(key, *altKeys)
        for (k in keys) {
            val value = obj[k]?.jsonPrimitiveOrNull?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            if (value != null) return value
        }
        return null
    }

    private fun workspaceToolInvalidPathError(
        toolName: String,
        rawPath: String?,
        field: String? = null,
    ): JsonObject {
        val safeInput = rawPath
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.take(256)
        val hint = when (toolName) {
            "workspace_list" -> "Use a relative path like \"folder\" or omit `path` / use \"\" for the workspace root. Do not use \"..\"."
            "workspace_delete" -> "Use a relative path like \"folder/file.txt\". The workspace root can only be targeted with an empty string \"\" (dangerous). Do not use \"..\"."
            else -> "Use a relative path like \"folder/file.txt\". Do not start with \"/\" and do not use \"..\"."
        }
        return buildJsonObject {
            put("ok", false)
            put("error", field?.let { "Invalid $it" } ?: "Invalid path")
            put("error_code", "invalid_path")
            if (field != null) put("error_field", field)
            if (safeInput != null) put("input_path", safeInput)
            put("hint", hint)
        }
    }


    private fun workspaceToolsCommonSystemPrompt(): String {
        return WORKSPACE_COMMON_RULES_PROMPT
    }

    private fun workspaceToolPromptVariables(includeCommonRules: Boolean): Map<String, String> {
        return mapOf(
            WORKSPACE_COMMON_RULES_VARIABLE to if (includeCommonRules) {
                workspaceToolsCommonSystemPrompt()
            } else {
                ""
            }
        )
    }

    private fun workspaceToolCustomPromptVariables(): Map<String, String> {
        return mapOf(
            WORKSPACE_COMMON_RULES_VARIABLE to workspaceToolsCommonSystemPrompt()
        )
    }

    private fun workspaceToolSystemPrompt(
        toolName: String,
        includeCommonRules: Boolean,
    ): String {
        return renderToolSystemPromptTemplate(
            template = workspaceToolSystemPromptTemplate(
                toolName = toolName,
                includeCommonRules = includeCommonRules,
            ),
            variables = workspaceToolPromptVariables(includeCommonRules),
        )
    }

    private suspend fun createWorkspaceListTool(
        assistant: Assistant,
        settingsSnapshot: Settings,
    ): Tool {
        val requiresApproval = toolNeedsApproval(assistant, "workspace_list")
        return Tool(
            name = "workspace_list",
            description = "List workspace files and directories.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "Relative path inside the assistant workspace directory. Omit or use empty string for root.")
                        })
                        put("recursive", buildJsonObject {
                            put("type", "boolean")
                            put("description", "Whether to list recursively (default: false).")
                        })
                        put("max_entries", buildJsonObject {
                            put("type", "integer")
                            put("description", "Maximum entries to return (default: 2000, max: 10000).")
                        })
                    }
                )
            },
            requiresUserApproval = requiresApproval,
            execute = { args ->
                val obj = args.jsonObject
                val rawPath = parseWorkspaceToolString(obj, "path", "dir", "directory")
                val normalizedPath = normalizeWorkspaceListToolPath(rawPath)
                    ?: return@Tool workspaceToolInvalidPathError(toolName = "workspace_list", rawPath = rawPath)

                val recursive = parseWorkspaceToolBool(obj, "recursive", false, "recurse")
                val maxEntries = parseWorkspaceToolInt(obj, "max_entries", 2000, 1, 10_000, "maxEntries")

                runCatching {
                    withContext(Dispatchers.IO) {
                        val workDir = resolveAssistantWorkspaceDir(assistant)
                        val dir = if (normalizedPath.isBlank()) {
                            workDir
                        } else {
                            safRepository.resolve(workDir, normalizedPath)
                        } ?: return@withContext buildJsonObject {
                            put("ok", false)
                            put("error", "Path not found: ${normalizedPath.ifBlank { "/" }}")
                        }

                        if (!dir.isDirectory) {
                            return@withContext buildJsonObject {
                                put("ok", false)
                                put("error", "Not a directory: ${normalizedPath.ifBlank { "/" }}")
                            }
                        }

                        var truncated = false
                        var count = 0
                        val entries = buildJsonArray {
                            val stack = ArrayDeque<Pair<DocumentFile, String>>()
                            stack.addLast(dir to normalizedPath)
                            while (stack.isNotEmpty()) {
                                val (current, prefix) = stack.removeLast()
                                current.listFiles().forEach { child ->
                                    if (count >= maxEntries) {
                                        truncated = true
                                        return@forEach
                                    }
                                    val name = child.name ?: return@forEach
                                    val childPath = if (prefix.isBlank()) name else "$prefix/$name"
                                    add(buildJsonObject {
                                        put("path", childPath)
                                        put("type", if (child.isDirectory) "dir" else "file")
                                        if (child.isFile) {
                                            put("bytes", child.length())
                                            put("last_modified", child.lastModified())
                                        }
                                    })
                                    count++
                                    if (recursive && child.isDirectory) {
                                        stack.addLast(child to childPath)
                                    }
                                }
                            }
                        }

                        buildJsonObject {
                            put("ok", true)
                            put("path", normalizedPath.ifBlank { "/" })
                            put("entries", entries)
                            put("truncated", truncated)
                        }
                    }
                }.getOrElse { e ->
                    buildJsonObject { put("ok", false); put("error", e.message ?: "Unknown error") }
                }
            },
            systemPrompt = { _, _ ->
                workspaceToolSystemPrompt(
                    toolName = "workspace_list",
                    includeCommonRules = true,
                )
            },
            systemPromptVariables = { _, _ -> workspaceToolCustomPromptVariables() },
        )
    }

    private suspend fun createWorkspaceReadFileTool(
        assistant: Assistant,
        settingsSnapshot: Settings,
    ): Tool {
        val requiresApproval = toolNeedsApproval(assistant, "workspace_read_file")
        return Tool(
            name = "workspace_read_file",
            description = "Read a workspace text file.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "Relative file path inside the assistant workspace directory, like \"folder/file.txt\". Do not start with \"/\" and do not use \"..\".")
                        })
                        put("max_chars", buildJsonObject {
                            put("type", "integer")
                            put("description", "Maximum characters to return (default: 200000, max: 2000000).")
                        })
                    },
                    required = listOf("path"),
                )
            },
            requiresUserApproval = requiresApproval,
            execute = { args ->
                val obj = args.jsonObject
                val rawPath = parseWorkspaceToolString(obj, "path", "file")
                val normalizedPath = normalizeWorkspaceToolPath(rawPath, allowBlank = false)
                    ?: return@Tool workspaceToolInvalidPathError(toolName = "workspace_read_file", rawPath = rawPath)

                val maxChars = parseWorkspaceToolInt(obj, "max_chars", 200_000, 1, 2_000_000, "maxChars")

                runCatching {
                    withContext(Dispatchers.IO) {
                        val workDir = resolveAssistantWorkspaceDir(assistant)
                        val file = safRepository.resolve(workDir, normalizedPath)
                            ?: return@withContext buildJsonObject { put("ok", false); put("error", "File not found: $normalizedPath") }

                        if (!file.isFile) {
                            return@withContext buildJsonObject { put("ok", false); put("error", "Not a file: $normalizedPath") }
                        }

                        val input = context.contentResolver.openInputStream(file.uri)
                            ?: return@withContext buildJsonObject { put("ok", false); put("error", "Failed to open file: $normalizedPath") }

                        val builder = StringBuilder()
                        var truncated = false
                        input.bufferedReader(Charsets.UTF_8).use { reader ->
                            val buffer = CharArray(8192)
                            while (true) {
                                val read = reader.read(buffer)
                                if (read <= 0) break
                                val remaining = maxChars - builder.length
                                if (remaining <= 0) {
                                    truncated = true
                                    break
                                }
                                if (read <= remaining) {
                                    builder.append(buffer, 0, read)
                                } else {
                                    builder.append(buffer, 0, remaining)
                                    truncated = true
                                    break
                                }
                            }
                        }

                        buildJsonObject {
                            put("ok", true)
                            put("path", normalizedPath)
                            put("bytes", file.length())
                            put("last_modified", file.lastModified())
                            put("truncated", truncated)
                            put("content", builder.toString())
                        }
                    }
                }.getOrElse { e ->
                    buildJsonObject { put("ok", false); put("error", e.message ?: "Unknown error") }
                }
            },
            systemPrompt = { _, _ ->
                workspaceToolSystemPrompt(
                    toolName = "workspace_read_file",
                    includeCommonRules = false,
                )
            },
            systemPromptVariables = { _, _ -> workspaceToolCustomPromptVariables() },
        )
    }

    private suspend fun createWorkspaceWriteFileTool(
        assistant: Assistant,
        settingsSnapshot: Settings,
    ): Tool {
        val requiresApproval = toolNeedsApproval(assistant, "workspace_write_file")
        return Tool(
            name = "workspace_write_file",
            description = "Write a workspace text file.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "Relative file path inside the assistant workspace directory, like \"folder/file.txt\". Do not start with \"/\" and do not use \"..\".")
                        })
                        put("content", buildJsonObject {
                            put("type", "string")
                            put("description", "File content (UTF-8).")
                        })
                        put("overwrite", buildJsonObject {
                            put("type", "boolean")
                            put("description", "Overwrite an existing file (default: true). Use only for temporary/internal files or when the user explicitly asks to replace it; user-facing deliverables must be written to a new, descriptively named path.")
                        })
                        put("append", buildJsonObject {
                            put("type", "boolean")
                            put("description", "Append to existing file (default: false).")
                        })
                        put("create_parents", buildJsonObject {
                            put("type", "boolean")
                            put("description", "Create parent directories if needed (default: true).")
                        })
                    },
                    required = listOf("path", "content"),
                )
            },
            requiresUserApproval = requiresApproval,
            execute = { args ->
                val obj = args.jsonObject
                val rawPath = parseWorkspaceToolString(obj, "path", "file")
                val normalizedPath = normalizeWorkspaceToolPath(rawPath, allowBlank = false)
                    ?: return@Tool workspaceToolInvalidPathError(toolName = "workspace_write_file", rawPath = rawPath)

                val content = obj["content"]?.jsonPrimitiveOrNull?.contentOrNull
                    ?: return@Tool buildJsonObject { put("ok", false); put("error", "Missing content") }

                val overwrite = parseWorkspaceToolBool(obj, "overwrite", defaultValue = true)
                val append = parseWorkspaceToolBool(obj, "append", defaultValue = false)
                val createParents = parseWorkspaceToolBool(obj, "create_parents", true, "createParents")

                runCatching {
                    withContext(Dispatchers.IO) {
                        val workDir = resolveAssistantWorkspaceDir(assistant)

                        val segments = safRepository.splitRelPath(normalizedPath)
                        val name = segments.lastOrNull()
                            ?: return@withContext buildJsonObject { put("ok", false); put("error", "Invalid path") }
                        val parentPath = segments.dropLast(1).joinToString("/")

                        val parent = if (parentPath.isBlank()) {
                            workDir
                        } else {
                            if (createParents) {
                                safRepository.resolveOrCreateDir(workDir, parentPath)
                            } else {
                                safRepository.resolve(workDir, parentPath)
                            }
                        } ?: return@withContext buildJsonObject {
                            put("ok", false)
                            put("error", "Parent directory not found: $parentPath")
                        }

                        if (!parent.isDirectory) {
                            return@withContext buildJsonObject { put("ok", false); put("error", "Not a directory: $parentPath") }
                        }

                        val existing = parent.findFile(name)
                        if (existing != null) {
                            if (existing.isDirectory) {
                                if (!overwrite || append) {
                                    return@withContext buildJsonObject { put("ok", false); put("error", "Path is a directory: $normalizedPath") }
                                }
                                if (!safRepository.deleteDocument(existing)) {
                                    return@withContext buildJsonObject { put("ok", false); put("error", "Failed to delete existing directory: $normalizedPath") }
                                }
                            } else if (!existing.isFile) {
                                return@withContext buildJsonObject { put("ok", false); put("error", "Invalid destination type: $normalizedPath") }
                            }
                        }

                        val resolvedExisting = parent.findFile(name)
                        if (resolvedExisting != null && !append && !overwrite) {
                            return@withContext buildJsonObject { put("ok", false); put("error", "File exists: $normalizedPath") }
                        }

                        val target = resolvedExisting ?: parent.createFile(guessMimeType(name), name)
                            ?: return@withContext buildJsonObject { put("ok", false); put("error", "Failed to create file: $normalizedPath") }

                        if (!target.isFile) {
                            return@withContext buildJsonObject { put("ok", false); put("error", "Not a file: $normalizedPath") }
                        }

                        val mode = if (append) "wa" else "wt"
                        val out = context.contentResolver.openOutputStream(target.uri, mode)
                            ?: return@withContext buildJsonObject { put("ok", false); put("error", "Failed to open file: $normalizedPath") }

                        val bytes = content.toByteArray(Charsets.UTF_8)
                        out.use { it.write(bytes) }

                        buildJsonObject {
                            put("ok", true)
                            put("path", normalizedPath)
                            put("bytes_written", bytes.size)
                        }
                    }
                }.getOrElse { e ->
                    buildJsonObject { put("ok", false); put("error", e.message ?: "Unknown error") }
                }
            },
            systemPrompt = { _, _ ->
                workspaceToolSystemPrompt(
                    toolName = "workspace_write_file",
                    includeCommonRules = false,
                )
            },
            systemPromptVariables = { _, _ -> workspaceToolCustomPromptVariables() },
        )
    }

    private suspend fun createWorkspaceMkdirTool(
        assistant: Assistant,
        settingsSnapshot: Settings,
    ): Tool {
        val requiresApproval = toolNeedsApproval(assistant, "workspace_mkdir")
        return Tool(
            name = "workspace_mkdir",
            description = "Create a workspace directory.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "Relative directory path inside the assistant workspace directory, like \"folder/sub\". Do not start with \"/\" and do not use \"..\".")
                        })
                        put("parents", buildJsonObject {
                            put("type", "boolean")
                            put("description", "Create parent directories if needed (default: true).")
                        })
                    },
                    required = listOf("path"),
                )
            },
            requiresUserApproval = requiresApproval,
            execute = { args ->
                val obj = args.jsonObject
                val rawPath = parseWorkspaceToolString(obj, "path", "dir", "directory")
                val normalizedPath = normalizeWorkspaceToolPath(rawPath, allowBlank = false)
                    ?: return@Tool workspaceToolInvalidPathError(toolName = "workspace_mkdir", rawPath = rawPath)

                val parents = parseWorkspaceToolBool(obj, "parents", true, "create_parents", "createParents")

                runCatching {
                    withContext(Dispatchers.IO) {
                        val workDir = resolveAssistantWorkspaceDir(assistant)

                        val segments = safRepository.splitRelPath(normalizedPath)
                        val name = segments.lastOrNull()
                            ?: return@withContext buildJsonObject { put("ok", false); put("error", "Invalid path") }
                        val parentPath = segments.dropLast(1).joinToString("/")

                        val parent = if (parentPath.isBlank()) {
                            workDir
                        } else {
                            if (parents) {
                                safRepository.resolveOrCreateDir(workDir, parentPath)
                            } else {
                                safRepository.resolve(workDir, parentPath)
                            }
                        } ?: return@withContext buildJsonObject {
                            put("ok", false)
                            put("error", "Parent directory not found: $parentPath")
                        }

                        if (!parent.isDirectory) {
                            return@withContext buildJsonObject { put("ok", false); put("error", "Not a directory: $parentPath") }
                        }

                        val existing = parent.findFile(name)
                        if (existing != null) {
                            if (existing.isDirectory) {
                                return@withContext buildJsonObject { put("ok", true); put("path", normalizedPath); put("created", false) }
                            }
                            return@withContext buildJsonObject { put("ok", false); put("error", "Path is a file: $normalizedPath") }
                        }

                        val created = parent.createDirectory(name)
                            ?: return@withContext buildJsonObject { put("ok", false); put("error", "Failed to create directory: $normalizedPath") }

                        buildJsonObject {
                            put("ok", true)
                            put("path", normalizedPath)
                            put("created", created.isDirectory)
                        }
                    }
                }.getOrElse { e ->
                    buildJsonObject { put("ok", false); put("error", e.message ?: "Unknown error") }
                }
            },
            systemPrompt = { _, _ ->
                workspaceToolSystemPrompt(
                    toolName = "workspace_mkdir",
                    includeCommonRules = false,
                )
            },
            systemPromptVariables = { _, _ -> workspaceToolCustomPromptVariables() },
        )
    }

    private suspend fun createWorkspaceDeleteTool(
        assistant: Assistant,
        settingsSnapshot: Settings,
    ): Tool {
        val requiresApproval = toolNeedsApproval(assistant, "workspace_delete")
        return Tool(
            name = "workspace_delete",
            description = "Delete a workspace file or directory.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("path", buildJsonObject {
                            put("type", "string")
                            put("description", "Relative path inside the assistant workspace directory. Use empty to delete the workspace directory root (dangerous).")
                        })
                        put("recursive", buildJsonObject {
                            put("type", "boolean")
                            put("description", "Delete directories recursively (default: false).")
                        })
                        put("missing_ok", buildJsonObject {
                            put("type", "boolean")
                            put("description", "Treat missing path as success (default: false).")
                        })
                    },
                    required = listOf("path"),
                )
            },
            requiresUserApproval = requiresApproval,
            execute = { args ->
                val obj = args.jsonObject
                val rawPath = obj["path"]?.jsonPrimitiveOrNull?.contentOrNull
                    ?: return@Tool buildJsonObject { put("ok", false); put("error", "Missing path") }
                val normalizedPath = normalizeWorkspaceToolPath(rawPath, allowBlank = true)
                    ?: return@Tool workspaceToolInvalidPathError(toolName = "workspace_delete", rawPath = rawPath)

                val recursive = parseWorkspaceToolBool(obj, "recursive", false, "recurse")
                val missingOk = parseWorkspaceToolBool(obj, "missing_ok", false, "missingOk")

                runCatching {
                    withContext(Dispatchers.IO) {
                        val workDir = resolveAssistantWorkspaceDir(assistant)
                        val target = if (normalizedPath.isBlank()) {
                            workDir
                        } else {
                            safRepository.resolve(workDir, normalizedPath)
                        }

                        if (target == null) {
                            if (missingOk) {
                                return@withContext buildJsonObject { put("ok", true); put("path", normalizedPath.ifBlank { "/" }); put("deleted", false) }
                            }
                            return@withContext buildJsonObject { put("ok", false); put("error", "Path not found: ${normalizedPath.ifBlank { "/" }}") }
                        }

                        if (target.isDirectory) {
                            if (!recursive && target.listFiles().isNotEmpty()) {
                                return@withContext buildJsonObject {
                                    put("ok", false)
                                    put("error", "Directory is not empty (set recursive=true): ${normalizedPath.ifBlank { "/" }}")
                                }
                            }
                            val deleted = safRepository.deleteDocument(target)
                            buildJsonObject { put("ok", deleted); put("path", normalizedPath.ifBlank { "/" }); put("deleted", deleted) }
                        } else {
                            val deleted = runCatching { target.delete() }.getOrDefault(false)
                            buildJsonObject { put("ok", deleted); put("path", normalizedPath.ifBlank { "/" }); put("deleted", deleted) }
                        }
                    }
                }.getOrElse { e ->
                    buildJsonObject { put("ok", false); put("error", e.message ?: "Unknown error") }
                }
            },
            systemPrompt = { _, _ ->
                workspaceToolSystemPrompt(
                    toolName = "workspace_delete",
                    includeCommonRules = false,
                )
            },
            systemPromptVariables = { _, _ -> workspaceToolCustomPromptVariables() },
        )
    }

    private suspend fun createWorkspaceRenameTool(
        assistant: Assistant,
        settingsSnapshot: Settings,
    ): Tool {
        val requiresApproval = toolNeedsApproval(assistant, "workspace_rename")
        return Tool(
            name = "workspace_rename",
            description = "Rename or move a workspace file or directory.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("from", buildJsonObject {
                            put("type", "string")
                            put("description", "Source relative path inside the assistant workspace directory.")
                        })
                        put("to", buildJsonObject {
                            put("type", "string")
                            put("description", "Destination relative path inside the assistant workspace directory.")
                        })
                        put("overwrite", buildJsonObject {
                            put("type", "boolean")
                            put("description", "Overwrite destination if exists (default: false).")
                        })
                        put("create_parents", buildJsonObject {
                            put("type", "boolean")
                            put("description", "Create parent directories if needed (default: true).")
                        })
                    },
                    required = listOf("from", "to"),
                )
            },
            requiresUserApproval = requiresApproval,
            execute = { args ->
                val obj = args.jsonObject
                val rawFrom = parseWorkspaceToolString(obj, "from", "source", "src")
                val fromPath = normalizeWorkspaceToolPath(rawFrom, allowBlank = false)
                    ?: return@Tool workspaceToolInvalidPathError(toolName = "workspace_rename", rawPath = rawFrom, field = "from")
                val rawTo = parseWorkspaceToolString(obj, "to", "dest", "dst", "destination")
                val toPath = normalizeWorkspaceToolPath(rawTo, allowBlank = false)
                    ?: return@Tool workspaceToolInvalidPathError(toolName = "workspace_rename", rawPath = rawTo, field = "to")

                val overwrite = parseWorkspaceToolBool(obj, "overwrite", defaultValue = false)
                val createParents = parseWorkspaceToolBool(obj, "create_parents", true, "createParents")

                runCatching {
                    withContext(Dispatchers.IO) {
                        val workDir = resolveAssistantWorkspaceDir(assistant)

                        val source = safRepository.resolve(workDir, fromPath)
                            ?: return@withContext buildJsonObject { put("ok", false); put("error", "Source not found: $fromPath") }

                        if (source.isDirectory && toPath.startsWith("$fromPath/")) {
                            return@withContext buildJsonObject { put("ok", false); put("error", "Cannot move a directory into itself") }
                        }

                        val toSegments = safRepository.splitRelPath(toPath)
                        val toName = toSegments.lastOrNull()
                            ?: return@withContext buildJsonObject { put("ok", false); put("error", "Invalid to") }
                        val toParentPath = toSegments.dropLast(1).joinToString("/")

                        val destParent = if (toParentPath.isBlank()) {
                            workDir
                        } else {
                            if (createParents) {
                                safRepository.resolveOrCreateDir(workDir, toParentPath)
                            } else {
                                safRepository.resolve(workDir, toParentPath)
                            }
                        } ?: return@withContext buildJsonObject { put("ok", false); put("error", "Destination parent not found: $toParentPath") }

                        if (!destParent.isDirectory) {
                            return@withContext buildJsonObject { put("ok", false); put("error", "Not a directory: $toParentPath") }
                        }

                        val existingDest = destParent.findFile(toName)
                        if (existingDest != null) {
                            if (!overwrite) {
                                return@withContext buildJsonObject { put("ok", false); put("error", "Destination exists: $toPath") }
                            }
                            if (!safRepository.deleteDocument(existingDest)) {
                                return@withContext buildJsonObject { put("ok", false); put("error", "Failed to delete destination: $toPath") }
                            }
                        }

                        val fromParentPath = safRepository.splitRelPath(fromPath).dropLast(1).joinToString("/")
                        if (fromParentPath == toParentPath) {
                            val renamedOk = runCatching { source.renameTo(toName) }.getOrDefault(false)
                            if (renamedOk) {
                                return@withContext buildJsonObject { put("ok", true); put("from", fromPath); put("to", toPath) }
                            }
                        }

                        val createdDest = (
                            if (source.isDirectory) {
                                destParent.createDirectory(toName)
                            } else if (source.isFile) {
                                destParent.createFile(guessMimeType(toName), toName)
                            } else {
                                null
                            }
                        ) ?: return@withContext buildJsonObject { put("ok", false); put("error", "Failed to create destination: $toPath") }

                        fun copyRec(src: DocumentFile, dst: DocumentFile): Boolean {
                            if (src.isDirectory) {
                                if (!dst.isDirectory) return false
                                src.listFiles().forEach { child ->
                                    val childName = child.name ?: return@forEach
                                    val nextDst = when {
                                        child.isDirectory -> dst.createDirectory(childName)
                                        child.isFile -> dst.createFile(guessMimeType(childName), childName)
                                        else -> null
                                    } ?: return false
                                    if (!copyRec(child, nextDst)) return false
                                }
                                return true
                            }
                            if (!src.isFile || !dst.isFile) return false
                            return runCatching {
                                context.contentResolver.openInputStream(src.uri)?.use { input ->
                                    context.contentResolver.openOutputStream(dst.uri, "wt")?.use { output ->
                                        input.copyTo(output)
                                    } != null
                                } ?: false
                            }.getOrDefault(false)
                        }

                        val copied = copyRec(source, createdDest)
                        if (!copied) {
                            safRepository.deleteDocument(createdDest)
                            return@withContext buildJsonObject { put("ok", false); put("error", "Failed to copy source to destination") }
                        }

                        val deleted = safRepository.deleteDocument(source)
                        if (!deleted) {
                            return@withContext buildJsonObject { put("ok", false); put("error", "Failed to delete source after copy") }
                        }

                        buildJsonObject { put("ok", true); put("from", fromPath); put("to", toPath) }
                    }
                }.getOrElse { e ->
                    buildJsonObject { put("ok", false); put("error", e.message ?: "Unknown error") }
                }
            },
            systemPrompt = { _, _ ->
                workspaceToolSystemPrompt(
                    toolName = "workspace_rename",
                    includeCommonRules = false,
                )
            },
            systemPromptVariables = { _, _ -> workspaceToolCustomPromptVariables() },
        )
    }

}
