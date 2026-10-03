package me.rerere.rikkahub.data.interactive

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class InteractiveRuntimeSessionTest {
    private val runtime = InteractiveRuntime("({})", listOf(InteractiveWatch(listOf("/input"), "calculate")))
    private fun model(value: Int) = buildJsonObject { put("input", value); put("result", 0) }

    @Test fun `rapid input changes debounce and preserve only latest input`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            var current = model(1)
            var runs = 0
            val done = CompletableDeferred<Unit>()
            val session = InteractiveRuntimeSession(runtime, scope, execute = { _, input ->
                runs++
                val snapshot = input()
                InteractiveScriptOutput(snapshot, """[{"path":"/result","value":${snapshot.model["input"]}}]""")
            }, model = { current }, apply = { current = it; done.complete(Unit) }, active = { true }, validate = {})
            session.scheduleWatch(0)
            delay(30)
            current = model(2)
            session.scheduleWatch(0)
            delay(30)
            current = model(3)
            session.scheduleWatch(0)
            assertTrue(session.status.value.busy)
            withTimeout(2000) { done.await() }
            assertEquals(1, runs)
            assertEquals(JsonPrimitive(3), current["result"])
        } finally { scope.cancel() }
    }

    @Test fun `stale automatic result is discarded and latest data is recomputed`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            var current = model(1)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val done = CompletableDeferred<Unit>()
            var runs = 0
            val applied = mutableListOf<Int>()
            val session = InteractiveRuntimeSession(runtime, scope, execute = { _, input ->
                val snapshot = input()
                if (++runs == 1) { started.complete(Unit); release.await() }
                InteractiveScriptOutput(snapshot, """[{"path":"/result","value":${snapshot.model["input"]}}]""")
            }, model = { current }, apply = { current = it; applied += it.getValue("result").jsonPrimitive.int; done.complete(Unit) }, active = { true }, validate = {})
            session.scheduleWatch(0, true)
            withTimeout(2000) { started.await() }
            current = model(2)
            release.complete(Unit)
            withTimeout(2000) { done.await() }
            assertEquals(listOf(2), applied)
            assertEquals(2, runs)
        } finally { scope.cancel() }
    }

    @Test fun `failed execution preserves data pauses watches and retries`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            var current = model(1)
            var runs = 0
            val session = InteractiveRuntimeSession(runtime, scope, execute = { _, input ->
                if (++runs == 1) throw InteractiveScriptException("PROCESS_EXITED")
                InteractiveScriptOutput(input(), """[{"path":"/result","value":9}]""")
            }, model = { current }, apply = { current = it }, active = { true }, validate = {})
            session.scheduleWatch(0, true)
            withTimeout(2000) { while (session.status.value.error == null) delay(5) }
            assertEquals(JsonPrimitive(0), current["result"])
            session.scheduleWatch(0, true)
            delay(30)
            assertEquals(1, runs)
            session.retry()
            withTimeout(2000) { while (current["result"] != JsonPrimitive(9)) delay(5) }
            assertNull(session.status.value.error)
        } finally { scope.cancel() }
    }

    @Test fun `duplicate taps are ignored and an inactive card never applies a late result`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var active = true
            var applied = false
            var runs = 0
            val session = InteractiveRuntimeSession(runtime.copy(watch = emptyList()), scope, execute = { _, input ->
                runs++
                val snapshot = input()
                started.complete(Unit)
                release.await()
                InteractiveScriptOutput(snapshot, """[{"path":"/result","value":9}]""")
            }, model = { model(1) }, apply = { applied = true }, active = { active }, validate = {})
            assertTrue(session.runButton("calculate", JsonObject(emptyMap())))
            assertFalse(session.runButton("calculate", JsonObject(emptyMap())))
            withTimeout(2000) { started.await() }
            active = false
            release.complete(Unit)
            withTimeout(2000) { while (session.status.value.busy) delay(5) }
            assertEquals(1, runs)
            assertFalse(applied)
        } finally { scope.cancel() }
    }

    @Test fun `submission checks actual inputs even before their observer runs`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            var current = model(1)
            val session = InteractiveRuntimeSession(runtime, scope, execute = { _, input ->
                InteractiveScriptOutput(input(), "[]")
            }, model = { current }, apply = { current = it }, active = { true }, validate = {})
            assertFalse(session.canSubmit())
            session.scheduleWatch(0, true)
            withTimeout(2000) { while (!session.canSubmit()) delay(5) }
            current = model(2)
            assertFalse(session.canSubmit())
        } finally { scope.cancel() }
    }
}
