package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ImageGenerationToolsTest {
    @Test
    fun `parse request maps aspect ratio aliases and clamps count`() {
        val landscape = parseImageGenerationToolRequest(
            buildJsonObject {
                put("prompt", "A mountain lake")
                put("aspect_ratio", "wide")
                put("count", 9)
            }
        )
        val portrait = parseImageGenerationToolRequest(
            buildJsonObject {
                put("prompt", "A tall lighthouse")
                put("aspect_ratio", "tall")
                put("count", 0)
            }
        )
        val fallback = parseImageGenerationToolRequest(
            buildJsonObject {
                put("prompt", "A sketch")
                put("aspect_ratio", "unknown")
            }
        )
        val directLandscape = parseImageGenerationToolRequest(
            buildJsonObject {
                put("prompt", "A city skyline")
                put("aspect_ratio", "16:9")
            }
        )
        val directPortrait = parseImageGenerationToolRequest(
            buildJsonObject {
                put("prompt", "A portrait")
                put("aspect_ratio", "9:16")
            }
        )

        assertEquals("16:9", landscape?.aspectRatio)
        assertEquals(4, landscape?.count)
        assertEquals("9:16", portrait?.aspectRatio)
        assertEquals(1, portrait?.count)
        assertEquals("16:9", directLandscape?.aspectRatio)
        assertEquals("9:16", directPortrait?.aspectRatio)
        assertEquals("1:1", fallback?.aspectRatio)
        assertEquals(1, fallback?.count)
    }

    @Test
    fun `parse request rejects blank prompt`() {
        assertNull(parseImageGenerationToolRequest(buildJsonObject { put("prompt", "  ") }))
    }

    @Test
    fun `selection reports missing image model`() {
        val selection = resolveImageGenerationToolSelection(
            Settings(imageGenerationModelId = Uuid.parse("10000000-0000-0000-0000-000000000001"))
        )

        assertTrue(selection is ImageGenerationToolSelection.Error)
        assertTrue((selection as ImageGenerationToolSelection.Error).message.contains("No image generation model"))
    }

    @Test
    fun `selection resolves selected model provider`() {
        val modelId = Uuid.parse("10000000-0000-0000-0000-000000000002")
        val provider = ProviderSetting.OpenAI(
            id = Uuid.parse("10000000-0000-0000-0000-000000000003"),
            models = listOf(Model(id = modelId, modelId = "image-test")),
        )

        val selection = resolveImageGenerationToolSelection(
            Settings(
                imageGenerationModelId = modelId,
                providers = listOf(provider),
            )
        )

        assertTrue(selection is ImageGenerationToolSelection.Ready)
        assertEquals(provider, (selection as ImageGenerationToolSelection.Ready).provider)
    }

    @Test
    fun `tool result exposes markdown image and clear failures`() {
        val success = buildImageGenerationToolSuccess(
            listOf(
                SavedGeneratedToolImage(
                    uri = "file:///data/user/0/test/files/images/image.png",
                    path = "/data/user/0/test/files/images/image.png",
                    markdownImage = "![Generated image](file:///data/user/0/test/files/images/image.png)",
                )
            )
        )
        val failure = buildImageGenerationToolError("prompt is required")

        assertTrue(success["success"]?.jsonPrimitive?.booleanOrNull == true)
        assertEquals(1, success["images"]?.jsonArray?.size)
        assertEquals("prompt is required", failure["error"]?.jsonPrimitive?.content)
        assertFalse(failure["success"]?.jsonPrimitive?.booleanOrNull == true)
    }

    @Test
    fun `image generation option serializes with stable type`() {
        val assistant = Assistant(localTools = listOf(LocalToolOption.ImageGeneration))

        val encoded = JsonInstant.encodeToString(assistant)
        val decoded = JsonInstant.decodeFromString<Assistant>(encoded)

        assertTrue(encoded.contains("\"image_generation\""))
        assertEquals(listOf(LocalToolOption.ImageGeneration), decoded.localTools)
    }
}
