package me.rerere.rikkahub.service

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import me.rerere.ai.util.HttpStatusException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.SocketTimeoutException

class MemoryConsolidationRetriesTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `unsupported model parameters stop the current batch and future automatic scans`() = runBlocking {
        val file = folder.root.resolve("retries.preferences_pb")
        val firstScope = SupervisorJob()
        val firstStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + firstScope),
            produceFile = { file },
        )
        val firstScan = MemoryConsolidationRetries(firstStore)
        val error = HttpStatusException(400, "level \"minimal\" not supported, valid levels: low, medium, high")
        try {
            val result = runCatching {
                firstScan.runRequest("model", "first-chat", "first prompt", isManual = true) { throw error }
            }
            assertEquals(error, result.exceptionOrNull())
            assertFalse(firstScan.runRequest("model", "next-chat", "another prompt", isManual = true) {
                error("The manual batch should stop using the rejected model")
            })
        } finally {
            firstScope.cancel()
            firstScope.join()
        }

        val secondScope = SupervisorJob()
        val secondStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + secondScope),
            produceFile = { file },
        )
        try {
            val nextScan = MemoryConsolidationRetries(secondStore)
            assertFalse(nextScan.runRequest("model", "next-chat", "another prompt", isManual = false) {
                error("An automatic scan should skip the rejected model")
            })
            assertTrue(nextScan.runRequest("updated-model", "next-chat", "another prompt", isManual = false) {})
            assertTrue(nextScan.runRequest("model", "first-chat", "first prompt", isManual = true) {})
            assertTrue(nextScan.runRequest("model", "next-chat", "another prompt", isManual = false) {})
        } finally {
            secondScope.cancel()
            secondScope.join()
        }
    }

    @Test
    fun `temporary failures back off and stop after three attempts on the same request`() {
        val temporary = MemoryRequestFailure.TEMPORARY
        val first = FailedMemoryRequest.next(null, "same", temporary, now = 100L)
        assertTrue(first.blocks("same", first.retryAt - 1))
        assertFalse(first.blocks("same", first.retryAt))

        val second = FailedMemoryRequest.next(first, "same", temporary, now = first.retryAt)
        assertTrue(second.retryAt - first.retryAt > first.retryAt - 100L)
        val third = FailedMemoryRequest.next(second, "same", temporary, now = second.retryAt)
        assertTrue(third.blocks("same", Long.MAX_VALUE))
        assertFalse(third.blocks("changed", second.retryAt))
        assertEquals(1, FailedMemoryRequest.next(third, "changed", temporary, now = 100L).attempts)
    }

    @Test
    fun `request errors stay terminal and workers only retry transient failures twice`() {
        val rejected = HttpStatusException(400, "The prompt is too long")
        val failure = memoryRequestFailure(rejected)
        assertEquals(MemoryRequestFailure.CONVERSATION, failure)
        assertTrue(FailedMemoryRequest.next(null, "same", failure, now = 100L).blocks("same", 100L))
        assertFalse(shouldRetryMemoryWork(rejected, runAttemptCount = 0))

        listOf(HttpStatusException(429, "Rate limit"), SocketTimeoutException()).forEach { temporary ->
            assertTrue(shouldRetryMemoryWork(temporary, runAttemptCount = 0))
            assertTrue(shouldRetryMemoryWork(temporary, runAttemptCount = 1))
            assertFalse(shouldRetryMemoryWork(temporary, runAttemptCount = 2))
        }
    }
}
