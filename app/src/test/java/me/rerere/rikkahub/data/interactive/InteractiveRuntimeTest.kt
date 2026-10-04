package me.rerere.rikkahub.data.interactive

import kotlinx.serialization.json.*
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class InteractiveRuntimeTest {
    private fun json(text: String) = JsonInstant.parseToJsonElement(text)
    private val model = json("""{"amount":2,"result":0,"nested":{"a/b":1},"items":[1,2]}""") as JsonObject
    private val runtime = InteractiveRuntime("({calculate(){return []}})", listOf(InteractiveWatch(listOf("/amount"), "calculate")))

    @Test fun `updates apply atomically with escaped paths and existing array elements`() {
        val updated = applyInteractiveScriptResult(model, """[{"path":"/result","value":4},{"path":"/nested/a~1b","value":3},{"path":"/items/0","value":8}]""", runtime, true)
        assertEquals(JsonPrimitive(4), updated["result"])
        assertEquals(JsonPrimitive(3), interactivePathValue(updated, "/nested/a~1b"))
        assertEquals(JsonPrimitive(8), interactivePathValue(updated, "/items/0"))
        assertEquals(JsonPrimitive(0), model["result"])
        assertThrows(Exception::class.java) {
            applyInteractiveScriptResult(model, """[{"path":"/result","value":4},{"path":"/missing","value":3}]""", runtime, false)
        }
        assertEquals(JsonPrimitive(0), model["result"])
    }

    @Test fun `automatic updates cannot touch watched inputs but buttons can`() {
        listOf("/amount", "/amount/child").forEach { path ->
            assertThrows(Exception::class.java) {
                applyInteractiveScriptResult(model, """[{"path":"$path","value":3}]""", runtime, true)
            }
        }
        val parentWatch = runtime.copy(watch = listOf(InteractiveWatch(listOf("/nested/a~1b"), "calculate")))
        assertThrows(Exception::class.java) { applyInteractiveScriptResult(model, """[{"path":"/nested","value":{}}]""", parentWatch, true) }
        assertEquals(JsonPrimitive(3), applyInteractiveScriptResult(model, """[{"path":"/amount","value":3}]""", runtime, false)["amount"])
    }

    @Test fun `invalid outputs and oversized data are rejected`() {
        listOf("{}", "null", """[{"path":"/","value":{}}]""", """[{"path":"/result"}]""",
            """[{"path":"/items/01","value":0}]""", """[{"path":"/__proto__","value":0}]""").forEach {
            assertThrows(Exception::class.java) { applyInteractiveScriptResult(model, it, runtime, false) }
        }
        val large = """[{"path":"/items","value":[${List(201) { "1" }.joinToString(",")}]}]"""
        assertThrows(Exception::class.java) { applyInteractiveScriptResult(model, large, runtime, false) }
        assertThrows(Exception::class.java) { applyInteractiveScriptResult(model, "[".repeat(1000), runtime, false) }
        assertThrows(Exception::class.java) { InteractiveRuntime.parse(json("""{"version":1,"code":"${"中".repeat(12000)}"}""")) }
        assertThrows(Exception::class.java) { InteractiveRuntime.parse(json("""{"version":2,"code":"({})"}""")) }
    }

    @Test fun `script extension is stripped and render-time script calls are rejected`() {
        val create = """{"version":"v0.9.1","createSurface":{"surfaceId":"tool","catalogId":"$FLIT_INTERACTIVE_CATALOG","flitRuntime":{"version":1,"code":"({calculate(){return []}})"}}}"""
        val button = """{"version":"v0.9.1","updateComponents":{"surfaceId":"tool","components":[{"id":"root","component":"Button","child":"label","action":{"functionCall":{"call":"runScript","args":{"handler":"calculate"}}}},{"id":"label","component":"Text","text":"Calculate"}]}}"""
        val decoder = InteractiveDocument("old")
        decoder.consume("$create\n$button", true)
        assertNotNull(decoder.runtime)
        assertFalse(interactiveRendererMessage(create).contains("flitRuntime"))
        val text = """{"version":"v0.9.1","updateComponents":{"surfaceId":"tool","components":[{"id":"root","component":"Text","text":{"call":"runScript","args":{"handler":"calculate"}}}]}}"""
        assertThrows(Exception::class.java) { InteractiveDocument("old").consume("$create\n$text", true) }
        assertThrows(Exception::class.java) { InteractiveDocument("old").consume("${create.replace(FLIT_INTERACTIVE_CATALOG, "old")}\n$button", true) }
    }

    @Test fun `packaged local tools validate independently`() {
        val tools = listOf("SKILL.md", "references/script-examples.md").flatMap { path ->
            interactiveFences(File("src/main/assets/builtin-skills/interactive-components/$path").readText())
        }.map { fence ->
            InteractiveDocument(FLIT_INTERACTIVE_CATALOG).also {
                it.consume(fence.code, true)
                assertFalse(it.hasIncompleteContent)
            }
        }.filter { it.runtime != null }
        assertEquals(setOf("budget-tool", "filter-tool", "quiz-tool"), tools.map { it.surfaceId }.toSet())
    }
}
