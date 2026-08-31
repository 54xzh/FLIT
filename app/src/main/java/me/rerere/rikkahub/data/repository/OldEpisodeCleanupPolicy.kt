package me.rerere.rikkahub.data.repository

internal const val OLD_EPISODE_CLEANUP_RETENTION_MILLIS = 30L * 24L * 60L * 60L * 1000L
internal const val OLD_EPISODE_CLEANUP_SQL_BATCH_SIZE = 500

/** A memory exactly 30 days old is retained; only strictly older rows are removed. */
internal fun oldEpisodeCleanupCutoff(nowMillis: Long): Long =
    nowMillis - OLD_EPISODE_CLEANUP_RETENTION_MILLIS

internal fun shouldClearOldEpisode(endTimeMillis: Long, nowMillis: Long): Boolean =
    endTimeMillis < oldEpisodeCleanupCutoff(nowMillis)

internal fun <T> oldEpisodeCleanupBatches(items: List<T>): List<List<T>> =
    items.chunked(OLD_EPISODE_CLEANUP_SQL_BATCH_SIZE)
