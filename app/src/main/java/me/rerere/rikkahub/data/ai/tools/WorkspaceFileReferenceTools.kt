package me.rerere.rikkahub.data.ai.tools

import android.webkit.MimeTypeMap
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.db.entity.WorkspaceType
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull

const val WORKSPACE_FILE_REFERENCE_TOOL_NAME = "workspace_send_file"

suspend fun createWorkspaceFileReferenceTool(
    workspaceId: String,
    workspaceRepository: WorkspaceRepository,
): Tool {
    val workspace = withContext(Dispatchers.IO) {
        workspaceRepository.getById(workspaceId)
    }
        ?: error("Workspace not found: $workspaceId")
    val isSandbox = workspace.type == WorkspaceType.SANDBOX

    return Tool(
        name = WORKSPACE_FILE_REFERENCE_TOOL_NAME,
        description = if (isSandbox) {
            "Create a user-visible reference to an existing regular file under /workspace. This does not upload or copy the file."
        } else {
            "Create a user-visible reference to an existing regular file in the workspace. This does not upload or copy the file."
        },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            if (isSandbox) {
                                "Absolute regular-file path under /workspace"
                            } else {
                                "Relative regular-file path inside the workspace"
                            },
                        )
                    })
                },
                required = listOf("path"),
            )
        },
        requiresUserApproval = false,
        systemPrompt = { _, _ ->
            if (isSandbox) {
                SANDBOX_FILE_REFERENCE_PROMPT
            } else {
                // includeCommonRules = false: the common-rules block is carried by workspace_list,
                // and GenerationHandler dedups tool prompts by whole rendered text (no markdown
                // parsing). Carrying the shared block here would inject it twice, since this
                // tool's own `## tool:` block differs from workspace_list's. The tool's own
                // description, schema, and examples are enough when workspace_list is absent.
                workspaceToolSystemPromptTemplate(
                    toolName = WORKSPACE_FILE_REFERENCE_TOOL_NAME,
                    includeCommonRules = false,
                ).renderWorkspaceFileReferencePrompt()
            }
        },
        systemPromptVariables = { _, _ ->
            mapOf(
                WORKSPACE_COMMON_RULES_VARIABLE to if (isSandbox) {
                    SANDBOX_FILE_REFERENCE_PROMPT
                } else {
                    WORKSPACE_COMMON_RULES_PROMPT
                },
            )
        },
        execute = execute@{ args ->
            val params = args as? JsonObject
            val rawPath = params?.get("path")?.jsonPrimitiveOrNull?.contentOrNull
            val path = normalizeWorkspaceFileReferencePath(rawPath, sandbox = isSandbox)
                ?: return@execute workspaceFileReferenceError("Invalid path")
            val entry = try {
                workspaceRepository.resolveWorkspaceFile(workspaceId, path)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
                ?: return@execute workspaceFileReferenceError("File not found or workspace is unavailable")

            buildJsonObject {
                put("ok", true)
                put("type", "workspace_file_reference")
                put("workspace_id", workspaceId)
                put("path", entry.path)
                put("name", entry.name)
                put("mime", guessWorkspaceFileMime(entry.name))
                put("size_bytes", entry.sizeBytes)
                put("updated_at", entry.updatedAt)
                put("message", "The file is available in the conversation with open and save actions.")
            }
        },
    )
}

internal fun normalizeWorkspaceFileReferencePath(rawPath: String?, sandbox: Boolean): String? {
    var normalized = rawPath?.replace('\\', '/')?.trim() ?: return null
    if (normalized.contains('\u0000')) return null
    if (sandbox) {
        normalized = when {
            normalized.startsWith("/workspace/") -> normalized.removePrefix("/workspace/")
            else -> return null
        }
    }
    normalized = normalized.trim('/')
    if (normalized.isBlank()) return null
    val segments = normalized.split('/')
    if (segments.any { it.isBlank() || it == "." || it == ".." }) return null
    return normalized
}

private fun workspaceFileReferenceError(message: String) = buildJsonObject {
    put("ok", false)
    put("type", "workspace_file_reference")
    put("error", message)
}

private fun guessWorkspaceFileMime(name: String): String {
    val extension = name.substringAfterLast('.', "").lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        ?: "application/octet-stream"
}

private fun String.renderWorkspaceFileReferencePrompt(): String = renderToolSystemPromptTemplate(
    template = this,
    variables = mapOf(WORKSPACE_COMMON_RULES_VARIABLE to WORKSPACE_COMMON_RULES_PROMPT),
)

private const val SANDBOX_FILE_REFERENCE_PROMPT = """
You are using a persistent Linux sandbox. Use this tool only when the user asks to receive, share, open, or save a file.
The tool creates a reference to an existing regular file; it does not upload or copy the file into the conversation.
The path must be an absolute file path under /workspace, for example /workspace/output/report.pdf.
If the change produced a new deliverable file, reference that new file so earlier versions remain available.
"""
