package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class TextFilePreviewTest {
    @Test
    fun `reader marks content beyond limit as truncated`() {
        val result = ByteArrayInputStream("abcdef".toByteArray())
            .readTextFilePreview(maxChars = 3)

        assertTrue(result is TextFilePreviewResult.Success)
        result as TextFilePreviewResult.Success
        assertEquals("abc", result.content)
        assertTrue(result.truncated)
        assertFalse(result.encodingSuspect)
    }

    @Test
    fun `reader rejects nul byte as binary`() {
        val result = ByteArrayInputStream(byteArrayOf('a'.code.toByte(), 0, 'b'.code.toByte()))
            .readTextFilePreview()

        assertEquals(TextFilePreviewResult.Binary, result)
    }
}
