package me.rerere.rikkahub.data.sync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantSearchMode
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.search.SearchServiceOptions
import me.rerere.search.GrokSearchApiType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class RikkaHubCompatSettingsImporterTest {
    @Test
    fun `converts common providers tools assistant search and tts settings`() {
        val assistantId = Uuid.parse("10000000-0000-0000-0000-000000000001")
        val modelId = Uuid.parse("10000000-0000-0000-0000-000000000002")
        val providerId = Uuid.parse("10000000-0000-0000-0000-000000000003")
        val searchId = Uuid.parse("10000000-0000-0000-0000-000000000004")
        val ttsId = Uuid.parse("10000000-0000-0000-0000-000000000005")
        val settings = Settings(
            assistantId = assistantId,
            assistants = listOf(Assistant(id = assistantId, chatModelId = modelId)),
            providers = listOf(
                ProviderSetting.OpenAI(
                    id = providerId,
                    models = listOf(
                        Model(
                            id = modelId,
                            modelId = "gpt-test",
                            displayName = "GPT Test",
                            tools = setOf(BuiltInTools.Search),
                        )
                    ),
                    apiKey = "sk-test",
                    baseUrl = "https://example.test/v1",
                )
            ),
            searchServices = listOf(
                SearchServiceOptions.DoubaoSearchOptions(id = searchId, apiKey = "doubao-key"),
            ),
            searchServiceSelected = 0,
            ttsProviders = listOf(
                TTSProviderSetting.ElevenLabs(
                    id = ttsId,
                    apiKey = "eleven-key",
                    modelId = "eleven-test",
                )
            ),
            selectedTTSProviderId = ttsId,
        )

        val source = JsonInstant.parseToJsonElement(JsonInstant.encodeToString(settings)).jsonObject
            .toMutableMap()
        source["assistants"] = JsonArray(
            source.getValue("assistants").jsonArray.map { assistantElement ->
                JsonObject(assistantElement.jsonObject.toMutableMap().apply {
                    remove("searchMode")
                    put("enableWebSearch", JsonPrimitive(true))
                    put(
                        "localTools",
                        JsonArray(
                            listOf(
                                buildJsonObject { put("type", "time_info") },
                                buildJsonObject { put("type", "clipboard") },
                            )
                        )
                    )
                })
            }
        )
        source["providers"] = JsonArray(
            source.getValue("providers").jsonArray.map { providerElement ->
                JsonObject(providerElement.jsonObject.toMutableMap().apply {
                    put(
                        "models",
                        JsonArray(
                            getValue("models").jsonArray.map { modelElement ->
                                JsonObject(modelElement.jsonObject.toMutableMap().apply {
                                    put(
                                        "tools",
                                        JsonArray(
                                            listOf(
                                                buildJsonObject { put("type", "search") },
                                                buildJsonObject { put("type", "image_generation") },
                                            )
                                        )
                                    )
                                })
                            }
                        )
                    )
                })
            }
        )
        source["searchServices"] = JsonArray(
            listOf(
                buildJsonObject {
                    put("type", "rikkahub")
                    put("id", "20000000-0000-0000-0000-000000000001")
                    put("apiKey", "unsupported-key")
                },
                buildJsonObject {
                    put("type", "doubao")
                    put("id", searchId.toString())
                    put("apiKey", "doubao-key")
                    put("mode", "custom")
                },
            )
        )
        source["searchServiceSelected"] = JsonPrimitive(1)
        source["ttsProviders"] = JsonArray(
            listOf(
                buildJsonObject {
                    put("type", "elevenlabs")
                    put("id", ttsId.toString())
                    put("apiKey", "eleven-key")
                    put("model", "eleven-test")
                },
                buildJsonObject {
                    put("type", "qwen")
                    put("id", "20000000-0000-0000-0000-000000000002")
                },
            )
        )
        source["selectedTTSProviderId"] = JsonPrimitive(ttsId.toString())
        source.remove("objectStorageConfig")
        source["s3Config"] = buildJsonObject {
            put("endpoint", "https://account.r2.cloudflarestorage.com")
            put("accessKeyId", "r2-access")
            put("secretAccessKey", "r2-secret")
            put("bucket", "backups")
            put("region", "auto")
            put("pathStyle", true)
            put("items", JsonArray(listOf(JsonPrimitive("DATABASE"))))
        }
        source["webDavConfig"] = buildJsonObject {
            put("url", "https://dav.example/backups")
            put("username", "dav-user")
            put("password", "dav-password")
            put("path", "rikkahub-backups")
            put("items", JsonArray(listOf(JsonPrimitive("DATABASE"), JsonPrimitive("FILES"))))
        }
        source["mcpServers"] = JsonArray(
            listOf(
                buildJsonObject {
                    put("type", "sse")
                    put("id", "10000000-0000-0000-0000-000000000006")
                    put("url", "https://mcp.example/sse")
                    put(
                        "commonOptions",
                        buildJsonObject {
                            put("name", "MCP")
                            put(
                                "tools",
                                JsonArray(
                                    listOf(
                                        buildJsonObject {
                                            put("name", "dangerous_tool")
                                            put("needsApproval", true)
                                        }
                                    )
                                )
                            )
                        }
                    )
                }
            )
        )
        source["lorebooks"] = JsonArray(listOf(JsonObject(emptyMap())))

        val result = RikkaHubCompatSettingsImporter.convert(JsonObject(source).toString())
        val converted = JsonInstant.decodeFromString<Settings>(result.json)
        val assistant = converted.assistants.single()
        val provider = converted.providers.single() as ProviderSetting.OpenAI
        val model = provider.models.single()

        assertEquals("sk-test", provider.apiKey)
        assertEquals("https://example.test/v1", provider.baseUrl)
        assertEquals(modelId, model.id)
        assertEquals(setOf(BuiltInTools.Search), model.tools)
        assertEquals(listOf(LocalToolOption.GetCurrentTime), assistant.localTools)
        assertEquals(AssistantSearchMode.Provider(0), assistant.searchMode)
        assertEquals(0, converted.searchServiceSelected)
        assertEquals("doubao-key", converted.searchServices.single { it.id == searchId }.let { service ->
            (service as SearchServiceOptions.DoubaoSearchOptions).apiKey
        })
        assertEquals("eleven-test", (converted.ttsProviders.single() as TTSProviderSetting.ElevenLabs).modelId)
        assertEquals("r2-access", converted.objectStorageConfig.accessKeyId)
        assertEquals("r2-secret", converted.objectStorageConfig.secretAccessKey)
        assertEquals("https://dav.example/backups", converted.webDavConfig.url)
        assertEquals("dav-user", converted.webDavConfig.username)
        assertEquals("dav-password", converted.webDavConfig.password)
        assertEquals("rikkahub-backups", converted.webDavConfig.path)
        assertEquals(2, converted.webDavConfig.items.size)
        assertFalse(converted.webDavConfig.autoEnabled)
        val mcpTool = (converted.mcpServers.single() as McpServerConfig.SseTransportServer)
            .commonOptions.tools.single()
        assertTrue(mcpTool.requireApproval)
        assertEquals(emptyList<Any>(), converted.lorebooks)
        assertTrue(result.skippedItems >= 4)
        assertTrue("time_info" !in result.skippedTypes)
        assertTrue("clipboard" in result.skippedTypes)
        assertTrue("doubao_mode" in result.skippedTypes)
    }

    @Test
    fun `skips virtual host s3 without retaining a broken object storage config`() {
        val root = JsonObject(
            mapOf(
                "s3Config" to buildJsonObject {
                    put("endpoint", "https://example.com")
                    put("bucket", "backups")
                    put("pathStyle", false)
                }
            )
        )

        val result = RikkaHubCompatSettingsImporter.convert(root.toString())
        val converted = JsonInstant.decodeFromString<Settings>(result.json)

        assertEquals("", converted.objectStorageConfig.endpoint)
        assertEquals(1, result.skippedItems)
        assertEquals(setOf("s3_path_style"), result.skippedTypes)
    }

    @Test
    fun `maps grok custom endpoint path without losing the path prefix`() {
        val root = buildJsonObject {
            put(
                "searchServices",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("type", "grok")
                            put("id", "20000000-0000-0000-0000-000000000003")
                            put("apiKey", "grok-key")
                            put("customUrl", "https://proxy.example/v1/responses")
                            put("systemPrompt", "proxy prompt")
                        }
                    )
                )
            )
        }

        val converted = JsonInstant.decodeFromString<Settings>(
            RikkaHubCompatSettingsImporter.convert(root.toString()).json
        )
        val grok = converted.searchServices.single() as SearchServiceOptions.GrokOptions

        assertEquals("https://proxy.example/v1", grok.customBaseUrl)
        assertEquals(GrokSearchApiType.RESPONSES, grok.apiType)
        assertEquals("/responses", grok.legacyCustomPath)
        assertEquals("proxy prompt", grok.customSystemPrompt)
        assertTrue(grok.enableCustom)
    }

    @Test
    fun `remaps an existing assistant search mode after filtering services`() {
        val root = buildJsonObject {
            put("providers", JsonArray(emptyList()))
            put(
                "searchServices",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("type", "rikkahub")
                            put("id", "20000000-0000-0000-0000-000000000010")
                        },
                        buildJsonObject {
                            put("type", "doubao")
                            put("id", "20000000-0000-0000-0000-000000000011")
                        },
                    )
                )
            )
            put("searchServiceSelected", 1)
            put(
                "assistants",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("id", "20000000-0000-0000-0000-000000000012")
                            put(
                                "searchMode",
                                buildJsonObject {
                                    put("type", "provider")
                                    put("index", 1)
                                }
                            )
                        }
                    )
                )
            )
            put("ttsProviders", JsonArray(emptyList()))
        }

        val converted = JsonInstant.decodeFromString<Settings>(
            RikkaHubCompatSettingsImporter.convert(root.toString()).json
        )

        assertEquals(AssistantSearchMode.Provider(0), converted.assistants.single().searchMode)
    }

    @Test
    fun `fails instead of replacing malformed core arrays with empty settings`() {
        val root = buildJsonObject {
            put("providers", JsonPrimitive("not-an-array"))
        }

        assertThrows(IllegalStateException::class.java) {
            RikkaHubCompatSettingsImporter.convert(root.toString())
        }
    }
}
