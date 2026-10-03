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
        val examples = context.assets.open("builtin-skills/interactive-components/references/script-examples.md").bufferedReader().use { it.readText() }
        interactiveFences(examples).forEachIndexed { index, fence ->
            val decoder = InteractiveDocument("old")
            decoder.consume(fence.code, true)
            val runtime = requireNotNull(decoder.runtime)
            val original = fence.code.lines().map { JsonInstant.parseToJsonElement(it).jsonObject }
                .firstNotNullOf { (it["updateDataModel"] as? JsonObject)?.get("value") as? JsonObject }
            val model = when (index) {
                0 -> JsonObject(original + ("expenses" to JsonPrimitive(4500)))
                1 -> JsonObject(original + ("query" to JsonPrimitive("app")))
                else -> JsonObject(original + ("answer" to JsonArray(listOf(JsonPrimitive("4")))))
            }
            val handler = if (index == 2) "score" else runtime.watch.single().handler
            val result = runner.execute(runtime, handler) { InteractiveScriptInput(model, JsonObject(emptyMap())) }
            val updated = applyInteractiveScriptResult(model, result.result, runtime, index != 2)
            when (index) {
                0 -> assertEquals(500.0, updated.getValue("remaining").jsonPrimitive.double, 0.0)
                1 -> assertEquals(1, updated.getValue("filtered").jsonArray.size)
                else -> assertEquals(1, updated.getValue("score").jsonPrimitive.int)
            }
        }
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
