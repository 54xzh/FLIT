package me.rerere.rikkahub.service

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.clearGeminiThoughtSignatures
import me.rerere.ai.ui.hasGeminiThoughtSignature
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.data.model.MessageNode

internal data class NodeSignatureClearOutcome(
    val nodes: List<MessageNode>,
    val clearedMessageCount: Int,
)

/**
 * 计算当前会话实际会发送给模型的有效上下文消息列表，规则与 GenerationHandler 保持一致：
 * 1. 应用截断线（truncateIndex）；
 * 2. 应用上下文总结边界（contextSummary + contextSummaryUpToIndex）；
 * 3. 应用历史条数上限与助手上下文条数上限（maxHistoryMessages / contextMessageSize）。
 */
internal fun resolveEffectiveContextMessagesForThoughtSignatureCheck(
    fullNodes: List<MessageNode>,
    truncateIndex: Int,
    contextSummary: String?,
    contextSummaryUpToIndex: Int,
    enableHistorySummarization: Boolean,
    maxHistoryMessages: Int?,
    contextMessageSize: Int,
): List<UIMessage> {
    val fullMessages = fullNodes.mapNotNull { node ->
        node.messages.getOrNull(node.selectIndex)
    }
    if (fullMessages.isEmpty()) return emptyList()

    val afterTruncate = when {
        truncateIndex >= fullMessages.size -> return emptyList()
        truncateIndex in 0..fullMessages.lastIndex -> fullMessages.subList(truncateIndex, fullMessages.size)
        else -> fullMessages
    }

    val hasSummary = !contextSummary.isNullOrBlank()
    val afterSummary = if (hasSummary && contextSummaryUpToIndex in 0 until afterTruncate.size) {
        afterTruncate.drop(contextSummaryUpToIndex + 1)
    } else {
        afterTruncate
    }

    val historyLimited = if (enableHistorySummarization) {
        maxHistoryMessages?.takeIf { it > 0 }?.let { limit ->
            afterSummary.limitContext(limit)
        } ?: afterSummary
    } else {
        afterSummary
    }

    return historyLimited.limitContext(contextMessageSize.coerceAtLeast(0))
}

internal fun hasGeminiThoughtSignatureInEffectiveContext(
    fullNodes: List<MessageNode>,
    truncateIndex: Int,
    contextSummary: String?,
    contextSummaryUpToIndex: Int,
    enableHistorySummarization: Boolean,
    maxHistoryMessages: Int?,
    contextMessageSize: Int,
): Boolean {
    val effectiveMessages = resolveEffectiveContextMessagesForThoughtSignatureCheck(
        fullNodes = fullNodes,
        truncateIndex = truncateIndex,
        contextSummary = contextSummary,
        contextSummaryUpToIndex = contextSummaryUpToIndex,
        enableHistorySummarization = enableHistorySummarization,
        maxHistoryMessages = maxHistoryMessages,
        contextMessageSize = contextMessageSize,
    )
    return effectiveMessages.any { it.hasGeminiThoughtSignature() }
}

/**
 * 清除节点列表中所有消息（包含同节点的非当前分支）的 Gemini 思考签名。
 */
internal fun clearGeminiThoughtSignaturesInNodes(
    nodes: List<MessageNode>,
): NodeSignatureClearOutcome {
    var clearedMessageCount = 0
    val nextNodes = nodes.map { node ->
        var nodeChanged = false
        val nextMessages = node.messages.map { message ->
            val (cleanedMessage, cleared) = message.clearGeminiThoughtSignatures()
            if (cleared) clearedMessageCount++
            if (cleanedMessage !== message) nodeChanged = true
            cleanedMessage
        }
        if (nodeChanged) node.copy(messages = nextMessages) else node
    }
    return NodeSignatureClearOutcome(
        nodes = nextNodes,
        clearedMessageCount = clearedMessageCount,
    )
}
