package me.rerere.rikkahub.data.ai

import java.io.IOException
import kotlinx.coroutines.CancellationException
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.HttpStatusException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationHandlerRetryTest {
    @Test
    fun shouldRetryHttpRequest_retriesTemporaryHttpErrors() {
        val retryableCodes = listOf(408, 429, 500, 502, 503, 504)

        retryableCodes.forEach { code ->
            assertTrue(
                "HTTP $code should be retried",
                shouldRetryHttpRequest(
                    throwable = HttpStatusException(code, "temporary error"),
                    attempt = 1,
                    maxRetries = 2,
                )
            )
        }
    }

    @Test
    fun shouldRetryHttpRequest_ignoresPermanentHttpErrors() {
        val permanentCodes = listOf(400, 401, 403, 404, 422)

        permanentCodes.forEach { code ->
            assertFalse(
                "HTTP $code should not be retried",
                shouldRetryHttpRequest(
                    throwable = HttpStatusException(code, "permanent error"),
                    attempt = 1,
                    maxRetries = 2,
                )
            )
        }
    }

    @Test
    fun shouldRetryHttpRequest_retriesNetworkErrors() {
        assertTrue(
            shouldRetryHttpRequest(
                throwable = IOException("timeout"),
                attempt = 1,
                maxRetries = 2,
            )
        )
    }

    @Test
    fun shouldRetryHttpRequest_doesNotRetryAfterStreamOutputOrCancellation() {
        assertFalse(
            shouldRetryHttpRequest(
                throwable = HttpStatusException(503, "temporary error"),
                attempt = 1,
                maxRetries = 2,
                emittedAnyChunk = true,
            )
        )
        assertFalse(
            shouldRetryHttpRequest(
                throwable = IOException("canceled", CancellationException("user canceled")),
                attempt = 1,
                maxRetries = 2,
            )
        )
    }

    @Test
    fun shouldAutoContinueOnNetworkError_returnsTrueForNetworkIOException() {
        assertTrue(IOException("socket closed").shouldAutoContinueOnNetworkError())
    }

    @Test
    fun shouldAutoContinueOnNetworkError_walksCauseChainForIOException() {
        val wrapped = RuntimeException("request failed", IOException("connection reset"))
        assertTrue(wrapped.shouldAutoContinueOnNetworkError())
    }

    @Test
    fun shouldAutoContinueOnNetworkError_excludesSseCanceledIOException() {
        assertFalse(IOException("canceled").shouldAutoContinueOnNetworkError())
        assertFalse(IOException("cancelled").shouldAutoContinueOnNetworkError())
    }

    @Test
    fun shouldAutoContinueOnNetworkError_excludesCancellationException() {
        assertFalse(CancellationException("user stopped").shouldAutoContinueOnNetworkError())
    }

    @Test
    fun shouldAutoContinueOnNetworkError_excludesCancellationInCauseChain() {
        val wrapped = RuntimeException("wrap", CancellationException("user stopped"))
        assertFalse(wrapped.shouldAutoContinueOnNetworkError())
    }

    @Test
    fun shouldAutoContinueOnNetworkError_excludesHttpStatusErrors() {
        // 网络续写只覆盖 IO 错误，不覆盖 5xx/429 等 HTTP 状态码错
        listOf(408, 429, 500, 502, 503, 504).forEach { code ->
            assertFalse(
                "HTTP $code should not trigger network auto-continue",
                HttpStatusException(code, "server error").shouldAutoContinueOnNetworkError(),
            )
        }
    }

    @Test
    fun shouldAutoContinueOnNetworkError_excludesGenericExceptions() {
        assertFalse(IllegalStateException("bad state").shouldAutoContinueOnNetworkError())
        assertFalse(RuntimeException("other").shouldAutoContinueOnNetworkError())
    }

    @Test
    fun computeHttpRetryDelayMs_usesFixedConfiguredDelay() {
        assertEquals(1_000L, computeHttpRetryDelayMs(0))
        assertEquals(12_000L, computeHttpRetryDelayMs(12))
        assertEquals(30_000L, computeHttpRetryDelayMs(99))
    }

    @Test
    fun computeStreamUiUpdateIntervalMs_usesLengthBasedBuckets() {
        assertEquals(0L, computeStreamUiUpdateIntervalMs(1_000))
        assertEquals(120L, computeStreamUiUpdateIntervalMs(1_001))
        assertEquals(120L, computeStreamUiUpdateIntervalMs(2_500))
        assertEquals(180L, computeStreamUiUpdateIntervalMs(2_501))
        assertEquals(180L, computeStreamUiUpdateIntervalMs(5_000))
        assertEquals(260L, computeStreamUiUpdateIntervalMs(5_001))
        assertEquals(260L, computeStreamUiUpdateIntervalMs(10_000))
        assertEquals(360L, computeStreamUiUpdateIntervalMs(10_001))
    }

    @Test
    fun streamUiUpdateGate_throttlesOnlyLongTextUpdates() {
        var now = 0L
        val gate = StreamUiUpdateGate(nowMs = { now })
        val message = UIMessage.assistant("x".repeat(1_001))

        assertTrue(gate.shouldEmit(listOf(message), emptySet()))

        now = 60L
        assertFalse(
            gate.shouldEmit(
                listOf(message.withText("x".repeat(1_100))),
                emptySet(),
            )
        )

        now = 120L
        assertTrue(
            gate.shouldEmit(
                listOf(message.withText("x".repeat(1_200))),
                emptySet(),
            )
        )
    }

    @Test
    fun streamUiUpdateGate_keepsShortTextImmediate() {
        var now = 0L
        val gate = StreamUiUpdateGate(nowMs = { now })
        val message = UIMessage.assistant("x")

        assertTrue(gate.shouldEmit(listOf(message), emptySet()))

        now = 1L
        assertTrue(
            gate.shouldEmit(
                listOf(message.withText("x".repeat(900))),
                emptySet(),
            )
        )
    }

    @Test
    fun streamUiUpdateGate_emitsFinishAndStructuralChangesImmediately() {
        var now = 0L
        val gate = StreamUiUpdateGate(nowMs = { now })
        val message = UIMessage.assistant("x".repeat(10_001))

        assertTrue(gate.shouldEmit(listOf(message), emptySet()))

        now = 1L
        assertTrue(
            gate.shouldEmit(
                listOf(message.withText("x".repeat(10_100))),
                setOf("stop"),
            )
        )

        val gateForToolCall = StreamUiUpdateGate(nowMs = { now })
        val toolMessage = message.copy(
            parts = message.parts + UIMessagePart.ToolCall(
                toolCallId = "call-1",
                toolName = "search_web",
                arguments = "{}",
            )
        )

        assertTrue(gateForToolCall.shouldEmit(listOf(message), emptySet()))

        now = 2L
        assertTrue(gateForToolCall.shouldEmit(listOf(toolMessage), emptySet()))
    }

    @Test
    fun shouldIncludeCurrentDateSection_includesSearchAgentTool() {
        assertTrue(shouldIncludeCurrentDateSection(listOf("search_web")))
        assertTrue(shouldIncludeCurrentDateSection(listOf("search_agent")))
        assertFalse(shouldIncludeCurrentDateSection(listOf("memory_search")))
    }

    private fun UIMessage.withText(text: String): UIMessage {
        return copy(parts = listOf(UIMessagePart.Text(text)))
    }
}
