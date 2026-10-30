package me.rerere.ai.provider.providers.google

import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.registry.ModelRegistry

fun String.isGeminiArgon(): Boolean = ModelRegistry.GEMINI_4_ARGON.match(this)

internal fun googleThinkingLevel(modelId: String, level: ReasoningLevel): String? = when (level) {
    ReasoningLevel.AUTO -> if (modelId.isGeminiArgon()) "medium" else null
    ReasoningLevel.OFF -> if (modelId.isGeminiArgon()) "low" else "minimal"
    ReasoningLevel.LOW -> "low"
    ReasoningLevel.MEDIUM -> "medium"
    else -> "high"
}
