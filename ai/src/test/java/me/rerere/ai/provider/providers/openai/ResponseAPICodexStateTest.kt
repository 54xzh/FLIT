package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
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
    fun `Codex built in tools are registered with function tools`() {
        val model = Model(
            modelId = "gpt-codex",
            abilities = listOf(ModelAbility.TOOL),
            tools = setOf(BuiltInTools.CodexWebSearch, BuiltInTools.CodexImageGeneration),
        )
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hello"))),
        )
        val builtInOnlyBody = buildResponseRequest(
            messages = messages,
            params = TextGenerationParams(model = model),
            stream = true,
        )
        val builtInTools = builtInOnlyBody["tools"]?.jsonArray ?: error("web search tool missing")
        assertEquals(2, builtInTools.size)
        assertEquals("web_search", builtInTools.first().jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("image_generation", builtInTools.last().jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("gpt-image-2", builtInTools.last().jsonObject["model"]?.jsonPrimitive?.content)
        assertEquals("1024x1024", builtInTools.last().jsonObject["size"]?.jsonPrimitive?.content)
        assertEquals("medium", builtInTools.last().jsonObject["quality"]?.jsonPrimitive?.content)
        assertTrue(
            builtInOnlyBody["instructions"]?.jsonPrimitive?.content
                ?.contains("use the image_generation tool") == true
        )

        val body = buildResponseRequest(
            messages = messages,
            params = TextGenerationParams(
                model = model,
                tools = listOf(
                    Tool(
                        name = "local_tool",
                        description = "A normal local tool",
                        execute = { JsonObject(emptyMap()) },
                    ),
                ),
            ),
            stream = true,
        )

        val tools = body["tools"]?.jsonArray ?: error("normal tool missing")
        assertEquals(3, tools.size)
        assertEquals("web_search", tools.first().jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("image_generation", tools[1].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("function", tools.last().jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("local_tool", tools.last().jsonObject["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `completed image generation call is added as an assistant image`() {
        val done = api.parseResponseDelta(
            jsonObject(
                "type" to JsonPrimitive("response.output_item.done"),
                "item" to jsonObject(
                    "type" to JsonPrimitive("image_generation_call"),
                    "id" to JsonPrimitive("img_123"),
                    "result" to JsonPrimitive("aGVsbG8="),
                    "output_format" to JsonPrimitive("webp"),
                ),
            ),
            ResponseStreamState(),
        )

        val image = done?.choices?.first()?.delta?.parts
            ?.filterIsInstance<UIMessagePart.Image>()
            ?.single()
            ?: error("generated image missing")
        assertEquals("data:image/webp;base64,aGVsbG8=", image.url)
    }

    @Test
    fun `completed built in web search sources are attached to the response`() {
        val state = ResponseStreamState()
        assertNull(
            api.parseResponseDelta(
                jsonObject(
                    "type" to JsonPrimitive("response.output_item.done"),
                    "item" to jsonObject(
                        "type" to JsonPrimitive("web_search_call"),
                        "action" to jsonObject(
                            "sources" to buildJsonArray {
                                add(jsonObject(
                                    "url" to JsonPrimitive("https://openai.com"),
                                    "title" to JsonPrimitive("OpenAI"),
                                ))
                            },
                        ),
                    ),
                ),
                state,
            )
        )

        val message = api.parseResponseDelta(
            jsonObject(
                "type" to JsonPrimitive("response.output_item.done"),
                "item" to jsonObject(
                    "type" to JsonPrimitive("message"),
                    "content" to buildJsonArray {
                        add(jsonObject(
                            "type" to JsonPrimitive("output_text"),
                            "text" to JsonPrimitive("result"),
                        ))
                    },
                ),
            ),
            state,
        )

        val citation = message?.choices?.first()?.delta?.annotations?.singleOrNull()
            as? UIMessageAnnotation.UrlCitation
        assertEquals("OpenAI", citation?.title)
        assertEquals("https://openai.com", citation?.url)
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
    fun `completed message retains URL citations`() {
        val done = api.parseResponseDelta(
            jsonObject(
                "type" to JsonPrimitive("response.output_item.done"),
                "item" to jsonObject(
                    "type" to JsonPrimitive("message"),
                    "content" to buildJsonArray {
                        add(jsonObject(
                            "type" to JsonPrimitive("output_text"),
                            "text" to JsonPrimitive("citation"),
                            "annotations" to buildJsonArray {
                                add(jsonObject(
                                    "type" to JsonPrimitive("url_citation"),
                                    "url" to JsonPrimitive("https://openai.com"),
                                    "title" to JsonPrimitive("OpenAI"),
                                ))
                            },
                        ))
                    },
                ),
            ),
            ResponseStreamState(),
        )

        val citation = done?.choices?.first()?.delta?.annotations?.singleOrNull()
            as? UIMessageAnnotation.UrlCitation
        assertEquals("OpenAI", citation?.title)
        assertEquals("https://openai.com", citation?.url)
    }

    @Test
    fun `streamed annotation event retains URL citation`() {
        val chunk = api.parseResponseDelta(
            jsonObject(
                "type" to JsonPrimitive("response.output_text.annotation.added"),
                "item_id" to JsonPrimitive("msg_1"),
                "annotation" to jsonObject(
                    "type" to JsonPrimitive("url_citation"),
                    "url_citation" to jsonObject(
                        "url" to JsonPrimitive("https://openai.com"),
                        "title" to JsonPrimitive("OpenAI"),
                    ),
                ),
            ),
            ResponseStreamState(),
        )

        val citation = chunk?.choices?.first()?.delta?.annotations?.singleOrNull()
            as? UIMessageAnnotation.UrlCitation
        assertEquals("https://openai.com", citation?.url)
    }

    @Test
    fun `non-stream output de-duplicates URL citations`() {
        val response = jsonObject(
            "id" to JsonPrimitive("response_1"),
            "model" to JsonPrimitive("gpt-codex"),
            "output" to buildJsonArray {
                repeat(2) {
                    add(jsonObject(
                        "type" to JsonPrimitive("message"),
                        "content" to buildJsonArray {
                            add(jsonObject(
                                "type" to JsonPrimitive("output_text"),
                                "text" to JsonPrimitive("text"),
                                "annotations" to buildJsonArray {
                                    add(jsonObject(
                                        "type" to JsonPrimitive("url_citation"),
                                        "url" to JsonPrimitive("https://openai.com"),
                                        "title" to JsonPrimitive("OpenAI"),
                                    ))
                                },
                            ))
                        },
                    ))
                }
            },
        )

        val method = ResponseAPI::class.java.getDeclaredMethod("parseResponseOutput", JsonObject::class.java)
            .apply { isAccessible = true }
        val chunk = method.invoke(api, response) as me.rerere.ai.ui.MessageChunk

        assertEquals(1, chunk.choices.single().message?.annotations?.size)
    }

    @Test
    fun `unknown and incomplete annotation events are ignored`() {
        assertNull(api.parseResponseDelta(jsonObject("type" to JsonPrimitive("response.unknown")), ResponseStreamState()))
        assertNull(
            api.parseResponseDelta(
                jsonObject("type" to JsonPrimitive("response.output_text.annotation.added")),
                ResponseStreamState(),
            )
        )
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
        val body = buildResponseRequest(messages, params, stream = true)

        val input = body["input"]?.jsonArray ?: error("input missing")
        val reasoningItem = input.first().jsonObject
        assertEquals("reasoning", reasoningItem["type"]?.jsonPrimitive?.content)
        assertEquals("rs_123", reasoningItem["id"]?.jsonPrimitive?.content)
        assertEquals("encrypted-state", reasoningItem["encrypted_content"]?.jsonPrimitive?.content)
        assertTrue(input.any { it.jsonObject["role"]?.jsonPrimitive?.content == "user" })
    }

    private fun buildResponseRequest(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        stream: Boolean,
    ): JsonObject {
        val method = ResponseAPI::class.java.getDeclaredMethod(
            "buildRequestBody",
            List::class.java,
            TextGenerationParams::class.java,
            java.lang.Boolean.TYPE,
        ).apply { isAccessible = true }
        return method.invoke(api, messages, params, stream) as JsonObject
    }

    private fun jsonObject(vararg entries: Pair<String, kotlinx.serialization.json.JsonElement>): JsonObject =
        buildJsonObject { entries.forEach { (key, value) -> put(key, value) } }
}
