package me.rerere.ai.provider.providers.codex

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderProxy
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import me.rerere.ai.provider.providers.codex.CodexProtocolConfig.ORIGINATOR
import me.rerere.ai.provider.providers.codex.CodexProtocolConfig.USER_AGENT
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.encoding.Base64

class CodexProtocolClientTest {
    private val client = CodexProtocolClient(OkHttpClient())

    @Test
    fun `authenticated requests use Codex compatibility identity and account`() {
        val credential = CodexCredential(
            accessToken = "access-token",
            refreshToken = "refresh-token",
            expiresAtEpochMillis = Long.MAX_VALUE,
            accountId = "account-id",
        )

        val request = client.authenticatedRequest("https://example.com/models", credential).build()

        assertEquals("Bearer access-token", request.header("Authorization"))
        assertEquals("account-id", request.header("ChatGPT-Account-ID"))
        assertEquals(ORIGINATOR, request.header("originator"))
        assertEquals(USER_AGENT, request.header("User-Agent"))
    }

    @Test
    fun `Responses requests include Codex session metadata without experimental beta header`() {
        val provider = OpenAICodexProvider(
            client = OkHttpClient(),
            sessionProvider = object : CodexSessionProvider {
                override suspend fun getCredential(providerId: kotlin.uuid.Uuid): CodexCredential? = null

                override suspend fun requireValidCredential(
                    providerSetting: me.rerere.ai.provider.ProviderSetting.OpenAICodex,
                ): CodexCredential = error("not used")

                override suspend fun forceRefreshCredential(
                    providerSetting: me.rerere.ai.provider.ProviderSetting.OpenAICodex,
                    failedAccessToken: String?,
                ): CodexCredential = error("not used")
            },
        )
        val credential = CodexCredential(
            accessToken = "access-token",
            refreshToken = "refresh-token",
            expiresAtEpochMillis = Long.MAX_VALUE,
            accountId = "account-id",
        )
        val messages = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text("hello")),
            ),
        )
        val method = OpenAICodexProvider::class.java.getDeclaredMethod(
            "codexParams",
            TextGenerationParams::class.java,
            CodexCredential::class.java,
            List::class.java,
            String::class.java,
        ).apply { isAccessible = true }

        val params = method.invoke(
            provider,
            TextGenerationParams(
                model = Model(
                    modelId = "gpt-codex",
                    tools = setOf(me.rerere.ai.provider.BuiltInTools.CodexWebSearch),
                ),
                customHeaders = listOf(
                    me.rerere.ai.provider.CustomHeader("Accept", "application/json"),
                    me.rerere.ai.provider.CustomHeader("OpenAI-Beta", "responses=experimental"),
                ),
                customBody = listOf(
                    me.rerere.ai.provider.CustomBody("stream", JsonPrimitive(false)),
                ),
            ),
            credential,
            messages,
            "provider-id",
        ) as TextGenerationParams

        val headers = params.customHeaders.associate { it.name.lowercase() to it.value }
        val metadata = params.customBody
            .single { it.key == "client_metadata" }
            .value
            .jsonObject
        val included = params.customBody
            .single { it.key == "include" }
            .value
            .jsonArray
            .map { it.jsonPrimitive.content }
        assertEquals("account-id", headers["chatgpt-account-id"])
        assertTrue(headers["session-id"].isNullOrBlank().not())
        assertEquals(headers["session-id"], headers["thread-id"])
        assertEquals(headers["thread-id"], headers["x-client-request-id"])
        assertTrue(headers["x-codex-window-id"].isNullOrBlank().not())
        assertNull(headers["openai-beta"])
        assertNull(headers["accept"])
        assertTrue(params.customBody.none { it.key == "stream" })
        assertTrue("web_search_call.action.sources" in included)
        assertEquals("provider-id", metadata["x-codex-installation-id"]?.jsonPrimitive?.content)
        assertEquals(headers["session-id"], metadata["session_id"]?.jsonPrimitive?.content)
        assertEquals(headers["thread-id"], metadata["thread_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `token response reads account claims from id token not opaque access token`() {
        val claims =
            """{"email":"user@example.com","exp":2000000000,"https://api.openai.com/auth":{"chatgpt_account_id":"account-id","chatgpt_user_id":"user-id","chatgpt_plan_type":"plus"}}"""
        val idToken = "header.${Base64.UrlSafe.encode(claims.encodeToByteArray()).trimEnd('=')}.signature"

        val credential = client.parseCredential(
            """{"access_token":"opaque-access-token","refresh_token":"refresh-token","id_token":"$idToken"}""",
            previousCredential = null,
        )

        assertEquals("opaque-access-token", credential.accessToken)
        assertEquals("account-id", credential.accountId)
        assertEquals("user@example.com", credential.email)
        assertEquals("user-id", credential.userId)
        assertEquals("plus", credential.planType)
        assertEquals(2_000_000_000_000L, credential.expiresAtEpochMillis)
    }

    @Test
    fun `refresh token request uses Codex JSON payload`() = runBlocking {
        lateinit var capturedRequest: Request
        val protocolClient = CodexProtocolClient(
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    capturedRequest = chain.request()
                    Response.Builder()
                        .request(capturedRequest)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            """{"access_token":"new-access"}"""
                                .toResponseBody("application/json".toMediaType())
                        )
                        .build()
                }
                .build()
        )
        val previous = CodexCredential(
            accessToken = "old-access",
            refreshToken = "refresh-token",
            expiresAtEpochMillis = 0L,
            accountId = "account-id",
        )

        val refreshed = protocolClient.refreshCredential(previous, ProviderProxy.None)

        val body = Buffer().also { capturedRequest.body?.writeTo(it) }.readUtf8()
        val payload = json.parseToJsonElement(body).jsonObject
        assertEquals("application", capturedRequest.body?.contentType()?.type)
        assertEquals("json", capturedRequest.body?.contentType()?.subtype)
        assertEquals("refresh_token", payload["grant_type"]?.jsonPrimitive?.content)
        assertEquals("refresh-token", payload["refresh_token"]?.jsonPrimitive?.content)
        assertEquals(CodexProtocolConfig.CLIENT_ID, payload["client_id"]?.jsonPrimitive?.content)
        assertTrue(refreshed.expiresAtEpochMillis > System.currentTimeMillis())
    }

    @Test
    fun `native web search uses fixed live mode and Codex request identity`() = runBlocking {
        lateinit var capturedRequest: Request
        val protocolClient = CodexProtocolClient(
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    capturedRequest = chain.request()
                    Response.Builder()
                        .request(capturedRequest)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"sources":[{"title":"OpenAI","url":"https://openai.com"}]}"""
                            .toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build()
        )
        val credential = CodexCredential(
            accessToken = "access-token",
            refreshToken = "refresh-token",
            expiresAtEpochMillis = Long.MAX_VALUE,
            accountId = "account-id",
        )

        protocolClient.search(
            credential = credential,
            proxy = ProviderProxy.None,
            request = CodexSearchRequest(
                id = "search-id",
                model = "gpt-codex",
                input = buildJsonArray { add(JsonPrimitive("input")) },
                commands = buildJsonObject {
                    put("search_query", buildJsonArray { add(buildJsonObject { put("q", "Codex") }) })
                },
                maxOutputTokens = 321,
            ),
            metadata = CodexSearchRequestMetadata(
                sessionId = "session-id",
                threadId = "thread-id",
                clientRequestId = "request-id",
                windowId = "window-id",
                turnMetadata = buildJsonObject { put("turn_id", "turn-id") },
            ),
        )

        val body = Buffer().also { capturedRequest.body?.writeTo(it) }.readUtf8()
        val payload = json.parseToJsonElement(body).jsonObject
        assertEquals("/backend-api/codex/alpha/search", capturedRequest.url.encodedPath)
        assertEquals("Bearer access-token", capturedRequest.header("Authorization"))
        assertEquals("account-id", capturedRequest.header("ChatGPT-Account-ID"))
        assertEquals("session-id", capturedRequest.header("session-id"))
        assertEquals("thread-id", capturedRequest.header("thread-id"))
        assertEquals("request-id", capturedRequest.header("x-client-request-id"))
        assertEquals(CodexProtocolConfig.COMPATIBILITY_VERSION, capturedRequest.header("version"))
        assertEquals("live", payload["settings"]?.jsonObject?.get("web_search")?.jsonPrimitive?.content)
        assertEquals(321, payload["max_output_tokens"]?.jsonPrimitive?.content?.toInt())
        assertTrue(payload["commands"]?.jsonObject?.containsKey("search_query") == true)
    }

    @Test
    fun `native web search instruction states that live web search is available`() {
        val model = Model(
            modelId = "account-model",
            tools = setOf(me.rerere.ai.provider.BuiltInTools.CodexWebSearch),
        )
        val messages = listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("search"))))
        val provider = OpenAICodexProvider(
            client = OkHttpClient(),
            sessionProvider = object : CodexSessionProvider {
                override suspend fun getCredential(providerId: kotlin.uuid.Uuid): CodexCredential? = null

                override suspend fun requireValidCredential(
                    providerSetting: me.rerere.ai.provider.ProviderSetting.OpenAICodex,
                ): CodexCredential = error("not used")

                override suspend fun forceRefreshCredential(
                    providerSetting: me.rerere.ai.provider.ProviderSetting.OpenAICodex,
                    failedAccessToken: String?,
                ): CodexCredential = error("not used")
            },
        )

        val prompt = provider.createNativeTools(
            provider = me.rerere.ai.provider.ProviderSetting.OpenAICodex(),
            model = model,
            messages = messages,
            maxOutputTokens = null,
        ).single().systemPrompt(model, messages)

        assertTrue(prompt.contains("Live web search is available in this turn"))
        assertTrue(prompt.contains("always live"))
    }

    @Test
    fun `native web search refreshes once after unauthorized`() = runBlocking {
        val providerSetting = me.rerere.ai.provider.ProviderSetting.OpenAICodex()
        val initial = testCredential("old-access")
        val refreshed = testCredential("new-access")
        var refreshCalls = 0
        val protocol = object : CodexProtocolClient(OkHttpClient()) {
            val usedTokens = mutableListOf<String>()

            override suspend fun search(
                credential: CodexCredential,
                proxy: ProviderProxy,
                request: CodexSearchRequest,
                metadata: CodexSearchRequestMetadata,
            ): JsonElement {
                usedTokens += credential.accessToken
                if (credential.accessToken == initial.accessToken) {
                    throw CodexProtocolException(401, "expired")
                }
                return buildJsonObject {
                    put("sources", buildJsonArray {
                        add(buildJsonObject {
                            put("title", "OpenAI")
                            put("url", "https://openai.com")
                        })
                    })
                }
            }
        }
        val provider = OpenAICodexProvider(
            client = OkHttpClient(),
            sessionProvider = object : CodexSessionProvider {
                override suspend fun getCredential(providerId: kotlin.uuid.Uuid): CodexCredential? = initial

                override suspend fun requireValidCredential(
                    providerSetting: me.rerere.ai.provider.ProviderSetting.OpenAICodex,
                ): CodexCredential = initial

                override suspend fun forceRefreshCredential(
                    providerSetting: me.rerere.ai.provider.ProviderSetting.OpenAICodex,
                    failedAccessToken: String?,
                ): CodexCredential {
                    refreshCalls += 1
                    assertEquals(initial.accessToken, failedAccessToken)
                    return refreshed
                }
            },
            protocolClient = protocol,
        )

        val tool = provider.createNativeTools(
            provider = providerSetting,
            model = Model(modelId = "account-model", tools = setOf(me.rerere.ai.provider.BuiltInTools.CodexWebSearch)),
            messages = listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("search")))),
            maxOutputTokens = 100,
        ).single()
        val output = tool.execute(buildJsonObject {
            put("search_query", buildJsonArray { add(buildJsonObject { put("q", "OpenAI") }) })
        }).jsonObject

        assertEquals(listOf("old-access", "new-access"), protocol.usedTokens)
        assertEquals(1, refreshCalls)
        assertEquals("completed", output["_tool_result_metadata"]?.jsonObject?.get("status")?.jsonPrimitive?.content)
    }

    @Test
    fun `native web search turns protocol errors into safe tool output`() = runBlocking {
        val providerSetting = me.rerere.ai.provider.ProviderSetting.OpenAICodex()
        val protocol = object : CodexProtocolClient(OkHttpClient()) {
            override suspend fun search(
                credential: CodexCredential,
                proxy: ProviderProxy,
                request: CodexSearchRequest,
                metadata: CodexSearchRequestMetadata,
            ): JsonElement = throw CodexProtocolException(403, "raw backend diagnostic: token-like-value")
        }
        val credential = testCredential("access")
        val provider = OpenAICodexProvider(
            client = OkHttpClient(),
            sessionProvider = object : CodexSessionProvider {
                override suspend fun getCredential(providerId: kotlin.uuid.Uuid): CodexCredential? = credential

                override suspend fun requireValidCredential(
                    providerSetting: me.rerere.ai.provider.ProviderSetting.OpenAICodex,
                ): CodexCredential = credential

                override suspend fun forceRefreshCredential(
                    providerSetting: me.rerere.ai.provider.ProviderSetting.OpenAICodex,
                    failedAccessToken: String?,
                ): CodexCredential = credential
            },
            protocolClient = protocol,
        )

        val tool = provider.createNativeTools(
            provider = providerSetting,
            model = Model(modelId = "account-model", tools = setOf(me.rerere.ai.provider.BuiltInTools.CodexWebSearch)),
            messages = listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("search")))),
            maxOutputTokens = 100,
        ).single()
        val output = tool.execute(buildJsonObject {
            put("search_query", buildJsonArray { add(buildJsonObject { put("q", "OpenAI") }) })
        }).jsonObject
        val error = output["error"]?.jsonObject ?: error("safe error missing")

        assertEquals("codex_web_search_error", error["type"]?.jsonPrimitive?.content)
        assertEquals("protocol_unsupported", error["code"]?.jsonPrimitive?.content)
        assertEquals("false", error["retryable"]?.jsonPrimitive?.content)
        assertFalse(json.encodeToString(output).contains("token-like-value"))
        assertNotEquals("raw backend diagnostic: token-like-value", error["message"]?.jsonPrimitive?.content)
    }

    @Test
    fun `device code accepts a string polling interval`() = runBlocking {
        val protocolClient = CodexProtocolClient(
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            """{"device_auth_id":"device","user_code":"CODE","interval":"11"}"""
                                .toResponseBody("application/json".toMediaType())
                        )
                        .build()
                }
                .build()
        )

        val code = protocolClient.requestDeviceCode(ProviderProxy.None)

        assertEquals(11_000L, code.intervalMillis)
    }

    @Test
    fun `model catalog filters hidden rows and tolerates missing optional fields`() {
        val models = client.parseModelCatalog(
            """
            {
              "models": [
                {
                  "slug": "gpt-visible",
                  "display_name": "GPT Visible",
                  "visibility": "list",
                  "supported_reasoning_levels": [{"effort":"low"}],
                  "input_modalities": ["text", "image"]
                },
                {"slug":"gpt-hidden", "visibility":"hidden"},
                {"slug":"gpt-picker-hidden", "show_in_picker":false},
                {"slug":"gpt-not-in-api", "supported_in_api":false},
                {"id":"gpt-minimal"},
                {"slug":"gpt-visible", "display_name":"duplicate"},
                {"display_name":"missing id"}
              ]
            }
            """.trimIndent()
        )

        assertEquals(listOf("gpt-visible", "gpt-minimal"), models.map { it.modelId })
        assertTrue(ModelAbility.REASONING in models.first().abilities)
        assertEquals(listOf(Modality.TEXT, Modality.IMAGE), models.first().inputModalities)
        assertFalse(ModelAbility.REASONING in models.last().abilities)
    }

    @Test
    fun `model catalog page accepts app server fields and a next cursor`() {
        val page = client.parseModelCatalogPage(
            """{"data":[{"id":"gpt-page-2"}],"nextCursor":"cursor-2"}"""
        )

        assertEquals(listOf("gpt-page-2"), page.models.map { it.modelId })
        assertEquals("cursor-2", page.nextCursor)
    }

    @Test
    fun `quota parser supports multiple buckets and an absent secondary window`() {
        val quota = client.parseQuotaResponse(
            """
            {
              "plan_type":"plus",
              "rate_limits_by_limit_id": {
                "codex": {
                  "limit_name":"Codex",
                  "primary_window": {
                    "used_percent":25.4,
                    "limit_window_seconds":18000,
                    "reset_at":2000000000
                  }
                },
                "review": {
                  "primary_window":{"used_percent":10},
                  "secondary_window":{"used_percent":80,"limit_window_seconds":604800}
                }
              },
              "credits":{"balance":12.5}
            }
            """.trimIndent()
        )

        assertEquals("plus", quota.planType)
        assertEquals(2, quota.buckets.size)
        assertEquals(25.4f, quota.buckets.first().primary?.usedPercent)
        assertNull(quota.buckets.first().secondary)
        assertEquals("7d", quota.buckets.last().secondary?.label)
        assertEquals(12.5, quota.creditBalance ?: 0.0, 0.0)
    }

    @Test
    fun `quota parser includes additional model-specific limits`() {
        val quota = client.parseQuotaResponse(
            """
            {
              "rate_limit":{"primary_window":{"used_percent":10}},
              "additional_rate_limits":[
                {
                  "limit_name":"Codex Spark",
                  "rate_limit":{"primary_window":{"used_percent":65,"limit_window_seconds":3600}}
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(2, quota.buckets.size)
        assertEquals("Codex Spark", quota.buckets.last().name)
        assertEquals(65f, quota.buckets.last().primary?.usedPercent)
    }

    private fun testCredential(accessToken: String): CodexCredential = CodexCredential(
        accessToken = accessToken,
        refreshToken = "refresh-token",
        expiresAtEpochMillis = Long.MAX_VALUE,
        accountId = "account-id",
    )
}
