package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.ToolResultImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceToolImageTest {
    @Test
    fun `detects all supported image headers without relying on filename`() {
        assertEquals("image/jpeg", detectWorkspaceImageType(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))?.mimeType)
        assertEquals(
            "image/png",
            detectWorkspaceImageType(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))?.mimeType,
        )
        assertEquals("image/gif", detectWorkspaceImageType("GIF89a".toByteArray())?.mimeType)
        assertEquals(
            "image/webp",
            detectWorkspaceImageType("RIFFxxxxWEBP".toByteArray())?.mimeType,
        )
    }

    @Test
    fun `invalid header and forged image extension are not treated as images`() {
        assertNull(detectWorkspaceImageType("not an image".toByteArray()))
        assertTrue(isWorkspaceImageFileName("forged.PNG"))
        assertFalse(isWorkspaceImageFileName("no-extension"))
    }

    @Test
    fun `image attachment stays outside the visible tool result json`() {
        val result = workspaceToolImageError("diagram.png", "image_too_large", "too large")
            .withToolResultImages(
                listOf(
                    ToolResultImage(
                        url = "file:///chat_uploads/1/diagram.png",
                        mimeType = "image/png",
                        fileName = "diagram.png",
                    )
                )
            )

        val images = result[TOOL_RESULT_IMAGES_KEY]!!.jsonArray
        assertEquals(1, images.size)
        assertEquals("image/png", images.single().jsonObject["mimeType"]!!.jsonPrimitive.content)
        assertEquals("image_too_large", result["error_code"]!!.jsonPrimitive.content)
    }
}
