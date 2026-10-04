package me.rerere.rikkahub.data.interactive

import kotlinx.serialization.json.*
import me.rerere.rikkahub.ui.components.interactive.interactiveCatalog
import me.rerere.rikkahub.ui.components.interactive.interactiveMessageValidator
import me.rerere.rikkahub.ui.components.interactive.interactiveScriptCatalog
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class InteractiveMessageIsolationTest {
    private val create = """{"version":"v0.9.1","createSurface":{"surfaceId":"card","catalogId":"catalog"}}"""
    private fun batch(vararg components: String) =
        """{"version":"v0.9.1","updateComponents":{"surfaceId":"card","components":[${components.joinToString(",")}]}}"""
    private fun text(id: String, value: String) = """{"id":"$id","component":"Text","text":"$value"}"""
    private val initial = batch(text("root", "Initial"))
    private val following = batch(text("root", "Following"))

    @Test fun `bad JSON line does not stop subsequent components or data messages`() {
        val data = """{"version":"v0.9.1","updateDataModel":{"surfaceId":"card","value":{"name":"Alice"}}}"""
        val code = "$create\n$initial\n{bad}\n$following\n$data"
        val decoder = InteractiveDocument("catalog")
        assertEquals(listOf(create, initial, following, data), decoder.consume(code, true))
        assertEquals(JsonPrimitive("Following"), decoder.componentSnapshot.getValue("root")["text"])
        assertTrue(decoder.hasIncompleteContent)
        assertTrue(decoder.consume(code, true).isEmpty())
    }

    @Test fun `bad line does not block later messages during streaming or reset the consumed prefix`() {
        val decoder = InteractiveDocument("catalog")
        val early = "$create\n$initial\n{bad}\n"
        assertEquals(listOf(create, initial), decoder.consume(early, false))
        val later = "$early$following\n"
        assertFalse(decoder.hasChangedPrefix(later))
        assertEquals(listOf(following), decoder.consume(later, false))
        assertTrue(decoder.consume(later, true).isEmpty())
        assertTrue(decoder.hasIncompleteContent)
    }

    @Test fun `a repaired line can be followed by ordinary messages while streaming`() {
        val decoder = InteractiveDocument("catalog")
        val code = "$create\n${initial.dropLast(1)}\n$following\n"
        assertEquals(listOf(create, initial, following), decoder.consume(code, false))
        assertFalse(decoder.hasIncompleteContent)
        assertTrue(decoder.consume(code, true).isEmpty())
    }

    @Test fun `invalid component batches leave neither partial updates nor added components`() {
        val decoder = InteractiveDocument("catalog")
        decoder.consume("$create\n$initial\n", false)
        val invalid = batch(text("root", "Changed"), text("extra", "Extra"), """{"id":"bad","component":"Unknown"}""")
        assertTrue(decoder.consume("$create\n$initial\n$invalid", true).isEmpty())
        assertEquals(mapOf("root" to JsonInstant.parseToJsonElement(text("root", "Initial"))), decoder.componentSnapshot)
        assertTrue(decoder.hasIncompleteContent)
    }

    @Test fun `cyclic candidate graph is rejected without replacing a valid root`() {
        val cyclic = batch("""{"id":"root","component":"Column","children":["loop"]}""",
            """{"id":"loop","component":"Card","child":"root"}""")
        val decoder = InteractiveDocument("catalog")
        assertEquals(listOf(create, initial, following), decoder.consume("$create\n$initial\n$cyclic\n$following", true))
        assertFalse("loop" in decoder.componentSnapshot)
        assertTrue(decoder.hasIncompleteContent)
    }

    @Test fun `overwrites wait for the full streaming batch and stay unchanged if it fails`() {
        val base = "$create\n$initial\n"
        val update = batch(text("root", "Changed"), """{"id":"bad","component":"Unknown"}""")
        val early = base + update.substringBefore(",{\"id\":\"bad\"") + ","
        val decoder = InteractiveDocument("catalog")
        decoder.consume(base, false)
        assertTrue(decoder.consume(early, false).isEmpty())
        assertEquals(JsonPrimitive("Initial"), decoder.componentSnapshot.getValue("root")["text"])
        assertEquals(listOf(following), decoder.consume("$base$update\n$following", true))
        assertEquals(JsonPrimitive("Following"), decoder.componentSnapshot.getValue("root")["text"])
        assertFalse("bad" in decoder.componentSnapshot)
    }

    @Test fun `new previews and deferred overwrites each reach the renderer once`() {
        val base = "$create\n$initial\n"
        val update = batch(text("root", "Changed"), text("extra", "Extra"))
        val decoder = InteractiveDocument("catalog")
        decoder.consume(base, false)
        val previews = decoder.consume(base + update.dropLast(3), false)
        assertEquals(1, previews.size)
        assertEquals(JsonPrimitive("Initial"), decoder.componentSnapshot.getValue("root")["text"])
        assertTrue("extra" in decoder.componentSnapshot)
        assertEquals(listOf(batch(text("root", "Changed"))), decoder.consume(base + update, true))
        assertTrue(decoder.consume(base + update, true).isEmpty())
    }

    @Test fun `failed creation does not activate scripts or accept dependent messages`() {
        val invalidCreate = """{"version":"v0.9.1","createSurface":{"surfaceId":"card","catalogId":"flit:interactive/v1","flitRuntime":{"version":2,"code":"({})"}}}"""
        val decoder = InteractiveDocument("catalog")
        assertEquals(listOf(create, following), decoder.consume("$invalidCreate\n$initial\n$create\n$following", true))
        assertNull(decoder.runtime)
        assertFalse(decoder.usesFlitCatalog)
        assertTrue(decoder.hasIncompleteContent)
    }

    @Test fun `failed data and lifecycle messages do not block a later valid update`() {
        val badData = """{"version":"v0.9.1","updateDataModel":{"surfaceId":"card","value":"invalid root"}}"""
        val wrongSurface = """{"version":"v0.9.1","deleteSurface":{"surfaceId":"other"}}"""
        val decoder = InteractiveDocument("catalog")
        assertEquals(listOf(create, initial, following), decoder.consume("$create\n$initial\n$badData\n$wrongSurface\n$following", true))
        assertFalse(decoder.deleted)
        assertTrue(decoder.hasIncompleteContent)
    }

    @Test fun `native catalog rejection is isolated before the candidate state is committed`() {
        val validator = interactiveMessageValidator(interactiveScriptCatalog(interactiveCatalog {}))
        val invalid = batch("""{"id":"root","component":"Text","text":[]}""")
        val decoder = InteractiveDocument("catalog", validator)
        assertEquals(listOf(create, initial, following), decoder.consume("$create\n$initial\n$invalid\n$following", true))
        assertEquals(JsonPrimitive("Following"), decoder.componentSnapshot.getValue("root")["text"])
        assertTrue(decoder.hasIncompleteContent)
    }

    @Test fun `native preflight keeps all packaged form and script examples valid`() {
        val validator = interactiveMessageValidator(interactiveScriptCatalog(interactiveCatalog {}))
        listOf("SKILL.md", "references/script-examples.md").forEach { path ->
            val source = File("src/main/assets/builtin-skills/interactive-components/$path").readText()
            interactiveFences(source).forEach { fence ->
                val decoder = InteractiveDocument(FLIT_INTERACTIVE_CATALOG, validator)
                decoder.consume(fence.code, true)
                assertFalse("Rejected example in $path", decoder.hasIncompleteContent)
            }
        }
    }
}
