package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant

private const val DATA_VERSION_V4 = 4

/**
 * V4: 把旧的 thinkingBudget(Int?) 字段迁移为 reasoningLevel(ReasoningLevel)。
 *
 * 旧 fork 用 token 预算数字表示推理档位，新版本改回枚举。需要迁移两处持久化数据：
 * 1. Assistant.thinkingBudget -> Assistant.reasoningLevel
 * 2. GroupChatSeatOverrides.thinkingBudget -> GroupChatSeatOverrides.reasoningLevel
 *
 * 旧 budget 到新枚举的精确映射（按 fork 原定义，避免数值就近导致错位）：
 *   null  -> AUTO (旧默认即模型自决)
 *   -1    -> AUTO
 *   0     -> OFF
 *   1024  -> LOW
 *   16000 -> MEDIUM
 *   32000 -> HIGH
 *   64000 -> XHIGH
 * 其它正数按就近档位兜底。
 *
 * 迁移策略：直接在 JSON 层面读取旧 thinkingBudget 字段（新数据类已无此字段，
 * 反序列化后拿不到），映射成 reasoningLevel 后用对应 serializer 重新编码写入，
 * 让 @SerialName 自动产出正确值，避免手写字符串的脆弱耦合。
 * 任意一步失败都不写版本号，下次启动重试。
 */
class PreferenceStoreV4Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < DATA_VERSION_V4
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()

        val ok = runCatching {
            migrateAssistants(prefs)
            migrateGroupChatTemplates(prefs)
        }.isSuccess

        if (ok) {
            prefs[SettingsStore.VERSION] = DATA_VERSION_V4
        }
        return prefs.toPreferences()
    }

    private fun migrateAssistants(prefs: androidx.datastore.preferences.core.MutablePreferences) {
        val assistantsJson = prefs[SettingsStore.ASSISTANTS] ?: return
        if (assistantsJson.isBlank()) return

        val assistants = JsonInstant.parseToJsonElement(assistantsJson).jsonArray
        prefs[SettingsStore.ASSISTANTS] = JsonInstant.encodeToString(migrateAssistantsArray(assistants))
    }

    private fun migrateGroupChatTemplates(prefs: androidx.datastore.preferences.core.MutablePreferences) {
        val templatesJson = prefs[SettingsStore.GROUP_CHAT_TEMPLATES] ?: return
        if (templatesJson.isBlank()) return

        val templates = JsonInstant.parseToJsonElement(templatesJson).jsonArray
        prefs[SettingsStore.GROUP_CHAT_TEMPLATES] =
            JsonInstant.encodeToString(migrateGroupChatTemplatesArray(templates))
    }

    override suspend fun cleanUp() {}
}

/**
 * 恢复旧备份时复用 V4 的 JSON 迁移，避免先按新版 Settings 解码后丢失 thinkingBudget。
 * 只改写已知字段，其它未知字段保持原样。
 */
internal fun migrateLegacyReasoningSettingsJson(settingsJson: String): String {
    val original = JsonInstant.parseToJsonElement(settingsJson).jsonObject
    val migrated = JsonObject(original.toMutableMap().apply {
        (original["assistants"] as? JsonArray)?.let { assistants ->
            put("assistants", migrateAssistantsArray(assistants))
        }
        (original["groupChatTemplates"] as? JsonArray)?.let { templates ->
            put("groupChatTemplates", migrateGroupChatTemplatesArray(templates))
        }
    })
    return JsonInstant.encodeToString(migrated)
}

private fun migrateAssistantsArray(assistants: JsonArray): JsonArray = JsonArray(
    assistants.map { element ->
        val original = element.jsonObject
        val legacyBudget = original["thinkingBudget"]
        JsonObject(original.toMutableMap().apply {
            if ("reasoningLevel" !in original && legacyBudget != null && legacyBudget !is JsonNull) {
                put("reasoningLevel", encodeReasoningLevel(mapLegacyBudgetToLevel(legacyBudget.jsonPrimitive)))
            }
            remove("thinkingBudget")
        })
    }
)

private fun migrateGroupChatTemplatesArray(templates: JsonArray): JsonArray = JsonArray(
    templates.map { templateElement ->
        val template = templateElement.jsonObject
        val seats = template["seats"] as? JsonArray ?: return@map template
        val migratedSeats = JsonArray(seats.map { seatElement ->
            val seat = seatElement.jsonObject
            val overrides = seat["overrides"] as? JsonObject ?: return@map seat
            val legacyBudget = overrides["thinkingBudget"]
            val migratedOverrides = JsonObject(overrides.toMutableMap().apply {
                if ("reasoningLevel" !in overrides && legacyBudget != null && legacyBudget !is JsonNull) {
                    put("reasoningLevel", encodeReasoningLevel(mapLegacyBudgetToLevel(legacyBudget.jsonPrimitive)))
                }
                remove("thinkingBudget")
            })
            JsonObject(seat.toMutableMap().apply { put("overrides", migratedOverrides) })
        })
        JsonObject(template.toMutableMap().apply { put("seats", migratedSeats) })
    }
)

private fun encodeReasoningLevel(level: ReasoningLevel) =
    JsonInstant.encodeToJsonElement(ReasoningLevel.serializer(), level)

private fun mapLegacyBudgetToLevel(primitive: JsonPrimitive): ReasoningLevel {
    val value = primitive.intOrNull
    return when (value) {
        null -> ReasoningLevel.AUTO
        -1 -> ReasoningLevel.AUTO
        0 -> ReasoningLevel.OFF
        1024 -> ReasoningLevel.LOW
        16000 -> ReasoningLevel.MEDIUM
        32000 -> ReasoningLevel.HIGH
        64000 -> ReasoningLevel.XHIGH
        else -> ReasoningLevel.fromBudgetTokens(value)
    }
}
