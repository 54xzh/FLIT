package me.rerere.rikkahub

import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.ui.pages.setting.components.PROVIDER_PRESETS
import me.rerere.rikkahub.ui.pages.setting.components.toProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexProviderPresetTest {
    @Test
    fun `Codex preset is second and starts without models`() {
        assertEquals(ProviderSetting.OpenAI::class, PROVIDER_PRESETS.first().type)
        val codexPreset = PROVIDER_PRESETS[1]
        assertEquals(ProviderSetting.OpenAICodex::class, codexPreset.type)
        assertTrue(codexPreset.requiresCodexLogin)
        assertTrue((codexPreset.toProviderSetting() as ProviderSetting.OpenAICodex).models.isEmpty())
    }
}
