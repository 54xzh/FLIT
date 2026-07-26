package me.rerere.rikkahub.ui.components.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningPreviewTailTest {
    @Test
    fun `short reasoning returns itself`() {
        val text = "简短的思考内容"
        assertEquals(text, text.reasoningPreviewTail())
    }

    @Test
    fun `reasoning within threshold returns itself`() {
        val text = "字".repeat(2500)
        assertEquals(text, text.reasoningPreviewTail())
    }

    @Test
    fun `long reasoning keeps only tail`() {
        val head = "开头内容。".repeat(1000)
        val tailMarker = "结尾的关键内容"
        val text = head + "\n" + "中间内容。".repeat(200) + "\n" + tailMarker
        val tail = text.reasoningPreviewTail()

        assertTrue(tail.length < text.length)
        assertTrue(tail.endsWith(tailMarker))
    }

    @Test
    fun `cut point stays stable while content grows within one step`() {
        // 同一档位内追加内容时，裁剪点保持不动，尾部只是纯追加，
        // 这样预览高度持续增长、自动跟底动画才平滑
        val base = "x".repeat(2600)
        val grown = base + "yy"

        assertEquals(base.reasoningPreviewTail() + "yy", grown.reasoningPreviewTail())
    }

    @Test
    fun `cut aligns to next line start when newline is nearby`() {
        val line = "a".repeat(100)
        val text = (line + "\n").repeat(50)
        val tail = text.reasoningPreviewTail()

        // 裁剪点对齐到换行后，尾部应从完整一行开头开始
        assertTrue(tail.startsWith(line))
        assertEquals(0, tail.length % (line.length + 1))
    }

    @Test
    fun `cut does not split surrogate pair`() {
        // 前置 1 个字符使裁剪点（1000 的整数倍）落在 emoji 代理对中间
        val text = "a" + "😀".repeat(1300)
        val tail = text.reasoningPreviewTail()

        assertFalse(tail.first().isLowSurrogate())
        assertTrue(tail.first().isHighSurrogate())
    }

    @Test
    fun `reopens code fence when cut lands inside code block`() {
        val text = "前置说明\n```kotlin\n" + "val x = 1\n".repeat(300)
        val tail = text.reasoningPreviewTail()

        assertTrue(tail.startsWith("```\n"))
    }

    @Test
    fun `does not reopen fence when code blocks are balanced`() {
        val text = "```kotlin\nval x = 1\n```\n" + "后续大量普通文字。".repeat(300)
        val tail = text.reasoningPreviewTail()

        assertFalse(tail.startsWith("```"))
    }
}
