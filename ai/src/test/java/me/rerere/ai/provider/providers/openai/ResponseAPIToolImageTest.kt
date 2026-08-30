package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Modality
import me.rerere.ai.ui.ToolResultImage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ResponseAPIToolImageTest {
    @Test
    fun `function output keeps image beside matching call id`() {
        val imageFile = File.createTempFile("tool-result", ".png").apply {
            writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
            deleteOnExit()
        }
        val method = ResponseAPI::class.java.getDeclaredMethod(
            "buildMessages",
            List::class.java,
            Model::class.java,
        ).apply { isAccessible = true }
        val messages = listOf(
            UIMessage(
                role = MessageRole.TOOL,
                parts = listOf(
                    UIMessagePart.ToolResult(
                        toolCallId = "call_1",
                        toolName = "workspace_read_file",
                        content = JsonPrimitive("ok"),
                        arguments = JsonObject(emptyMap()),
                        images = listOf(
                            ToolResultImage(
                                url = imageFile.toURI().toString(),
                                mimeType = "image/png",
                                fileName = "diagram.png",
                            )
                        ),
                    )
                ),
            )
        )

        val input = method.invoke(
            ResponseAPI(OkHttpClient(), KeyRoulette.default()),
            messages,
            Model(inputModalities = listOf(Modality.TEXT, Modality.IMAGE)),
        ) as JsonArray
        val output = input.single().jsonObject

        assertEquals("call_1", output["call_id"]?.jsonPrimitive?.content)
        val parts = output["output"]?.jsonArray ?: error("missing function output")
        assertEquals("input_text", parts[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("input_image", parts[1].jsonObject["type"]?.jsonPrimitive?.content)
        assertTrue(parts[1].jsonObject["image_url"]?.jsonPrimitive?.content.orEmpty().startsWith("data:image/png;base64,"))
    }
}
