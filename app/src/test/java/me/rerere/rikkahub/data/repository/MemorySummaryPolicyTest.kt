package me.rerere.rikkahub.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import me.rerere.rikkahub.data.db.entity.MemorySummaryChangeType
import me.rerere.rikkahub.data.db.entity.MemorySummaryChangeEntity
import me.rerere.rikkahub.data.db.entity.MemorySummaryStateEntity
import me.rerere.rikkahub.data.db.entity.MemorySummaryVersionEntity

class MemorySummaryPolicyTest {
    @Test
    fun disablingActiveSummaryForcesManualUpdateToUseAllMemories() {
        val normalized = normalizeManualMemorySummaryUpdateOptions(
            options = MemorySummaryUpdateOptions(
                includeActiveSummary = false,
                includeRecentRequirements = false,
                memoryScope = MemorySummaryMemoryScope.ADDED,
            ),
            hasActiveSummary = true,
        )

        assertFalse(normalized.includeActiveSummary)
        assertFalse(normalized.includeRecentRequirements)
        assertEquals(MemorySummaryMemoryScope.ALL, normalized.memoryScope)
    }

    @Test
    fun noActiveSummaryDisablesUsingThePreviousSummary() {
        val normalized = normalizeManualMemorySummaryUpdateOptions(
            options = MemorySummaryUpdateOptions(),
            hasActiveSummary = false,
        )

        assertFalse(normalized.includeActiveSummary)
        assertEquals(MemorySummaryMemoryScope.ALL, normalized.memoryScope)
    }

    @Test
    fun newMemoryStaysIncrementalAfterContentEdits() {
        assertEquals(
            MemorySummaryChangeType.ADDED,
            mergeMemorySummaryChangeType(
                previous = MemorySummaryChangeType.ADDED,
                requested = MemorySummaryChangeType.UPDATED,
            ),
        )
    }

    @Test
    fun newMemoryRemovedBeforeUpdateClearsPendingChange() {
        assertEquals(
            null,
            mergeMemorySummaryChangeType(
                previous = MemorySummaryChangeType.ADDED,
                requested = MemorySummaryChangeType.DELETED,
            ),
        )
    }

    @Test
    fun thresholdMustBeExceededNotMerelyReached() {
        assertFalse(
            hasEnoughMemorySummaryChanges(
                activeVersion = null,
                pendingChanges = 0,
                currentMemoryCount = 10,
                threshold = 10,
            ),
        )
        assertTrue(
            hasEnoughMemorySummaryChanges(
                activeVersion = null,
                pendingChanges = 0,
                currentMemoryCount = 11,
                threshold = 10,
            ),
        )
    }

    @Test
    fun minimumIntervalIsStrict() {
        val now = 1_000_000_000L
        val interval = MemorySummaryRepository.DAY_MILLIS * 3
        assertEquals(1L, remainingMemorySummaryDelayMillis(now - interval, 3, now))
        assertEquals(0L, remainingMemorySummaryDelayMillis(now - interval - 1L, 3, now))
    }

    @Test
    fun editsAndDeletesRequireAFullUpdate() {
        val active = MemorySummaryVersionEntity(
            assistantId = "assistant",
            content = "summary",
            generatedAt = 1L,
            updateMode = 0,
            sourceChangeCount = 1,
        )
        val edit = MemorySummaryChangeEntity(
            assistantId = "assistant",
            memoryType = 0,
            memoryId = 1,
            changeType = MemorySummaryChangeType.UPDATED,
            changedAt = 2L,
            changeToken = "edit",
        )
        val addition = edit.copy(changeType = MemorySummaryChangeType.ADDED)

        assertTrue(shouldUseFullMemorySummaryUpdate(active, listOf(edit), forceFull = false))
        assertFalse(shouldUseFullMemorySummaryUpdate(active, listOf(addition), forceFull = false))
    }

    @Test
    fun restoredVersionRequiresAFullUpdate() {
        val active = MemorySummaryVersionEntity(
            assistantId = "assistant",
            content = "summary",
            generatedAt = 1L,
            updateMode = 0,
            sourceChangeCount = 1,
        )

        assertTrue(
            shouldUseFullMemorySummaryUpdate(
                activeVersion = active,
                changes = emptyList(),
                forceFull = false,
                requiresFullUpdate = true,
            )
        )
    }

    @Test
    fun retentionKeepsTheActiveVersionEvenWhenItIsOld() {
        val versions = (1L..11L).reversed().map { id ->
            MemorySummaryVersionEntity(
                id = id,
                assistantId = "assistant",
                content = "summary $id",
                generatedAt = id,
                updateMode = 0,
                sourceChangeCount = 0,
            )
        }

        assertEquals(
            listOf(2L),
            memorySummaryVersionIdsToPrune(
                versionsNewestFirst = versions,
                activeVersionId = 1L,
            )
        )
    }

    @Test
    fun requirementChangeOnlyPublishesForTheSnapshotItStartedWith() {
        val state = MemorySummaryStateEntity(
            assistantId = "assistant",
            activeVersionId = 4L,
            revision = 7L,
        )

        assertTrue(matchesMemorySummaryActiveVersion(state, 4L, 7L))
        assertFalse(matchesMemorySummaryActiveVersion(state, 3L, 7L))
        assertFalse(matchesMemorySummaryActiveVersion(state, 4L, 6L))
    }
}
