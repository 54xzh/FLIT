package me.rerere.rikkahub.ui.components.message

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.MessageNode

internal data class ChatProcessDisplayPlan(
    val prefixedProcessPartsByIndex: Map<Int, List<UIMessagePart>> = emptyMap(),
    val prefixedDisplaySegmentsByIndex: Map<Int, List<List<UIMessagePart>>> = emptyMap(),
    val standaloneProcessPartsByIndex: Map<Int, List<UIMessagePart>> = emptyMap(),
    val standaloneAssistantOwnerIndexByIndex: Map<Int, Int> = emptyMap(),
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

private fun UIMessage.hasSameSpeakerIdentity(other: UIMessage): Boolean {
    return speakerSeatId == other.speakerSeatId &&
        speakerAssistantId == other.speakerAssistantId &&
        modelId == other.modelId
}

internal fun planChatProcessDisplay(nodes: List<MessageNode>): ChatProcessDisplayPlan {
    val prefixedProcessPartsByIndex = mutableMapOf<Int, List<UIMessagePart>>()
    val prefixedDisplaySegmentsByIndex = mutableMapOf<Int, List<List<UIMessagePart>>>()
    val standaloneProcessPartsByIndex = mutableMapOf<Int, List<UIMessagePart>>()
    val standaloneAssistantOwnerIndexByIndex = mutableMapOf<Int, Int>()
    val hiddenNodeIndexes = linkedSetOf<Int>()

    val pendingNodeIndexes = mutableListOf<Int>()
    val pendingProcessParts = mutableListOf<UIMessagePart>()

    fun clearPending() {
        pendingNodeIndexes.clear()
        pendingProcessParts.clear()
    }

    fun flushStandalone() {
        if (pendingNodeIndexes.isEmpty() || pendingProcessParts.isEmpty()) return
        val anchorIndex = pendingNodeIndexes.last()
        val assistantOwnerIndex = pendingNodeIndexes
            .asReversed()
            .firstOrNull { nodes[it].currentMessage.role == MessageRole.ASSISTANT }
        standaloneProcessPartsByIndex[anchorIndex] = pendingProcessParts.toList()
        if (assistantOwnerIndex != null) {
            standaloneAssistantOwnerIndexByIndex[anchorIndex] = assistantOwnerIndex
        }
        hiddenNodeIndexes += pendingNodeIndexes
        clearPending()
    }

    nodes.forEachIndexed { index, node ->
        val message = node.currentMessage
        if (message.isProcessOnlyDisplayMessage()) {
            pendingNodeIndexes += index
            pendingProcessParts += message.processDisplayParts()
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
            prefixedDisplaySegmentsByIndex[index] = listOf(pendingProcessParts.toList())
            hiddenNodeIndexes += pendingNodeIndexes
            clearPending()
        } else {
            flushStandalone()
        }
    }

    flushStandalone()

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
        standaloneProcessPartsByIndex = standaloneProcessPartsByIndex,
        standaloneAssistantOwnerIndexByIndex = standaloneAssistantOwnerIndexByIndex,
        hiddenNodeIndexes = hiddenNodeIndexes,
    )
}
