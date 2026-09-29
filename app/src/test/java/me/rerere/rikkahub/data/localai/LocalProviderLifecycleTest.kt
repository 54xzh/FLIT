package me.rerere.rikkahub.data.localai

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.DEFAULT_PROVIDERS
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.pages.setting.components.PROVIDER_PRESETS
import me.rerere.rikkahub.ui.pages.setting.components.toProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class LocalProviderLifecycleTest {
    @Test
    fun `local provider is opt in and has a localized preset`() {
        assertFalse(DEFAULT_PROVIDERS.any { it is ProviderSetting.Local })
        val preset = PROVIDER_PRESETS.single { it.type == ProviderSetting.Local::class }
        assertEquals(me.rerere.rikkahub.R.string.local_models_title, preset.nameRes)
        assertEquals(ProviderSetting.Local().id, preset.toProviderSetting().id)
    }

    @Test
    fun `background reconciliation never recreates a removed entry`() {
        val settings = Settings(providers = listOf(ProviderSetting.OpenAI()))
        assertSame(settings, settings.withLocalModels(listOf(Model(modelId = "retained-file"))))
    }

    @Test
    fun `adding the entry again restores retained models`() {
        val model = Model(modelId = "retained-file")
        val settings = Settings(providers = listOf(ProviderSetting.Local()))
        assertEquals(listOf(model), settings.withLocalModels(listOf(model)).providers.single().models)
    }

    @Test
    fun `sync preserves ordering and model edits while removing missing files`() {
        val diskModel = Model(modelId = "existing", displayName = "Original")
        val editedModel = diskModel.copy(displayName = "My name")
        val newModel = Model(modelId = "new")
        val local = ProviderSetting.Local(models = listOf(editedModel, Model(modelId = "missing")))
        val remote = ProviderSetting.OpenAI()
        val updated = Settings(providers = listOf(local, remote)).withLocalModels(listOf(diskModel, newModel))
        assertEquals(listOf(local.id, remote.id), updated.providers.map { it.id })
        assertEquals(listOf(editedModel, newModel), updated.providers.first().models)
        assertSame(remote, updated.providers.last())
    }
}
