package me.rerere.rikkahub.ui.components.message

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

internal class ChatMessageDisplayState(
    val message: UIMessage,
    val renderBlocks: List<MessageRenderBlock>,
) {
    // 复制/朗读/预览全文只在用户点击对应操作时才需要，流式期间每 tick 预先拼接会产生大量短命字符串
    val copyBlocks: List<String> by lazy(LazyThreadSafetyMode.NONE) {
        buildMessageCopyBlocks(renderBlocks)
    }
    val copyText: String by lazy(LazyThreadSafetyMode.NONE) {
        copyBlocks
            .filter { it.isNotBlank() }
            .joinToString(separator = "\n\n")
            .trim()
    }
    val selectionCopyBlocks: List<String> by lazy(LazyThreadSafetyMode.NONE) {
        buildSelectionCopyBlocks(renderBlocks)
    }
    val selectionCopyText: String by lazy(LazyThreadSafetyMode.NONE) {
        selectionCopyBlocks
            .filter { it.isNotBlank() }
            .joinToString(separator = "\n\n")
            .trim()
    }
    val previewText: String by lazy(LazyThreadSafetyMode.NONE) {
        message.parts
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString(separator = "\n\n") { it.text }
            .trim()
    }
    val ttsText: String
        get() = previewText
}

internal fun buildChatMessageDisplayState(
    message: UIMessage,
    leadingDisplaySegments: List<List<UIMessagePart>>,
): ChatMessageDisplayState {
    val displaySegments = leadingDisplaySegments.filter { it.isNotEmpty() } + listOf(message.parts)
    val renderBlocks = buildMessageRenderBlocksFromSegments(displaySegments)
    val displayMessage = if (leadingDisplaySegments.isEmpty()) {
        message
    } else {
        message.copy(parts = displaySegments.flatten())
    }

    return ChatMessageDisplayState(
        message = displayMessage,
        renderBlocks = renderBlocks,
    )
}

private fun buildMessageRenderBlocksFromSegments(segments: List<List<UIMessagePart>>): List<MessageRenderBlock> {
    val blocks = mutableListOf<MessageRenderBlock>()

    segments.forEach { segment ->
        buildMessageRenderBlocks(
            leadingProcessParts = emptyList(),
            parts = segment,
        ).forEach { block ->
            val lastBlock = blocks.lastOrNull()
            when {
                lastBlock is MessageRenderBlock.ProcessGroup && block is MessageRenderBlock.ProcessGroup -> {
                    blocks[blocks.lastIndex] = MessageRenderBlock.ProcessGroup(
                        parts = lastBlock.parts + block.parts,
                    )
                }

                lastBlock is MessageRenderBlock.ImageGroup && block is MessageRenderBlock.ImageGroup -> {
                    blocks[blocks.lastIndex] = MessageRenderBlock.ImageGroup(
                        parts = lastBlock.parts + block.parts,
                    )
                }

                lastBlock is MessageRenderBlock.VideoGroup && block is MessageRenderBlock.VideoGroup -> {
                    blocks[blocks.lastIndex] = MessageRenderBlock.VideoGroup(
                        parts = lastBlock.parts + block.parts,
                    )
                }

                lastBlock is MessageRenderBlock.AudioGroup && block is MessageRenderBlock.AudioGroup -> {
                    blocks[blocks.lastIndex] = MessageRenderBlock.AudioGroup(
                        parts = lastBlock.parts + block.parts,
                    )
                }

                lastBlock is MessageRenderBlock.DocumentGroup && block is MessageRenderBlock.DocumentGroup -> {
                    blocks[blocks.lastIndex] = MessageRenderBlock.DocumentGroup(
                        parts = lastBlock.parts + block.parts,
                    )
                }

                else -> blocks += block
            }
        }
    }

    return blocks
}

private fun buildMessageCopyBlocks(blocks: List<MessageRenderBlock>): List<String> {
    return blocks.flatMap { block ->
        when (block) {
            is MessageRenderBlock.ProcessGroup -> block.parts.mapNotNull(::buildProcessCopyBlock)
            is MessageRenderBlock.TextBlock -> listOfNotNull(block.part.text.takeIf { it.isNotBlank() })
            is MessageRenderBlock.ImageGroup,
            is MessageRenderBlock.VideoGroup,
            is MessageRenderBlock.AudioGroup,
            is MessageRenderBlock.DocumentGroup,
            is MessageRenderBlock.WorkspaceFileReferenceBlock,
            is MessageRenderBlock.QuotedFollowUpBlock,
                -> emptyList()
        }
    }
}

private fun buildSelectionCopyBlocks(blocks: List<MessageRenderBlock>): List<String> {
    return blocks.mapNotNull { block ->
        when (block) {
            is MessageRenderBlock.TextBlock -> block.part.text.takeIf { it.isNotBlank() }
            else -> null
        }
    }
}

private fun buildProcessCopyBlock(part: UIMessagePart): String? {
    return when (part) {
        is UIMessagePart.Reasoning -> part.reasoning.takeIf { it.isNotBlank() }
        is UIMessagePart.Thinking -> part.thinking.takeIf { it.isNotBlank() }
        is UIMessagePart.AskUser -> {
            val qs = part.questions
            val as_ = part.answers
            if (qs != null && as_ != null) {
                qs.zip(as_).joinToString("\n") { (q, a) -> "${q.question}: $a" }.takeIf { it.isNotBlank() }
            } else {
                listOf(part.question, part.answer).filterNotNull().joinToString("\n").takeIf { it.isNotBlank() }
            }
        }
        is UIMessagePart.ToolCall,
        is UIMessagePart.ToolApproval,
        is UIMessagePart.ToolResult,
            -> null
        else -> null
    }
}
