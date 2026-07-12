package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.utils.JsonInstant

private const val DATA_VERSION_V2 = 2
private const val DEFAULT_CONTEXT_HISTORY_LIMIT = 10

class PreferenceStoreV2Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < DATA_VERSION_V2
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        val assistantsJson = prefs[SettingsStore.ASSISTANTS]

        if (!assistantsJson.isNullOrBlank()) {
            runCatching {
                val assistantObjects = JsonInstant.parseToJsonElement(assistantsJson).jsonArray
                val migratedAssistants = assistantObjects.map { element ->
                    val original = element.jsonObject
                    val assistant = JsonInstant.decodeFromJsonElement(Assistant.serializer(), element)
                    var normalized = assistant

                    // Dynamic pruning and auto-summarize are mutually exclusive.
                    if (normalized.enableHistorySummarization && normalized.autoRegenerateSummary) {
                        normalized = normalized.copy(autoRegenerateSummary = false)
                    }

                    val hasLegacyHistoryLimit = (normalized.maxHistoryMessages ?: 0) > 0
                    if (
                        hasLegacyHistoryLimit &&
                        !normalized.enableHistorySummarization &&
                        !normalized.autoRegenerateSummary
                    ) {
                        // Backward compatibility:
                        // old versions treated maxHistoryMessages as hard context cap.
                        normalized = normalized.copy(enableHistorySummarization = true)
                    }

                    if (
                        normalized.maxHistoryMessages == null &&
                        (normalized.enableHistorySummarization || normalized.autoRegenerateSummary)
                    ) {
                        normalized = normalized.copy(maxHistoryMessages = DEFAULT_CONTEXT_HISTORY_LIMIT)
                    }

                    val normalizedObject = JsonInstant.encodeToJsonElement(Assistant.serializer(), normalized).jsonObject
                    JsonObject(original.toMutableMap().apply { putAll(normalizedObject) })
                }
                prefs[SettingsStore.ASSISTANTS] = JsonInstant.encodeToString(migratedAssistants)
            }
        }

        prefs[SettingsStore.VERSION] = DATA_VERSION_V2
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}
