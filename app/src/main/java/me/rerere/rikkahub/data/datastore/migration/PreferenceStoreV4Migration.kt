package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.GroupChatSeatOverrides
import me.rerere.rikkahub.data.model.GroupChatTemplate
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

        val assistantObjects = JsonInstant.parseToJsonElement(assistantsJson).jsonArray.map { it.jsonObject }
        val migrated = assistantObjects.map { original ->
            val legacyBudget = original["thinkingBudget"]
            if (legacyBudget == null || legacyBudget is JsonNull) {
                // 没有旧字段，原样保留（新数据类反序列化时 reasoningLevel 取默认 AUTO）
                original
            } else {
                val level = mapLegacyBudgetToLevel(legacyBudget.jsonPrimitive)
                // 解码成 Assistant 对象（旧 thinkingBudget 字段被忽略），写入 reasoningLevel 后重新编码
                val assistant = JsonInstant.decodeFromJsonElement(Assistant.serializer(), original)
                val updated = assistant.copy(reasoningLevel = level)
                // 保留原始 JSON 里可能存在的其它未知字段（前向兼容）
                val encoded = JsonInstant.encodeToJsonElement(Assistant.serializer(), updated).jsonObject
                JsonObject(original.toMutableMap().apply { putAll(encoded); remove("thinkingBudget") })
            }
        }
        prefs[SettingsStore.ASSISTANTS] = JsonInstant.encodeToString(JsonArray(migrated))
    }

    private fun migrateGroupChatTemplates(prefs: androidx.datastore.preferences.core.MutablePreferences) {
        val templatesJson = prefs[SettingsStore.GROUP_CHAT_TEMPLATES] ?: return
        if (templatesJson.isBlank()) return

        val templates = JsonInstant.decodeFromString<List<GroupChatTemplate>>(templatesJson)
        // GroupChatTemplate 反序列化时，旧 overrides.thinkingBudget 已被忽略（reasoningLevel 取默认 null）。
        // 需要从原始 JSON 重新读出每个 seat 的旧 thinkingBudget，再覆盖到对应 overrides.reasoningLevel。
        val templateObjects = JsonInstant.parseToJsonElement(templatesJson).jsonArray.map { it.jsonObject }

        val migratedTemplates = templates.mapIndexed { index, template ->
            val templateObject = templateObjects[index]
            val seatsArray = templateObject["seats"]?.jsonArray ?: return@mapIndexed template
            val migratedSeats = template.seats.mapIndexed { seatIndex, seat ->
                val seatObject = seatsArray[seatIndex].jsonObject
                val overridesObject = seatObject["overrides"]?.jsonObject
                val legacyBudget = overridesObject?.get("thinkingBudget")
                if (legacyBudget == null || legacyBudget is JsonNull) {
                    seat
                } else {
                    val level = mapLegacyBudgetToLevel(legacyBudget.jsonPrimitive)
                    seat.copy(overrides = seat.overrides.copy(reasoningLevel = level))
                }
            }
            template.copy(seats = migratedSeats)
        }
        prefs[SettingsStore.GROUP_CHAT_TEMPLATES] = JsonInstant.encodeToString(migratedTemplates)
    }

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

    override suspend fun cleanUp() {}
}