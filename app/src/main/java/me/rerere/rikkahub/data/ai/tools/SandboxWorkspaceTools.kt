package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.db.entity.toolDefaultNeedsApproval

private const val SANDBOX_MAX_TIMEOUT_SECONDS = 600L

suspend fun createSandboxWorkspaceTools(
    workspaceId: String,
    workspaceRepository: WorkspaceRepository,
): List<Tool> {
    val overrides = workspaceRepository.getById(workspaceId)?.toolApprovalOverrides().orEmpty()
    fun approval(name: String): Boolean = overrides[name] ?: toolDefaultNeedsApproval(name)
    return listOf(
        createWorkspaceFileReferenceTool(workspaceId, workspaceRepository),
        sandboxReadTool(workspaceId, workspaceRepository, ::approval),
        sandboxWriteTool(workspaceId, workspaceRepository, ::approval),
        sandboxEditTool(workspaceId, workspaceRepository, ::approval),
        sandboxShellTool(workspaceId, workspaceRepository, ::approval),
    )
}

private fun sandboxReadTool(id: String, repository: WorkspaceRepository, approval: (String) -> Boolean) = Tool(
    name = "sandbox_read_file",
    description = "Read a UTF-8 file in the persistent sandbox. The path must be absolute under /workspace.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject { putSandboxPath(required = true) }, required = listOf("path"))
    },
    requiresUserApproval = approval("sandbox_read_file"),
    systemPrompt = { _, _ -> SANDBOX_PROMPT },
    execute = { args ->
        val path = args.jsonObject.sandboxPath("path")
        buildJsonObject {
            put("path", path)
            put("text", repository.readSandboxText(id, path.removePrefix("/workspace/").removePrefix("/workspace")))
        }
    },
)

private fun sandboxWriteTool(id: String, repository: WorkspaceRepository, approval: (String) -> Boolean) = Tool(
    name = "sandbox_write_file",
    description = "Write a UTF-8 file in the persistent sandbox. The path must be absolute under /workspace.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putSandboxPath(required = true)
                put("text", buildJsonObject { put("type", "string"); put("description", "UTF-8 content") })
                put("overwrite", buildJsonObject { put("type", "boolean"); put("description", "Overwrite an existing file; defaults to true. Use only for temporary/internal files or when the user explicitly asks to replace it; user-facing deliverables must be written to a new, descriptively named path.") })
            },
            required = listOf("path", "text"),
        )
    },
    requiresUserApproval = approval("sandbox_write_file"),
    systemPrompt = { _, _ -> SANDBOX_PROMPT },
    execute = { args ->
        val params = args.jsonObject
        val path = params.sandboxPath("path")
        val text = params.string("text") ?: error("text is required")
        val overwrite = params["overwrite"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        val entry = repository.writeSandboxText(id, path.relativeSandboxPath(), text, overwrite)
        buildJsonObject { put("path", "/workspace/${entry.path}"); put("size_bytes", entry.sizeBytes); put("updated_at", entry.updatedAt) }
    },
)

private fun sandboxEditTool(id: String, repository: WorkspaceRepository, approval: (String) -> Boolean) = Tool(
    name = "sandbox_edit_file",
    description = "Make a precise replacement in a UTF-8 sandbox file. The path must be absolute under /workspace.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putSandboxPath(required = true)
                put("old_text", buildJsonObject { put("type", "string") })
                put("new_text", buildJsonObject { put("type", "string") })
                put("replace_all", buildJsonObject { put("type", "boolean") })
            },
            required = listOf("path", "old_text", "new_text"),
        )
    },
    requiresUserApproval = approval("sandbox_edit_file"),
    systemPrompt = { _, _ -> SANDBOX_PROMPT },
    execute = { args ->
        val params = args.jsonObject
        val path = params.sandboxPath("path")
        val oldText = params.string("old_text") ?: error("old_text is required")
        val newText = params.string("new_text") ?: error("new_text is required")
        require(oldText.isNotEmpty()) { "old_text must not be empty" }
        val replaceAll = params["replace_all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val relative = path.relativeSandboxPath()
        val original = repository.readSandboxText(id, relative)
        val occurrences = original.windowed(oldText.length, 1).count { it == oldText }
        require(occurrences > 0) { "old_text was not found" }
        require(replaceAll || occurrences == 1) { "old_text occurs $occurrences times; use replace_all=true" }
        val updated = if (replaceAll) original.replace(oldText, newText) else original.replaceFirst(oldText, newText)
        val entry = repository.writeSandboxText(id, relative, updated, overwrite = true)
        buildJsonObject { put("path", path); put("replacements", if (replaceAll) occurrences else 1); put("size_bytes", entry.sizeBytes) }
    },
)

private fun sandboxShellTool(id: String, repository: WorkspaceRepository, approval: (String) -> Boolean) = Tool(
    name = "sandbox_shell",
    description = "Run a shell command in the sandbox Rootfs. /workspace, /skills, /upload and /tool_outputs are mounted and writable. Folders mounted from the phone under /workspace modify the original phone files. Always inspect the command before approving it.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject { put("type", "string") })
                put("cwd", buildJsonObject { put("type", "string"); put("description", "Optional absolute /workspace path; defaults to /workspace") })
                put("timeout", buildJsonObject { put("type", "integer"); put("description", "Seconds, default 30, max 600") })
            },
            required = listOf("command"),
        )
    },
    requiresUserApproval = approval("sandbox_shell"),
    systemPrompt = { _, _ -> SANDBOX_PROMPT },
    execute = { args ->
        val params = args.jsonObject
        val command = params.string("command")?.takeIf { it.isNotBlank() } ?: error("command is required")
        val cwd = params["cwd"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { "/workspace" }
        require(cwd == "/workspace" || cwd.startsWith("/workspace/")) { "cwd must be inside /workspace" }
        val timeout = params["timeout"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.coerceIn(1, SANDBOX_MAX_TIMEOUT_SECONDS)?.times(1_000)
            ?: 30_000L
        val result = repository.executeSandboxCommand(id, command, cwd.relativeSandboxPath(), timeout)
        buildJsonObject {
            put("exit_code", result.exitCode)
            put("stdout", result.stdout)
            put("stderr", result.stderr)
            put("timed_out", result.timedOut)
            if (result.truncated) put("truncated", true)
        }
    },
)

private fun JsonObject.sandboxPath(name: String): String {
    val path = string(name)?.trim() ?: error("$name is required")
    require(path == "/workspace" || path.startsWith("/workspace/")) { "$name must be inside /workspace" }
    require(!path.contains('\u0000')) { "$name contains an invalid character" }
    return path
}

private fun String.relativeSandboxPath(): String = removePrefix("/workspace/").removePrefix("/workspace")
private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

private const val SANDBOX_PROMPT = """
You are using a persistent Linux sandbox. Sandbox file tools accept absolute paths under /workspace only.
Use sandbox_shell for commands. The shell can write /workspace, /skills, /upload and /tool_outputs; do not claim that uploads are read-only.
Mounted phone folders under /workspace are live and writable; changes affect the original phone files.

### deliverable versioning
- `workspace_send_file` does not copy the file into the conversation; it creates a live reference to the file path. When the user later opens or saves a referenced file, the app reads whatever is at that path at that moment.
- If you overwrite an existing file, every earlier reference to it silently starts showing the new content, and the user's previous version is gone everywhere at once.
- So when a change produces a file meant for the user (to send, share, open, or save), keep the existing file and write the changed result to a new file in the same directory.
- Name the new file after the change the user asked for, keeping the original base name and its language. Example: if the user asks to translate /workspace/report.pdf into English, write /workspace/report-english.pdf; if they ask to shorten /workspace/notes.docx, write /workspace/notes-shortened.docx.
- If that name is already taken, add a short distinguishing note (such as /workspace/report-english-revised.pdf) instead of overwriting either file.
- Do not delete previous versions unless the user explicitly asks.
- Temporary or internal files may still be overwritten when needed.

### folder organization
- When a task produces new files for the first time, put them in a new folder named after the task instead of the /workspace root (example: /workspace/travel-plan/itinerary.md, not /workspace/itinerary.md).
- Keep folder names short and descriptive; one level is usually enough.
- If the user specifies a path, follow it.
- Revised versions of an existing file stay in its current directory (see deliverable versioning); this rule is for new files.
- Temporary or internal scratch files may stay anywhere convenient.
"""

private fun kotlinx.serialization.json.JsonObjectBuilder.putSandboxPath(required: Boolean) {
    put("path", buildJsonObject {
        put("type", "string")
        put("description", "Absolute path inside /workspace")
    })
}
