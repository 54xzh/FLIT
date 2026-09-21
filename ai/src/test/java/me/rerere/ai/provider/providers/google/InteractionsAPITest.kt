package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.vertex.ServiceAccountTokenProvider
import me.rerere.ai.ui.GoogleThoughtMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractionsAPITest {

    private val interactionsAPI = InteractionsAPI(
        client = OkHttpClient(),
        keyRoulette = KeyRoulette.default(),
        serviceAccountTokenProvider = ServiceAccountTokenProvider(OkHttpClient()),
    )

    @Test
    fun `buildRequestBody should format system instruction, input steps, thinking and tools properly`() {
        val messages = listOf(
            UIMessage(
                role = MessageRole.SYSTEM,
                parts = listOf(UIMessagePart.Text("You are an AI assistant."))
            ),
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text("Hello world!"))
            ),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Text("Hi there!"),
                    UIMessagePart.ToolCall(
                        toolCallId = "call_abc123",
                        toolName = "get_weather",
                        arguments = "{\"location\":\"Beijing\"}"
                    )
                )
            ),
            UIMessage(
                role = MessageRole.TOOL,
                parts = listOf(
                    UIMessagePart.ToolResult(
                        toolCallId = "call_abc123",
                        toolName = "get_weather",
                        content = JsonPrimitive("Sunny 25C"),
                        arguments = kotlinx.serialization.json.JsonObject(emptyMap()),
                    )
                )
            )
        )

        val params = TextGenerationParams(
            model = Model(
                modelId = "gemini-2.5-pro",
                abilities = listOf(ModelAbility.REASONING, ModelAbility.TOOL),
                tools = setOf(BuiltInTools.Search)
            ),
            temperature = 0.7f,
            reasoningLevel = ReasoningLevel.HIGH,
            tools = listOf(
                Tool(
                    name = "get_weather",
                    description = "Get current weather",
                    parameters = { null },
                    execute = { it }
                )
            )
        )

        val body = interactionsAPI.buildRequestBody(messages, params, stream = true)

        assertEquals("gemini-2.5-pro", body["model"]?.jsonPrimitive?.contentOrNull)
        assertEquals(false, body["store"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(true, body["stream"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("You are an AI assistant.", body["system_instruction"]?.jsonPrimitive?.contentOrNull)

        val input = body["input"]?.jsonArray
        assertNotNull(input)
        assertEquals(4, input!!.size)

        // 1. User turn
        val userTurn = input[0].jsonObject
        assertEquals("user_input", userTurn["type"]?.jsonPrimitive?.contentOrNull)
        val userContent = userTurn["content"]?.jsonArray
        assertEquals("Hello world!", userContent?.get(0)?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull)

        // 2. Assistant text and tool call
        val assistantTextTurn = input[1].jsonObject
        assertEquals("model_output", assistantTextTurn["type"]?.jsonPrimitive?.contentOrNull)

        val assistantCallTurn = input[2].jsonObject
        assertEquals("function_call", assistantCallTurn["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("get_weather", assistantCallTurn["name"]?.jsonPrimitive?.contentOrNull)
        assertEquals("call_abc123", assistantCallTurn["id"]?.jsonPrimitive?.contentOrNull)

        // 3. Tool result formatted with result array
        val toolResultTurn = input[3].jsonObject
        assertEquals("function_result", toolResultTurn["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("get_weather", toolResultTurn["name"]?.jsonPrimitive?.contentOrNull)
        assertEquals("call_abc123", toolResultTurn["call_id"]?.jsonPrimitive?.contentOrNull)
        val toolResultContent = toolResultTurn["result"]?.jsonArray
        assertNotNull(toolResultContent)
        assertEquals(1, toolResultContent!!.size)
        assertEquals("text", toolResultContent[0].jsonObject["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("Sunny 25C", toolResultContent[0].jsonObject["text"]?.jsonPrimitive?.contentOrNull)

        // 4. Generation Config with flat thinking_level & thinking_summaries
        val genConfig = body["generation_config"]?.jsonObject
        assertNotNull(genConfig)
        assertEquals("high", genConfig!!["thinking_level"]?.jsonPrimitive?.contentOrNull)
        assertEquals("auto", genConfig["thinking_summaries"]?.jsonPrimitive?.contentOrNull)

        // 5. Flat tools array containing both built-in search and custom function
        val tools = body["tools"]?.jsonArray
        assertNotNull(tools)
        assertEquals(2, tools!!.size)
        val searchTool = tools[0].jsonObject
        assertEquals("google_search", searchTool["type"]?.jsonPrimitive?.contentOrNull)
        val funcTool = tools[1].jsonObject
        assertEquals("function", funcTool["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("get_weather", funcTool["name"]?.jsonPrimitive?.contentOrNull)
        assertEquals("Get current weather", funcTool["description"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `parseStreamMessageChunk handles thought and text steps with official fields`() {
        val tracker = InteractionsAPI.StepContextTracker()

        // 1. Thought step start (supports index-only without id)
        val stepStartJson = buildJsonObject {
            put("step", buildJsonObject {
                put("index", 0)
                put("type", "thought")
            })
        }
        interactionsAPI.parseStreamMessageChunk(
            jsonData = stepStartJson,
            eventType = "step.start",
            modelId = "gemini-2.5-pro",
            rawResponse = "",
            stepTracker = tracker
        )
        assertEquals("thought", tracker.currentStepType)

        // 2. Thought delta using official thought_summary and thought_signature fields
        val thoughtSummaryJson = buildJsonObject {
            put("event_type", "step.delta")
            put("delta", buildJsonObject {
                put("type", "thought_summary")
                put("content", buildJsonObject {
                    put("type", "text")
                    put("text", "Analyzing the user query...")
                })
            })
        }
        val chunk2 = interactionsAPI.parseStreamMessageChunk(
            jsonData = thoughtSummaryJson,
            eventType = "step.delta",
            modelId = "gemini-2.5-pro",
            rawResponse = "",
            stepTracker = tracker
        )
        assertNotNull(chunk2)
        val choice2 = chunk2!!.choices.first()
        val part2 = choice2.delta?.parts?.first()
        assertTrue(part2 is UIMessagePart.Reasoning)
        assertEquals("Analyzing the user query...", (part2 as UIMessagePart.Reasoning).reasoning)

        val thoughtSignatureJson = buildJsonObject {
            put("event_type", "step.delta")
            put("delta", buildJsonObject {
                put("type", "thought_signature")
                put("signature", "sig_xyz_123")
            })
        }
        val chunk2Sig = interactionsAPI.parseStreamMessageChunk(
            jsonData = thoughtSignatureJson,
            eventType = "step.delta",
            modelId = "gemini-2.5-pro",
            rawResponse = "",
            stepTracker = tracker
        )
        assertNotNull(chunk2Sig)
        val part2Sig = chunk2Sig!!.choices.first().delta?.parts?.first()
        assertTrue(part2Sig is UIMessagePart.Reasoning)
        assertEquals("sig_xyz_123", part2Sig?.metadataAs<GoogleThoughtMetadata>()?.thoughtSignature)
        val rawThought = part2Sig?.metadataAs<GoogleThoughtMetadata>()?.interactionStep
        assertEquals("thought", rawThought?.get("type")?.jsonPrimitive?.contentOrNull)
        assertEquals("sig_xyz_123", rawThought?.get("signature")?.jsonPrimitive?.contentOrNull)
        assertEquals(
            "Analyzing the user query...",
            rawThought?.get("summary")?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("text")?.jsonPrimitive?.contentOrNull,
        )

        // 3. Model output step start
        val modelOutputStartJson = buildJsonObject {
            put("step", buildJsonObject {
                put("index", 1)
                put("type", "model_output")
            })
        }
        interactionsAPI.parseStreamMessageChunk(
            jsonData = modelOutputStartJson,
            eventType = "step.start",
            modelId = "gemini-2.5-pro",
            rawResponse = "",
            stepTracker = tracker
        )
        assertEquals("model_output", tracker.currentStepType)

        // 4. Model output text delta
        val textDeltaJson = buildJsonObject {
            put("delta", buildJsonObject {
                put("text", "Hello, I am ready to help!")
            })
        }
        val chunk4 = interactionsAPI.parseStreamMessageChunk(
            jsonData = textDeltaJson,
            eventType = "step.delta",
            modelId = "gemini-2.5-pro",
            rawResponse = "",
            stepTracker = tracker
        )
        assertNotNull(chunk4)
        val part4 = chunk4!!.choices.first().delta?.parts?.first()
        assertTrue(part4 is UIMessagePart.Text)
        assertEquals("Hello, I am ready to help!", (part4 as UIMessagePart.Text).text)

        // 5. Official interaction.completed event with nested interaction usage and status
        val interactionCompletedJson = buildJsonObject {
            put("event_type", "interaction.completed")
            put("interaction", buildJsonObject {
                put("id", "v1_completed_id")
                put("status", "completed")
                put("usage", buildJsonObject {
                    put("total_input_tokens", 100)
                    put("total_output_tokens", 50)
                    put("total_cached_tokens", 20)
                    put("total_tokens", 150)
                })
            })
        }
        val chunk5 = interactionsAPI.parseStreamMessageChunk(
            jsonData = interactionCompletedJson,
            eventType = "interaction.completed",
            modelId = "gemini-2.5-pro",
            rawResponse = "",
            stepTracker = tracker
        )
        assertNotNull(chunk5)
        assertEquals("v1_completed_id", chunk5!!.id)
        assertEquals(100, chunk5.usage?.promptTokens)
        assertEquals(50, chunk5.usage?.completionTokens)
        assertEquals(20, chunk5.usage?.cachedTokens)
        assertEquals(150, chunk5.usage?.totalTokens)
        assertEquals("completed", chunk5.finishReasons.firstOrNull())
    }

    @Test
    fun `parseStreamMessageChunk handles streaming function_call with partial_arguments`() {
        val tracker = InteractionsAPI.StepContextTracker()

        // 1. Step start registers call_id and name, and carries empty arguments {}
        val functionCallStartJson = buildJsonObject {
            put("event_type", "step.start")
            put("step", buildJsonObject {
                put("index", 2)
                put("type", "function_call")
                put("call_id", "call_weather_999")
                put("name", "get_weather")
                put("arguments", buildJsonObject {})
            })
        }
        val startChunk = interactionsAPI.parseStreamMessageChunk(
            jsonData = functionCallStartJson,
            eventType = "step.start",
            modelId = "gemini-2.5-pro",
            rawResponse = "",
            stepTracker = tracker
        )
        assertEquals("function_call", tracker.currentStepType)
        assertEquals("call_weather_999", tracker.currentFunctionCallId)
        assertEquals("get_weather", tracker.currentFunctionName)
        assertNotNull(startChunk)
        val startPart = startChunk!!.choices.first().delta?.parts?.first() as UIMessagePart.ToolCall
        assertEquals("", startPart.arguments)

        // 2. Step delta arrives with type "arguments_delta" and arguments string
        val functionCallDeltaJson = buildJsonObject {
            put("event_type", "step.delta")
            put("delta", buildJsonObject {
                put("type", "arguments_delta")
                put("arguments", "{\"location\": \"Tokyo\"}")
            })
        }
        val deltaChunk = interactionsAPI.parseStreamMessageChunk(
            jsonData = functionCallDeltaJson,
            eventType = "step.delta",
            modelId = "gemini-2.5-pro",
            rawResponse = "",
            stepTracker = tracker
        )

        assertNotNull(deltaChunk)
        val deltaPart = deltaChunk!!.choices.first().delta?.parts?.first() as UIMessagePart.ToolCall
        assertEquals("get_weather", deltaPart.toolName)
        assertEquals("call_weather_999", deltaPart.toolCallId)
        assertEquals("{\"location\": \"Tokyo\"}", deltaPart.arguments)

        // 3. Merging start and delta must NOT produce {}{"location": "Tokyo"}
        val mergedPart = startPart.merge(deltaPart)
        assertEquals("{\"location\": \"Tokyo\"}", mergedPart.arguments)

        // 4. Defensive check: even if start was "{}" placeholder, merge replaces it
        val placeholderPart = startPart.copy(arguments = "{}")
        val mergedFromPlaceholder = placeholderPart.merge(deltaPart)
        assertEquals("{\"location\": \"Tokyo\"}", mergedFromPlaceholder.arguments)
    }

    @Test
    fun `parseNonStreamResponse keeps output media and top-level incomplete status`() {
        val nonStreamBody = buildJsonObject {
            put("id", "interaction_response_1")
            put("status", "incomplete")
            put("steps", buildJsonArray {
                add(buildJsonObject {
                    put("type", "thought")
                    put("signature", "sig_456")
                    put("summary", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", "Planning response...")
                        })
                    })
                })
                add(buildJsonObject {
                    put("type", "function_call")
                    put("id", "call_789")
                    put("name", "search_tool")
                    put("arguments", buildJsonObject {
                        put("query", "Android 16 preview")
                    })
                })
                add(buildJsonObject {
                    put("type", "model_output")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", "Let me search that for you.")
                        })
                        add(buildJsonObject {
                            put("type", "image")
                            put("data", "aW1hZ2U=")
                            put("mime_type", "image/jpeg")
                        })
                        add(buildJsonObject {
                            put("type", "audio")
                            put("data", "YXVkaW8=")
                            put("mime_type", "audio/wav")
                        })
                    })
                })
            })
            put("usage", buildJsonObject {
                put("total_input_tokens", 80)
                put("total_output_tokens", 30)
                put("total_cached_tokens", 10)
                put("total_tokens", 110)
            })
        }

        val result = interactionsAPI.parseNonStreamResponse(
            bodyJson = nonStreamBody,
            modelId = "gemini-2.5-pro",
            rawResponse = nonStreamBody.toString()
        )

        val choice = result.choices.first()
        val parts = choice.message?.parts
        assertNotNull(parts)
        assertEquals(5, parts!!.size)

        // Thought
        assertTrue(parts[0] is UIMessagePart.Reasoning)
        assertEquals("Planning response...", (parts[0] as UIMessagePart.Reasoning).reasoning)
        assertEquals("sig_456", parts[0].metadataAs<GoogleThoughtMetadata>()?.thoughtSignature)
        assertEquals(
            nonStreamBody["steps"]?.jsonArray?.get(0),
            parts[0].metadataAs<GoogleThoughtMetadata>()?.interactionStep,
        )

        // Function call
        assertTrue(parts[1] is UIMessagePart.ToolCall)
        val toolCall = parts[1] as UIMessagePart.ToolCall
        assertEquals("search_tool", toolCall.toolName)
        assertEquals("call_789", toolCall.toolCallId)
        assertTrue(toolCall.arguments.contains("Android 16 preview"))

        // Text
        assertTrue(parts[2] is UIMessagePart.Text)
        assertEquals("Let me search that for you.", (parts[2] as UIMessagePart.Text).text)
        assertEquals("data:image/jpeg;base64,aW1hZ2U=", (parts[3] as UIMessagePart.Image).url)
        assertEquals("data:audio/wav;base64,YXVkaW8=", (parts[4] as UIMessagePart.Audio).url)

        // Usage
        assertNotNull(result.usage)
        assertEquals(80, result.usage?.promptTokens)
        assertEquals(30, result.usage?.completionTokens)
        assertEquals(10, result.usage?.cachedTokens)
        assertEquals(110, result.usage?.totalTokens)
        assertEquals("incomplete", choice.finishReason)
        assertEquals(setOf("incomplete"), result.finishReasons)
    }

    @Test
    fun `buildRequestBody preserves signed stateless interaction steps`() {
        val thought = buildJsonObject {
            put("type", "thought")
            put("signature", "thought-signature")
            put("summary", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", "I need to search first.")
                })
            })
        }
        val searchCall = buildJsonObject {
            put("type", "google_search_call")
            put("id", "search-1")
            put("signature", "search-call-signature")
            put("query", "Gemini Interactions API")
        }
        val searchResult = buildJsonObject {
            put("type", "google_search_result")
            put("call_id", "search-1")
            put("signature", "search-result-signature")
            put("result", buildJsonArray {})
        }
        val response = buildJsonObject {
            put("id", "interaction-response")
            put("status", "completed")
            put("steps", buildJsonArray {
                add(thought)
                add(searchCall)
                add(searchResult)
            })
        }

        val responseMessage = interactionsAPI.parseNonStreamResponse(
            bodyJson = response,
            modelId = "gemini-2.5-pro",
            rawResponse = response.toString(),
        ).choices.single().message!!
        val body = interactionsAPI.buildRequestBody(
            messages = listOf(
                UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Text("Search this.")),
                ),
                responseMessage,
            ),
            params = TextGenerationParams(model = Model(modelId = "gemini-2.5-pro")),
            stream = false,
        )

        val input = body["input"]?.jsonArray
        assertNotNull(input)
        assertEquals("user_input", input!![0].jsonObject["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals(response["steps"]?.jsonArray?.toList(), input.drop(1))
    }

    @Test
    fun `parseStreamMessageChunk keeps image and audio output`() {
        val tracker = InteractionsAPI.StepContextTracker()
        interactionsAPI.parseStreamMessageChunk(
            jsonData = buildJsonObject {
                put("step", buildJsonObject {
                    put("type", "model_output")
                    put("index", 0)
                })
            },
            eventType = "step.start",
            modelId = "gemini-2.5-pro",
            rawResponse = "",
            stepTracker = tracker,
        )

        val image = interactionsAPI.parseStreamMessageChunk(
            jsonData = buildJsonObject {
                put("delta", buildJsonObject {
                    put("type", "image")
                    put("data", "aW1hZ2U=")
                    put("mime_type", "image/jpeg")
                })
            },
            eventType = "step.delta",
            modelId = "gemini-2.5-pro",
            rawResponse = "",
            stepTracker = tracker,
        )?.choices?.single()?.delta?.parts?.single() as? UIMessagePart.Image
        assertEquals("data:image/jpeg;base64,aW1hZ2U=", image?.url)

        val audio = interactionsAPI.parseStreamMessageChunk(
            jsonData = buildJsonObject {
                put("delta", buildJsonObject {
                    put("type", "audio")
                    put("data", "YXVkaW8=")
                    put("mime_type", "audio/wav")
                })
            },
            eventType = "step.delta",
            modelId = "gemini-2.5-pro",
            rawResponse = "",
            stepTracker = tracker,
        )?.choices?.single()?.delta?.parts?.single() as? UIMessagePart.Audio
        assertEquals("data:audio/wav;base64,YXVkaW8=", audio?.url)
    }
}
