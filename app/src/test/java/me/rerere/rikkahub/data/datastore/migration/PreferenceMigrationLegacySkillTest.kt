package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.preferences.core.preferencesOf
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferenceMigrationLegacySkillTest {
    private val legacySkillId = "11111111-1111-4111-8111-111111111111"

    @Test
    fun `v2 migration preserves legacy enabled skill ids`() = runBlocking {
        val input = preferencesOf(
            SettingsStore.VERSION to 1,
            SettingsStore.ASSISTANTS to
                """[{"name":"assistant","maxHistoryMessages":10,"enabledSkillIds":["$legacySkillId"]}]""",
        )

        val output = PreferenceStoreV2Migration().migrate(input)

        assertTrue(output[SettingsStore.ASSISTANTS]!!.contains("\"enabledSkillIds\":[\"$legacySkillId\"]"))
    }

    @Test
    fun `v3 migration preserves legacy enabled skill ids`() = runBlocking {
        val input = preferencesOf(
            SettingsStore.VERSION to 2,
            SettingsStore.MODES to """[{"name":"default-mode","defaultEnabled":true}]""",
            SettingsStore.ASSISTANTS to
                """[{"name":"assistant","enabledSkillIds":["$legacySkillId"]}]""",
        )

        val output = PreferenceStoreV3Migration().migrate(input)

        assertTrue(output[SettingsStore.ASSISTANTS]!!.contains("\"enabledSkillIds\":[\"$legacySkillId\"]"))
    }
}
