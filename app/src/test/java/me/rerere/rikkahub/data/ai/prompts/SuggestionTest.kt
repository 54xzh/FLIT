package me.rerere.rikkahub.data.ai.prompts

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test

class SuggestionTest {
    @Test
    fun legacyDefaultPromptUpgradesWithoutChangingCustomPrompts() {
        assertEquals(DEFAULT_SUGGESTION_PROMPT, upgradeLegacySuggestionPrompt(LEGACY_DEFAULT_SUGGESTION_PROMPT))
        assertEquals("custom {content}", upgradeLegacySuggestionPrompt("custom {content}"))
    }

    @Test
    fun recentUserMessagesExcludePresetAndInterruptedMarker() {
        val preset = UIMessage.user("preset")
        val messages = listOf(
            preset,
            UIMessage.user("first"),
            UIMessage.assistant("reply"),
            UIMessage.user("second"),
            UIMessage.user("third"),
            UIMessage.user("fourth"),
            UIMessage.user("fifth"),
            UIMessage.user("<app_context>The user stopped the output.</app_context>"),
            UIMessage.user("sixth"),
        )

        assertEquals(
            "fourth\n\nfifth\n\nsixth",
            buildRecentUserMessagesForSuggestion(
                messages = messages,
                truncateIndex = -1,
                presetMessageIds = setOf(preset.id),
            ),
        )
    }

    @Test
    fun recentUserMessagesHonorConversationTruncationAndSkipBlankText() {
        val messages = listOf(
            UIMessage.user("before truncation"),
            UIMessage.user("kept"),
            UIMessage.user(""),
            UIMessage.assistant("assistant reply"),
            UIMessage.user("latest"),
        )

        assertEquals(
            "kept\n\nlatest",
            buildRecentUserMessagesForSuggestion(
                messages = messages,
                truncateIndex = 1,
                presetMessageIds = emptySet(),
            ),
        )
    }

    @Test
    fun contentForSuggestionExcludesThinkingAndKeepsAtMostFourMessages() {
        val messages = listOf(
            UIMessage.user("message 1"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Thinking("internal thought 1"),
                    UIMessagePart.Text("reply 1"),
                ),
            ),
            UIMessage.user("message 2"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning("internal reasoning 2"),
                    UIMessagePart.Text("reply 2"),
                ),
            ),
            UIMessage.user("message 3"),
        )

        val content = buildContentForSuggestion(
            messages = messages,
            truncateIndex = -1,
        )

        assertEquals(
            "[ASSISTANT]: reply 1\n\n[USER]: message 2\n\n[ASSISTANT]: reply 2\n\n[USER]: message 3",
            content,
        )
    }
}

