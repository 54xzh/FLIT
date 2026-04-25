package me.rerere.rikkahub.data.repository

import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.data.model.MessageNode

internal const val CONVERSATION_SEARCH_TEXT_VERSION = 2

internal fun buildConversationVisibleSearchText(messageNodes: List<MessageNode>): String {
    return messageNodes
        .asSequence()
        .mapNotNull { node -> node.messages.getOrNull(node.selectIndex) }
        .mapNotNull { message ->
            val text = message.toContentText()
            if (text.isBlank()) return@mapNotNull null
            val prefix = when (message.role) {
                MessageRole.USER -> "[user]"
                MessageRole.ASSISTANT -> "[assistant]"
                MessageRole.SYSTEM -> "[system]"
                MessageRole.TOOL -> "[tool]"
            }
            "$prefix $text"
        }
        .joinToString(separator = "\n")
}
