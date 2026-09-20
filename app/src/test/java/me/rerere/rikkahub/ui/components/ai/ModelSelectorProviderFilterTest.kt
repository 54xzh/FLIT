package me.rerere.rikkahub.ui.components.ai

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelSelectorProviderFilterTest {
    private val chatModel = Model(modelId = "chat", type = ModelType.CHAT)
    private val imageModel = Model(modelId = "image", type = ModelType.IMAGE)
    private val decisionModel = Model(modelId = "jev-latest", type = ModelType.DECISION)

    @Test
    fun defaultFilterExcludesDisabledProviders() {
        val enabled = ProviderSetting.OpenAI(name = "enabled", models = listOf(chatModel))
        val disabled = ProviderSetting.OpenAI(name = "disabled", enabled = false, models = listOf(chatModel))

        val result = filterModelSelectorProviders(
            providers = listOf(enabled, disabled),
            type = ModelType.CHAT,
        )

        assertEquals(listOf(enabled), result)
    }

    @Test
    fun connectionTestCanIncludeDisabledProviderWithoutWrongModelTypes() {
        val disabledWithChat = ProviderSetting.OpenAI(
            name = "disabled chat",
            enabled = false,
            models = listOf(chatModel),
        )
        val disabledWithImageOnly = ProviderSetting.OpenAI(
            name = "disabled image",
            enabled = false,
            models = listOf(imageModel),
        )
        val enabledWithImageOnly = ProviderSetting.OpenAI(
            name = "enabled image",
            models = listOf(imageModel),
        )

        val result = filterModelSelectorProviders(
            providers = listOf(disabledWithChat, disabledWithImageOnly, enabledWithImageOnly),
            type = ModelType.CHAT,
            includeDisabledProviders = true,
        )

        assertEquals(listOf(disabledWithChat), result)
    }

    @Test
    fun hostSelectorCanIncludeChatAndDecisionModels() {
        val chatProvider = ProviderSetting.OpenAI(name = "chat", models = listOf(chatModel))
        val decisionProvider = ProviderSetting.OpenAI(name = "decision", models = listOf(decisionModel))
        val imageProvider = ProviderSetting.OpenAI(name = "image", models = listOf(imageModel))

        val result = filterModelSelectorProviders(
            providers = listOf(chatProvider, decisionProvider, imageProvider),
            type = ModelType.CHAT,
            additionalTypes = setOf(ModelType.DECISION),
        )

        assertEquals(listOf(chatProvider, decisionProvider), result)
    }
}
