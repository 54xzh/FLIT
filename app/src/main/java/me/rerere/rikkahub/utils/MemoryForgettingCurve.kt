package me.rerere.rikkahub.utils

import kotlin.math.exp
import kotlin.math.ln

object MemoryForgettingCurve {
    data class Params(
        val coreBaseHours: Double = 30.0 * 24.0,
        val episodicBaseHours: Double = 7.0 * 24.0,
        val coreImportanceFactor: Double = 1.2,
        val reinforcementK: Double = 0.25,
        val coreArchiveThreshold: Double = 0.08,
        val episodicArchiveThreshold: Double = 0.08,
        val corePurgeAfterDays: Long = 180,
        val episodicPurgeAfterDays: Long = 60,
    )

    val Default = Params()

    fun retentionScoreForCore(
        lastAccessedAt: Long,
        createdAt: Long,
        accessCount: Int,
        isPinned: Boolean,
        now: Long = System.currentTimeMillis(),
        params: Params = Default,
    ): Float {
        if (isPinned) return 1f
        return retentionScore(
            baseHours = params.coreBaseHours,
            importanceFactor = params.coreImportanceFactor,
            accessCount = accessCount,
            lastAccessedAt = lastAccessedAt,
            createdAt = createdAt,
            now = now,
            reinforcementK = params.reinforcementK,
        )
    }

    fun retentionScoreForEpisode(
        lastAccessedAt: Long,
        startTime: Long,
        endTime: Long,
        significance: Int,
        accessCount: Int,
        now: Long = System.currentTimeMillis(),
        params: Params = Default,
    ): Float {
        return retentionScore(
            baseHours = params.episodicBaseHours,
            importanceFactor = significanceFactor(significance),
            accessCount = accessCount,
            lastAccessedAt = lastAccessedAt,
            createdAt = maxOf(startTime, endTime),
            now = now,
            reinforcementK = params.reinforcementK,
        )
    }

    fun shouldArchiveCore(
        retentionScore: Float,
        isPinned: Boolean,
        params: Params = Default,
    ): Boolean = !isPinned && retentionScore < params.coreArchiveThreshold

    fun shouldArchiveEpisode(
        retentionScore: Float,
        params: Params = Default,
    ): Boolean = retentionScore < params.episodicArchiveThreshold

    fun shouldPurgeArchived(
        archivedAt: Long,
        purgeAfterDays: Long,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val purgeAfterMs = purgeAfterDays.coerceAtLeast(1) * 24 * 60 * 60 * 1000L
        return now - archivedAt >= purgeAfterMs
    }

    fun significanceFactor(significance: Int): Double {
        val clamped = significance.coerceIn(1, 10)
        return 0.7 + ((clamped - 1).toDouble() / 9.0) * 0.9
    }

    private fun retentionScore(
        baseHours: Double,
        importanceFactor: Double,
        accessCount: Int,
        lastAccessedAt: Long,
        createdAt: Long,
        now: Long,
        reinforcementK: Double,
    ): Float {
        val anchor = when {
            lastAccessedAt > 0L -> lastAccessedAt
            createdAt > 0L -> createdAt
            else -> now
        }

        val dtMs = (now - anchor).coerceAtLeast(0L)
        val tHours = dtMs / (1000.0 * 60.0 * 60.0)

        val safeBaseHours = baseHours.coerceAtLeast(1.0)
        val safeImportance = importanceFactor.coerceAtLeast(0.1)
        val reinforcement = 1.0 + ln(1.0 + accessCount.coerceAtLeast(0)) * reinforcementK.coerceAtLeast(0.0)
        val strengthHours = safeBaseHours * safeImportance * reinforcement.coerceAtLeast(1.0)

        return exp(-tHours / strengthHours)
            .toFloat()
            .coerceIn(0f, 1f)
    }
}

