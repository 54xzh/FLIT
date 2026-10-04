package me.rerere.rikkahub.interactive

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.interactive.*
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test

class InteractiveScriptServiceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun input() = InteractiveScriptInput(buildJsonObject { put("result", 0) }, JsonObject(emptyMap()))
    private suspend fun valid(runner: InteractiveScriptRunner): String = runner.execute(
        InteractiveRuntime("({calculate(){return [{path:'/result',value:42}]}})", emptyList()), "calculate", ::input,
    ).result

    @Test fun packagedToolsExecuteInTheIsolatedService() = runBlocking {
        val runner = InteractiveScriptRunner(context)
        val examples = listOf("SKILL.md", "references/script-examples.md").flatMap { path ->
            val source = context.assets.open("builtin-skills/interactive-components/$path").bufferedReader().use { it.readText() }
            interactiveFences(source)
        }
        val testedSurfaces = mutableSetOf<String>()
        examples.forEach { fence ->
            val decoder = InteractiveDocument(FLIT_INTERACTIVE_CATALOG)
            decoder.consume(fence.code, true)
            assertFalse(decoder.hasIncompleteContent)
            val runtime = decoder.runtime ?: return@forEach
            val surfaceId = requireNotNull(decoder.surfaceId)
            val original = fence.code.lines().map { JsonInstant.parseToJsonElement(it).jsonObject }
                .firstNotNullOf { (it["updateDataModel"] as? JsonObject)?.get("value") as? JsonObject }
            val model = when (surfaceId) {
                "budget-tool" -> JsonObject(original + ("expenses" to JsonPrimitive(4500)))
                "filter-tool" -> JsonObject(original + ("query" to JsonPrimitive("app")))
                "quiz-tool" -> JsonObject(original + ("answer" to JsonArray(listOf(JsonPrimitive("4")))))
                else -> error("Unexpected script example: $surfaceId")
            }
            val automatic = surfaceId != "quiz-tool"
            val handler = if (automatic) runtime.watch.single().handler else "score"
            val result = runner.execute(runtime, handler) { InteractiveScriptInput(model, JsonObject(emptyMap())) }
            val updated = applyInteractiveScriptResult(model, result.result, runtime, automatic)
            when (surfaceId) {
                "budget-tool" -> assertEquals(500.0, updated.getValue("remaining").jsonPrimitive.double, 0.0)
                "filter-tool" -> assertEquals(1, updated.getValue("filtered").jsonArray.size)
                "quiz-tool" -> assertEquals(1, updated.getValue("score").jsonPrimitive.int)
            }
            testedSurfaces += surfaceId
        }
        assertEquals(setOf("budget-tool", "filter-tool", "quiz-tool"), testedSurfaces)
    }

    @Test fun infiniteLoopsStackOverflowMemoryErrorsAndInvalidResultsPermitRecovery() = runBlocking {
        val runner = InteractiveScriptRunner(context)
        val scripts = listOf(
            "({calculate(){while(true){}}})",
            "({calculate(){function recurse(){return recurse()}return recurse()}})",
            "({calculate(){const list=[];while(true){list.push(new Array(10000).fill('large'))}}})",
            "({async calculate(){return []}})",
            "({calculate(){throw Error('failure')}})",
            "({calculate(){return [{path:'/result',value:Infinity}]}})",
        )
        scripts.forEach { code ->
            val failure = runCatching {
                withTimeout(8_000) { runner.execute(InteractiveRuntime(code, emptyList()), "calculate", ::input) }
            }.exceptionOrNull()
            assertNotNull("Script must fail: $code", failure)
            assertFalse("Runner must complete or report its own timeout", failure is TimeoutCancellationException)
            assertTrue(valid(runner).contains("42"))
        }
    }

    @Test fun runtimeProvidesNoNetworkFileOrAndroidBridgeAndInputsAreData() = runBlocking {
        val runner = InteractiveScriptRunner(context)
        val runtime = InteractiveRuntime("""({calculate({args}){return [{path:'/result',value:[typeof fetch,typeof require,typeof Android,args.text].join('|')}]}})""", emptyList())
        val text = "'); throw Error('injected'); ('"
        val result = runner.execute(runtime, "calculate") {
            InteractiveScriptInput(input().model, buildJsonObject { put("text", text) })
        }
        val updated = applyInteractiveScriptResult(input().model, result.result, runtime, false)
        assertEquals("undefined|undefined|undefined|$text", updated["result"]?.jsonPrimitive?.content)
    }

    @Test fun cancellationStopsExecutionBeforeTheNextCardRuns() = runBlocking {
        val runner = InteractiveScriptRunner(context)
        // 先预热服务，确保取消发生在已开始的执行上。
        valid(runner)
        val job = launch { runner.execute(InteractiveRuntime("({calculate(){while(true){}}})", emptyList()), "calculate", ::input) }
        delay(100)
        job.cancelAndJoin()
        assertTrue(valid(runner).contains("42"))
    }
}
