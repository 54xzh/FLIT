package me.rerere.ai.provider.providers.google

import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.registry.defineModel
import me.rerere.ai.registry.tokenRegex

fun String.isGeminiArgon(): Boolean = ModelRegistry.GEMINI_4_ARGON.match(this)

private enum class ThinkingLevel {
    MINIMAL, LOW, MEDIUM, HIGH, XHIGH
}

private val gemini4WithXhigh = defineModel { tokens("gemini", "4", "hydrogen|neon") }
private val gemini38Or37Flash = defineModel { tokens("gemini", "3", "7|8", "flash") }
private val gemini36Or35Flash = defineModel { tokens("gemini", "3", "5|6", "flash") }
private val gemini31Pro = defineModel { tokens("gemini", "3", "1", "pro") }
private val gemini31FlashLite = defineModel { tokens("gemini", "3", "1", "flash", "lite") }
private val nanoBanana21 = defineModel { tokens("nano", "banana", "2", "1") }
private val gemini3Flash = defineModel {
    tokens("gemini", "3", "flash")
    notTokens(tokenRegex("gemini"), tokenRegex("3"), tokenRegex("[0-9]+"), tokenRegex("flash"))
}
private val gemini3Pro = defineModel {
    tokens("gemini", "3", "pro")
    notTokens(tokenRegex("gemini"), tokenRegex("3"), tokenRegex("[0-9]+"), tokenRegex("pro"))
}
private val gemini25FlashLite = defineModel { tokens("gemini", "2", "5", "flash", "lite") }

private val standardLevels = listOf(ThinkingLevel.LOW, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH)
private val levelsWithMinimal = listOf(ThinkingLevel.MINIMAL) + standardLevels
private val levelsWithXhigh = standardLevels + ThinkingLevel.XHIGH

internal fun googleThinkingLevel(modelId: String, level: ReasoningLevel): String? {
    val requested = when (level) {
        ReasoningLevel.AUTO -> return if (modelId.isGeminiArgon()) "medium" else null
        ReasoningLevel.OFF -> ThinkingLevel.MINIMAL
        ReasoningLevel.LOW -> ThinkingLevel.LOW
        ReasoningLevel.MEDIUM -> ThinkingLevel.MEDIUM
        ReasoningLevel.HIGH -> ThinkingLevel.HIGH
        ReasoningLevel.XHIGH, ReasoningLevel.MAX -> ThinkingLevel.XHIGH
    }
    val supported = when {
        gemini4WithXhigh.match(modelId) -> levelsWithXhigh
        ModelRegistry.GEMINI_4_SERIES.match(modelId) -> standardLevels
        ModelRegistry.GEMINI_3_PRO_IMAGE.match(modelId) -> listOf(ThinkingLevel.HIGH)
        ModelRegistry.GEMINI_3_1_FLASH_IMAGE.match(modelId) ->
            listOf(ThinkingLevel.MINIMAL, ThinkingLevel.HIGH)
        nanoBanana21.match(modelId) -> listOf(ThinkingLevel.MINIMAL, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH)
        gemini38Or37Flash.match(modelId) || gemini31Pro.match(modelId) -> standardLevels
        gemini36Or35Flash.match(modelId) || gemini31FlashLite.match(modelId) ||
            gemini3Flash.match(modelId) -> levelsWithMinimal
        gemini3Pro.match(modelId) -> listOf(ThinkingLevel.LOW, ThinkingLevel.HIGH)
        // Interactions uses levels even for Gemini 2.5. Flash-Lite defaults to off.
        gemini25FlashLite.match(modelId) && level == ReasoningLevel.OFF -> return null
        ModelRegistry.GEMINI_2_5_FLASH.match(modelId) ||
            ModelRegistry.GEMINI_2_5_PRO.match(modelId) -> standardLevels
        else -> return null
    }
    return (supported.lastOrNull { it <= requested } ?: supported.first()).name.lowercase()
}
