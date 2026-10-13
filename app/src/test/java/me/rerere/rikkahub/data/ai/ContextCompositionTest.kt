package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ContextCompositionKind
import me.rerere.ai.ui.ContextCompositionSlice
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.service.injectWelcomePhraseIntoFirstUserMessage
import me.rerere.rikkahub.service.welcomeAppContextPrefix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCompositionTest {
    @Test
    fun estimatorCountsEmptyAsZeroAndChineseAboveCharQuarter() {
        assertEquals(0, ContextTokenEstimator.estimate(""))
        assertTrue(ContextTokenEstimator.estimate("hello") > 0)
        val chinese = "你好世界这是一段较长的中文内容用来比较字数估算"
        assertTrue(ContextTokenEstimator.estimate(chinese) > chinese.length / 4)
    }

    @Test
    fun interruptMarkerIsSplitOutOfConversationText() {
        val marker = "<app_context>The user stopped the output.</app_context>"
        val (conversation, other) = partitionHistoryText("hello$marker")
        assertEquals("hello", conversation)
        assertTrue(other.contains("The user stopped the output."))

        val (standaloneConversation, standaloneOther) = partitionHistoryText(marker)
        assertEquals("", standaloneConversation)
        assertEquals(marker, standaloneOther)
    }

    @Test
    fun plainHistoryStaysInConversation() {
        val (conversation, other) = partitionHistoryText("just a user message")
        assertEquals("just a user message", conversation)
        assertEquals("", other)
    }

    @Test
    fun scaledTokensUseInputTotalAndKeepLocalProportions() {
        assertEquals(listOf(25, 75), scaleCompositionTokens(listOf(1, 3), 100))
        assertEquals(100, scaleCompositionTokens(listOf(1, 1, 1), 100).sum())
        assertEquals(listOf(1, 3), scaleCompositionTokens(listOf(1, 3), null))
    }

    @Test
    fun tokenMagnitudeUsesThousandsAboveOneK() {
        assertEquals("567", formatCompositionTokenMagnitude(567))
        assertEquals("1K", formatCompositionTokenMagnitude(1_000))
        assertEquals("2.6K", formatCompositionTokenMagnitude(2_600))
        assertEquals("10.5K", formatCompositionTokenMagnitude(10_500))
    }

    @Test
    fun barHitTestingFindsTheTappedSegment() {
        val weights = listOf(1, 3)
        assertEquals(0, compositionSegmentIndexAt(10f, 100f, weights))
        assertEquals(1, compositionSegmentIndexAt(30f, 100f, weights))
        assertEquals(0.625f, compositionSegmentCenterFraction(weights, 1), 0.001f)
    }

    @Test
    fun percentagesSumToOneHundred() {
        assertEquals(listOf(50, 50), compositionPercentages(listOf(1, 1)))
        assertEquals(100, compositionPercentages(listOf(1, 1, 1)).sum())
        assertEquals(listOf(100), compositionPercentages(listOf(7)))
    }

    @Test
    fun welcomePrefixIsCountedAsOtherWithoutChangingConversation() {
        val injected = injectWelcomePhraseIntoFirstUserMessage(
            messages = listOf(UIMessage.user("user text")),
            uiWelcomePhrase = "你好呀",
        )
        val text = (injected.messages.single().parts.single() as UIMessagePart.Text).text
        val prefix = welcomeAppContextPrefix(text)
        assertTrue(prefix != null && prefix.contains("你好呀"))

        val slices = listOf(
            ContextCompositionSlice(ContextCompositionKind.CONVERSATION, 10),
        ).includingWelcomePrefixes(injected.messages)

        assertEquals(10, slices!!.first { it.kind == ContextCompositionKind.CONVERSATION }.estimatedTokens)
        assertTrue(slices.first { it.kind == ContextCompositionKind.OTHER }.estimatedTokens > 0)
    }

    @Test
    fun missingWelcomeLeavesSlicesUntouched() {
        val slices = listOf(
            ContextCompositionSlice(ContextCompositionKind.CONVERSATION, 4),
        )
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hello"))),
        )
        assertEquals(slices, slices.includingWelcomePrefixes(messages))
        assertNull(welcomeAppContextPrefix("hello"))
    }
}
