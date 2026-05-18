package me.rerere.rikkahub.ui.pages.chat

import kotlin.uuid.Uuid

internal data class ChatSendScrollRequest(
    val id: Long,
    val expectedMessageIndex: Int,
)

internal data class ChatSendScrollAnchor(
    val requestId: Long,
    val nodeId: Uuid,
)

internal data class ChatSendScrollLayout(
    val visibleUserMessageHeightPx: Int,
    val userMessageScrollOffsetPx: Int,
    val dynamicSpacerHeightPx: Int,
    val replyAreaOverflowing: Boolean,
)

internal fun shouldLockSendScrollPosition(
    hasPendingRequest: Boolean,
    hasActiveAnchor: Boolean,
    initialAnimationDone: Boolean,
    replyAreaOverflowing: Boolean?,
): Boolean {
    return hasPendingRequest ||
        (hasActiveAnchor && (!initialAnimationDone || replyAreaOverflowing != true))
}

internal fun resolveSendScrollLayout(
    viewportHeightPx: Int,
    userMessageHeightPx: Int,
    trailingContentHeightPx: Int,
    afterContentPaddingPx: Int,
): ChatSendScrollLayout {
    val safeViewportHeight = viewportHeightPx.coerceAtLeast(0)
    val safeUserMessageHeight = userMessageHeightPx.coerceAtLeast(0)
    val safeTrailingContentHeight = trailingContentHeightPx.coerceAtLeast(0)
    val safeAfterContentPadding = afterContentPaddingPx.coerceAtLeast(0)

    val maxVisibleUserMessageHeight = (safeViewportHeight / 8).coerceAtLeast(1)
    val visibleUserMessageHeight = if (safeUserMessageHeight == 0) {
        0
    } else {
        safeUserMessageHeight.coerceAtMost(maxVisibleUserMessageHeight)
    }
    val userMessageScrollOffset = (safeUserMessageHeight - visibleUserMessageHeight).coerceAtLeast(0)
    val fixedContentHeight = visibleUserMessageHeight +
        safeTrailingContentHeight +
        safeAfterContentPadding
    val dynamicSpacerHeight = (safeViewportHeight - fixedContentHeight).coerceAtLeast(0)

    return ChatSendScrollLayout(
        visibleUserMessageHeightPx = visibleUserMessageHeight,
        userMessageScrollOffsetPx = userMessageScrollOffset,
        dynamicSpacerHeightPx = dynamicSpacerHeight,
        replyAreaOverflowing = fixedContentHeight > safeViewportHeight,
    )
}

internal fun resolveSendScrollTrailingContentHeightPx(
    anchorIndex: Int,
    messageItemHeightsPx: List<Int>,
    loading: Boolean,
    loadingIndicatorHeightPx: Int,
    bottomMarkerHeightPx: Int,
    itemSpacingPx: Int,
): Int {
    if (anchorIndex !in messageItemHeightsPx.indices) return 0

    val spacing = itemSpacingPx.coerceAtLeast(0)
    var height = 0
    for (index in (anchorIndex + 1) until messageItemHeightsPx.size) {
        height += spacing + messageItemHeightsPx[index].coerceAtLeast(0)
    }
    if (loading) {
        height += spacing + loadingIndicatorHeightPx.coerceAtLeast(0)
    }
    height += spacing + bottomMarkerHeightPx.coerceAtLeast(0)
    return height
}
