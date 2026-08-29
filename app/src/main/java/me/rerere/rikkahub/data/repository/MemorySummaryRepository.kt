package me.rerere.rikkahub.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.ChatEpisodeDAO
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.dao.MemorySummaryDao
import me.rerere.rikkahub.data.db.entity.MemorySummaryChangeEntity
import me.rerere.rikkahub.data.db.entity.MemorySummaryChangeType
import me.rerere.rikkahub.data.db.entity.MemorySummaryUpdateMode
import me.rerere.rikkahub.data.db.entity.MemorySummaryVersionEntity
import me.rerere.rikkahub.data.db.entity.MemoryType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

data class MemorySummaryStatus(
    val activeVersion: MemorySummaryVersionEntity? = null,
    val pendingChangeCount: Int = 0,
)

data class MemorySummarySource(
    val type: Int,
    val id: Int,
    val content: String,
    val timestamp: Long,
    val timestampLabel: String,
)

class MemorySummaryRepository(
    private val summaryDao: MemorySummaryDao,
    private val memoryDao: MemoryDAO,
    private val episodeDao: ChatEpisodeDAO,
    private val database: AppDatabase,
    private val scheduler: MemorySummaryScheduler,
) {
    fun observeVersions(assistantId: String): Flow<List<MemorySummaryVersionEntity>> =
        summaryDao.observeVersions(assistantId)

    fun observeStatus(assistantId: String): Flow<MemorySummaryStatus> = combine(
        summaryDao.observeVersions(assistantId),
        summaryDao.observeChangeCount(assistantId),
    ) { versions, pending ->
        MemorySummaryStatus(activeVersion = versions.firstOrNull(), pendingChangeCount = pending)
    }

    suspend fun getActiveVersion(assistantId: String): MemorySummaryVersionEntity? =
        summaryDao.getActiveVersion(assistantId)

    suspend fun getActiveContent(assistantId: String): String =
        summaryDao.getActiveVersion(assistantId)?.content.orEmpty()

    suspend fun getPendingChanges(assistantId: String): List<MemorySummaryChangeEntity> =
        summaryDao.getChanges(assistantId)

    suspend fun getPendingChangeCount(assistantId: String): Int =
        summaryDao.getChangeCount(assistantId)

    suspend fun getCurrentMemoryCount(assistantId: String): Int =
        memoryDao.getMemoriesOfAssistant(assistantId).size + episodeDao.getEpisodesOfAssistant(assistantId).size

    suspend fun getAllSources(assistantId: String): List<MemorySummarySource> = buildList {
        memoryDao.getMemoriesOfAssistant(assistantId).forEach { memory ->
            add(
                MemorySummarySource(
                    type = MemoryType.CORE,
                    id = memory.id,
                    content = memory.content,
                    timestamp = memory.updatedAt ?: memory.createdAt,
                    timestampLabel = if (memory.updatedAt != null) "Updated" else "Created",
                )
            )
        }
        episodeDao.getEpisodesOfAssistant(assistantId).forEach { episode ->
            add(
                MemorySummarySource(
                    type = MemoryType.EPISODIC,
                    id = episode.id,
                    content = episode.content,
                    timestamp = episode.updatedAt ?: episode.endTime,
                    timestampLabel = if (episode.updatedAt != null) "Updated" else "Ended",
                )
            )
        }
    }.filter { it.content.isNotBlank() }.sortedBy { it.timestamp }

    suspend fun getAddedSources(
        assistantId: String,
        changes: List<MemorySummaryChangeEntity>,
    ): List<MemorySummarySource> = buildList {
        changes.filter { it.changeType == MemorySummaryChangeType.ADDED }.forEach { change ->
            when (change.memoryType) {
                MemoryType.CORE -> memoryDao.getMemoryById(change.memoryId)?.takeIf { it.assistantId == assistantId }?.let { memory ->
                    add(
                        MemorySummarySource(
                            type = MemoryType.CORE,
                            id = memory.id,
                            content = memory.content,
                            timestamp = memory.updatedAt ?: memory.createdAt,
                            timestampLabel = if (memory.updatedAt != null) "Updated" else "Created",
                        )
                    )
                }

                MemoryType.EPISODIC -> episodeDao.getEpisodeById(change.memoryId)?.takeIf { it.assistantId == assistantId }?.let { episode ->
                    add(
                        MemorySummarySource(
                            type = MemoryType.EPISODIC,
                            id = episode.id,
                            content = episode.content,
                            timestamp = episode.updatedAt ?: episode.endTime,
                            timestampLabel = if (episode.updatedAt != null) "Updated" else "Ended",
                        )
                    )
                }
            }
        }
    }.filter { it.content.isNotBlank() }.sortedBy { it.timestamp }

    fun formatSources(sources: List<MemorySummarySource>): String = sources.joinToString("\n\n") { source ->
        val type = if (source.type == MemoryType.CORE) "CORE" else "EPISODIC"
        val date = Instant.ofEpochMilli(source.timestamp)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .format(DateTimeFormatter.ISO_LOCAL_DATE)
        "[$type][ID: ${source.id}][${source.timestampLabel}: $date]\n${source.content.trim()}"
    }

    suspend fun recordChange(
        assistantId: String,
        memoryType: Int,
        memoryId: Int,
        requestedType: Int,
    ) {
        database.withTransaction {
            val existing = summaryDao.getChange(assistantId, memoryType, memoryId)
            val mergedType = mergeMemorySummaryChangeType(existing?.changeType, requestedType)
            if (mergedType == null) {
                summaryDao.deleteChange(assistantId, memoryType, memoryId)
            } else {
                summaryDao.upsertChange(
                    MemorySummaryChangeEntity(
                        assistantId = assistantId,
                        memoryType = memoryType,
                        memoryId = memoryId,
                        changeType = mergedType,
                        changedAt = System.currentTimeMillis(),
                        changeToken = UUID.randomUUID().toString(),
                    )
                )
            }
        }
        scheduler.enqueueAutomatic(assistantId)
    }

    suspend fun publishVersion(
        assistantId: String,
        content: String,
        updateMode: Int,
        snapshotChanges: List<MemorySummaryChangeEntity>,
    ) {
        database.withTransaction {
            summaryDao.insertVersion(
                MemorySummaryVersionEntity(
                    assistantId = assistantId,
                    content = content.trim(),
                    generatedAt = System.currentTimeMillis(),
                    updateMode = updateMode,
                    sourceChangeCount = snapshotChanges.size,
                )
            )
            val staleIds = summaryDao.getVersions(assistantId).drop(10).map { it.id }
            if (staleIds.isNotEmpty()) summaryDao.deleteVersions(staleIds)
            snapshotChanges.forEach { change ->
                summaryDao.deleteChangeIfTokenMatches(
                    assistantId = change.assistantId,
                    memoryType = change.memoryType,
                    memoryId = change.memoryId,
                    changeToken = change.changeToken,
                )
            }
        }
    }

    suspend fun clearAllForAssistant(assistantId: String) {
        database.withTransaction {
            summaryDao.deleteVersionsOfAssistant(assistantId)
            summaryDao.deleteChangesOfAssistant(assistantId)
        }
    }

    fun requestManualUpdate(assistantId: String, forceFull: Boolean, forceRebuild: Boolean) {
        scheduler.enqueueManual(assistantId, forceFull, forceRebuild)
    }

    fun scheduleAutomaticCheck(assistantId: String) {
        scheduler.enqueueAutomatic(assistantId)
    }

    fun shouldUseFullUpdate(
        activeVersion: MemorySummaryVersionEntity?,
        changes: List<MemorySummaryChangeEntity>,
        forceFull: Boolean,
    ): Boolean = shouldUseFullMemorySummaryUpdate(activeVersion, changes, forceFull)

    fun hasEnoughChanges(
        activeVersion: MemorySummaryVersionEntity?,
        pendingChanges: Int,
        currentMemoryCount: Int,
        threshold: Int,
    ): Boolean {
        return hasEnoughMemorySummaryChanges(activeVersion, pendingChanges, currentMemoryCount, threshold)
    }

    fun requiredDelayMillis(lastSuccessAt: Long, intervalDays: Int, now: Long = System.currentTimeMillis()): Long =
        remainingMemorySummaryDelayMillis(lastSuccessAt, intervalDays, now)

    companion object {
        const val DAY_MILLIS = 24L * 60L * 60L * 1000L
    }
}
