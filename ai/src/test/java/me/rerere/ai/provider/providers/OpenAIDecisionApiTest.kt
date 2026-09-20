package me.rerere.ai.provider.providers

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.provider.ProviderSetting
import me.rerere.common.http.jsonPrimitiveOrNull
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenAIDecisionApiTest {
    @Test
    fun `official TypeSafe endpoint uses systemone`() {
        val endpoint = ProviderSetting.OpenAI(
            baseUrl = "https://api.typesafe.ai/v1/",
        ).resolveDecisionEndpoint()

        assertEquals("https://api.typesafe.ai/v1/systemone", endpoint.url)
        assertEquals(OpenAIDecisionProtocol.TYPESAFE, endpoint.protocol)
    }

    @Test
    fun `Vercel standard gateway endpoint uses evaluate`() {
        val endpoint = ProviderSetting.OpenAI(
            baseUrl = "https://ai-gateway.vercel.sh/v1",
        ).resolveDecisionEndpoint()

        assertEquals("https://ai-gateway.vercel.sh/v1/evaluate", endpoint.url)
        assertEquals(OpenAIDecisionProtocol.VERCEL_AI_GATEWAY, endpoint.protocol)
    }

    @Test
    fun `Vercel gateway root automatically adds v1`() {
        val provider = ProviderSetting.OpenAI(
            baseUrl = "https://ai-gateway.vercel.sh",
        )
        val endpoint = provider.resolveDecisionEndpoint()

        assertEquals("https://ai-gateway.vercel.sh/v1/evaluate", endpoint.url)
        assertEquals("https://ai-gateway.vercel.sh/v1/models", provider.resolveModelsEndpoint())
        assertEquals(OpenAIDecisionProtocol.VERCEL_AI_GATEWAY, endpoint.protocol)
    }

    @Test
    fun `Vercel TypeSafe endpoint keeps compatible protocol`() {
        val provider = ProviderSetting.OpenAI(
            baseUrl = "https://ai-gateway.vercel.sh/typesafe",
        )
        val endpoint = provider.resolveDecisionEndpoint()

        assertEquals("https://ai-gateway.vercel.sh/typesafe/v1/systemone", endpoint.url)
        assertEquals("https://ai-gateway.vercel.sh/typesafe/v1/models", provider.resolveModelsEndpoint())
        assertEquals(OpenAIDecisionProtocol.TYPESAFE, endpoint.protocol)
    }

    @Test
    fun `versioned Vercel TypeSafe endpoint does not duplicate v1`() {
        val provider = ProviderSetting.OpenAI(
            baseUrl = "https://ai-gateway.vercel.sh/typesafe/v1/",
        )

        assertEquals(
            "https://ai-gateway.vercel.sh/typesafe/v1/systemone",
            provider.resolveDecisionEndpoint().url,
        )
        assertEquals("https://ai-gateway.vercel.sh/typesafe/v1/models", provider.resolveModelsEndpoint())
    }

    @Test
    fun `Vercel request converts noul question to boolean`() {
        val body = buildJsonObject {
            put(
                "questions",
                buildJsonObject {
                    put(
                        "speaker",
                        buildJsonObject {
                            put("type", "noul")
                            put("instructions", "Should this speaker answer?")
                        },
                    )
                },
            )
        }.adaptDecisionRequestBody(OpenAIDecisionProtocol.VERCEL_AI_GATEWAY)

        val questions = body["questions"] as JsonObject
        val speaker = questions["speaker"] as JsonObject
        assertEquals("boolean", speaker["type"]?.jsonPrimitiveOrNull?.contentOrNull)
        assertEquals(
            "Should this speaker answer?",
            speaker["instructions"]?.jsonPrimitiveOrNull?.contentOrNull,
        )
    }

    @Test
    fun `Vercel boolean answer is normalized to noul`() {
        val answers = buildJsonObject {
            put(
                "speaker",
                buildJsonObject {
                    put("type", "boolean")
                    put("probability", 0.82)
                },
            )
        }.normalizeDecisionAnswers(OpenAIDecisionProtocol.VERCEL_AI_GATEWAY)

        val speaker = answers["speaker"] as JsonObject
        assertEquals("noul", speaker["type"]?.jsonPrimitiveOrNull?.contentOrNull)
        assertEquals(0.82, speaker["noul"]?.jsonPrimitiveOrNull?.doubleOrNull ?: 0.0, 0.0001)
    }
}
