package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

internal const val WORKSPACE_FILE_REFERENCE_WORKSPACE_ID_METADATA_KEY = "lastchat_workspace_id"

/** The workspace identity kept by the app for a Markdown file reference. */
internal data class WorkspaceFileReferenceContext(
    val workspaceId: String,
)

internal fun UIMessagePart.Text.workspaceFileReferenceContextOrNull(): WorkspaceFileReferenceContext? {
    val workspaceId = metadata
        ?.get(WORKSPACE_FILE_REFERENCE_WORKSPACE_ID_METADATA_KEY)
        ?.let { it as? JsonPrimitive }
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    return WorkspaceFileReferenceContext(workspaceId)
}

/**
 * Add the original workspace only to text parts that can contain the new Markdown link format.
 * Existing provider metadata is copied unchanged.
 */
internal fun UIMessage.withWorkspaceFileReferenceContext(
    context: WorkspaceFileReferenceContext,
): UIMessage {
    if (context.workspaceId.isBlank()) return this
    var changed = false
    val updatedParts = parts.map { part ->
        if (part is UIMessagePart.Text && part.text.contains("](/workspace/")) {
            val updatedMetadata = buildJsonObject {
                part.metadata?.forEach { (key, value) -> put(key, value) }
                put(WORKSPACE_FILE_REFERENCE_WORKSPACE_ID_METADATA_KEY, context.workspaceId)
            }
            if (updatedMetadata != part.metadata) changed = true
            part.copy(metadata = updatedMetadata)
        } else {
            part
        }
    }
    return if (changed) copy(parts = updatedParts) else this
}
