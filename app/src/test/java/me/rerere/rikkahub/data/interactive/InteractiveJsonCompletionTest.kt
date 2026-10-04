package me.rerere.rikkahub.data.interactive

import org.junit.Assert.*
import org.junit.Test

class InteractiveJsonCompletionTest {
    @Test fun `only missing closing brackets are appended in nesting order`() {
        assertEquals("""{"items":[{"value":1}]}""", completeInteractiveJson("""{"items":[{"value":1}"""))
        assertEquals("""{"items":[]}""", completeInteractiveJson("""{"items":[]"""))
    }

    @Test fun `brackets and escaped quotes inside strings do not change nesting`() {
        val input = """{"code":"({run(){return \"[}]\";}})","items":[]"""
        assertEquals("$input}", completeInteractiveJson(input))
    }

    @Test fun `truncated content and mismatched brackets are never guessed`() {
        listOf(
            """{"text":"unfinished""",
            """{"value":tru""",
            """{"value":1e""",
            """{"value":""",
            """{"value":1,""",
            """{"items":[}""",
            """{"value":1}}""",
            """{"value":1}""",
        ).forEach { assertNull(it, completeInteractiveJson(it)) }
    }
}
