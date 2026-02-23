package me.rerere.ai

import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.registry.ModelRegistry
import org.junit.Test

class ModelRegistryTest {
    @Test
    fun testGPT5() {
        assert(ModelRegistry.GPT_5.match("gpt-5"))
        assert(!ModelRegistry.GPT_5.match("gpt-5-chat"))
        assert(ModelRegistry.GPT_5.match("gpt-5-mini"))
        assert(!ModelRegistry.GPT_5.match("deepseek-v3"))
        assert(!ModelRegistry.GPT_5.match("gemini-2.0-flash"))
        assert(!ModelRegistry.GPT_5.match("gpt-5.1"))
        assert(!ModelRegistry.GPT_5.match("gpt-4o"))
        assert(!ModelRegistry.GPT_5.match("gpt-5.0"))
        assert(!ModelRegistry.GPT_5.match("gpt-6"))
    }

    @Test
    fun testGemini25() {
        assert(ModelRegistry.GEMINI_LATEST.match("gemini-flash-latest"))
        assert(ModelRegistry.GEMINI_LATEST.match("gemini-pro-latest"))
        assert(ModelRegistry.GEMINI_2_5_FLASH.match("gemini-2.5-flash"))
        assert(!ModelRegistry.GEMINI_2_5_FLASH.match("gemini-2.5-pro"))
        assert(!ModelRegistry.GEMINI_2_5_FLASH.match("gemini-2.5-flash-image-preview"))
        assert(ModelRegistry.GEMINI_2_5_IMAGE.match("gemini-2.5-flash-image"))
        assert(ModelRegistry.MODEL_OUTPUT_MODALITIES.getData("gemini-2.5-flash-image") == listOf(Modality.TEXT, Modality.IMAGE))
        assert(ModelRegistry.MODEL_OUTPUT_MODALITIES.getData("gemini-2.5-flash") == listOf(Modality.TEXT))
    }

    @Test
    fun testGemini31Pro() {
        val modelId = "gemini-3.1-pro"
        assert(ModelRegistry.GEMINI_3_PRO.match(modelId))
        assert(ModelRegistry.GEMINI_3_SERIES.match(modelId))
        assert(ModelRegistry.GEMINI_SERIES.match(modelId))
        assert(ModelRegistry.MODEL_INPUT_MODALITIES.getData(modelId) == listOf(Modality.TEXT, Modality.IMAGE))
        assert(ModelRegistry.MODEL_ABILITIES.getData(modelId).contains(ModelAbility.TOOL))
        assert(ModelRegistry.MODEL_ABILITIES.getData(modelId).contains(ModelAbility.REASONING))
    }

    @Test
    fun testClaude45() {
        assert(ModelRegistry.CLAUDE_4_5.match("claude-sonnet-4.5-20250929"))
        assert(ModelRegistry.CLAUDE_4_5.match("claude-4.5-sonnet"))
        assert(!ModelRegistry.CLAUDE_4_5.match("claude-sonnet-4-20250929"))
        assert(!ModelRegistry.CLAUDE_4_5.match("claude-4-sonnet"))
    }

    @Test
    fun testKimiK25() {
        val modelId = "kimi-k2.5"
        assert(ModelRegistry.MODEL_INPUT_MODALITIES.getData(modelId) == listOf(Modality.TEXT, Modality.IMAGE))
        assert(ModelRegistry.MODEL_ABILITIES.getData(modelId).contains(ModelAbility.TOOL))
        assert(ModelRegistry.MODEL_ABILITIES.getData(modelId).contains(ModelAbility.REASONING))
    }

    @Test
    fun testGlm5AndMinimaxM25() {
        assertEquals(
            listOf(Modality.TEXT),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("glm-5")
        )
        assertEquals(
            listOf(Modality.TEXT),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("minimax-m2.5")
        )
        assertEquals(
            listOf(ModelAbility.TOOL, ModelAbility.REASONING),
            ModelRegistry.MODEL_ABILITIES.getData("glm-5")
        )
        assertEquals(
            listOf(ModelAbility.TOOL, ModelAbility.REASONING),
            ModelRegistry.MODEL_ABILITIES.getData("minimax-m2.5")
        )
    }
}
