package me.rerere.rikkahub.data.localai

import com.google.ai.edge.litertlm.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException

class LiteRtModelInputTest {
    @Test
    fun `validates both formats and rejects renamed or truncated models`() {
        validateLocalModelHeader(ByteArrayInputStream("LITERTLM\u0000payload".toByteArray()), LocalModelFormat.LITERT_LM)
        validateLocalModelHeader(ByteArrayInputStream("GGUFpayload".toByteArray()), LocalModelFormat.GGUF)
        assertThrows(IllegalArgumentException::class.java) {
            validateLocalModelHeader(ByteArrayInputStream("GGUFpayload".toByteArray()), LocalModelFormat.LITERT_LM)
        }
        assertThrows(EOFException::class.java) {
            validateLocalModelHeader(ByteArrayInputStream("LITER".toByteArray()), LocalModelFormat.LITERT_LM)
        }
    }

    @Test
    fun `header validation handles short stream reads`() {
        val stream = object : ByteArrayInputStream("LITERTLMpayload".toByteArray()) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = super.read(bytes, offset, minOf(length, 1))
        }
        validateLocalModelHeader(stream, LocalModelFormat.LITERT_LM)
    }

    @Test
    fun `history preserves order and maps assistant to model without repeating last message`() {
        val history = listOf(
            LocalInferenceMessage("system", "用中文回答"),
            LocalInferenceMessage("user", "第一问"),
            LocalInferenceMessage("assistant", "第一答"),
            LocalInferenceMessage("user", "修改后的第二问"),
        )
        val turns = liteRtMessages(history)
        assertEquals(listOf(Role.SYSTEM, Role.USER, Role.MODEL, Role.USER), turns.map { it.role })
        assertEquals(history.map { it.content }, turns.map { it.toString() })
        assertEquals("修改后的第二问", turns.last().toString())
        assertEquals(3, turns.dropLast(1).size)
    }
}
