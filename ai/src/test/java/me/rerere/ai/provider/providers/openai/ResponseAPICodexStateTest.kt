package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseAPICodexStateTest {
    private val api = ResponseAPI(OkHttpClient(), KeyRoulette.default())

    @Test
    fun `streamed function arguments keep Responses call id`() {
        val state = ResponseStreamState()
        val added = api.parseResponseDelta(
            jsonObject(
                "type" to JsonPrimitive("response.output_item.added"),
                "item" to jsonObject(
                    "type" to JsonPrimitive("function_call"),
                    "id" to JsonPrimitive("fc_123"),
                    "call_id" to JsonPrimitive("call_123"),
                    "name" to JsonPrimitive("search"),
                    "arguments" to JsonPrimitive(""),
                ),
            ),
            state,
        )
        val done = api.parseResponseDelta(
            jsonObject(
                "type" to JsonPrimitive("response.function_call_arguments.done"),
                "item_id" to JsonPrimitive("fc_123"),
                "arguments" to JsonPrimitive("{\"q\":\"test\"}"),
            ),
            state,
        )

        assertEquals("call_123", added?.choices?.first()?.delta?.getToolCalls()?.single()?.toolCallId)
        assertEquals("call_123", done?.choices?.first()?.delta?.getToolCalls()?.single()?.toolCallId)
    }

    @Test
    fun `completed function call without item id is accepted`() {
        val done = api.parseResponseDelta(
            jsonObject(
                "type" to JsonPrimitive("response.output_item.done"),
                "item" to jsonObject(
                    "type" to JsonPrimitive("function_call"),
                    "call_id" to JsonPrimitive("call_456"),
                    "name" to JsonPrimitive("search"),
                    "arguments" to JsonPrimitive("{\"q\":\"done\"}"),
                ),
            ),
            ResponseStreamState(),
        )

        val call = done?.choices?.first()?.delta?.getToolCalls()?.single()
            ?: error("tool call missing")
        assertEquals("call_456", call.toolCallId)
        assertEquals("search", call.toolName)
        assertEquals("{\"q\":\"done\"}", call.arguments)
    }

    @Test
    fun `completed message is used when no text deltas arrived`() {
        val done = api.parseResponseDelta(
            jsonObject(
                "type" to JsonPrimitive("response.output_item.done"),
                "item" to jsonObject(
                    "type" to JsonPrimitive("message"),
                    "id" to JsonPrimitive("msg_123"),
                    "content" to kotlinx.serialization.json.buildJsonArray {
                        add(jsonObject(
                            "type" to JsonPrimitive("output_text"),
                            "text" to JsonPrimitive("completed text"),
                        ))
                    },
                ),
            ),
            ResponseStreamState(),
        )

        assertEquals("completed text", done?.choices?.first()?.delta?.toContentText())
    }

    @Test
    fun `completed message does not duplicate streamed text`() {
        val state = ResponseStreamState()
        api.parseResponseDelta(
            jsonObject(
                "type" to JsonPrimitive("response.output_text.delta"),
                "item_id" to JsonPrimitive("msg_123"),
                "delta" to JsonPrimitive("streamed"),
            ),
            state,
        )
        val done = api.parseResponseDelta(
            jsonObject(
                "type" to JsonPrimitive("response.output_item.done"),
                "item" to jsonObject(
                    "type" to JsonPrimitive("message"),
                    "id" to JsonPrimitive("msg_123"),
                    "content" to kotlinx.serialization.json.buildJsonArray {
                        add(jsonObject(
                            "type" to JsonPrimitive("output_text"),
                            "text" to JsonPrimitive("streamed"),
                        ))
                    },
                ),
            ),
            state,
        )

        assertNull(done)
    }

    @Test
    fun `completed reasoning joins summaries into one state item`() {
        val done = api.parseResponseDelta(
            jsonObject(
                "type" to JsonPrimitive("response.output_item.done"),
                "item" to jsonObject(
                    "type" to JsonPrimitive("reasoning"),
                    "id" to JsonPrimitive("rs_multi"),
                    "encrypted_content" to JsonPrimitive("encrypted"),
                    "summary" to kotlinx.serialization.json.buildJsonArray {
                        add(jsonObject(
                            "type" to JsonPrimitive("summary_text"),
                            "text" to JsonPrimitive("first"),
                        ))
                        add(jsonObject(
                            "type" to JsonPrimitive("summary_text"),
                            "text" to JsonPrimitive("second"),
                        ))
                    },
                ),
            ),
            ResponseStreamState(),
        )

        val reasoning = done?.choices?.first()?.delta?.parts
            ?.filterIsInstance<UIMessagePart.Reasoning>()
            ?.single()
            ?: error("reasoning part missing")
        assertEquals("first\nsecond", reasoning.reasoning)
    }

    @Test
    fun `incomplete response is surfaced as an error`() {
        val error = api.extractStreamError(
            jsonObject(
                "type" to JsonPrimitive("response.incomplete"),
                "response" to jsonObject(
                    "status" to JsonPrimitive("incomplete"),
                    "incomplete_details" to jsonObject(
                        "reason" to JsonPrimitive("max_output_tokens"),
                    ),
                ),
            )
        )

        assertTrue(error?.message?.contains("max_output_tokens") == true)
    }

    @Test
    fun `encrypted reasoning is retained in the next Responses input`() {
        val reasoningChunk = api.parseResponseDelta(
            jsonObject(
                "type" to JsonPrimitive("response.output_item.done"),
                "item" to jsonObject(
                    "type" to JsonPrimitive("reasoning"),
                    "id" to JsonPrimitive("rs_123"),
                    "encrypted_content" to JsonPrimitive("encrypted-state"),
                ),
            ),
            ResponseStreamState(),
        )
        val reasoning = reasoningChunk?.choices?.first()?.delta?.parts
            ?.filterIsInstance<UIMessagePart.Reasoning>()
            ?.single()
            ?: error("reasoning part missing")
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(reasoning),
            ),
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("continue"))),
        )
        val params = TextGenerationParams(model = Model(modelId = "gpt-codex"))
        val method = ResponseAPI::class.java.getDeclaredMethod(
            "buildRequestBody",
            List::class.java,
            TextGenerationParams::class.java,
            java.lang.Boolean.TYPE,
        ).apply { isAccessible = true }
        val body = method.invoke(api, messages, params, true) as JsonObject

        val input = body["input"]?.jsonArray ?: error("input missing")
        val reasoningItem = input.first().jsonObject
        assertEquals("reasoning", reasoningItem["type"]?.jsonPrimitive?.content)
        assertEquals("rs_123", reasoningItem["id"]?.jsonPrimitive?.content)
        assertEquals("encrypted-state", reasoningItem["encrypted_content"]?.jsonPrimitive?.content)
        assertTrue(input.any { it.jsonObject["role"]?.jsonPrimitive?.content == "user" })
    }

    private fun jsonObject(vararg entries: Pair<String, kotlinx.serialization.json.JsonElement>): JsonObject =
        buildJsonObject { entries.forEach { (key, value) -> put(key, value) } }
}
