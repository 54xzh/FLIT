package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ClaudeReasoningMetadata
import me.rerere.ai.ui.GoogleThoughtMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.hasGeminiThoughtSignature
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.toMetadata
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiThoughtSignatureContextTest {

    private fun signedAssistantMessage(text: String, sig: String): UIMessage = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Reasoning(
                reasoning = "thinking-$text",
                metadata = GoogleThoughtMetadata(thoughtSignature = sig).toMetadata(),
            ),
            UIMessagePart.Text(
                text = text,
                metadata = GoogleThoughtMetadata(thoughtSignature = "$sig-text").toMetadata(),
            ),
        ),
    )

    private fun plainAssistantMessage(text: String): UIMessage = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text(text)),
    )

    @Test
    fun `effective context check respects truncateIndex, summary boundary, and contextMessageSize`() {
        val nodes = listOf(
            MessageNode.of(UIMessage.user("u1")),
            MessageNode.of(signedAssistantMessage("a1", "sig-1")),
            MessageNode.of(UIMessage.user("u2")),
            MessageNode.of(plainAssistantMessage("a2")),
        )

        // Full context includes signed a1 -> true
        assertTrue(
            hasGeminiThoughtSignatureInEffectiveContext(
                fullNodes = nodes,
                truncateIndex = -1,
                contextSummary = null,
                contextSummaryUpToIndex = -1,
                enableHistorySummarization = false,
                maxHistoryMessages = null,
                contextMessageSize = 64,
            ),
        )

        // Truncated after a1 -> only u2, a2 in effective context -> false
        assertFalse(
            hasGeminiThoughtSignatureInEffectiveContext(
                fullNodes = nodes,
                truncateIndex = 2,
                contextSummary = null,
                contextSummaryUpToIndex = -1,
                enableHistorySummarization = false,
                maxHistoryMessages = null,
                contextMessageSize = 64,
            ),
        )

        // Truncated at the very end (truncateIndex == size) -> empty effective context -> false
        assertFalse(
            hasGeminiThoughtSignatureInEffectiveContext(
                fullNodes = nodes,
                truncateIndex = nodes.size,
                contextSummary = null,
                contextSummaryUpToIndex = -1,
                enableHistorySummarization = false,
                maxHistoryMessages = null,
                contextMessageSize = 64,
            ),
        )

        // Summarized up to index 1 (covers u1, a1) -> only u2, a2 left -> false
        assertFalse(
            hasGeminiThoughtSignatureInEffectiveContext(
                fullNodes = nodes,
                truncateIndex = -1,
                contextSummary = "Summary of first turn",
                contextSummaryUpToIndex = 1,
                enableHistorySummarization = false,
                maxHistoryMessages = null,
                contextMessageSize = 64,
            ),
        )

        // contextMessageSize = 2 -> only u2, a2 kept -> false
        assertFalse(
            hasGeminiThoughtSignatureInEffectiveContext(
                fullNodes = nodes,
                truncateIndex = -1,
                contextSummary = null,
                contextSummaryUpToIndex = -1,
                enableHistorySummarization = false,
                maxHistoryMessages = null,
                contextMessageSize = 2,
            ),
        )
    }

    @Test
    fun `clearGeminiThoughtSignaturesInNodes clears all branches and preserves non-Gemini metadata`() {
        val claudeMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(
                    reasoning = "claude thinking",
                    metadata = ClaudeReasoningMetadata(signature = "claude-sig").toMetadata(),
                ),
                UIMessagePart.Text("claude answer"),
            ),
        )
        val nodes = listOf(
            MessageNode.of(UIMessage.user("u1")),
            MessageNode(
                messages = listOf(
                    signedAssistantMessage("branch-1", "sig-b1"),
                    signedAssistantMessage("branch-2", "sig-b2"),
                    claudeMessage,
                ),
                selectIndex = 0,
            ),
        )

        val outcome = clearGeminiThoughtSignaturesInNodes(nodes)

        assertEquals(2, outcome.clearedMessageCount)
        val clearedNode = outcome.nodes[1]
        assertFalse(clearedNode.messages[0].hasGeminiThoughtSignature())
        assertFalse(clearedNode.messages[1].hasGeminiThoughtSignature())
        assertEquals("branch-1", (clearedNode.messages[0].parts[1] as UIMessagePart.Text).text)
        assertEquals("branch-2", (clearedNode.messages[1].parts[1] as UIMessagePart.Text).text)
        assertEquals(
            "claude-sig",
            clearedNode.messages[2].parts.filterIsInstance<UIMessagePart.Reasoning>()
                .single().metadataAs<ClaudeReasoningMetadata>()?.signature,
        )
    }
}
