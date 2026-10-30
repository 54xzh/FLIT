package me.rerere.ai.provider.providers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.ensureBuiltInSearchTool
import me.rerere.ai.ui.GoogleThoughtMetadata
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolResultImage
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.toMetadata
import okhttp3.OkHttpClient
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleProviderTest {
    private fun buildCompletionRequestBody(model: Model, level: ReasoningLevel): JsonObject {
        val method = GoogleProvider::class.java.getDeclaredMethod(
            "buildCompletionRequestBody",
            List::class.java,
            TextGenerationParams::class.java,
        )
        method.isAccessible = true
        return method.invoke(
            GoogleProvider(OkHttpClient()),
            listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Hello")))),
            TextGenerationParams(model = model, reasoningLevel = level),
        ) as JsonObject
    }

    @Test
    fun `Gemini 3 and 4 requests use thinking levels instead of budgets`() {
        listOf(
            "gemini-3-pro-preview",
            "gemini-3-flash-preview",
            "gemini-3.1-pro-preview",
            "gemini-3.1-flash-image-preview",
            "gemini-4-pro",
            "gemini-4-flash",
            "gemini-4.0-pro-preview",
            "google/Gemini4-Flash",
        ).forEach { modelId ->
            ReasoningLevel.entries.forEach { level ->
                val body = buildCompletionRequestBody(
                    Model(modelId = modelId, abilities = listOf(ModelAbility.REASONING)),
                    level,
                )
                val config = body["generationConfig"]?.jsonObject?.get("thinkingConfig") as? JsonObject
                    ?: error("Missing thinkingConfig for $modelId/$level")
                assertFalse("$modelId/$level", config.containsKey("thinkingBudget"))
                val expectedLevel = when (level) {
                    ReasoningLevel.AUTO -> null
                    ReasoningLevel.OFF -> "minimal"
                    ReasoningLevel.LOW -> "low"
                    ReasoningLevel.MEDIUM -> "medium"
                    else -> "high"
                }
                assertEquals("$modelId/$level", expectedLevel, config["thinkingLevel"]?.jsonPrimitive?.content)
            }
        }
    }

    @Test
    fun `Gemini 2_5 requests preserve thinking budgets`() {
        listOf("gemini-2.5-flash", "gemini-2.5-pro").forEach { modelId ->
            ReasoningLevel.entries.forEach { level ->
                val body = buildCompletionRequestBody(
                    Model(modelId = modelId, abilities = listOf(ModelAbility.REASONING)),
                    level,
                )
                val config = body["generationConfig"]?.jsonObject?.get("thinkingConfig") as? JsonObject
                    ?: error("Missing thinkingConfig for $modelId/$level")
                assertFalse("$modelId/$level", config.containsKey("thinkingLevel"))
                val expectedBudget = when {
                    level == ReasoningLevel.AUTO -> null
                    level == ReasoningLevel.OFF && modelId == "gemini-2.5-pro" -> null
                    else -> level.budgetTokens.toString()
                }
                assertEquals("$modelId/$level", expectedBudget, config["thinkingBudget"]?.jsonPrimitive?.content)
            }
        }
    }

    @Test
    fun `Gemini 4 search requests include google search tool`() {
        val model = Model(modelId = "gemini-4-pro").ensureBuiltInSearchTool()
        assertTrue(model.tools.contains(BuiltInTools.Search))
        val body = buildCompletionRequestBody(model, ReasoningLevel.AUTO)
        val tools = body["tools"] as? JsonArray ?: error("Missing built-in search tools")
        assertTrue(tools.any { (it as? JsonObject)?.containsKey("google_search") == true })
    }

    @Test
    fun `Argon requests include summaries and only supported thinking levels`() {
        val levels = mapOf(ReasoningLevel.AUTO to "medium", ReasoningLevel.OFF to "low",
            ReasoningLevel.LOW to "low", ReasoningLevel.MEDIUM to "medium",
            ReasoningLevel.HIGH to "high", ReasoningLevel.XHIGH to "high", ReasoningLevel.MAX to "high")
        levels.forEach { (level, expected) ->
            val body = buildCompletionRequestBody(
                Model(modelId = "gemini-4-argon", abilities = listOf(ModelAbility.REASONING)), level)
            val thinking = body["generationConfig"]?.jsonObject?.get("thinkingConfig")?.jsonObject
                ?: error("Missing thinking config")
            assertEquals(expected, thinking["thinkingLevel"]?.jsonPrimitive?.content)
            assertEquals("true", thinking["includeThoughts"]?.jsonPrimitive?.content)
            assertFalse(thinking.containsKey("thinkingBudget"))
        }
    }

    private fun parseMessagePart(part: JsonObject): UIMessagePart? {
        val method = GoogleProvider::class.java.getDeclaredMethod("parseMessagePart", JsonObject::class.java)
        method.isAccessible = true
        return method.invoke(
            GoogleProvider(OkHttpClient()),
            part,
        ) as UIMessagePart?
    }

    private fun parseStreamMessageChunk(payload: JsonObject): MessageChunk? {
        val method = GoogleProvider::class.java.getDeclaredMethod(
            "parseStreamMessageChunk",
            JsonObject::class.java,
            String::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(
            GoogleProvider(OkHttpClient()),
            payload,
            "gemini-test",
            "raw-sse-event",
        ) as MessageChunk?
    }

    private fun buildContents(messages: List<UIMessage>): JsonArray {
        val method = GoogleProvider::class.java.getDeclaredMethod(
            "buildContents",
            List::class.java,
            Model::class.java,
        )
        method.isAccessible = true
        return method.invoke(
            GoogleProvider(OkHttpClient()),
            messages,
            Model(inputModalities = listOf(Modality.TEXT, Modality.IMAGE)),
        ) as JsonArray
    }

    @Test
    fun `tool result messages should not upload blank text parts`() {
        val contents = buildContents(
            listOf(
                UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Run tool"))),
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.ToolCall(
                            toolCallId = "call_1",
                            toolName = "lookup",
                            arguments = "{}",
                        )
                    ),
                ),
                UIMessage(
                    role = MessageRole.TOOL,
                    parts = listOf(
                        UIMessagePart.ToolResult(
                            toolCallId = "call_1",
                            toolName = "lookup",
                            content = JsonPrimitive("ok"),
                            arguments = JsonObject(emptyMap()),
                        ),
                        UIMessagePart.Text(""),
                    ),
                ),
            )
        )

        val toolResultParts = contents[2].jsonObject["parts"]!!.jsonArray
        assertEquals(1, toolResultParts.size)
        assertNotNull(toolResultParts[0].jsonObject["functionResponse"])
        assertNull(toolResultParts[0].jsonObject["text"])
    }

    @Test
    fun `blank-only messages should be omitted from google contents`() {
        val contents = buildContents(
            listOf(
                UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("   "))),
            )
        )

        assertEquals(0, contents.size)
    }

    @Test
    fun `signature-only stream parts preserve metadata and let later parts parse`() {
        val signaturePart = parseMessagePart(
            buildJsonObject { put("thoughtSignature", "opaque-signature") },
        ) as? UIMessagePart.Text
            ?: error("signature-only part should be represented as text")

        assertEquals("", signaturePart.text)
        assertEquals(
            "opaque-signature",
            signaturePart.metadataAs<GoogleThoughtMetadata>()?.thoughtSignature,
        )
        val snakeCaseSignaturePart = parseMessagePart(
            buildJsonObject { put("thought_signature", "snake-case-signature") },
        ) as? UIMessagePart.Text
            ?: error("snake case signature-only part should be represented as text")
        assertEquals(
            "snake-case-signature",
            snakeCaseSignaturePart.metadataAs<GoogleThoughtMetadata>()?.thoughtSignature,
        )
        assertEquals(
            "continued text",
            (parseMessagePart(buildJsonObject { put("text", "continued text") }) as UIMessagePart.Text).text,
        )
        assertNull(parseMessagePart(buildJsonObject { put("executableCode", "ignored") }))
    }

    @Test
    fun `signature-only parts are returned to Gemini unchanged`() {
        val contents = buildContents(
            listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Text(
                            text = "",
                            metadata = GoogleThoughtMetadata("opaque-signature").toMetadata(),
                        ),
                    ),
                ),
            ),
        )

        val part = contents.single().jsonObject["parts"]!!.jsonArray.single().jsonObject
        assertEquals("opaque-signature", part["thoughtSignature"]?.jsonPrimitive?.content)
        assertFalse(part.containsKey("text"))
    }

    @Test
    fun `stop frames keep usage and finish reason without an empty delta`() {
        val chunk = parseStreamMessageChunk(
            buildJsonObject {
                putJsonArray("candidates") {
                    add(buildJsonObject {
                        put("index", 0)
                        put("finishReason", "STOP")
                    })
                    add(buildJsonObject {
                        put("index", 1)
                        put("content", buildJsonObject {
                            putJsonArray("parts") { }
                        })
                    })
                }
                put("usageMetadata", buildJsonObject {
                    put("promptTokenCount", 3)
                    put("candidatesTokenCount", 5)
                    put("totalTokenCount", 8)
                })
            },
        ) ?: error("stop frame should produce a usage-only chunk")

        assertTrue(chunk.choices.isEmpty())
        assertEquals(setOf("STOP"), chunk.finishReasons)
        assertEquals(3, chunk.usage?.promptTokens)
        assertEquals(5, chunk.usage?.completionTokens)
        assertEquals("raw-sse-event", chunk.rawResponse)
    }

    @Test
    fun `image parts should use gemini camel case inline data fields`() {
        val contents = buildContents(
            listOf(
                UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Image("data:image/png;base64,AAAA")),
                ),
            )
        )

        val imagePart = contents[0].jsonObject["parts"]!!.jsonArray[0].jsonObject
        val inlineData = imagePart["inlineData"]!!.jsonObject
        assertNotNull(inlineData["mimeType"])
        assertEquals("AAAA", inlineData["data"]!!.jsonPrimitive.content)
        assertFalse(imagePart.containsKey("inline_data"))
        assertFalse(inlineData.containsKey("mime_type"))
    }

    @Test
    fun `tool result image follows its function response with actual media type`() {
        val imageFile = File.createTempFile("tool-result", ".webp").apply {
            writeBytes("RIFFxxxxWEBP".toByteArray())
            deleteOnExit()
        }
        val contents = buildContents(
            listOf(
                UIMessage(
                    role = MessageRole.TOOL,
                    parts = listOf(
                        UIMessagePart.ToolResult(
                            toolCallId = "call_1",
                            toolName = "sandbox_read_file",
                            content = JsonPrimitive("ok"),
                            arguments = JsonObject(emptyMap()),
                            images = listOf(
                                ToolResultImage(
                                    url = imageFile.toURI().toString(),
                                    mimeType = "image/webp",
                                    fileName = "diagram.webp",
                                )
                            ),
                        )
                    ),
                ),
            )
        )

        val parts = contents.single().jsonObject["parts"]?.jsonArray ?: error("missing tool parts")
        assertNotNull(parts[0].jsonObject["functionResponse"])
        val inlineData = parts[1].jsonObject["inlineData"]?.jsonObject ?: error("missing inline data")
        assertEquals("image/webp", inlineData["mimeType"]?.jsonPrimitive?.content)
        assertEquals("UklGRnh4eHhXRUJQ", inlineData["data"]?.jsonPrimitive?.content)
    }
}
