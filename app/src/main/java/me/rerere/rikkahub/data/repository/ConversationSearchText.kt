package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.model.MessageNode

internal const val CONVERSATION_SEARCH_TEXT_VERSION = 1

internal fun buildConversationVisibleSearchText(messageNodes: List<MessageNode>): String {
    return messageNodes
        .asSequence()
        .mapNotNull { node -> node.messages.getOrNull(node.selectIndex) }
        .map { message -> message.toContentText() }
        .filter { text -> text.isNotBlank() }
        .joinToString(separator = "\n")
}
