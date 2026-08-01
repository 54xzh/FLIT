package me.rerere.rikkahub.ui.components.message

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isStandaloneInterruptedAppContextMarker
import me.rerere.rikkahub.data.model.MessageNode

internal data class ChatProcessDisplayPlan(
    val prefixedProcessPartsByIndex: Map<Int, List<UIMessagePart>> = emptyMap(),
    val prefixedDisplaySegmentsByIndex: Map<Int, List<List<UIMessagePart>>> = emptyMap(),
    val standaloneProcessSegmentsByIndex: Map<Int, List<List<UIMessagePart>>> = emptyMap(),
    val standaloneAssistantOwnerIndexByIndex: Map<Int, Int> = emptyMap(),
    val visibleTrailingProcessOwnerIndexes: Set<Int> = emptySet(),
    val hiddenNodeIndexes: Set<Int> = emptySet(),
)

internal fun UIMessagePart.isProcessDisplayPart(): Boolean {
    return when (this) {
        is UIMessagePart.Reasoning,
        is UIMessagePart.Thinking,
        is UIMessagePart.ToolCall,
        is UIMessagePart.ToolApproval,
        is UIMessagePart.ToolResult,
        is UIMessagePart.AskUser,
            -> true

        else -> false
    }
}

internal fun UIMessage.processDisplayParts(): List<UIMessagePart> {
    return parts.filter { it.isProcessDisplayPart() }
}

internal fun UIMessage.hasRenderableNonProcessParts(): Boolean {
    return parts.any { part ->
        when (part) {
            is UIMessagePart.Text -> part.text.isNotBlank()
            is UIMessagePart.Image -> part.url.isNotBlank()
            is UIMessagePart.Video -> part.url.isNotBlank()
            is UIMessagePart.Audio -> part.url.isNotBlank()
            is UIMessagePart.Document -> part.url.isNotBlank()
            else -> false
        }
    }
}

internal fun UIMessage.isProcessOnlyDisplayMessage(): Boolean {
    return processDisplayParts().isNotEmpty() && !hasRenderableNonProcessParts()
}

/**
 * Tool calls and their results are stored as separate nodes, so the raw last node
 * is not necessarily the only node that belongs to the active assistant turn. Keep
 * the user node too until the first assistant/tool node has been appended.
 */
internal fun findCurrentGenerationNodeIndexes(nodes: List<MessageNode>): Set<Int> {
    val latestUserIndex = nodes.indexOfLast {
        it.currentMessage.role == MessageRole.USER &&
            !it.currentMessage.isStandaloneInterruptedAppContextMarker()
    }
    if (latestUserIndex < 0) return nodes.indices.toSet()
    if (latestUserIndex == nodes.lastIndex) return setOf(latestUserIndex)
    return nodes.indices.filter { it > latestUserIndex }.toSet()
}

private fun UIMessage.hasSameSpeakerIdentity(other: UIMessage): Boolean {
    return speakerSeatId == other.speakerSeatId &&
        speakerAssistantId == other.speakerAssistantId &&
        modelId == other.modelId
}

internal fun planChatProcessDisplay(
    nodes: List<MessageNode>,
    keepTrailingProcessOwnerVisible: Boolean = false,
): ChatProcessDisplayPlan {
    val prefixedProcessPartsByIndex = mutableMapOf<Int, List<UIMessagePart>>()
    val prefixedDisplaySegmentsByIndex = mutableMapOf<Int, List<List<UIMessagePart>>>()
    val standaloneProcessSegmentsByIndex = mutableMapOf<Int, List<List<UIMessagePart>>>()
    val standaloneAssistantOwnerIndexByIndex = mutableMapOf<Int, Int>()
    val visibleTrailingProcessOwnerIndexes = linkedSetOf<Int>()
    val hiddenNodeIndexes = linkedSetOf<Int>()

    val pendingNodeIndexes = mutableListOf<Int>()
    val pendingProcessParts = mutableListOf<UIMessagePart>()
    val pendingProcessSegments = mutableListOf<List<UIMessagePart>>()

    fun clearPending() {
        pendingNodeIndexes.clear()
        pendingProcessParts.clear()
        pendingProcessSegments.clear()
    }

    fun flushStandalone(isTrailingProcessGroup: Boolean = false) {
        if (pendingNodeIndexes.isEmpty() || pendingProcessParts.isEmpty()) return
        val anchorIndex = pendingNodeIndexes.last()
        val assistantOwnerIndex = pendingNodeIndexes
            .asReversed()
            .firstOrNull { nodes[it].currentMessage.role == MessageRole.ASSISTANT }
        if (
            keepTrailingProcessOwnerVisible &&
            isTrailingProcessGroup &&
            assistantOwnerIndex != null
        ) {
            visibleTrailingProcessOwnerIndexes += assistantOwnerIndex
            hiddenNodeIndexes += pendingNodeIndexes.filter { it != assistantOwnerIndex }
            clearPending()
            return
        }
        standaloneProcessSegmentsByIndex[anchorIndex] = pendingProcessSegments.toList()
        if (assistantOwnerIndex != null) {
            standaloneAssistantOwnerIndexByIndex[anchorIndex] = assistantOwnerIndex
        }
        hiddenNodeIndexes += pendingNodeIndexes
        clearPending()
    }

    nodes.forEachIndexed { index, node ->
        val message = node.currentMessage
        // 纯打断标记的独立 user 消息: 整条隐藏, 不渲染成空气泡, 也不参与连续发言头判断.
        if (message.isStandaloneInterruptedAppContextMarker()) {
            // 标记位于工具过程之后。先收束待处理节点，否则 clearPending 会让
            // 工具调用和工具结果重新作为独立消息卡片渲染，并各自显示工具栏。
            flushStandalone(isTrailingProcessGroup = true)
            hiddenNodeIndexes += index
            clearPending()
            return@forEachIndexed
        }
        if (message.isProcessOnlyDisplayMessage()) {
            val processParts = message.processDisplayParts()
            pendingNodeIndexes += index
            pendingProcessParts += processParts
            pendingProcessSegments += processParts
            return@forEachIndexed
        }

        if (pendingProcessParts.isEmpty()) return@forEachIndexed

        val pendingAssistantOwner = pendingNodeIndexes
            .asReversed()
            .asSequence()
            .map { nodes[it].currentMessage }
            .firstOrNull { it.role == MessageRole.ASSISTANT }
        val canAttachToCurrentMessage =
            message.role == MessageRole.ASSISTANT &&
                message.hasRenderableNonProcessParts() &&
                (pendingAssistantOwner == null || pendingAssistantOwner.hasSameSpeakerIdentity(message))

        if (canAttachToCurrentMessage) {
            prefixedProcessPartsByIndex[index] = pendingProcessParts.toList()
            prefixedDisplaySegmentsByIndex[index] = pendingProcessSegments.toList()
            hiddenNodeIndexes += pendingNodeIndexes
            clearPending()
        } else {
            flushStandalone()
        }
    }

    flushStandalone(isTrailingProcessGroup = true)

    var pendingAssistantIndex: Int? = null
    var pendingAssistantMessage: UIMessage? = null
    var pendingAssistantDisplaySegments: List<List<UIMessagePart>> = emptyList()

    nodes.indices.forEach { index ->
        if (index in hiddenNodeIndexes) return@forEach

        val message = nodes[index].currentMessage
        if (message.role != MessageRole.ASSISTANT || !message.hasRenderableNonProcessParts()) {
            pendingAssistantIndex = null
            pendingAssistantMessage = null
            pendingAssistantDisplaySegments = emptyList()
            return@forEach
        }

        val currentLeadingSegments = prefixedDisplaySegmentsByIndex[index].orEmpty()
        val previousAssistantIndex = pendingAssistantIndex
        val previousAssistantMessage = pendingAssistantMessage

        if (
            previousAssistantIndex != null &&
            previousAssistantMessage != null &&
            previousAssistantMessage.hasSameSpeakerIdentity(message)
        ) {
            prefixedDisplaySegmentsByIndex[index] = pendingAssistantDisplaySegments + currentLeadingSegments
            hiddenNodeIndexes += previousAssistantIndex
        }

        pendingAssistantIndex = index
        pendingAssistantMessage = message
        pendingAssistantDisplaySegments = prefixedDisplaySegmentsByIndex[index].orEmpty() + listOf(message.parts)
    }

    return ChatProcessDisplayPlan(
        prefixedProcessPartsByIndex = prefixedProcessPartsByIndex,
        prefixedDisplaySegmentsByIndex = prefixedDisplaySegmentsByIndex,
        standaloneProcessSegmentsByIndex = standaloneProcessSegmentsByIndex,
        standaloneAssistantOwnerIndexByIndex = standaloneAssistantOwnerIndexByIndex,
        visibleTrailingProcessOwnerIndexes = visibleTrailingProcessOwnerIndexes,
        hiddenNodeIndexes = hiddenNodeIndexes,
    )
}
