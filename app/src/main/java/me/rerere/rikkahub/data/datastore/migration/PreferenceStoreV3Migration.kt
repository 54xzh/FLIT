package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANTS
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Mode
import me.rerere.rikkahub.utils.JsonInstant

private const val DATA_VERSION_V3 = 3

class PreferenceStoreV3Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < DATA_VERSION_V3
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        val modesJson = prefs[SettingsStore.MODES]

        if (!modesJson.isNullOrBlank()) {
            runCatching {
                val modes = JsonInstant.decodeFromString<List<Mode>>(modesJson)
                val legacyDefaultEnabledModeIds = modes
                    .filter { it.defaultEnabled }
                    .map { it.id }
                    .toSet()

                if (legacyDefaultEnabledModeIds.isNotEmpty()) {
                    val assistantsJson = prefs[SettingsStore.ASSISTANTS]
                    val assistants = if (assistantsJson.isNullOrBlank()) {
                        DEFAULT_ASSISTANTS
                    } else {
                        JsonInstant.decodeFromString<List<Assistant>>(assistantsJson)
                    }

                    prefs[SettingsStore.ASSISTANTS] = JsonInstant.encodeToString(
                        assistants.map { assistant ->
                            assistant.copy(
                                enabledModeIds = assistant.enabledModeIds + legacyDefaultEnabledModeIds
                            )
                        }
                    )
                }

                prefs[SettingsStore.MODES] = JsonInstant.encodeToString(
                    modes.map { mode -> mode.copy(defaultEnabled = false) }
                )
            }
        }

        prefs[SettingsStore.VERSION] = DATA_VERSION_V3
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}
