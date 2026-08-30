package me.rerere.ai.provider.providers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ClaudePromptCacheTtl
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Modality
import me.rerere.ai.ui.ToolResultImage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ClaudeProviderToolImageTest {
    @Test
    fun `tool result content contains text and matching image block`() {
        val imageFile = File.createTempFile("tool-result", ".gif").apply {
            writeBytes("GIF89a".toByteArray())
            deleteOnExit()
        }
        val method = ClaudeProvider::class.java.getDeclaredMethod(
            "buildMessages",
            List::class.java,
            Model::class.java,
            Boolean::class.javaPrimitiveType,
            ClaudePromptCacheTtl::class.java,
        ).apply { isAccessible = true }
        val messages = listOf(
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
                                mimeType = "image/gif",
                                fileName = "diagram.gif",
                            )
                        ),
                    )
                ),
            )
        )

        val requestMessages = method.invoke(
            ClaudeProvider(OkHttpClient()),
            messages,
            Model(inputModalities = listOf(Modality.TEXT, Modality.IMAGE)),
            false,
            ClaudePromptCacheTtl.FIVE_MINUTES,
        ) as JsonArray
        val toolResult = requestMessages.single().jsonObject["content"]?.jsonArray
            ?.single()?.jsonObject ?: error("missing tool result")
        val content = toolResult["content"]?.jsonArray ?: error("missing nested content")

        assertEquals("call_1", toolResult["tool_use_id"]?.jsonPrimitive?.content)
        assertEquals("text", content[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("image", content[1].jsonObject["type"]?.jsonPrimitive?.content)
        val source = content[1].jsonObject["source"]?.jsonObject ?: error("missing image source")
        assertEquals("image/gif", source["media_type"]?.jsonPrimitive?.content)
        assertTrue(source["data"]?.jsonPrimitive?.content.orEmpty().isNotBlank())
    }
}
