package me.rerere.ai.provider.providers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Modality
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolResultImage
import okhttp3.OkHttpClient
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GoogleProviderTest {
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
