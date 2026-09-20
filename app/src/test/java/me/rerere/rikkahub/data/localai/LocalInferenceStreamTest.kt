package me.rerere.rikkahub.data.localai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalInferenceStreamTest {
    @Test
    fun `fast native producer does not drop chunks and closes once`() = runBlocking {
        var closes = 0
        val events = localInferenceStream(
            start = { emit, done ->
                repeat(256) { emit(LocalInferenceEvent.Text(it.toString())) }
                done(null)
            },
            cancel = { error("Completed inference must not be cancelled") },
            close = { closes++ },
        ).toList()
        assertEquals((0..255).map { LocalInferenceEvent.Text(it.toString()) }, events.dropLast(1))
        assertEquals(LocalInferenceEvent.Finished("stop"), events.last())
        assertEquals(1, closes)
    }

    @Test
    fun `cancellation waits for native completion before destroying conversation`() = runBlocking {
        withTimeout(5_000) {
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val closed = CompletableDeferred<Unit>()
            lateinit var complete: (Throwable?) -> Unit
            val collecting = launch {
                localInferenceStream(
                    start = { _, done -> complete = done; started.complete(Unit) },
                    cancel = { cancelled.complete(Unit) },
                    close = { closed.complete(Unit) },
                ).collect()
            }
            started.await()
            val stopping = async { collecting.cancelAndJoin() }
            cancelled.await()
            try {
                assertFalse(closed.isCompleted)
                assertFalse(stopping.isCompleted)
            } finally {
                complete(null)
            }
            stopping.await()
            assertTrue(closed.isCompleted)
        }
    }

    @Test
    fun `native error is preserved and resources are closed`() = runBlocking {
        val nativeError = IllegalStateException("Context is full")
        var closed = false
        val result = runCatching {
            localInferenceStream(
                start = { _, done -> done(nativeError) },
                cancel = { error("Terminal callback already received") },
                close = { closed = true },
            ).collect()
        }
        // Coroutine stack-trace recovery may copy the exception across the channel boundary.
        assertEquals(nativeError.javaClass, result.exceptionOrNull()?.javaClass)
        assertEquals(nativeError.message, result.exceptionOrNull()?.message)
        assertTrue(closed)
    }

    @Test
    fun `failed startup closes partially initialized resources without waiting for a callback`() = runBlocking {
        var closed = false
        val startupError = IllegalStateException("Unsupported model")
        val result = runCatching {
            localInferenceStream(
                start = { _, _ -> throw startupError },
                cancel = { error("No native generation was started") },
                close = { closed = true },
            ).collect()
        }
        assertSame(startupError, result.exceptionOrNull())
        assertTrue(closed)
    }

}
