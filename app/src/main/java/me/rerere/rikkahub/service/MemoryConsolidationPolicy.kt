package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.model.Assistant

/**
 * Keeps automatic consolidation paused without blocking an explicitly requested manual run.
 */
internal fun Assistant.canConsolidateConversation(
    conversationUpdateAt: Long,
    isManual: Boolean,
): Boolean = isManual || (
    !isMemoryConsolidationPaused && conversationUpdateAt > memoryConsolidationResumeAt
)

internal fun Assistant.withMemoryConsolidationPaused(
    paused: Boolean,
    now: Long,
): Assistant = when {
    paused -> copy(isMemoryConsolidationPaused = true)
    isMemoryConsolidationPaused -> copy(
        isMemoryConsolidationPaused = false,
        memoryConsolidationResumeAt = now,
    )
    else -> this
}
