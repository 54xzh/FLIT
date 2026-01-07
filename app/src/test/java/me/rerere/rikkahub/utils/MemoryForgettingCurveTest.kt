package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryForgettingCurveTest {
    @Test
    fun `pinned core never decays`() {
        val now = 1_700_000_000_000L
        val old = now - 365L * 24 * 60 * 60 * 1000L

        val retention = MemoryForgettingCurve.retentionScoreForCore(
            lastAccessedAt = old,
            createdAt = old,
            accessCount = 0,
            isPinned = true,
            now = now,
        )

        assertEquals(1f, retention, 0.0001f)
        assertFalse(MemoryForgettingCurve.shouldArchiveCore(retentionScore = retention, isPinned = true))
    }

    @Test
    fun `core retention decays over time`() {
        val now = 1_700_000_000_000L
        val createdAt = now
        val later = now + 30L * 24 * 60 * 60 * 1000L

        val retentionNow = MemoryForgettingCurve.retentionScoreForCore(
            lastAccessedAt = createdAt,
            createdAt = createdAt,
            accessCount = 0,
            isPinned = false,
            now = now,
        )
        val retentionLater = MemoryForgettingCurve.retentionScoreForCore(
            lastAccessedAt = createdAt,
            createdAt = createdAt,
            accessCount = 0,
            isPinned = false,
            now = later,
        )

        assertEquals(1f, retentionNow, 0.0001f)
        assertTrue(retentionLater < retentionNow)
    }

    @Test
    fun `access reinforcement increases retention`() {
        val now = 1_700_000_000_000L
        val createdAt = now
        val later = now + 30L * 24 * 60 * 60 * 1000L

        val retentionLow = MemoryForgettingCurve.retentionScoreForCore(
            lastAccessedAt = createdAt,
            createdAt = createdAt,
            accessCount = 0,
            isPinned = false,
            now = later,
        )
        val retentionHigh = MemoryForgettingCurve.retentionScoreForCore(
            lastAccessedAt = createdAt,
            createdAt = createdAt,
            accessCount = 10,
            isPinned = false,
            now = later,
        )

        assertTrue(retentionHigh > retentionLow)
    }

    @Test
    fun `episode significance increases retention`() {
        val now = 1_700_000_000_000L
        val start = now - 10L * 24 * 60 * 60 * 1000L
        val end = now - 9L * 24 * 60 * 60 * 1000L

        val low = MemoryForgettingCurve.retentionScoreForEpisode(
            lastAccessedAt = end,
            startTime = start,
            endTime = end,
            significance = 1,
            accessCount = 0,
            now = now,
        )
        val high = MemoryForgettingCurve.retentionScoreForEpisode(
            lastAccessedAt = end,
            startTime = start,
            endTime = end,
            significance = 10,
            accessCount = 0,
            now = now,
        )

        assertTrue(high > low)
    }

    @Test
    fun `purge decision respects grace period`() {
        val dayMs = 24L * 60 * 60 * 1000L
        val now = 1_700_000_000_000L
        val archivedAt = now - 10L * dayMs

        assertTrue(MemoryForgettingCurve.shouldPurgeArchived(archivedAt = archivedAt, purgeAfterDays = 7, now = now))
        assertFalse(MemoryForgettingCurve.shouldPurgeArchived(archivedAt = archivedAt, purgeAfterDays = 14, now = now))
    }
}

