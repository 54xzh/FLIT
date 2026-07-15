package me.rerere.rikkahub.data.datastore.migration

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PreferenceStoreV4MigrationTest {
    @Test
    fun `migrates legacy reasoning fields in restored settings json`() {
        val migrated = migrateLegacyReasoningSettingsJson(
            """
            {
              "unknownRoot": "kept",
              "assistants": [
                {"name":"off","thinkingBudget":0,"unknownAssistant":true},
                {"name":"low","thinkingBudget":1024},
                {"name":"medium","thinkingBudget":16000},
                {"name":"high","thinkingBudget":32000},
                {"name":"xhigh","thinkingBudget":64000},
                {"name":"auto","thinkingBudget":-1},
                {"name":"default","thinkingBudget":null}
              ],
              "groupChatTemplates": [
                {
                  "seats": [
                    {"overrides":{"thinkingBudget":64000,"unknownOverride":"kept"}},
                    {"overrides":{"thinkingBudget":null}}
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        val root = JsonInstant.parseToJsonElement(migrated).jsonObject
        assertEquals("kept", root["unknownRoot"]?.jsonPrimitive?.content)

        val assistants = root["assistants"]!!.jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("off", "low", "medium", "high", "xhigh", "auto", null),
            assistants.map { it["reasoningLevel"]?.jsonPrimitive?.content }
        )
        assertEquals(true, assistants.first()["unknownAssistant"]?.jsonPrimitive?.content?.toBoolean())
        assistants.forEach { assertFalse("thinkingBudget" in it) }

        val seats = root["groupChatTemplates"]!!.jsonArray.first().jsonObject["seats"]!!.jsonArray
        val firstOverrides = seats[0].jsonObject["overrides"]!!.jsonObject
        val secondOverrides = seats[1].jsonObject["overrides"]!!.jsonObject
        assertEquals("xhigh", firstOverrides["reasoningLevel"]?.jsonPrimitive?.content)
        assertEquals("kept", firstOverrides["unknownOverride"]?.jsonPrimitive?.content)
        assertFalse("thinkingBudget" in firstOverrides)
        assertFalse("thinkingBudget" in secondOverrides)
    }

    @Test
    fun `keeps existing reasoning level when legacy field also exists`() {
        val migrated = migrateLegacyReasoningSettingsJson(
            """
            {
              "assistants": [
                {"reasoningLevel":"high","thinkingBudget":0}
              ],
              "groupChatTemplates": [
                {"seats":[{"overrides":{"reasoningLevel":"medium","thinkingBudget":64000}}]}
              ]
            }
            """.trimIndent()
        )

        val root = JsonInstant.parseToJsonElement(migrated).jsonObject
        val assistant = root["assistants"]!!.jsonArray.first().jsonObject
        val overrides = root["groupChatTemplates"]!!.jsonArray.first().jsonObject["seats"]!!
            .jsonArray.first().jsonObject["overrides"]!!.jsonObject

        assertEquals("high", assistant["reasoningLevel"]?.jsonPrimitive?.content)
        assertEquals("medium", overrides["reasoningLevel"]?.jsonPrimitive?.content)
        assertFalse("thinkingBudget" in assistant)
        assertFalse("thinkingBudget" in overrides)
    }
}
