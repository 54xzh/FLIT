package me.rerere.rikkahub.data.ai

import java.io.IOException
import kotlinx.coroutines.CancellationException
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
    fun computeHttpRetryDelayMs_usesFixedConfiguredDelay() {
        assertEquals(1_000L, computeHttpRetryDelayMs(0))
        assertEquals(12_000L, computeHttpRetryDelayMs(12))
        assertEquals(30_000L, computeHttpRetryDelayMs(99))
    }

    @Test
    fun shouldIncludeCurrentDateSection_includesSearchAgentTool() {
        assertTrue(shouldIncludeCurrentDateSection(listOf("search_web")))
        assertTrue(shouldIncludeCurrentDateSection(listOf("search_agent")))
        assertFalse(shouldIncludeCurrentDateSection(listOf("memory_search")))
    }
}
