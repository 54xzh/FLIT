package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.db.entity.MemorySummaryChangeEntity
import me.rerere.rikkahub.data.db.entity.MemorySummaryChangeType
import me.rerere.rikkahub.data.db.entity.MemorySummaryVersionEntity

internal fun mergeMemorySummaryChangeType(previous: Int?, requested: Int): Int? = when {
    previous == null -> requested
    previous == MemorySummaryChangeType.ADDED && requested == MemorySummaryChangeType.UPDATED ->
        MemorySummaryChangeType.ADDED
    previous == MemorySummaryChangeType.ADDED && requested == MemorySummaryChangeType.DELETED -> null
    requested == MemorySummaryChangeType.DELETED -> MemorySummaryChangeType.DELETED
    else -> previous
}

internal fun shouldUseFullMemorySummaryUpdate(
    activeVersion: MemorySummaryVersionEntity?,
    changes: List<MemorySummaryChangeEntity>,
    forceFull: Boolean,
): Boolean = forceFull || activeVersion == null || changes.any {
    it.changeType != MemorySummaryChangeType.ADDED
}

internal fun hasEnoughMemorySummaryChanges(
    activeVersion: MemorySummaryVersionEntity?,
    pendingChanges: Int,
    currentMemoryCount: Int,
    threshold: Int,
): Boolean = (if (activeVersion == null) currentMemoryCount else pendingChanges) > threshold

internal fun remainingMemorySummaryDelayMillis(
    lastSuccessAt: Long,
    intervalDays: Int,
    now: Long,
): Long = ((intervalDays.coerceIn(1, 30) * MemorySummaryRepository.DAY_MILLIS) - (now - lastSuccessAt) + 1L)
    .coerceAtLeast(0L)
