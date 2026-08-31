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
import me.rerere.rikkahub.data.db.entity.MemorySummaryStateEntity
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
    val activeRevision: Long = 0,
    val requiresFullUpdate: Boolean = false,
)

data class MemorySummaryActiveSnapshot(
    val activeVersion: MemorySummaryVersionEntity? = null,
    val revision: Long = 0,
    val requiresFullUpdate: Boolean = false,
)

enum class MemorySummaryVersionOperationResult {
    SUCCESS,
    VERSION_NOT_FOUND,
    CANNOT_DELETE_ACTIVE,
    EMPTY_CONTENT,
    UNCHANGED_CONTENT,
}

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
        summaryDao.observeActiveVersion(assistantId),
        summaryDao.observeState(assistantId),
        summaryDao.observeChangeCount(assistantId),
    ) { activeVersion, state, pending ->
        MemorySummaryStatus(
            activeVersion = activeVersion,
            pendingChangeCount = pending,
            activeRevision = state?.revision ?: 0,
            requiresFullUpdate = state?.requiresFullUpdate ?: false,
        )
    }

    suspend fun getActiveVersion(assistantId: String): MemorySummaryVersionEntity? =
        summaryDao.getActiveVersion(assistantId)

    suspend fun getActiveSnapshot(assistantId: String): MemorySummaryActiveSnapshot =
        database.withTransaction {
            val state = summaryDao.getState(assistantId)
            MemorySummaryActiveSnapshot(
                activeVersion = summaryDao.getActiveVersion(assistantId),
                revision = state?.revision ?: 0,
                requiresFullUpdate = state?.requiresFullUpdate ?: false,
            )
        }

    suspend fun getVersion(assistantId: String, versionId: Long): MemorySummaryVersionEntity? =
        summaryDao.getVersion(assistantId, versionId)

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

    /**
     * Records a large episodic deletion in one transaction. The caller decides
     * whether a summary job should be scheduled now; bulk maintenance uses the
     * next normal cycle to avoid an unexpected second model bill.
     */
    suspend fun recordEpisodeDeletions(
        episodeIdsByAssistant: Map<String, List<Int>>,
        scheduleAutomatically: Boolean,
    ) {
        if (episodeIdsByAssistant.isEmpty()) return
        database.withTransaction {
            episodeIdsByAssistant.forEach { (assistantId, episodeIds) ->
                episodeIds.distinct().forEach { episodeId ->
                    val existing = summaryDao.getChange(
                        assistantId,
                        MemoryType.EPISODIC,
                        episodeId,
                    )
                    val mergedType = mergeMemorySummaryChangeType(
                        existing?.changeType,
                        MemorySummaryChangeType.DELETED,
                    )
                    if (mergedType == null) {
                        summaryDao.deleteChange(assistantId, MemoryType.EPISODIC, episodeId)
                    } else {
                        summaryDao.upsertChange(
                            MemorySummaryChangeEntity(
                                assistantId = assistantId,
                                memoryType = MemoryType.EPISODIC,
                                memoryId = episodeId,
                                changeType = mergedType,
                                changedAt = System.currentTimeMillis(),
                                changeToken = UUID.randomUUID().toString(),
                            ),
                        )
                    }
                }
            }
        }
        if (scheduleAutomatically) {
            episodeIdsByAssistant.keys.forEach(scheduler::enqueueAutomatic)
        }
    }

    fun scheduleAutomaticAfter(assistantId: String, delayMillis: Long) {
        scheduler.enqueueAutomatic(assistantId, delayMillis.coerceAtLeast(0L))
    }

    /**
     * Persists a generated version only when the active selection has not changed since generation
     * began. A stale response is discarded so explicit user choices always win.
     */
    suspend fun publishVersion(
        assistantId: String,
        content: String,
        updateMode: Int,
        snapshotChanges: List<MemorySummaryChangeEntity>,
        expectedActiveVersionId: Long?,
        expectedRevision: Long,
    ): Boolean {
        return database.withTransaction {
            val currentState = summaryDao.getState(assistantId)
            val matchesExpectedState = if (expectedActiveVersionId == null) {
                currentState == null && expectedRevision == 0L
            } else {
                currentState?.activeVersionId == expectedActiveVersionId &&
                    currentState.revision == expectedRevision
            }
            if (!matchesExpectedState) return@withTransaction false

            val versionId = summaryDao.insertVersion(
                MemorySummaryVersionEntity(
                    assistantId = assistantId,
                    content = content.trim(),
                    generatedAt = System.currentTimeMillis(),
                    updateMode = updateMode,
                    sourceChangeCount = snapshotChanges.size,
                )
            )
            summaryDao.upsertState(
                MemorySummaryStateEntity(
                    assistantId = assistantId,
                    activeVersionId = versionId,
                    requiresFullUpdate = false,
                    revision = expectedRevision + 1,
                )
            )
            val staleIds = memorySummaryVersionIdsToPrune(
                versionsNewestFirst = summaryDao.getVersions(assistantId),
                activeVersionId = versionId,
            )
            if (staleIds.isNotEmpty()) summaryDao.deleteVersions(staleIds)
            snapshotChanges.forEach { change ->
                summaryDao.deleteChangeIfTokenMatches(
                    assistantId = change.assistantId,
                    memoryType = change.memoryType,
                    memoryId = change.memoryId,
                    changeToken = change.changeToken,
                )
            }
            true
        }
    }

    suspend fun activateVersion(
        assistantId: String,
        versionId: Long,
    ): MemorySummaryVersionOperationResult = database.withTransaction {
        val version = summaryDao.getVersion(assistantId, versionId)
            ?: return@withTransaction MemorySummaryVersionOperationResult.VERSION_NOT_FOUND
        val currentState = summaryDao.getState(assistantId)
        if (currentState?.activeVersionId == version.id) {
            return@withTransaction MemorySummaryVersionOperationResult.SUCCESS
        }
        summaryDao.upsertState(
            MemorySummaryStateEntity(
                assistantId = assistantId,
                activeVersionId = version.id,
                requiresFullUpdate = true,
                revision = (currentState?.revision ?: 0) + 1,
            )
        )
        MemorySummaryVersionOperationResult.SUCCESS
    }

    suspend fun createManualVersion(
        assistantId: String,
        baseVersionId: Long,
        content: String,
    ): MemorySummaryVersionOperationResult = database.withTransaction {
        val baseVersion = summaryDao.getVersion(assistantId, baseVersionId)
            ?: return@withTransaction MemorySummaryVersionOperationResult.VERSION_NOT_FOUND
        val normalizedContent = content.trim()
        if (normalizedContent.isEmpty()) {
            return@withTransaction MemorySummaryVersionOperationResult.EMPTY_CONTENT
        }
        if (normalizedContent == baseVersion.content.trim()) {
            return@withTransaction MemorySummaryVersionOperationResult.UNCHANGED_CONTENT
        }
        val currentState = summaryDao.getState(assistantId)
        val editingHistory = currentState?.activeVersionId != baseVersion.id
        val versionId = summaryDao.insertVersion(
            MemorySummaryVersionEntity(
                assistantId = assistantId,
                content = normalizedContent,
                generatedAt = System.currentTimeMillis(),
                updateMode = MemorySummaryUpdateMode.MANUAL,
                sourceChangeCount = 0,
            )
        )
        summaryDao.upsertState(
            MemorySummaryStateEntity(
                assistantId = assistantId,
                activeVersionId = versionId,
                requiresFullUpdate = (currentState?.requiresFullUpdate ?: false) || editingHistory,
                revision = (currentState?.revision ?: 0) + 1,
            )
        )
        val staleIds = memorySummaryVersionIdsToPrune(
            versionsNewestFirst = summaryDao.getVersions(assistantId),
            activeVersionId = versionId,
        )
        if (staleIds.isNotEmpty()) summaryDao.deleteVersions(staleIds)
        MemorySummaryVersionOperationResult.SUCCESS
    }

    suspend fun deleteHistoryVersion(
        assistantId: String,
        versionId: Long,
    ): MemorySummaryVersionOperationResult = database.withTransaction {
        val version = summaryDao.getVersion(assistantId, versionId)
            ?: return@withTransaction MemorySummaryVersionOperationResult.VERSION_NOT_FOUND
        if (summaryDao.getState(assistantId)?.activeVersionId == version.id) {
            return@withTransaction MemorySummaryVersionOperationResult.CANNOT_DELETE_ACTIVE
        }
        if (summaryDao.deleteVersion(assistantId, version.id) == 0) {
            MemorySummaryVersionOperationResult.VERSION_NOT_FOUND
        } else {
            MemorySummaryVersionOperationResult.SUCCESS
        }
    }

    suspend fun clearAllForAssistant(assistantId: String) {
        database.withTransaction {
            summaryDao.deleteStateOfAssistant(assistantId)
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
        requiresFullUpdate: Boolean = false,
    ): Boolean = shouldUseFullMemorySummaryUpdate(
        activeVersion = activeVersion,
        changes = changes,
        forceFull = forceFull,
        requiresFullUpdate = requiresFullUpdate,
    )

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
