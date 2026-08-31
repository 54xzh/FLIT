package me.rerere.rikkahub.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OldEpisodeCleanupPolicyTest {
    private val now = 2_000_000_000_000L

    @Test
    fun `episode exactly 30 days old is retained`() {
        val boundary = oldEpisodeCleanupCutoff(now)

        assertFalse(shouldClearOldEpisode(boundary, now))
        assertTrue(shouldClearOldEpisode(boundary - 1L, now))
    }

    @Test
    fun `cleanup batches more than SQLite parameter limit`() {
        val ids = (1..1_001).toList()

        val batches = oldEpisodeCleanupBatches(ids)

        assertEquals(listOf(500, 500, 1), batches.map(List<Int>::size))
        assertEquals(ids, batches.flatten())
    }
}
