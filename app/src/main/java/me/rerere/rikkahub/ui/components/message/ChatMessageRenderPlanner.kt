package me.rerere.rikkahub.ui.components.message

import me.rerere.ai.ui.UIMessagePart

internal sealed interface MessageRenderBlock {
    data class ProcessGroup(
        val parts: List<UIMessagePart>,
    ) : MessageRenderBlock

    data class WorkspaceFileReferenceGroup(
        val items: List<WorkspaceFileReferenceRenderItem>,
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

internal fun MessageRenderBlock.needsWorkspaceFileTimelineSpacing(
    nextBlock: MessageRenderBlock?,
): Boolean {
    return this is MessageRenderBlock.WorkspaceFileReferenceGroup &&
        nextBlock is MessageRenderBlock.ProcessGroup
}

internal fun buildMessageRenderBlocks(
    leadingProcessParts: List<UIMessagePart>,
    parts: List<UIMessagePart>,
): List<MessageRenderBlock> {
    val orderedParts = parts.normalizeMessagePartsForDisplay()
    val blocks = mutableListOf<MessageRenderBlock>()
    val pendingProcessParts = mutableListOf<UIMessagePart>()
    val pendingMediaParts = mutableListOf<UIMessagePart>()
    val pendingWorkspaceFileReferences = mutableListOf<WorkspaceFileReferenceRenderItem>()
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

    fun flushWorkspaceFileReferences() {
        if (pendingWorkspaceFileReferences.isEmpty()) return
        blocks += MessageRenderBlock.WorkspaceFileReferenceGroup(
            items = pendingWorkspaceFileReferences.toList(),
        )
        pendingWorkspaceFileReferences.clear()
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
                    // File-send lifecycle entries are hidden from the timeline, so they should
                    // not split a visible row of consecutively sent files.
                    if (part.isHiddenWorkspaceFileReferencePart() && pendingWorkspaceFileReferences.isNotEmpty()) {
                        return@forEach
                    }
                    flushWorkspaceFileReferences()
                    flushMediaParts()
                    pendingProcessParts += part
                }

            is UIMessagePart.Reasoning,
            is UIMessagePart.Thinking,
            is UIMessagePart.AskUser,
                -> {
                    flushWorkspaceFileReferences()
                    flushMediaParts()
                    pendingProcessParts += part
                }

            is UIMessagePart.ToolResult -> {
                val fileReferenceItem = part.workspaceFileReferenceRenderItem()
                if (fileReferenceItem == null) {
                    flushWorkspaceFileReferences()
                    flushMediaParts()
                    pendingProcessParts += part
                } else {
                    flushMediaParts()
                    flushProcessParts()
                    pendingWorkspaceFileReferences += fileReferenceItem
                }
            }

            is UIMessagePart.Text -> {
                flushWorkspaceFileReferences()
                flushMediaParts()
                flushProcessParts()
                blocks += MessageRenderBlock.TextBlock(
                    part = part,
                    textIndex = textIndex++,
                )
            }

            is UIMessagePart.Image -> {
                flushWorkspaceFileReferences()
                flushProcessParts()
                appendMediaPart(kind = "image", part = part)
            }

            is UIMessagePart.Video -> {
                flushWorkspaceFileReferences()
                flushProcessParts()
                appendMediaPart(kind = "video", part = part)
            }

            is UIMessagePart.Audio -> {
                flushWorkspaceFileReferences()
                flushProcessParts()
                appendMediaPart(kind = "audio", part = part)
            }

            is UIMessagePart.Document -> {
                flushWorkspaceFileReferences()
                flushProcessParts()
                appendMediaPart(kind = "document", part = part)
            }

            is UIMessagePart.QuotedFollowUp -> {
                flushWorkspaceFileReferences()
                flushProcessParts()
                flushMediaParts()
                blocks += MessageRenderBlock.QuotedFollowUpBlock(part = part)
            }

            else -> Unit
        }
    }

    flushMediaParts()
    flushWorkspaceFileReferences()
    flushProcessParts()

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
