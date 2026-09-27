package me.rerere.rikkahub.data.ai.prompts

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.isStandaloneInterruptedAppContextMarker
import me.rerere.ai.ui.stripInterruptedAppContextForDisplay
import me.rerere.ai.ui.truncate
import kotlin.uuid.Uuid

internal fun buildContentForSuggestion(
    messages: List<UIMessage>,
    truncateIndex: Int,
    maxMessages: Int = 4,
): String = messages.truncate(truncateIndex)
    .asSequence()
    .mapNotNull { message ->
        val text = message.toContentText().stripInterruptedAppContextForDisplay().trim()
        if (text.isNotBlank()) {
            "[${message.role.name}]: $text"
        } else {
            null
        }
    }
    .toList()
    .takeLast(maxMessages)
    .joinToString("\n\n")

internal fun buildRecentUserMessagesForSuggestion(
    messages: List<UIMessage>,
    truncateIndex: Int,
    presetMessageIds: Set<Uuid>,
    maxMessages: Int = 3,
): String = messages.truncate(truncateIndex)
    .asSequence()
    .filter { message ->
        message.role == MessageRole.USER &&
            message.id !in presetMessageIds &&
            !message.isStandaloneInterruptedAppContextMarker()
    }
    .map { it.toContentText().stripInterruptedAppContextForDisplay().trim() }
    .filter { it.isNotBlank() }
    .toList()
    .takeLast(maxMessages)
    .joinToString("\n\n")

internal val DEFAULT_SUGGESTION_PROMPT = """
    I will provide you with recent chat content in the `<content>` block and recent user messages in the `<recent_user_messages>` block.
    You need to act as the **User** to reply to the assistant, generating 3~5 appropriate and contextually relevant responses.

    Rules:
    1. Reply directly with suggestions, do not add any formatting, and separate suggestions with newlines, no need to add markdown list formats.
    2. Use {locale} language.
    3. Ensure each suggestion is valid.
    4. Each suggestion should not exceed 10 characters.
    5. Imitate the user's previous conversational style shown in `<recent_user_messages>`.
    6. Act strictly as the User! Do not act as an Assistant, and do not act as any in-story or roleplay character (e.g., NPCs, narrators).
    7. Output distinct suggestions without duplicates or repetitive phrasing.

    <content>
    {content}
    </content>

    <recent_user_messages>
    {recent_user_messages}
    </recent_user_messages>
""".trimIndent()

internal val LEGACY_DEFAULT_SUGGESTION_PROMPT = """
    I will provide you with some chat content in the `<content>` block, including conversations between the User and the AI assistant.
    You need to act as the **User** to reply to the assistant, generating 3~5 appropriate and contextually relevant responses to the assistant.

    Rules:
    1. Reply directly with suggestions, do not add any formatting, and separate suggestions with newlines, no need to add markdown list formats.
    2. Use {locale} language.
    3. Ensure each suggestion is valid.
    4. Each suggestion should not exceed 10 characters.
    5. Imitate the user's previous conversational style.
    6. Act as a User, not an Assistant!

    <content>
    {content}
    </content>
""".trimIndent()

internal fun upgradeLegacySuggestionPrompt(prompt: String): String =
    if (prompt == LEGACY_DEFAULT_SUGGESTION_PROMPT) DEFAULT_SUGGESTION_PROMPT else prompt
