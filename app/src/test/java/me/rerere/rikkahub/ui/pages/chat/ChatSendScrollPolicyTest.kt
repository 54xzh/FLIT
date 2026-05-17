package me.rerere.rikkahub.ui.pages.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSendScrollPolicyTest {
    @Test
    fun resolveSendScrollLayout_showsWholeShortUserMessage() {
        val layout = resolveSendScrollLayout(
            viewportHeightPx = 800,
            userMessageHeightPx = 72,
            trailingContentHeightPx = 40,
            afterContentPaddingPx = 120,
        )

        assertEquals(72, layout.visibleUserMessageHeightPx)
        assertEquals(0, layout.userMessageScrollOffsetPx)
        assertEquals(568, layout.dynamicSpacerHeightPx)
        assertFalse(layout.replyAreaOverflowing)
    }

    @Test
    fun resolveSendScrollLayout_capsLongUserMessageAtOneEighthScreen() {
        val layout = resolveSendScrollLayout(
            viewportHeightPx = 800,
            userMessageHeightPx = 260,
            trailingContentHeightPx = 40,
            afterContentPaddingPx = 120,
        )

        assertEquals(100, layout.visibleUserMessageHeightPx)
        assertEquals(160, layout.userMessageScrollOffsetPx)
        assertEquals(540, layout.dynamicSpacerHeightPx)
        assertFalse(layout.replyAreaOverflowing)
    }

    @Test
    fun resolveSendScrollLayout_reducesSpacerAsAssistantGrows() {
        val early = resolveSendScrollLayout(
            viewportHeightPx = 800,
            userMessageHeightPx = 100,
            trailingContentHeightPx = 80,
            afterContentPaddingPx = 120,
        )
        val later = resolveSendScrollLayout(
            viewportHeightPx = 800,
            userMessageHeightPx = 100,
            trailingContentHeightPx = 300,
            afterContentPaddingPx = 120,
        )

        assertEquals(500, early.dynamicSpacerHeightPx)
        assertEquals(280, later.dynamicSpacerHeightPx)
        assertEquals(220, early.dynamicSpacerHeightPx - later.dynamicSpacerHeightPx)
    }

    @Test
    fun resolveSendScrollLayout_removesSpacerWhenAssistantOverflows() {
        val layout = resolveSendScrollLayout(
            viewportHeightPx = 800,
            userMessageHeightPx = 80,
            trailingContentHeightPx = 760,
            afterContentPaddingPx = 120,
        )

        assertEquals(0, layout.dynamicSpacerHeightPx)
        assertTrue(layout.replyAreaOverflowing)
    }

    @Test
    fun resolveSendScrollLayout_subtractsBottomPaddingForInputAndKeyboard() {
        val compactInput = resolveSendScrollLayout(
            viewportHeightPx = 800,
            userMessageHeightPx = 80,
            trailingContentHeightPx = 120,
            afterContentPaddingPx = 120,
        )
        val tallInputOrKeyboard = resolveSendScrollLayout(
            viewportHeightPx = 800,
            userMessageHeightPx = 80,
            trailingContentHeightPx = 120,
            afterContentPaddingPx = 320,
        )

        assertEquals(480, compactInput.dynamicSpacerHeightPx)
        assertEquals(280, tallInputOrKeyboard.dynamicSpacerHeightPx)
    }

    @Test
    fun resolveSendScrollTrailingContentHeightPx_countsMessagesLoadingBottomAndSpacing() {
        val trailingHeight = resolveSendScrollTrailingContentHeightPx(
            anchorIndex = 1,
            messageItemHeightsPx = listOf(60, 90, 240),
            loading = true,
            loadingIndicatorHeightPx = 36,
            bottomMarkerHeightPx = 5,
            itemSpacingPx = 4,
        )

        assertEquals(293, trailingHeight)
    }

    @Test
    fun shouldLockSendScrollPosition_locksWhileRequestIsPending() {
        assertTrue(
            shouldLockSendScrollPosition(
                hasPendingRequest = true,
                hasActiveAnchor = false,
                initialAnimationDone = false,
                replyAreaOverflowing = null,
            )
        )
    }

    @Test
    fun shouldLockSendScrollPosition_unlocksOnlyAfterOverflow() {
        assertTrue(
            shouldLockSendScrollPosition(
                hasPendingRequest = false,
                hasActiveAnchor = true,
                initialAnimationDone = true,
                replyAreaOverflowing = false,
            )
        )
        assertFalse(
            shouldLockSendScrollPosition(
                hasPendingRequest = false,
                hasActiveAnchor = true,
                initialAnimationDone = true,
                replyAreaOverflowing = true,
            )
        )
    }
}
