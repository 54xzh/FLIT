package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant

private const val DATA_VERSION_V5 = 5
/**
 * V5: 将 Google 提供商中旧的 Vertex AI 开关迁移为 Agent Platform 预设标记。
 *
 * Agent Platform 仍属于 Google 类型；迁移直接改写原始 JSON，保留已有配置。
 */
class PreferenceStoreV5Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < DATA_VERSION_V5
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        val migrated = runCatching {
            prefs[SettingsStore.PROVIDERS]?.let { providersJson ->
                prefs[SettingsStore.PROVIDERS] = migrateLegacyVertexAiProvidersJson(providersJson)
            }
        }.isSuccess

        if (migrated) {
            prefs[SettingsStore.VERSION] = DATA_VERSION_V5
        }
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}

/** Converts a complete settings JSON document before it is decoded during backup restore. */
internal fun migrateLegacyVertexAiProvidersSettingsJson(settingsJson: String): String {
    val original = JsonInstant.parseToJsonElement(settingsJson) as? JsonObject ?: return settingsJson
    val providers = original["providers"] as? JsonArray ?: return settingsJson
    return JsonInstant.encodeToString(
        JsonObject(original.toMutableMap().apply {
            put("providers", migrateLegacyVertexAiProviders(providers))
        })
    )
}

/** Converts a shared provider JSON document before polymorphic decoding. */
internal fun migrateLegacyVertexAiProviderJson(providerJson: String): String {
    val provider = JsonInstant.parseToJsonElement(providerJson) as? JsonObject ?: return providerJson
    return JsonInstant.encodeToString(migrateLegacyVertexAiProvider(provider))
}

internal fun migrateLegacyVertexAiProvidersJson(providersJson: String): String {
    val providers = JsonInstant.parseToJsonElement(providersJson) as? JsonArray ?: return providersJson
    return JsonInstant.encodeToString(migrateLegacyVertexAiProviders(providers))
}

private fun migrateLegacyVertexAiProviders(providers: JsonArray): JsonArray = JsonArray(
    providers.map { provider ->
        (provider as? JsonObject)?.let(::migrateLegacyVertexAiProvider) ?: provider
    }
)

private fun migrateLegacyVertexAiProvider(provider: JsonObject): JsonObject {
    val type = (provider["type"] as? JsonPrimitive)?.content
    if (type == "agent_platform") {
        return JsonObject(provider.toMutableMap().apply {
            put("type", JsonPrimitive("google"))
            put("platform", JsonPrimitive("agent_platform"))
        })
    }
    if (type != "google") return provider

    val isVertexAi = (provider["vertexAI"] as? JsonPrimitive)?.booleanOrNull == true
    return JsonObject(provider.toMutableMap().apply {
        remove("vertexAI")
        put("platform", JsonPrimitive(if (isVertexAi) "agent_platform" else "gemini"))
        if (isVertexAi) {
            val name = (provider["name"] as? JsonPrimitive)?.content
            if (name == "Google") {
                put("name", JsonPrimitive("Agent Platform"))
            }
        }
    })
}
