package me.rerere.rikkahub.data.interactive

import kotlinx.serialization.json.*
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class InteractiveDocumentTest {
    private val catalog = "catalog"
    private val create = """{"version":"v0.9.1","createSurface":{"surfaceId":"card","catalogId":"catalog"}}"""
    private val components = """{"version":"v0.9.1","updateComponents":{"surfaceId":"card","components":[{"id":"root","component":"Text","text":"Hello"}]}}"""
    private val complete = "$create\n$components"

    @Test fun `character streaming consumes complete messages exactly once including final line`() {
        val decoder = InteractiveDocument(catalog)
        val consumed = mutableListOf<String>()
        complete.indices.forEach { consumed += decoder.consume(complete.take(it + 1), false) }
        assertEquals(listOf(create, components), consumed)
        consumed += decoder.consume(complete, true)
        assertEquals(listOf(create, components), consumed)
        assertTrue(decoder.consume(complete, true).isEmpty())
    }

    @Test fun `separate cards do not share surface or consumption`() {
        val one = InteractiveDocument(catalog)
        val two = InteractiveDocument(catalog)
        assertEquals(2, one.consume(complete, true).size)
        assertEquals(2, two.consume(complete, true).size)
    }

    @Test fun `interrupted partial JSON remains buffered until finalization`() {
        val decoder = InteractiveDocument(catalog)
        assertEquals(listOf(create), decoder.consume("$create\n{\"version\":", false))
        assertThrows(Exception::class.java) { decoder.consume("$create\n{\"version\":", true) }
        assertEquals(2, InteractiveDocument(catalog).consume(complete, true).size)
    }

    @Test fun `unknown components invalid JSON and missing references stay within their document`() {
        assertThrows(IllegalArgumentException::class.java) { InteractiveDocument(catalog).consume(complete.replace("Text", "Unknown"), true) }
        assertThrows(Exception::class.java) { InteractiveDocument(catalog).consume("$create\n{bad}", true) }
        val missing = components.replace("\"component\":\"Text\",\"text\":\"Hello\"", "\"component\":\"Card\",\"child\":\"absent\"")
        assertThrows(IllegalArgumentException::class.java) { InteractiveDocument(catalog).consume("$create\n$missing", true) }
        assertEquals(2, InteractiveDocument(catalog).consume(complete, true).size)
    }

    @Test fun `version catalog lifecycle and size are checked`() {
        listOf(complete.replace("v0.9.1", "v0.8"), complete.replace("catalog", "other"), "$components\n$create", "$create\n$create\n$components")
            .forEach { input -> assertThrows(IllegalArgumentException::class.java) { InteractiveDocument(catalog).consume(input, true) } }
        assertThrows(IllegalArgumentException::class.java) { InteractiveDocument(catalog).consume("x".repeat(InteractiveDocument.MAX_CHARS + 1), false) }
    }

    @Test fun `cycles and repeated subtree explosion are bounded`() {
        val cyclic = components.replace("\"component\":\"Text\",\"text\":\"Hello\"", "\"component\":\"Card\",\"child\":\"root\"")
        assertThrows(IllegalArgumentException::class.java) { InteractiveDocument(catalog).consume("$create\n$cyclic", true) }
        val many = buildJsonObject {
            put("version", "v0.9.1")
            put("updateComponents", buildJsonObject { put("surfaceId", "card"); put("components", buildJsonArray {
                add(buildJsonObject { put("id", "root"); put("component", "Column"); put("children", JsonArray(List(201) { JsonPrimitive("leaf") })) })
                add(buildJsonObject { put("id", "leaf"); put("component", "Text"); put("text", "Leaf") })
            }) })
        }
        assertThrows(IllegalArgumentException::class.java) { InteractiveDocument(catalog).consume("$create\n$many", true) }
    }

    @Test fun `fences preserve order offsets indentation and ordinary code blocks`() {
        val text = "Before\n\n```kotlin\nval x = 1\n```\n\n```a2ui\n$complete\n```\n\nBetween\n\n```A2UI\n$complete\n```\nAfter"
        val fences = interactiveFences(text)
        assertEquals(2, fences.size)
        assertEquals(complete, fences.first().code)
        assertEquals(text.indexOf("```a2ui"), fences.first().offset)
        assertEquals(text.indexOf("```A2UI"), fences.last().offset)
        assertTrue(fences.all { it.closed })
        val transformed = transformAroundInteractiveFences(text) { it.uppercase() }
        assertEquals(fences.map { it.code }, interactiveFences(transformed).map { it.code })
        assertTrue(transformed.startsWith("BEFORE"))
    }

    @Test fun `edited descriptions invalidate saved fingerprint and prefixes`() {
        val decoder = InteractiveDocument(catalog)
        decoder.consume(complete, true)
        assertTrue(decoder.hasChangedPrefix(complete.replace("Hello", "Changed")))
        assertNotEquals(interactiveFingerprint(complete), interactiveFingerprint(complete.replace("Hello", "Changed")))
    }

    @Test fun `expanded templates use relative binding scopes and enforce total limit`() {
        val components = mapOf(
            "root" to JsonInstant.parseToJsonElement("""{"component":"Column","children":{"componentId":"item","path":"/items"}}""") as JsonObject,
            "item" to JsonInstant.parseToJsonElement("""{"component":"TextField","label":"Name","value":{"path":"name"}}""") as JsonObject,
        )
        val data = JsonInstant.parseToJsonElement("""{"items":[{"name":"A"},{"name":"B"}]}""")
        assertEquals(listOf("root" to "/", "item" to "/items/0", "item" to "/items/1"), interactiveInstances(components, data))
        assertEquals(JsonPrimitive("B"), interactiveValueAt(data, "/items/1/name"))
        val huge = buildJsonObject { put("items", JsonArray(List(200) { JsonObject(emptyMap()) })) }
        assertThrows(IllegalArgumentException::class.java) { interactiveInstances(components, huge) }
    }

    @Test fun `all packaged examples describe independent valid surfaces`() {
        listOf("SKILL.md", "references/examples.md").forEach { path ->
            val source = File("src/main/assets/builtin-skills/interactive-components/$path").readText()
            val examples = interactiveFences(source)
            assertTrue("Missing examples in $path", examples.isNotEmpty())
            examples.forEach { assertTrue(InteractiveDocument("<supported catalogId>").consume(it.code, true).size >= 3) }
        }
    }
    @Test fun `invalid layout weights fail locally before invoking Compose`() {
        val invalid = components.replace("\"text\":\"Hello\"", "\"text\":\"Hello\",\"weight\":-1")
        assertThrows(IllegalArgumentException::class.java) { InteractiveDocument(catalog).consume("$create\n$invalid", true) }
    }

}
