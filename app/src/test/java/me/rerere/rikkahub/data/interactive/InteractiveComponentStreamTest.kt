package me.rerere.rikkahub.data.interactive

import kotlinx.serialization.json.*
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test

class InteractiveComponentStreamTest {
    private val create = """{"version":"v0.9.1","createSurface":{"surfaceId":"card","catalogId":"catalog"}}"""
    private val root = """{"id":"root","component":"Column","children":["one","two"]}"""
    private val first = """{"id":"one","component":"Text","text":"First"}"""
    private val second = """{"id":"two","component":"Text","text":"Second"}"""
    private val header = """{"version":"v0.9.1","updateComponents":{"surfaceId":"card","components":["""
    private val code = "$create\n$header$root,$first,$second]}}"

    private fun ids(messages: List<String>): List<String> = messages.flatMap {
        val envelope = JsonInstant.parseToJsonElement(it).jsonObject
        envelope["updateComponents"]?.jsonObject?.get("components")?.jsonArray.orEmpty()
            .map { item -> item.jsonObject.getValue("id").jsonPrimitive.content }
    }

    @Test fun `closed components appear before the enclosing batch finishes`() {
        val decoder = InteractiveDocument("catalog")
        val early = "$create\n$header$root,$first,{\"id\":\"two\""
        assertEquals(listOf("root", "one"), ids(decoder.consume(early, false)))
        assertTrue(decoder.consume(early, false).isEmpty())
        assertEquals(listOf("two"), ids(decoder.consume(code, true)))
        assertEquals(3, decoder.componentSnapshot.size)
        assertTrue(decoder.consume(code, true).isEmpty())
    }

    @Test fun `character streaming and final newline do not apply previews twice`() {
        val decoder = InteractiveDocument("catalog")
        val messages = mutableListOf<String>()
        code.indices.forEach { messages += decoder.consume(code.take(it + 1), false) }
        assertEquals(listOf("root", "one", "two"), ids(messages))
        assertTrue(decoder.consume("$code\n", true).isEmpty())
    }

    @Test fun `edits to a previewed component invalidate the processor prefix`() {
        val decoder = InteractiveDocument("catalog")
        decoder.consume("$create\n$header$root,$first,", false)
        assertTrue(decoder.hasChangedPrefix(code.replace("First", "Edited")))
    }

    @Test fun `quoted braces escaped quotes and nested bindings remain intact`() {
        val text = "Contains } { [ ] and \"quoted\" text"
        val component = buildJsonObject {
            put("id", "root"); put("component", "Text"); put("text", text)
            put("accessibility", buildJsonObject { put("label", "A { label }") })
        }
        val decoder = InteractiveDocument("catalog")
        val stream = "$create\n$header$component]}}"
        val messages = mutableListOf<String>()
        stream.indices.forEach { messages += decoder.consume(stream.take(it + 1), false) }
        assertEquals(listOf("root"), ids(messages))
        assertEquals(JsonPrimitive(text), decoder.componentSnapshot.getValue("root")["text"])
    }

    @Test fun `unknown components and malformed completed messages fail locally`() {
        assertThrows(IllegalArgumentException::class.java) {
            InteractiveDocument("catalog").consume("$create\n$header${first.replace("Text", "Unknown")},", false)
        }
        val decoder = InteractiveDocument("catalog")
        decoder.consume("$create\n$header$root,$first,", false)
        assertThrows(Exception::class.java) { decoder.consume("$create\n$header$root,$first,broken", true) }
        assertEquals(listOf("root", "one"), decoder.componentSnapshot.keys.toList())
    }

    @Test fun `later batches can update an earlier component exactly once`() {
        val decoder = InteractiveDocument("catalog")
        decoder.consume("$code\n", false)
        val update = "$header${first.replace("First", "Updated")}]}}"
        val messages = mutableListOf<String>()
        val next = "$code\n$update"
        for (length in code.length + 1..next.length) messages += decoder.consume(next.take(length), false)
        assertEquals(listOf("one"), ids(messages))
        assertTrue(decoder.consume(next, true).isEmpty())
        assertEquals(JsonPrimitive("Updated"), decoder.componentSnapshot.getValue("one")["text"])
    }

    @Test fun `arrays outside updateComponents are never interpreted as components`() {
        val decoder = InteractiveDocument("catalog")
        val data = """{"version":"v0.9.1","updateDataModel":{"surfaceId":"card","value":{"components":[{"id":"root","component":"Text"}]}}}"""
        assertEquals(listOf(create), decoder.consume("$create\n$data", false))
        assertTrue(decoder.componentSnapshot.isEmpty())
    }
}
