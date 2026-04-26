package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage

internal data class QuotaTokenUsageDelta(
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val cachedTokens: Long = 0L,
) {
    val isEmpty: Boolean
        get() = inputTokens <= 0L && outputTokens <= 0L && cachedTokens <= 0L

    operator fun plus(usage: TokenUsage): QuotaTokenUsageDelta {
        return copy(
            inputTokens = inputTokens + usage.promptTokens.toLong(),
            outputTokens = outputTokens + usage.completionTokens.toLong(),
            cachedTokens = cachedTokens + usage.cachedTokens.toLong(),
        )
    }
}

internal fun calculateQuotaTokenUsageDelta(
    baselineMessages: List<UIMessage>,
    finalMessages: List<UIMessage>,
): QuotaTokenUsageDelta {
    val baselineIds = baselineMessages.mapTo(mutableSetOf()) { it.id }
    val baselineUsageById = baselineMessages.associate { message -> message.id to message.usage }

    return finalMessages
        .asSequence()
        .filter { message -> message.role == MessageRole.ASSISTANT }
        .mapNotNull { message ->
            val usage = message.usage ?: return@mapNotNull null
            val isNewMessage = message.id !in baselineIds
            val usageChanged = !isNewMessage && baselineUsageById[message.id] != usage
            if (isNewMessage || usageChanged) usage else null
        }
        .fold(QuotaTokenUsageDelta()) { acc, usage -> acc + usage }
}
