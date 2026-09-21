package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import me.rerere.rikkahub.data.datastore.SettingsStore

private const val DATA_VERSION_V6 = 6

/**
 * V6: 兼容开发版曾写入的独立 agent_platform 类型，改回 Google 类型的预设标记。
 */
class PreferenceStoreV6Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < DATA_VERSION_V6
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        val migrated = runCatching {
            prefs[SettingsStore.PROVIDERS]?.let { providersJson ->
                prefs[SettingsStore.PROVIDERS] = migrateLegacyVertexAiProvidersJson(providersJson)
            }
        }.isSuccess

        if (migrated) {
            prefs[SettingsStore.VERSION] = DATA_VERSION_V6
        }
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}
