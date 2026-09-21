package me.rerere.rikkahub.data.datastore.migration

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.GooglePlatform
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferenceStoreV5MigrationTest {
    @Test
    fun `converts temporary standalone Agent Platform data back to Google`() {
        val migrated = migrateLegacyVertexAiProviderJson(
            """
            {
              "type": "agent_platform",
              "name": "Agent Platform",
              "serviceAccountEmail": "service@example.iam.gserviceaccount.com",
              "privateKey": "private-key",
              "projectId": "project-123"
            }
            """.trimIndent()
        )

        val providerJson = JsonInstant.parseToJsonElement(migrated).jsonObject
        assertEquals("google", providerJson["type"]?.jsonPrimitive?.content)
        assertEquals("agent_platform", providerJson["platform"]?.jsonPrimitive?.content)

        val decoded = JsonInstant.decodeFromJsonElement(ProviderSetting.serializer(), providerJson)
        assertTrue(decoded is ProviderSetting.Google)
        decoded as ProviderSetting.Google
        assertEquals(GooglePlatform.AGENT_PLATFORM, decoded.platform)
        assertEquals("service@example.iam.gserviceaccount.com", decoded.serviceAccountEmail)
    }

    @Test
    fun `marks legacy Vertex AI provider as Agent Platform while keeping Google type`() {
        val migrated = migrateLegacyVertexAiProvidersSettingsJson(
            """
            {
              "providers": [
                {
                  "type": "google",
                  "id": "00000000-0000-0000-0000-000000000001",
                  "name": "Production Vertex",
                  "models": [{"id":"00000000-0000-0000-0000-000000000002","modelId":"gemini-2.5-pro"}],
                  "vertexAI": true,
                  "serviceAccountEmail": "service@example.iam.gserviceaccount.com",
                  "privateKey": "private-key",
                  "location": "asia-east1",
                  "projectId": "project-123",
                  "apiKey": "unused-google-key",
                  "baseUrl": "https://generativelanguage.googleapis.com/v1beta",
                  "unknownField": "kept"
                }
              ]
            }
            """.trimIndent()
        )

        val providerJson = JsonInstant.parseToJsonElement(migrated).jsonObject["providers"]
            ?.jsonArray
            ?.single()
            ?.jsonObject
            ?: error("Provider missing after migration")

        assertEquals("google", providerJson["type"]?.jsonPrimitive?.content)
        assertEquals("agent_platform", providerJson["platform"]?.jsonPrimitive?.content)
        assertEquals("Production Vertex", providerJson["name"]?.jsonPrimitive?.content)
        assertEquals("project-123", providerJson["projectId"]?.jsonPrimitive?.content)
        assertEquals("kept", providerJson["unknownField"]?.jsonPrimitive?.content)
        assertFalse("vertexAI" in providerJson)
        assertEquals("unused-google-key", providerJson["apiKey"]?.jsonPrimitive?.content)
        assertEquals("https://generativelanguage.googleapis.com/v1beta", providerJson["baseUrl"]?.jsonPrimitive?.content)

        val decoded = JsonInstant.decodeFromJsonElement(ProviderSetting.serializer(), providerJson)
        assertTrue(decoded is ProviderSetting.Google)
        decoded as ProviderSetting.Google
        assertEquals(GooglePlatform.AGENT_PLATFORM, decoded.platform)
        assertEquals("private-key", decoded.privateKey)
        assertEquals("service@example.iam.gserviceaccount.com", decoded.serviceAccountEmail)
        assertEquals("asia-east1", decoded.location)
        assertEquals("gemini-2.5-pro", decoded.models.single().modelId)
    }

    @Test
    fun `marks non Vertex Google provider as Gemini while keeping its fields`() {
        val migrated = migrateLegacyVertexAiProviderJson(
            """
            {
              "type": "google",
              "name": "Google Gemini",
              "vertexAI": false,
              "apiKey": "google-key",
              "privateKey": "stale-key",
              "location": "us-central1"
            }
            """.trimIndent()
        )

        val providerJson = JsonInstant.parseToJsonElement(migrated).jsonObject
        assertEquals("google", providerJson["type"]?.jsonPrimitive?.content)
        assertEquals("gemini", providerJson["platform"]?.jsonPrimitive?.content)
        assertEquals("google-key", providerJson["apiKey"]?.jsonPrimitive?.content)
        assertFalse("vertexAI" in providerJson)
        assertEquals("stale-key", providerJson["privateKey"]?.jsonPrimitive?.content)
        assertEquals("us-central1", providerJson["location"]?.jsonPrimitive?.content)
        assertTrue(
            JsonInstant.decodeFromJsonElement(ProviderSetting.serializer(), providerJson)
                is ProviderSetting.Google
        )
    }
}
