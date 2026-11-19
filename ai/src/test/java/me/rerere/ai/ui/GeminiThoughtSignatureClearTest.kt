package me.rerere.ai.ui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.GoogleProvider
import me.rerere.ai.provider.providers.google.InteractionsAPI
import me.rerere.ai.provider.providers.vertex.ServiceAccountTokenProvider
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiThoughtSignatureClearTest {

    @Test
    fun `model id matching matches gemini case-insensitively`() {
        assertTrue(isGeminiThoughtSignatureModelId("gemini-2.5-pro"))
        assertTrue(isGeminiThoughtSignatureModelId("google/gemini-3-flash-preview"))
        assertTrue(isGeminiThoughtSignatureModelId("vertex/Gemini-4-Argon"))
        assertFalse(isGeminiThoughtSignatureModelId("claude-sonnet-4-5"))
        assertFalse(isGeminiThoughtSignatureModelId("gpt-5"))
        assertFalse(isGeminiThoughtSignatureModelId(""))
        assertFalse(isGeminiThoughtSignatureModelId(null))
    }

    @Test
    fun `clearGeminiThoughtSignatures strips generateContent signatures and preserves visible text and unrelated metadata`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(
                    reasoning = "Step-by-step reasoning",
                    metadata = buildJsonObject {
                        put("thoughtSignature", "sig-reasoning")
                        put("signature", "claude-sig-untouched")
                    },
                ),
                UIMessagePart.Text(
                    text = "Final answer",
                    metadata = GoogleThoughtMetadata(thoughtSignature = "sig-text").toMetadata(),
                ),
                UIMessagePart.Text(
                    text = "",
                    metadata = GoogleThoughtMetadata(thoughtSignature = "sig-only-placeholder").toMetadata(),
                ),
                UIMessagePart.ToolCall(
                    toolCallId = "call_1",
                    toolName = "search",
                    arguments = """{"q":"flit"}""",
                    metadata = GoogleThoughtMetadata(thoughtSignature = "sig-tool").toMetadata(),
                ),
                UIMessagePart.Image(
                    url = "file:///tmp/image.png",
                    metadata = buildJsonObject {
                        put("thoughtSignature", "sig-image")
                        put(PERSISTENT_CONTEXT_IMAGE_METADATA_KEY, true)
                    },
                ),
            ),
        )

        assertTrue(message.hasGeminiThoughtSignature())

        val (cleaned, cleared) = message.clearGeminiThoughtSignatures()

        assertTrue(cleared)
        assertFalse(cleaned.hasGeminiThoughtSignature())
        // Signature-only empty text placeholder is dropped when other parts exist
        assertEquals(4, cleaned.parts.size)

        val reasoning = cleaned.parts[0] as UIMessagePart.Reasoning
        assertEquals("Step-by-step reasoning", reasoning.reasoning)
        assertNull(reasoning.metadataAs<GoogleThoughtMetadata>()?.thoughtSignature)
        assertEquals("claude-sig-untouched", reasoning.metadataAs<ClaudeReasoningMetadata>()?.signature)

        val text = cleaned.parts[1] as UIMessagePart.Text
        assertEquals("Final answer", text.text)
        assertNull(text.metadata)

        val toolCall = cleaned.parts[2] as UIMessagePart.ToolCall
        assertEquals("search", toolCall.toolName)
        assertNull(toolCall.metadataAs<GoogleThoughtMetadata>()?.thoughtSignature)
        assertEquals(true, toolCall.metadataAs<GoogleThoughtMetadata>()?.thoughtSignatureCleared)

        val image = cleaned.parts[3] as UIMessagePart.Image
        assertTrue(image.isPersistentContextImage())
        assertNull(image.metadataAs<GoogleThoughtMetadata>()?.thoughtSignature)

        // Idempotent: second clear reports no signatures left
        val (_, clearedAgain) = cleaned.clearGeminiThoughtSignatures()
        assertFalse(clearedAgain)
    }

    @Test
    fun `retrying final reply after clearing tool call signatures sends official skip validator in GoogleProvider`() {
        val provider = GoogleProvider(OkHttpClient())
        val buildContentsMethod = GoogleProvider::class.java.getDeclaredMethod(
            "buildContents",
            List::class.java,
            Model::class.java,
        ).apply { isAccessible = true }

        val assistantToolTurn = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(
                    reasoning = "Need weather and time",
                    metadata = GoogleThoughtMetadata(thoughtSignature = "old-reasoning-sig").toMetadata(),
                ),
                UIMessagePart.ToolCall(
                    toolCallId = "call_1",
                    toolName = "get_weather",
                    arguments = """{"city":"Beijing"}""",
                    metadata = GoogleThoughtMetadata(thoughtSignature = "old-tool-sig").toMetadata(),
                ),
                // Parallel sibling tool call without its own signature
                UIMessagePart.ToolCall(
                    toolCallId = "call_2",
                    toolName = "get_time",
                    arguments = """{"city":"Beijing"}""",
                ),
            ),
        )
        val (clearedAssistantToolTurn, cleared) = assistantToolTurn.clearGeminiThoughtSignatures()
        assertTrue(cleared)
        assertFalse(clearedAssistantToolTurn.hasGeminiThoughtSignature())

        val retryHistory = listOf(
            UIMessage.user("What is the weather and time in Beijing?"),
            clearedAssistantToolTurn,
            UIMessage(
                role = MessageRole.TOOL,
                parts = listOf(
                    UIMessagePart.ToolResult(
                        toolCallId = "call_1",
                        toolName = "get_weather",
                        content = JsonPrimitive("Sunny 20C"),
                        arguments = JsonObject(emptyMap()),
                    ),
                    UIMessagePart.ToolResult(
                        toolCallId = "call_2",
                        toolName = "get_time",
                        content = JsonPrimitive("14:00"),
                        arguments = JsonObject(emptyMap()),
                    ),
                ),
            ),
        )

        val contents = buildContentsMethod.invoke(
            provider,
            retryHistory,
            Model(modelId = "gemini-3-pro-preview"),
        ) as JsonArray

        assertEquals(3, contents.size)
        val modelParts = contents[1].jsonObject["parts"]!!.jsonArray
        assertEquals(2, modelParts.size)
        assertEquals(
            GOOGLE_SKIP_THOUGHT_SIGNATURE_VALIDATOR,
            modelParts[0].jsonObject["thoughtSignature"]?.jsonPrimitive?.contentOrNull,
        )
        assertFalse(modelParts[1].jsonObject.containsKey("thoughtSignature"))
    }

    @Test
    fun `clearing assistant message where signature lived on sibling reasoning marks first tool call for skip validator`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(
                    reasoning = "Thinking before tool",
                    metadata = GoogleThoughtMetadata(thoughtSignature = "sibling-sig").toMetadata(),
                ),
                UIMessagePart.ToolCall(
                    toolCallId = "call_1",
                    toolName = "lookup",
                    arguments = """{"q":"test"}""",
                ),
            ),
        )

        val (cleaned, cleared) = message.clearGeminiThoughtSignatures()
        assertTrue(cleared)
        assertFalse(cleaned.hasGeminiThoughtSignature())

        val toolCall = cleaned.parts.filterIsInstance<UIMessagePart.ToolCall>().single()
        assertEquals(true, toolCall.metadataAs<GoogleThoughtMetadata>()?.thoughtSignatureCleared)
    }

    @Test
    fun `clearGeminiThoughtSignatures strips Interactions API signatures and skips unsigned thought and built-in tool steps on replay`() {
        val interactionsAPI = InteractionsAPI(
            client = OkHttpClient(),
            keyRoulette = KeyRoulette.default(),
            serviceAccountTokenProvider = ServiceAccountTokenProvider(OkHttpClient()),
        )
        val response = buildJsonObject {
            put("id", "interaction_1")
            put("status", "completed")
            put("steps", buildJsonArray {
                add(buildJsonObject {
                    put("type", "thought")
                    put("signature", "thought-sig-1")
                    put("summary", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", "Checking web results")
                        })
                    })
                })
                add(buildJsonObject {
                    put("type", "google_search_call")
                    put("id", "s1")
                    put("signature", "search-call-sig")
                    put("arguments", buildJsonObject {
                        put("queries", buildJsonArray { add(JsonPrimitive("Gemini")) })
                    })
                })
                add(buildJsonObject {
                    put("type", "google_search_result")
                    put("call_id", "s1")
                    put("signature", "search-result-sig")
                    put("result", buildJsonArray {})
                })
                add(buildJsonObject {
                    put("type", "url_context_call")
                    put("id", "u1")
                    put("signature", "url-call-sig")
                    put("arguments", buildJsonObject {
                        put("urls", buildJsonArray { add(JsonPrimitive("https://example.com")) })
                    })
                })
                add(buildJsonObject {
                    put("type", "url_context_result")
                    put("call_id", "u1")
                    put("signature", "url-result-sig")
                    put("result", buildJsonArray {})
                })
                add(buildJsonObject {
                    put("type", "model_output")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", "Here is the answer.")
                        })
                    })
                })
            })
        }

        val originalMessage = interactionsAPI.parseNonStreamResponse(
            bodyJson = response,
            modelId = "gemini-2.5-pro",
            rawResponse = response.toString(),
        ).choices.single().message!!

        assertTrue(originalMessage.hasGeminiThoughtSignature())

        val (cleanedMessage, cleared) = originalMessage.clearGeminiThoughtSignatures()

        assertTrue(cleared)
        assertFalse(cleanedMessage.hasGeminiThoughtSignature())
        // Visible reasoning, search UI state, and answer text remain intact
        assertEquals(
            "Checking web results",
            cleanedMessage.parts.filterIsInstance<UIMessagePart.Reasoning>().single().reasoning,
        )
        assertEquals(
            listOf("Gemini"),
            cleanedMessage.parts.filterIsInstance<UIMessagePart.WebSearch>()
                .first { it.action == WebSearchAction.Search }.queries,
        )
        assertEquals(
            "Here is the answer.",
            cleanedMessage.parts.filterIsInstance<UIMessagePart.Text>().first { it.text.isNotEmpty() }.text,
        )
        // interactionSteps key is preserved for UI render planning
        assertTrue(
            cleanedMessage.parts.filterIsInstance<UIMessagePart.Reasoning>().single()
                .metadata?.containsKey("interactionSteps") == true,
        )

        val requestBody = interactionsAPI.buildRequestBody(
            messages = listOf(UIMessage.user("Follow-up question"), cleanedMessage),
            params = TextGenerationParams(model = Model(modelId = "gemini-2.5-pro")),
            stream = false,
        )
        val input = requestBody["input"]?.jsonArray
        assertNotNull(input)
        val replayedAssistantSteps = input!!.drop(1).map { it.jsonObject }
        assertEquals(1, replayedAssistantSteps.size)
        assertEquals("model_output", replayedAssistantSteps.single()["type"]?.jsonPrimitive?.contentOrNull)
    }
}
