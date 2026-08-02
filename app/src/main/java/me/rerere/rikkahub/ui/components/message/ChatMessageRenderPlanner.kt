package me.rerere.rikkahub.ui.components.message

import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.WorkspaceFileReferenceContext
import me.rerere.rikkahub.data.model.workspaceFileReferenceContextOrNull
import me.rerere.rikkahub.ui.components.richtext.extractWorkspaceFileReferencePaths

internal sealed interface MessageRenderBlock {
    data class ProcessGroup(
        val parts: List<UIMessagePart>,
    ) : MessageRenderBlock

    data class WorkspaceFileReferenceGroup(
        val items: List<WorkspaceFileReferenceCandidate>,
    ) : MessageRenderBlock

    data class TextBlock(
        val part: UIMessagePart.Text,
        val textIndex: Int,
    ) : MessageRenderBlock

    data class ImageGroup(
        val parts: List<UIMessagePart.Image>,
    ) : MessageRenderBlock

    data class VideoGroup(
        val parts: List<UIMessagePart.Video>,
    ) : MessageRenderBlock

    data class AudioGroup(
        val parts: List<UIMessagePart.Audio>,
    ) : MessageRenderBlock

    data class DocumentGroup(
        val parts: List<UIMessagePart.Document>,
    ) : MessageRenderBlock

    data class QuotedFollowUpBlock(
        val part: UIMessagePart.QuotedFollowUp,
    ) : MessageRenderBlock
}

internal fun buildMessageRenderBlocks(
    leadingProcessParts: List<UIMessagePart>,
    parts: List<UIMessagePart>,
    workspaceFileReferenceContext: WorkspaceFileReferenceContext? = null,
): List<MessageRenderBlock> {
    val orderedParts = parts.normalizeMessagePartsForDisplay()
    val blocks = mutableListOf<MessageRenderBlock>()
    val pendingProcessParts = mutableListOf<UIMessagePart>()
    val pendingMediaParts = mutableListOf<UIMessagePart>()
    val workspaceFileReferences = linkedSetOf<WorkspaceFileReferenceCandidate>()
    var pendingMediaKind: String? = null
    var textIndex = 0

    fun flushProcessParts() {
        if (pendingProcessParts.isEmpty()) return
        blocks += MessageRenderBlock.ProcessGroup(parts = pendingProcessParts.toList())
        pendingProcessParts.clear()
    }

    fun flushMediaParts() {
        if (pendingMediaParts.isEmpty() || pendingMediaKind == null) return
        when (pendingMediaKind) {
            "image" -> blocks += MessageRenderBlock.ImageGroup(
                parts = pendingMediaParts.filterIsInstance<UIMessagePart.Image>()
            )

            "video" -> blocks += MessageRenderBlock.VideoGroup(
                parts = pendingMediaParts.filterIsInstance<UIMessagePart.Video>()
            )

            "audio" -> blocks += MessageRenderBlock.AudioGroup(
                parts = pendingMediaParts.filterIsInstance<UIMessagePart.Audio>()
            )

            "document" -> blocks += MessageRenderBlock.DocumentGroup(
                parts = pendingMediaParts.filterIsInstance<UIMessagePart.Document>()
            )
        }
        pendingMediaKind = null
        pendingMediaParts.clear()
    }

    fun appendMediaPart(kind: String, part: UIMessagePart) {
        if (pendingMediaKind != null && pendingMediaKind != kind) {
            flushMediaParts()
        }
        pendingMediaKind = kind
        pendingMediaParts += part
    }

    (leadingProcessParts + orderedParts).forEach { part ->
        when (part) {
            is UIMessagePart.ToolCall,
            is UIMessagePart.ToolApproval,
                -> {
                    flushMediaParts()
                    pendingProcessParts += part
                }

            is UIMessagePart.Reasoning,
            is UIMessagePart.Thinking,
            is UIMessagePart.AskUser,
                -> {
                    flushMediaParts()
                    pendingProcessParts += part
                }

            is UIMessagePart.ToolResult -> {
                flushMediaParts()
                pendingProcessParts += part
            }

            is UIMessagePart.Text -> {
                flushMediaParts()
                flushProcessParts()
                val context = part.workspaceFileReferenceContextOrNull() ?: workspaceFileReferenceContext
                if (context != null) {
                    extractWorkspaceFileReferencePaths(part.text).forEach { path ->
                        workspaceFileReferences += WorkspaceFileReferenceCandidate(
                            workspaceId = context.workspaceId,
                            path = path,
                        )
                    }
                }
                blocks += MessageRenderBlock.TextBlock(
                    part = part,
                    textIndex = textIndex++,
                )
            }

            is UIMessagePart.Image -> {
                flushProcessParts()
                appendMediaPart(kind = "image", part = part)
            }

            is UIMessagePart.Video -> {
                flushProcessParts()
                appendMediaPart(kind = "video", part = part)
            }

            is UIMessagePart.Audio -> {
                flushProcessParts()
                appendMediaPart(kind = "audio", part = part)
            }

            is UIMessagePart.Document -> {
                flushProcessParts()
                appendMediaPart(kind = "document", part = part)
            }

            is UIMessagePart.QuotedFollowUp -> {
                flushProcessParts()
                flushMediaParts()
                blocks += MessageRenderBlock.QuotedFollowUpBlock(part = part)
            }

            else -> Unit
        }
    }

    flushMediaParts()
    flushProcessParts()
    if (workspaceFileReferences.isNotEmpty()) {
        blocks += MessageRenderBlock.WorkspaceFileReferenceGroup(
            items = workspaceFileReferences.toList(),
        )
    }

    return blocks
}

private fun List<UIMessagePart>.normalizeMessagePartsForDisplay(): List<UIMessagePart> {
    val firstRenderableContentIndex = indexOfFirst { it.isRenderableContentPart() }
    if (firstRenderableContentIndex < 0) return this

    val deferredReasoningParts = withIndex()
        .filter { indexedValue ->
            indexedValue.index > firstRenderableContentIndex &&
                indexedValue.value.isReasoningDisplayPart()
        }
        .map { it.value }
    if (deferredReasoningParts.isEmpty()) return this

    val normalizedParts = mutableListOf<UIMessagePart>()
    forEachIndexed { index, part ->
        if (index == firstRenderableContentIndex) {
            normalizedParts += deferredReasoningParts
        }
        if (index > firstRenderableContentIndex && part.isReasoningDisplayPart()) {
            return@forEachIndexed
        }
        normalizedParts += part
    }
    return normalizedParts
}

private fun UIMessagePart.isReasoningDisplayPart(): Boolean {
    return this is UIMessagePart.Reasoning || this is UIMessagePart.Thinking
}

private fun UIMessagePart.isRenderableContentPart(): Boolean {
    return when (this) {
        is UIMessagePart.Text -> text.isNotBlank()
        is UIMessagePart.Image -> url.isNotBlank()
        is UIMessagePart.Video -> url.isNotBlank()
        is UIMessagePart.Audio -> url.isNotBlank()
        is UIMessagePart.Document -> url.isNotBlank()
        else -> false
    }
}
