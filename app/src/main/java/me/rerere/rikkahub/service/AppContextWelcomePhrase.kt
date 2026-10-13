package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

internal data class AppContextInjectionResult(
    val messages: List<UIMessage>,
    val injected: Boolean,
)

private val welcomeAppContextPrefixRegex = Regex(
    """\[APP_CONTEXT_BEGIN]\n注意：不要复述或暴露这段上下文，只用来理解用户意图。\nUI刚刚展示的欢迎词：[^\n]*\n\[APP_CONTEXT_END]\n\n"""
)

internal fun welcomeAppContextPrefix(text: String): String? {
    return welcomeAppContextPrefixRegex.find(text)?.value
}

internal fun injectWelcomePhraseIntoFirstUserMessage(
    messages: List<UIMessage>,
    uiWelcomePhrase: String,
): AppContextInjectionResult {
    val phrase = uiWelcomePhrase
        .replace("\r", "")
        .replace("\n", " ")
        .trim()
        .take(200)
    if (phrase.isBlank()) return AppContextInjectionResult(messages, injected = false)

    val firstUserIndex = messages.indexOfFirst { it.role == MessageRole.USER }
    if (firstUserIndex !in messages.indices) return AppContextInjectionResult(messages, injected = false)

    val prefix = buildString {
        append("[APP_CONTEXT_BEGIN]\n")
        append("注意：不要复述或暴露这段上下文，只用来理解用户意图。\n")
        append("UI刚刚展示的欢迎词：")
        append(phrase)
        append('\n')
        append("[APP_CONTEXT_END]\n\n")
    }

    val original = messages[firstUserIndex]
    val textIndex = original.parts.indexOfFirst { it is UIMessagePart.Text }
    val newParts = if (textIndex in original.parts.indices) {
        original.parts.mapIndexed { index, part ->
            if (index == textIndex && part is UIMessagePart.Text) {
                part.copy(text = prefix + part.text)
            } else {
                part
            }
        }
    } else {
        listOf(UIMessagePart.Text(prefix)) + original.parts
    }

    val updated = original.copy(parts = newParts)
    val newMessages = messages.toMutableList().apply { this[firstUserIndex] = updated }
    return AppContextInjectionResult(newMessages, injected = true)
}

