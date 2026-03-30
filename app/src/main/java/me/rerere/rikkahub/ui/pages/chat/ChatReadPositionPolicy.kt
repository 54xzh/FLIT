package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.data.datastore.ConversationReadPosition
import me.rerere.rikkahub.data.model.MessageNode
import kotlin.uuid.Uuid

internal data class ChatListInitialPosition(
    val index: Int,
    val offset: Int,
)

internal data class ConversationReadPositionSample(
    val nodeId: Uuid,
    val offset: Int,
    val itemIndex: Int,
)

internal fun shouldRunReadPositionRestore(
    initialSearchQuery: String?,
    pendingJumpNodeId: Uuid?,
    previewMode: Boolean,
): Boolean {
    return initialSearchQuery.isNullOrBlank() && pendingJumpNodeId == null && !previewMode
}

internal fun shouldStartInitialReadPositionRestore(
    settingsReady: Boolean,
    conversationInitialized: Boolean,
    initialEntryHandled: Boolean,
    initialSearchQuery: String?,
    pendingJumpNodeId: Uuid?,
    previewMode: Boolean,
): Boolean {
    return settingsReady &&
        conversationInitialized &&
        !initialEntryHandled &&
        shouldRunReadPositionRestore(
            initialSearchQuery = initialSearchQuery,
            pendingJumpNodeId = pendingJumpNodeId,
            previewMode = previewMode,
        )
}

internal fun shouldPersistCurrentReadPosition(
    settingsReady: Boolean,
    previewMode: Boolean,
    initialEntryHandled: Boolean,
): Boolean {
    return settingsReady && !previewMode && initialEntryHandled
}

internal fun resolveReadPositionNodeIndex(
    messageNodes: List<MessageNode>,
    nodeId: String?,
): Int {
    val parsed = parseReadPositionNodeId(nodeId) ?: return -1
    return messageNodes.indexOfFirst { it.id == parsed }
}

internal fun parseReadPositionNodeId(nodeId: String?): Uuid? {
    if (nodeId.isNullOrBlank()) return null
    return runCatching { Uuid.parse(nodeId) }.getOrNull()
}

internal fun isCachedScrollPositionUsable(
    cachedPosition: Pair<Int, Int>?,
    itemCount: Int,
): Boolean {
    val position = cachedPosition ?: return false
    if (itemCount <= 0) return false
    return position.first in 0 until itemCount
}

internal fun resolveBottomFallbackIndex(
    itemCount: Int,
): Int {
    return itemCount.coerceAtLeast(0)
}

internal fun resolveInitialChatListPosition(
    cachedPosition: Pair<Int, Int>?,
    persistedReadPosition: ConversationReadPosition?,
    itemCount: Int,
): ChatListInitialPosition {
    if (itemCount <= 0) {
        return ChatListInitialPosition(index = 0, offset = 0)
    }

    val persistedIndex = persistedReadPosition
        ?.itemIndex
        ?.coerceIn(0, itemCount - 1)
    val persistedOffset = persistedReadPosition
        ?.offset
        ?.coerceAtLeast(0)

    return when {
        isCachedScrollPositionUsable(cachedPosition, itemCount) -> ChatListInitialPosition(
            index = cachedPosition?.first ?: persistedIndex ?: resolveBottomFallbackIndex(itemCount),
            offset = cachedPosition?.second ?: persistedOffset ?: 0,
        )
        persistedIndex != null -> ChatListInitialPosition(
            index = persistedIndex,
            offset = persistedOffset ?: 0,
        )
        else -> ChatListInitialPosition(
            index = resolveBottomFallbackIndex(itemCount),
            offset = 0,
        )
    }
}

internal fun resolveCurrentReadPositionSample(
    messageNodes: List<MessageNode>,
    itemIndex: Int,
    offset: Int,
): ConversationReadPositionSample? {
    if (messageNodes.isEmpty()) return null

    val normalizedItemIndex = itemIndex.coerceAtLeast(0)
    val safeMessageIndex = normalizedItemIndex.coerceIn(0, messageNodes.lastIndex)
    val nodeId = messageNodes
        .getOrNull(normalizedItemIndex)
        ?.id
        ?: messageNodes[safeMessageIndex].id

    return ConversationReadPositionSample(
        nodeId = nodeId,
        offset = offset.coerceAtLeast(0),
        itemIndex = safeMessageIndex,
    )
}

internal fun shouldPersistConversationReadPosition(
    existing: ConversationReadPosition?,
    incoming: ConversationReadPosition,
): Boolean {
    if (existing == null) return true
    return existing.nodeId != incoming.nodeId ||
        existing.offset != incoming.offset ||
        existing.itemIndex != incoming.itemIndex
}
