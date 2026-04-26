package me.rerere.rikkahub.service

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenUsageAccountingTest {
    @Test
    fun `counts every new assistant usage from tool loop`() {
        val user = UIMessage.user("Find the latest release notes")
        val toolCall = assistantMessage(
            text = "",
            usage = TokenUsage(promptTokens = 12_000, completionTokens = 120),
            parts = listOf(UIMessagePart.ToolCall("call_1", "search_web", "{}")),
        )
        val toolResult = UIMessage(
            role = MessageRole.TOOL,
            parts = listOf(
                UIMessagePart.ToolResult(
                    toolCallId = "call_1",
                    toolName = "search_web",
                    content = JsonPrimitive("large result"),
                    arguments = JsonPrimitive("{}"),
                )
            ),
        )
        val finalAnswer = assistantMessage(
            text = "Here is the summary",
            usage = TokenUsage(promptTokens = 23_032, completionTokens = 756, cachedTokens = 32),
        )

        val delta = calculateQuotaTokenUsageDelta(
            baselineMessages = listOf(user),
            finalMessages = listOf(user, toolCall, toolResult, finalAnswer),
        )

        assertEquals(35_032L, delta.inputTokens)
        assertEquals(876L, delta.outputTokens)
        assertEquals(32L, delta.cachedTokens)
    }

    @Test
    fun `does not count unchanged historical assistant usage`() {
        val user = UIMessage.user("Hello")
        val oldAssistant = assistantMessage(
            text = "Old answer",
            usage = TokenUsage(promptTokens = 1_000, completionTokens = 100),
        )

        val delta = calculateQuotaTokenUsageDelta(
            baselineMessages = listOf(user, oldAssistant),
            finalMessages = listOf(user, oldAssistant),
        )

        assertTrue(delta.isEmpty)
    }

    @Test
    fun `counts changed usage on continued assistant message as one new request`() {
        val original = assistantMessage(
            text = "Partial answer",
            usage = TokenUsage(promptTokens = 1_000, completionTokens = 100),
        )
        val continued = original.copy(
            parts = listOf(UIMessagePart.Text("Partial answer continued")),
            usage = TokenUsage(promptTokens = 4_000, completionTokens = 250, cachedTokens = 10),
        )

        val delta = calculateQuotaTokenUsageDelta(
            baselineMessages = listOf(original),
            finalMessages = listOf(continued),
        )

        assertEquals(4_000L, delta.inputTokens)
        assertEquals(250L, delta.outputTokens)
        assertEquals(10L, delta.cachedTokens)
    }

    private fun assistantMessage(
        text: String,
        usage: TokenUsage,
        parts: List<UIMessagePart> = listOf(UIMessagePart.Text(text)),
    ): UIMessage {
        return UIMessage(
            role = MessageRole.ASSISTANT,
            parts = parts,
            usage = usage,
        )
    }
}
