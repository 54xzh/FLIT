package me.rerere.rikkahub.data.repository

import android.util.Log
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.ai.AIRequestSource
import me.rerere.rikkahub.data.ai.rag.EmbeddingService
import me.rerere.rikkahub.data.ai.rag.EmbeddingTimeoutPolicy
import me.rerere.rikkahub.data.ai.rag.VectorEngine
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.ChatEpisodeDAO
import me.rerere.rikkahub.data.db.dao.EmbeddingCacheDAO
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.dao.MemoryRetrievalRow
import me.rerere.rikkahub.data.db.entity.ChatEpisodeEntity
import me.rerere.rikkahub.data.db.entity.EmbeddingCacheEntity
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.db.entity.MemoryType
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.service.MemoryEmbeddingBackfillScheduler
import me.rerere.rikkahub.utils.JsonInstant
import java.util.concurrent.ConcurrentHashMap

data class AssistantMemoryStats(
    val coreCount: Int = 0,
    val episodicCount: Int = 0,
    val embeddedCount: Int = 0,
    val totalCount: Int = 0,
)

internal fun mergeKeywordMemoryHits(
    rows: List<MemoryRetrievalRow>,
    matchedHits: List<KeywordSearchHit>,
    limit: Int,
    normalize: (String) -> String,
): List<KeywordSearchHit> {
    val pinnedHits = rows.asSequence()
        .filter { it.pinned }
        .map { row ->
            KeywordSearchHit(
                row = row,
                score = 1f,
                matchedTerms = emptyList(),
            )
        }
        .toList()
    val deduplicated = LinkedHashMap<String, KeywordSearchHit>()
    (pinnedHits + matchedHits)
        .sortedWith(
            compareByDescending<KeywordSearchHit> { it.row.pinned }
                .thenByDescending { it.score }
                .thenByDescending { it.row.significance ?: 0 }
                .thenByDescending { it.row.timestamp }
                .thenBy { it.row.id },
        )
        .forEach { hit ->
            val normalizedContent = normalize(hit.row.content).trim()
            val key = if (normalizedContent.isBlank()) "id:${hit.row.id}" else normalizedContent
            deduplicated.putIfAbsent(key, hit)
        }
    val pinned = deduplicated.values.filter { it.row.pinned }
    val dynamic = deduplicated.values
        .filterNot { it.row.pinned }
        .take(limit.coerceAtLeast(0))
    return pinned + dynamic
}

// 打分按块进行：每块先批量预取嵌入缓存再计算相似度，块间释放，检索峰值内存有上限
private const val EMBEDDING_SCORING_CHUNK_SIZE = 256
private const val EMBEDDING_BACKFILL_BATCH_SIZE = 32
private const val TAG = "MemoryRepository"

private data class EmbeddingBackfillTarget(
    val id: Int,
    val memoryType: Int,
    val content: String,
    val originalModelId: String?,
)

class MemoryRepository internal constructor(
    private val memoryDAO: MemoryDAO,
    private val chatEpisodeDAO: ChatEpisodeDAO,
    private val embeddingService: EmbeddingService,
    private val embeddingCacheDAO: EmbeddingCacheDAO,
    private val embeddingBackfillScheduler: MemoryEmbeddingBackfillScheduler,
    private val database: AppDatabase,
    private val appScope: AppScope,
    private val keywordTokenizer: MemoryKeywordTokenizer,
) {
    constructor(
        memoryDAO: MemoryDAO,
        chatEpisodeDAO: ChatEpisodeDAO,
        embeddingService: EmbeddingService,
        embeddingCacheDAO: EmbeddingCacheDAO,
        embeddingBackfillScheduler: MemoryEmbeddingBackfillScheduler,
        database: AppDatabase,
        appScope: AppScope,
    ) : this(
        memoryDAO = memoryDAO,
        chatEpisodeDAO = chatEpisodeDAO,
        embeddingService = embeddingService,
        embeddingCacheDAO = embeddingCacheDAO,
        embeddingBackfillScheduler = embeddingBackfillScheduler,
        database = database,
        appScope = appScope,
        keywordTokenizer = KeywordMemoryTokenizer(),
    )

    private val embeddingBackfillMutex = Mutex()
    private val keywordIndexMutex = Mutex()
    private val keywordIndexCache = LinkedHashMap<String, KeywordIndexCacheEntry>(16, 0.75f, true)
    private var keywordIndexCacheTermCount = 0
    private val pendingBackfillChecks = ConcurrentHashMap.newKeySet<String>()

    private data class KeywordIndexCacheEntry(
        val rows: List<MemoryRetrievalRow>,
        val index: KeywordMemoryIndex,
    )

    companion object {
        private const val KEYWORD_INDEX_TERM_BUDGET = 100_000
        private const val MEMORY_PAGE_SIZE = 30
        private const val MEMORY_INITIAL_LOAD_SIZE = 30
        private const val MEMORY_PREFETCH_DISTANCE = 10
        private const val MEMORY_MAX_SIZE = 180
        private const val PAGING_EPISODE_CONTENT_PREVIEW_LIMIT = 1200
        private const val PREVIEW_CORE_LIMIT = 6
        private const val PREVIEW_EPISODE_LIMIT = 6
        private const val PREVIEW_EPISODE_CONTENT_LIMIT = 240
    }

    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<AssistantMemory>> =
        memoryDAO.getMemoriesOfAssistantFlow(assistantId)
            .map { entities ->
                entities.map {
                    AssistantMemory(
                        id = it.id,
                        content = it.content,
                        type = it.type,
                        hasEmbedding = it.embedding != null,
                        embeddingModelId = it.embeddingModelId,
                        timestamp = it.createdAt,
                        pinned = it.pinned,
                    )
                }
            }

    /**
     * Get combined memories (core) and episodes (episodic) as AssistantMemory objects.
     * This includes significance scores for episodic memories.
     */
    fun getCombinedMemoriesFlow(assistantId: String): Flow<List<AssistantMemory>> =
        kotlinx.coroutines.flow.combine(
            memoryDAO.getMemoriesOfAssistantFlow(assistantId),
            chatEpisodeDAO.getEpisodesOfAssistantFlow(assistantId)
        ) { memories, episodes ->
            val coreMemories = memories.map { 
                AssistantMemory(
                    id = it.id,
                    content = it.content,
                    type = it.type,
                    hasEmbedding = it.embedding != null,
                    embeddingModelId = it.embeddingModelId,
                    timestamp = it.createdAt,
                    pinned = it.pinned,
                )
            }
            val episodicMemories = episodes.map { 
                AssistantMemory(-it.id, it.content, MemoryType.EPISODIC, it.embedding != null, it.embeddingModelId, it.startTime, it.significance)
            }
            coreMemories + episodicMemories
        }

    fun getAssistantMemoriesPaging(
        assistantId: String,
        memoryType: Int,
        searchQuery: String,
        sortOrder: Int,
    ): Flow<PagingData<AssistantMemory>> {
        val normalizedQuery = searchQuery.trim()
        return Pager(
            config = PagingConfig(
                pageSize = MEMORY_PAGE_SIZE,
                initialLoadSize = MEMORY_INITIAL_LOAD_SIZE,
                prefetchDistance = MEMORY_PREFETCH_DISTANCE,
                maxSize = MEMORY_MAX_SIZE,
                enablePlaceholders = false
            ),
            pagingSourceFactory = {
                memoryDAO.getAssistantMemoriesPaging(
                    assistantId = assistantId,
                    memoryType = memoryType,
                    searchQuery = normalizedQuery,
                    sortOrder = sortOrder,
                    episodeContentPreviewLimit = PAGING_EPISODE_CONTENT_PREVIEW_LIMIT
                )
            }
        ).flow.map { pagingData ->
            pagingData.map { row ->
                AssistantMemory(
                    id = row.id,
                    content = row.content,
                    type = row.type,
                    hasEmbedding = row.hasEmbedding,
                    embeddingModelId = row.embeddingModelId,
                    timestamp = row.timestamp,
                    significance = row.significance,
                    pinned = row.pinned,
                )
            }
        }
    }

    fun getMemoryPreviewFlow(assistantId: String): Flow<List<AssistantMemory>> = combine(
        memoryDAO.getRecentMemoriesOfAssistantFlow(assistantId, PREVIEW_CORE_LIMIT),
        chatEpisodeDAO.getEpisodesForUiFlow(
            assistantId = assistantId,
            limit = PREVIEW_EPISODE_LIMIT,
            contentPreviewLimit = PREVIEW_EPISODE_CONTENT_LIMIT
        ),
    ) { coreMemories, episodes ->
        val core = coreMemories.map {
            AssistantMemory(
                id = it.id,
                content = it.content,
                type = it.type,
                hasEmbedding = it.embedding != null,
                embeddingModelId = it.embeddingModelId,
                timestamp = it.createdAt,
                pinned = it.pinned,
            )
        }
        val episodic = episodes.map {
            AssistantMemory(
                id = -it.id,
                content = it.content,
                type = MemoryType.EPISODIC,
                hasEmbedding = it.hasEmbedding,
                embeddingModelId = it.embeddingModelId,
                timestamp = it.startTime,
                significance = it.significance,
            )
        }
        (core + episodic)
            .sortedByDescending { it.timestamp }
            .take(PREVIEW_CORE_LIMIT + PREVIEW_EPISODE_LIMIT)
    }

    fun getAssistantMemoryStatsFlow(assistantId: String): Flow<AssistantMemoryStats> =
        memoryDAO.getAssistantMemoryStatsFlow(assistantId)
            .map { row ->
                AssistantMemoryStats(
                    coreCount = row.coreCount,
                    episodicCount = row.episodicCount,
                    embeddedCount = row.embeddedCount,
                    totalCount = row.coreCount + row.episodicCount
                )
            }

    fun hasPendingEmbeddingsFlow(assistantId: String): Flow<Boolean> =
        memoryDAO.getPendingEmbeddingCountFlow(assistantId)
            .map { it > 0 }

    fun getAverageMemoryLength(assistantId: String): Flow<Int> =
        memoryDAO.getAverageMemoryContentLengthFlow(assistantId)
            .map { averageLength ->
                val value = averageLength ?: 150.0
                value.toInt().coerceAtLeast(1)
            }

    suspend fun getMemoriesOfAssistant(assistantId: String): List<AssistantMemory> {
        return memoryDAO.getMemoriesOfAssistant(assistantId)
            .map {
                AssistantMemory(
                    id = it.id,
                    content = it.content,
                    type = it.type,
                    hasEmbedding = it.embedding != null,
                    embeddingModelId = it.embeddingModelId,
                    timestamp = it.createdAt,
                    pinned = it.pinned,
                )
            }
    }

    suspend fun getPinnedMemoriesOfAssistant(assistantId: String): List<AssistantMemory> {
        return memoryDAO.getPinnedMemoriesOfAssistant(assistantId)
            .map {
                AssistantMemory(
                    id = it.id,
                    content = it.content,
                    type = it.type,
                    hasEmbedding = it.embedding != null,
                    embeddingModelId = it.embeddingModelId,
                    timestamp = it.createdAt,
                    pinned = it.pinned,
                )
            }
    }

    suspend fun getCoreMemoryById(id: Int): AssistantMemory? {
        val memory = memoryDAO.getMemoryById(id) ?: return null
        return AssistantMemory(
            id = memory.id,
            content = memory.content,
            type = memory.type,
            hasEmbedding = memory.embedding != null,
            embeddingModelId = memory.embeddingModelId,
            timestamp = memory.createdAt,
            pinned = memory.pinned,
        )
    }

    suspend fun getEpisodeMemoryById(id: Int): AssistantMemory? {
        val episode = chatEpisodeDAO.getEpisodeById(id) ?: return null
        return AssistantMemory(
            id = -episode.id,
            content = episode.content,
            type = MemoryType.EPISODIC,
            hasEmbedding = !episode.embeddingModelId.isNullOrBlank(),
            embeddingModelId = episode.embeddingModelId,
            timestamp = episode.startTime,
            significance = episode.significance,
        )
    }

    suspend fun getMemoryEntitiesOfAssistant(assistantId: String): List<MemoryEntity> {
        return memoryDAO.getMemoriesOfAssistant(assistantId)
    }

    suspend fun getEpisodeEntitiesOfAssistant(assistantId: String): List<ChatEpisodeEntity> {
        return chatEpisodeDAO.getEpisodesOfAssistant(assistantId)
    }

    suspend fun getRecentCombinedMemories(
        assistantId: String,
        limit: Int,
        includeCore: Boolean = true,
        includeEpisodes: Boolean = true,
    ): List<AssistantMemory> {
        val coreEntities = if (includeCore) {
            memoryDAO.getRecentMemoriesOfAssistant(assistantId, limit)
        } else {
            emptyList()
        }
        val episodeEntities = if (includeEpisodes) {
            chatEpisodeDAO.getRecentEpisodesOfAssistant(assistantId, limit)
        } else {
            emptyList()
        }

        val core = coreEntities.map {
            AssistantMemory(
                id = it.id,
                content = it.content,
                type = it.type,
                hasEmbedding = it.embedding != null,
                embeddingModelId = it.embeddingModelId,
                timestamp = it.createdAt,
                pinned = it.pinned,
            )
        }
        val episodes = episodeEntities.map { episode ->
            AssistantMemory(
                id = -episode.id,
                content = episode.content,
                type = MemoryType.EPISODIC,
                hasEmbedding = episode.embedding != null,
                embeddingModelId = episode.embeddingModelId,
                timestamp = maxOf(episode.endTime, episode.startTime),
                significance = episode.significance,
            )
        }

        return (core + episodes)
            .sortedByDescending { it.timestamp }
            .take(limit)
    }

    /**
     * Search memory text locally without loading embedding columns. Results include pinned core
     * memories and up to [limit] dynamic results.
     */
    internal suspend fun retrieveKeywordMemoriesWithScores(
        assistantId: String,
        query: String,
        limit: Int = 5,
        includeCore: Boolean = true,
        includeEpisodes: Boolean = true,
        nowMillis: Long = System.currentTimeMillis(),
        recordAccess: Boolean = true,
    ): List<KeywordSearchHit> {
        val rows = withContext(Dispatchers.IO) {
            memoryDAO.getMemoryRetrievalRows(
                assistantId = assistantId,
                includeCore = includeCore,
                includeEpisodes = includeEpisodes,
            ).sortedBy { it.id }
        }
        if (rows.isEmpty()) return emptyList()

        val matchedHits = if (query.isBlank() || limit <= 0) {
            emptyList()
        } else {
            keywordTokenizer.prepare()
            val retrievalContext = currentCoroutineContext()
            val checkCancelled = { retrievalContext.ensureActive() }
            var index = getKeywordIndex(
                cacheKey = "$assistantId:$includeCore:$includeEpisodes",
                rows = rows,
                checkCancelled = checkCancelled,
            )
            var hits = withContext(Dispatchers.Default) {
                index.search(
                    query = query,
                    tokenizer = keywordTokenizer,
                    nowMillis = nowMillis,
                    limit = limit.coerceAtLeast(0),
                    checkCancelled = checkCancelled,
                )
            }
            if (index.tokenizerRevision != keywordTokenizer.revision) {
                index = getKeywordIndex(
                    cacheKey = "$assistantId:$includeCore:$includeEpisodes",
                    rows = rows,
                    checkCancelled = checkCancelled,
                )
                hits = withContext(Dispatchers.Default) {
                    index.search(
                        query = query,
                        tokenizer = keywordTokenizer,
                        nowMillis = nowMillis,
                        limit = limit.coerceAtLeast(0),
                        checkCancelled = checkCancelled,
                    )
                }
            }
            hits
        }
        val hits = mergeKeywordMemoryHits(
            rows = rows,
            matchedHits = matchedHits,
            limit = limit,
            normalize = keywordTokenizer::normalize,
        )

        if (recordAccess) {
            updateLastAccessed(hits.map { hit ->
                AssistantMemory(
                    id = hit.row.id,
                    type = hit.row.type,
                )
            })
        }
        return hits
    }

    internal suspend fun invalidateKeywordMemoryCache(assistantId: String) {
        keywordIndexMutex.withLock {
            val iterator = keywordIndexCache.entries.iterator()
            while (iterator.hasNext()) {
                val (cacheKey, entry) = iterator.next()
                if (cacheKey.startsWith("$assistantId:")) {
                    keywordIndexCacheTermCount -= entry.index.estimatedTermCount
                    iterator.remove()
                }
            }
        }
    }

    internal suspend fun updateLastAccessed(memories: List<AssistantMemory>) {
        if (memories.isEmpty()) return
        try {
            val accessedAt = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                memories.asSequence()
                    .map { it.id }
                    .filter { it > 0 }
                    .distinct()
                    .chunked(500)
                    .forEach { ids -> memoryDAO.updateLastAccessedAt(ids, accessedAt) }
                memories.asSequence()
                    .map { it.id }
                    .filter { it < 0 }
                    .map { -it }
                    .distinct()
                    .chunked(500)
                    .forEach { ids -> chatEpisodeDAO.updateLastAccessedAt(ids, accessedAt) }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Log.w(TAG, "Failed to update memory access time: ${error.message}", error)
        }
    }

    private suspend fun getKeywordIndex(
        cacheKey: String,
        rows: List<MemoryRetrievalRow>,
        checkCancelled: () -> Unit,
    ): KeywordMemoryIndex = keywordIndexMutex.withLock {
        val cached = keywordIndexCache[cacheKey]
        if (
            cached != null &&
            cached.rows == rows &&
            cached.index.tokenizerRevision == keywordTokenizer.revision
        ) {
            return@withLock cached.index
        }

        val index = withContext(Dispatchers.Default) {
            KeywordMemoryIndex.build(rows, keywordTokenizer, checkCancelled)
        }
        keywordIndexCache.remove(cacheKey)?.let { keywordIndexCacheTermCount -= it.index.estimatedTermCount }
        // Do not retain an all-empty snapshot: its term budget is zero, but its rows could still
        // grow with many assistants and otherwise bypass the LRU bound.
        if (index.estimatedTermCount in 1..KEYWORD_INDEX_TERM_BUDGET) {
            keywordIndexCache[cacheKey] = KeywordIndexCacheEntry(rows = rows, index = index)
            keywordIndexCacheTermCount += index.estimatedTermCount
            while (keywordIndexCacheTermCount > KEYWORD_INDEX_TERM_BUDGET && keywordIndexCache.isNotEmpty()) {
                val iterator = keywordIndexCache.entries.iterator()
                val eldest = iterator.next()
                keywordIndexCacheTermCount -= eldest.value.index.estimatedTermCount
                iterator.remove()
            }
        }
        index
    }

    /**
     * Load an existing embedding without issuing a network request.
     */
    private suspend fun getExistingEmbedding(
        memoryId: Int,
        memoryType: Int,
        assistantId: String,
        existingEmbedding: String? = null,
        existingModelId: String? = null,
        // 非空表示调用方已批量查过嵌入缓存表；未命中时不再发单点查询
        preloadedCache: Map<Int, EmbeddingCacheEntity>? = null,
        modelId: String,
    ): List<Float>? {
        val cached = if (preloadedCache != null) {
            preloadedCache[memoryId]
        } else {
            embeddingCacheDAO.getEmbedding(memoryId, memoryType, modelId)
        }
        if (cached != null) {
            val decoded = runCatching {
                JsonInstant.decodeFromString<List<Float>>(cached.embedding)
            }.getOrNull()
            if (decoded != null) return decoded
        }

        if (existingEmbedding != null && existingModelId == modelId) {
            return try {
                val emb = JsonInstant.decodeFromString<List<Float>>(existingEmbedding)
                embeddingCacheDAO.insertEmbedding(
                    EmbeddingCacheEntity(
                        memoryId = memoryId,
                        memoryType = memoryType,
                        modelId = modelId,
                        embedding = existingEmbedding
                    )
                )
                emb
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                scheduleEmbeddingBackfillIfNeeded(
                    assistantId = assistantId,
                    includeCore = memoryType == MemoryType.CORE,
                    includeEpisodes = memoryType == MemoryType.EPISODIC,
                    force = true,
                )
                null
            }
        }
        return null
    }

    /**
     * 批量读取嵌入缓存。调用方通常已按 [EMBEDDING_SCORING_CHUNK_SIZE] 分块，
     * 此处仍防御性地按 500 切块，独立保证不超过 SQLite 单条语句绑定变量上限（约 999）。
     */
    private suspend fun batchLoadEmbeddingCache(
        memoryIds: List<Int>,
        memoryType: Int,
        modelId: String,
    ): Map<Int, EmbeddingCacheEntity> {
        if (memoryIds.isEmpty()) return emptyMap()
        return memoryIds.chunked(500)
            .flatMap { chunk -> embeddingCacheDAO.getEmbeddings(chunk, memoryType, modelId) }
            .associateBy { it.memoryId }
    }

    /**
     * Check if an embedding exists in cache for the current model.
     */
    suspend fun hasEmbeddingForCurrentModel(memoryId: Int, memoryType: Int, assistantId: String): Boolean {
        val modelId = embeddingService.getEmbeddingModelId(assistantId)
        return embeddingCacheDAO.hasEmbedding(memoryId, memoryType, modelId)
    }

    suspend fun deleteMemoriesOfAssistant(assistantId: String) {
        val memoryIds = memoryDAO.getMemoriesOfAssistant(assistantId).map { it.id }
        val episodeIds = chatEpisodeDAO.getEpisodesOfAssistant(assistantId).map { it.id }
        memoryDAO.deleteMemoriesOfAssistant(assistantId)
        chatEpisodeDAO.deleteEpisodesOfAssistant(assistantId)
        if (memoryIds.isNotEmpty()) {
            memoryIds.chunked(500).forEach { ids -> embeddingCacheDAO.deleteByMemoryIds(MemoryType.CORE, ids) }
        }
        if (episodeIds.isNotEmpty()) {
            episodeIds.chunked(500).forEach { ids -> embeddingCacheDAO.deleteByMemoryIds(MemoryType.EPISODIC, ids) }
        }
        invalidateKeywordMemoryCache(assistantId)
    }

    suspend fun updateCoreMemory(
        id: Int,
        content: String,
        pinned: Boolean,
        generateEmbedding: Boolean = true,
    ): AssistantMemory {
        val memory = memoryDAO.getMemoryById(id) ?: error("Memory not found")
        val normalizedContent = content.trim()
        require(normalizedContent.isNotEmpty()) { "Memory content cannot be blank" }
        val contentChanged = memory.content != normalizedContent
        val newMemory = memory.copy(
            content = normalizedContent,
            pinned = pinned,
            embedding = if (contentChanged) null else memory.embedding,
            embeddingModelId = if (contentChanged) null else memory.embeddingModelId,
        )
        memoryDAO.updateMemory(newMemory)

        if (contentChanged) {
            embeddingCacheDAO.deleteByMemoryId(id, MemoryType.CORE)
            if (generateEmbedding) {
                scheduleEmbeddingBackfillIfNeeded(
                    assistantId = memory.assistantId,
                    includeCore = true,
                    includeEpisodes = false,
                    force = true,
                )
            }
        }

        return AssistantMemory(
            id = newMemory.id,
            content = newMemory.content,
            type = newMemory.type,
            hasEmbedding = newMemory.embedding != null,
            timestamp = newMemory.createdAt,
            pinned = newMemory.pinned,
        )
    }

    suspend fun updateContent(
        id: Int,
        content: String,
        generateEmbedding: Boolean = true,
    ): AssistantMemory {
        val memory = memoryDAO.getMemoryById(id) ?: error("Memory not found")
        val normalizedContent = content.trim()
        require(normalizedContent.isNotEmpty()) { "Memory content cannot be blank" }
        val newMemory = memory.copy(
            content = normalizedContent,
            embedding = null,
            embeddingModelId = null,
        ) // Invalidate embedding
        memoryDAO.updateMemory(newMemory)

        // Invalidate cache
        embeddingCacheDAO.deleteByMemoryId(id, MemoryType.CORE)
        if (generateEmbedding) {
            scheduleEmbeddingBackfillIfNeeded(
                assistantId = memory.assistantId,
                includeCore = true,
                includeEpisodes = false,
                force = true,
            )
        }

        return AssistantMemory(
            id = newMemory.id,
            content = newMemory.content,
            type = newMemory.type,
            hasEmbedding = false,
            timestamp = newMemory.createdAt,
            pinned = newMemory.pinned,
        )
    }

    suspend fun updateEpisodeContent(
        id: Int,
        content: String,
        generateEmbedding: Boolean = true,
    ): AssistantMemory {
        val episode = chatEpisodeDAO.getEpisodeById(id) ?: error("Episode not found")
        val normalizedContent = content.trim()
        require(normalizedContent.isNotEmpty()) { "Memory content cannot be blank" }
        val newEpisode = episode.copy(
            content = normalizedContent,
            embedding = null,
            embeddingModelId = null,
        ) // Invalidate embedding
        chatEpisodeDAO.insertEpisode(newEpisode)

        // Invalidate cache
        embeddingCacheDAO.deleteByMemoryId(id, MemoryType.EPISODIC)
        if (generateEmbedding) {
            scheduleEmbeddingBackfillIfNeeded(
                assistantId = episode.assistantId,
                includeCore = false,
                includeEpisodes = true,
                force = true,
            )
        }

        return AssistantMemory(
            id = -newEpisode.id,
            content = newEpisode.content,
            type = MemoryType.EPISODIC,
            hasEmbedding = false,
            timestamp = newEpisode.startTime,
            significance = newEpisode.significance
        )
    }

    suspend fun addMemory(
        assistantId: String,
        content: String,
        pinned: Boolean = false,
        generateEmbedding: Boolean = true,
    ): AssistantMemory {
        val normalizedContent = content.trim()
        require(normalizedContent.isNotEmpty()) { "Memory content cannot be blank" }

        val embeddingResult = if (generateEmbedding) {
            try {
                embeddingService.embedWithModelId(
                    text = normalizedContent,
                    assistantId = assistantId,
                    source = AIRequestSource.MEMORY_EMBEDDING,
                )
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        } else {
            null
        }

        val embedding = embeddingResult?.embeddings?.firstOrNull()
        val entity = MemoryEntity(
            assistantId = assistantId,
            content = normalizedContent,
            embedding = embedding?.let { JsonInstant.encodeToString(it) },
            embeddingModelId = embedding?.let { embeddingResult?.modelId },
            type = MemoryType.CORE,
            pinned = pinned,
            createdAt = System.currentTimeMillis(),
            lastAccessedAt = System.currentTimeMillis()
        )
        
        val id = memoryDAO.insertMemory(entity)
        
        // Add to cache immediately if available
        if (embedding != null && embeddingResult != null) {
             embeddingCacheDAO.insertEmbedding(
                EmbeddingCacheEntity(
                    memoryId = id.toInt(),
                    memoryType = MemoryType.CORE,
                    modelId = embeddingResult.modelId,
                    embedding = JsonInstant.encodeToString(embedding)
                )
             )
        } else if (generateEmbedding) {
            scheduleEmbeddingBackfillIfNeeded(
                assistantId = assistantId,
                includeCore = true,
                includeEpisodes = false,
                force = true,
            )
        }

        return AssistantMemory(
            id = id.toInt(),
            content = normalizedContent,
            type = MemoryType.CORE,
            hasEmbedding = embedding != null,
            embeddingModelId = embedding?.let { embeddingResult?.modelId },
            pinned = pinned,
        )
    }

    suspend fun deleteMemory(id: Int) {
        memoryDAO.deleteMemory(id)
        embeddingCacheDAO.deleteByMemoryId(id, MemoryType.CORE)
    }

    suspend fun deleteEpisodeMemory(id: Int) {
        chatEpisodeDAO.deleteEpisode(id)
        embeddingCacheDAO.deleteByMemoryId(id, MemoryType.EPISODIC)
    }

    /**
     * Retrieve relevant memories with scores for debugging
     */
    suspend fun retrieveRelevantMemoriesWithScores(assistantId: String, query: String, limit: Int = 5, similarityThreshold: Float = 0.5f): List<Pair<AssistantMemory, Float>> {
        return retrieveRelevantMemoriesWithScores(
            assistantId = assistantId,
            query = query,
            limit = limit,
            similarityThreshold = similarityThreshold,
            includeCore = true,
            includeEpisodes = true
        )
    }

    suspend fun retrieveRelevantMemories(
        assistantId: String,
        query: String,
        limit: Int = 5,
        similarityThreshold: Float = 0.5f,
        includeCore: Boolean = true,
        includeEpisodes: Boolean = true
    ): List<AssistantMemory> {
        return retrieveRelevantMemoriesWithScores(
            assistantId, query, limit, similarityThreshold, includeCore, includeEpisodes
        ).map { it.first }
    }

    suspend fun retrieveRelevantMemoriesByEmbedding(
        assistantId: String,
        queryEmbedding: List<Float>,
        limit: Int = 5,
        similarityThreshold: Float = 0.5f,
        includeCore: Boolean = true,
        includeEpisodes: Boolean = true
    ): List<AssistantMemory> {
        return retrieveRelevantMemoriesWithScoresByEmbedding(
            assistantId = assistantId,
            queryEmbedding = queryEmbedding,
            limit = limit,
            similarityThreshold = similarityThreshold,
            includeCore = includeCore,
            includeEpisodes = includeEpisodes,
        ).map { it.first }
    }

    fun scheduleEmbeddingBackfillIfNeeded(
        assistantId: String,
        includeCore: Boolean = true,
        includeEpisodes: Boolean = true,
        force: Boolean = false,
    ) {
        if (!includeCore && !includeEpisodes) return
        val checkKey = "$assistantId:$includeCore:$includeEpisodes:$force"
        if (!pendingBackfillChecks.add(checkKey)) return

        appScope.launch(Dispatchers.IO) {
            try {
                val shouldEnqueue = force || memoryDAO.getPendingEmbeddingCount(
                    assistantId = assistantId,
                    modelId = embeddingService.getEmbeddingModelId(assistantId),
                    includeCore = includeCore,
                    includeEpisodes = includeEpisodes,
                ) > 0
                if (shouldEnqueue) {
                    embeddingBackfillScheduler.enqueue(
                        assistantId = assistantId,
                        includeCore = includeCore,
                        includeEpisodes = includeEpisodes,
                    )
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                Log.w(TAG, "Failed to schedule memory embedding backfill: ${error.message}", error)
            } finally {
                pendingBackfillChecks.remove(checkKey)
            }
        }
    }

    suspend fun retrieveRelevantMemoriesWithScoresByEmbedding(
        assistantId: String,
        queryEmbedding: List<Float>,
        limit: Int = 5,
        similarityThreshold: Float = 0.5f,
        includeCore: Boolean = true,
        includeEpisodes: Boolean = true,
        recordAccess: Boolean = true,
    ): List<Pair<AssistantMemory, Float>> {
        data class ScoredCandidate(
            val item: Any,
            val score: Float,
            val isMemory: Boolean,
            val isPinned: Boolean,
        ) {
            val key: String = when {
                isMemory -> "m:${(item as MemoryEntity).id}"
                else -> "e:${(item as ChatEpisodeEntity).id}"
            }
        }

        val limitInt = limit.coerceAtLeast(0)

        // Get both core memories and episodes
        val memories = if (includeCore) memoryDAO.getMemoriesOfAssistant(assistantId) else emptyList()
        val episodes = if (includeEpisodes) chatEpisodeDAO.getEpisodesOfAssistant(assistantId) else emptyList()

        // 批量预取嵌入缓存：此前每条记忆各发一次单点查询（N+1），记忆多时发消息前会明显停顿。
        // 按块「预取 + 打分」以约束缓存副本的额外驻留（每行携带十几 KB 向量 JSON）；
        // 注意上方实体列表本身仍是整表加载（既有行为），检索峰值内存的主导项不在此处
        val embeddingModelId = embeddingService.getEmbeddingModelId(assistantId)

        val scoredCore = memories.chunked(EMBEDDING_SCORING_CHUNK_SIZE).flatMap { memoryChunk ->
            val cachedMemoryEmbeddings =
                batchLoadEmbeddingCache(memoryChunk.map { it.id }, MemoryType.CORE, embeddingModelId)
            memoryChunk.mapNotNull { memory ->
                currentCoroutineContext().ensureActive()
                if (memory.pinned) {
                    val score = try {
                        val embedding = getExistingEmbedding(
                            memoryId = memory.id,
                            memoryType = MemoryType.CORE,
                            assistantId = assistantId,
                            existingEmbedding = memory.embedding,
                            existingModelId = memory.embeddingModelId,
                            preloadedCache = cachedMemoryEmbeddings,
                            modelId = embeddingModelId,
                        )
                        if (embedding != null) {
                            val similarity = VectorEngine.cosineSimilarity(queryEmbedding, embedding)
                            similarity * 1.05f
                        } else {
                            1f
                        }
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        1f
                    }
                    ScoredCandidate(item = memory, score = score, isMemory = true, isPinned = true)
                } else {
                    val embedding = getExistingEmbedding(
                        memoryId = memory.id,
                        memoryType = MemoryType.CORE,
                        assistantId = assistantId,
                        existingEmbedding = memory.embedding,
                        existingModelId = memory.embeddingModelId,
                        preloadedCache = cachedMemoryEmbeddings,
                        modelId = embeddingModelId,
                    ) ?: return@mapNotNull null

                    val similarity = VectorEngine.cosineSimilarity(queryEmbedding, embedding)
                    val score = similarity * 1.05f
                    if (score >= similarityThreshold) {
                        ScoredCandidate(item = memory, score = score, isMemory = true, isPinned = false)
                    } else {
                        null
                    }
                }
            }
        }

        val scoredEpisodes = episodes.chunked(EMBEDDING_SCORING_CHUNK_SIZE).flatMap { episodeChunk ->
            val cachedEpisodeEmbeddings =
                batchLoadEmbeddingCache(episodeChunk.map { it.id }, MemoryType.EPISODIC, embeddingModelId)
            episodeChunk.mapNotNull { episode ->
                currentCoroutineContext().ensureActive()
                val embedding = getExistingEmbedding(
                    memoryId = episode.id,
                    memoryType = MemoryType.EPISODIC,
                    assistantId = assistantId,
                    existingEmbedding = episode.embedding,
                    existingModelId = episode.embeddingModelId,
                    preloadedCache = cachedEpisodeEmbeddings,
                    modelId = embeddingModelId,
                ) ?: return@mapNotNull null

                val similarity = VectorEngine.cosineSimilarity(queryEmbedding, embedding)

                // Calculate Recency Score (7 days half-life)
                val ageInMillis = System.currentTimeMillis() - episode.startTime
                val ageInDays = ageInMillis / (1000.0 * 60 * 60 * 24)
                val recency = (1.0 / (1.0 + (ageInDays / 7.0))).toFloat()

                val score = (similarity * 0.7f) + (recency * 0.3f)

                if (score >= similarityThreshold) {
                    ScoredCandidate(item = episode, score = score, isMemory = false, isPinned = false)
                } else {
                    null
                }
            }
        }

        val pinnedCandidates = scoredCore.filter { it.isPinned }
        val unpinnedCandidates = (scoredCore.filterNot { it.isPinned } + scoredEpisodes)
            .sortedByDescending { it.score }
            .take(limitInt)

        val mergedByKey = LinkedHashMap<String, ScoredCandidate>()
        (pinnedCandidates + unpinnedCandidates).forEach { candidate ->
            mergedByKey.putIfAbsent(candidate.key, candidate)
        }

        fun ScoredCandidate.timestampForSort(): Long = if (isMemory) {
            (item as MemoryEntity).createdAt
        } else {
            (item as ChatEpisodeEntity).startTime
        }

        fun ScoredCandidate.idForSort(): Int = if (isMemory) {
            (item as MemoryEntity).id
        } else {
            (item as ChatEpisodeEntity).id
        }

        val finalCandidates = mergedByKey.values
            .sortedWith { left, right ->
                when {
                    left.isPinned != right.isPinned -> if (left.isPinned) -1 else 1
                    left.isPinned -> compareValuesBy(left, right, { it.timestampForSort() }, { it.idForSort() })
                    else -> compareValuesBy(right, left, { it.score }, { it.timestampForSort() }, { it.idForSort() })
                }
            }

        // Update lastAccessedAt for included items (pinned + top-k)
        // 批量写回：单条 UPDATE 替代逐行整行重写（旧写法每行重写含 embedding 的整行、各自提交
        // 一次事务，且每次写入都会让记忆列表的 Flow 订阅方全量重查一遍）
        if (recordAccess) {
            updateLastAccessed(finalCandidates.map { candidate ->
                if (candidate.isMemory) {
                    AssistantMemory(id = (candidate.item as MemoryEntity).id, type = MemoryType.CORE)
                } else {
                    AssistantMemory(id = -(candidate.item as ChatEpisodeEntity).id, type = MemoryType.EPISODIC)
                }
            })
        }

        return finalCandidates.mapNotNull { candidate ->
            if (candidate.isMemory) {
                val memory = candidate.item as MemoryEntity
                Pair(
                    AssistantMemory(
                        id = memory.id,
                        content = memory.content,
                        type = memory.type,
                        hasEmbedding = memory.embedding != null,
                        embeddingModelId = memory.embeddingModelId,
                        timestamp = memory.createdAt,
                        pinned = memory.pinned,
                    ),
                    candidate.score
                )
            } else {
                val episode = candidate.item as ChatEpisodeEntity
                Pair(
                    AssistantMemory(
                        id = -episode.id,
                        content = episode.content,
                        type = MemoryType.EPISODIC,
                        hasEmbedding = episode.embedding != null,
                        embeddingModelId = episode.embeddingModelId,
                        timestamp = episode.startTime,
                        significance = episode.significance,
                    ),
                    candidate.score
                )
            }
        }
    }

    suspend fun retrieveRelevantMemoriesWithScores(
        assistantId: String,
        query: String,
        limit: Int = 5,
        similarityThreshold: Float = 0.5f,
        includeCore: Boolean = true,
        includeEpisodes: Boolean = true
    ): List<Pair<AssistantMemory, Float>> {
        scheduleEmbeddingBackfillIfNeeded(
            assistantId = assistantId,
            includeCore = includeCore,
            includeEpisodes = includeEpisodes,
        )
        return try {
            withTimeout(embeddingService.getRetrievalTimeoutMillis()) {
                retrieveRelevantMemoriesWithScoresInternal(
                    assistantId = assistantId,
                    query = query,
                    limit = limit,
                    similarityThreshold = similarityThreshold,
                    includeCore = includeCore,
                    includeEpisodes = includeEpisodes,
                )
            }
        } catch (_: TimeoutCancellationException) {
            getPinnedMemoriesWithScores(assistantId, includeCore)
        }
    }

    private suspend fun retrieveRelevantMemoriesWithScoresInternal(
        assistantId: String,
        query: String,
        limit: Int,
        similarityThreshold: Float,
        includeCore: Boolean,
        includeEpisodes: Boolean,
    ): List<Pair<AssistantMemory, Float>> {
        data class ScoredCandidate(
            val item: Any,
            val score: Float,
            val isMemory: Boolean,
            val isPinned: Boolean,
        ) {
            val key: String = when {
                isMemory -> "m:${(item as MemoryEntity).id}"
                else -> "e:${(item as ChatEpisodeEntity).id}"
            }
        }

        val queryEmbedding = try {
            embeddingService.embed(
                text = query,
                assistantId = assistantId,
                source = AIRequestSource.MEMORY_RETRIEVAL,
                timeoutPolicy = EmbeddingTimeoutPolicy.RETRIEVAL,
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            e.printStackTrace()
            return getPinnedMemoriesWithScores(assistantId, includeCore)
        }

        val limitInt = limit.coerceAtLeast(0)

        // Get both core memories and episodes
        val memories = if (includeCore) memoryDAO.getMemoriesOfAssistant(assistantId) else emptyList()
        val episodes = if (includeEpisodes) chatEpisodeDAO.getEpisodesOfAssistant(assistantId) else emptyList()

        // 批量预取嵌入缓存：此前每条记忆各发一次单点查询（N+1），记忆多时发消息前会明显停顿。
        // 按块「预取 + 打分」以约束缓存副本的额外驻留（每行携带十几 KB 向量 JSON）；
        // 注意上方实体列表本身仍是整表加载（既有行为），检索峰值内存的主导项不在此处
        val embeddingModelId = embeddingService.getEmbeddingModelId(assistantId)

        val scoredCore = memories.chunked(EMBEDDING_SCORING_CHUNK_SIZE).flatMap { memoryChunk ->
            val cachedMemoryEmbeddings =
                batchLoadEmbeddingCache(memoryChunk.map { it.id }, MemoryType.CORE, embeddingModelId)
            memoryChunk.mapNotNull { memory ->
                currentCoroutineContext().ensureActive()
                if (memory.pinned) {
                    val score = try {
                        val embedding = getExistingEmbedding(
                            memoryId = memory.id,
                            memoryType = MemoryType.CORE,
                            assistantId = assistantId,
                            existingEmbedding = memory.embedding,
                            existingModelId = memory.embeddingModelId,
                            preloadedCache = cachedMemoryEmbeddings,
                            modelId = embeddingModelId,
                        )
                        if (embedding != null) {
                            val similarity = VectorEngine.cosineSimilarity(queryEmbedding, embedding)
                            similarity * 1.05f
                        } else {
                            1f
                        }
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        1f
                    }
                    ScoredCandidate(item = memory, score = score, isMemory = true, isPinned = true)
                } else {
                    val embedding = getExistingEmbedding(
                        memoryId = memory.id,
                        memoryType = MemoryType.CORE,
                        assistantId = assistantId,
                        existingEmbedding = memory.embedding,
                        existingModelId = memory.embeddingModelId,
                        preloadedCache = cachedMemoryEmbeddings,
                        modelId = embeddingModelId,
                    ) ?: return@mapNotNull null

                    val similarity = VectorEngine.cosineSimilarity(queryEmbedding, embedding)
                    val score = similarity * 1.05f
                    if (score >= similarityThreshold) {
                        ScoredCandidate(item = memory, score = score, isMemory = true, isPinned = false)
                    } else {
                        null
                    }
                }
            }
        }

        val scoredEpisodes = episodes.chunked(EMBEDDING_SCORING_CHUNK_SIZE).flatMap { episodeChunk ->
            val cachedEpisodeEmbeddings =
                batchLoadEmbeddingCache(episodeChunk.map { it.id }, MemoryType.EPISODIC, embeddingModelId)
            episodeChunk.mapNotNull { episode ->
                currentCoroutineContext().ensureActive()
                val embedding = getExistingEmbedding(
                    memoryId = episode.id,
                    memoryType = MemoryType.EPISODIC,
                    assistantId = assistantId,
                    existingEmbedding = episode.embedding,
                    existingModelId = episode.embeddingModelId,
                    preloadedCache = cachedEpisodeEmbeddings,
                    modelId = embeddingModelId,
                ) ?: return@mapNotNull null

                val similarity = VectorEngine.cosineSimilarity(queryEmbedding, embedding)

                // Calculate Recency Score (7 days half-life)
                val ageInMillis = System.currentTimeMillis() - episode.startTime
                val ageInDays = ageInMillis / (1000.0 * 60 * 60 * 24)
                val recency = (1.0 / (1.0 + (ageInDays / 7.0))).toFloat()

                val score = (similarity * 0.7f) + (recency * 0.3f)

                if (score >= similarityThreshold) {
                    ScoredCandidate(item = episode, score = score, isMemory = false, isPinned = false)
                } else {
                    null
                }
            }
        }

        val pinnedCandidates = scoredCore.filter { it.isPinned }
        val unpinnedCandidates = (scoredCore.filterNot { it.isPinned } + scoredEpisodes)
            .sortedByDescending { it.score }
            .take(limitInt)

        val mergedByKey = LinkedHashMap<String, ScoredCandidate>()
        (pinnedCandidates + unpinnedCandidates).forEach { candidate ->
            mergedByKey.putIfAbsent(candidate.key, candidate)
        }

        fun ScoredCandidate.timestampForSort(): Long = if (isMemory) {
            (item as MemoryEntity).createdAt
        } else {
            (item as ChatEpisodeEntity).startTime
        }

        fun ScoredCandidate.idForSort(): Int = if (isMemory) {
            (item as MemoryEntity).id
        } else {
            (item as ChatEpisodeEntity).id
        }

        val finalCandidates = mergedByKey.values
            .sortedWith { left, right ->
                when {
                    left.isPinned != right.isPinned -> if (left.isPinned) -1 else 1
                    left.isPinned -> compareValuesBy(left, right, { it.timestampForSort() }, { it.idForSort() })
                    else -> compareValuesBy(right, left, { it.score }, { it.timestampForSort() }, { it.idForSort() })
                }
            }

        // Update lastAccessedAt for included items (pinned + top-k)
        // 批量写回：单条 UPDATE 替代逐行整行重写（旧写法每行重写含 embedding 的整行、各自提交
        // 一次事务，且每次写入都会让记忆列表的 Flow 订阅方全量重查一遍）
        val accessedAt = System.currentTimeMillis()
        finalCandidates.filter { it.isMemory }
            .map { (it.item as MemoryEntity).id }
            .chunked(500)
            .forEach { memoryDAO.updateLastAccessedAt(it, accessedAt) }
        finalCandidates.filterNot { it.isMemory }
            .map { (it.item as ChatEpisodeEntity).id }
            .chunked(500)
            .forEach { chatEpisodeDAO.updateLastAccessedAt(it, accessedAt) }

        return finalCandidates.mapNotNull { candidate ->
            if (candidate.isMemory) {
                val memory = candidate.item as MemoryEntity
                Pair(
                    AssistantMemory(
                        id = memory.id,
                        content = memory.content,
                        type = memory.type,
                        hasEmbedding = memory.embedding != null,
                        embeddingModelId = memory.embeddingModelId,
                        timestamp = memory.createdAt,
                        pinned = memory.pinned,
                    ),
                    candidate.score
                )
            } else {
                val episode = candidate.item as ChatEpisodeEntity
                Pair(
                    AssistantMemory(
                        id = -episode.id,
                        content = episode.content,
                        type = MemoryType.EPISODIC,
                        hasEmbedding = episode.embedding != null,
                        embeddingModelId = episode.embeddingModelId,
                        timestamp = episode.startTime,
                        significance = episode.significance,
                    ),
                    candidate.score
                )
            }
        }
    }

    private suspend fun getPinnedMemoriesWithScores(
        assistantId: String,
        includeCore: Boolean,
    ): List<Pair<AssistantMemory, Float>> {
        if (!includeCore) return emptyList()
        val pinnedMemories = memoryDAO.getPinnedMemoriesOfAssistant(assistantId)
        val accessedAt = System.currentTimeMillis()
        pinnedMemories.map { it.id }
            .chunked(500)
            .forEach { memoryDAO.updateLastAccessedAt(it, accessedAt) }
        return pinnedMemories.map { memory ->
            AssistantMemory(
                id = memory.id,
                content = memory.content,
                type = memory.type,
                hasEmbedding = memory.embedding != null,
                embeddingModelId = memory.embeddingModelId,
                timestamp = memory.createdAt,
                pinned = memory.pinned,
            ) to 1f
        }
    }

    /**
     * Regenerate embeddings for memories and episodes that need it.
     * Only processes memories that:
     * - Have no embedding
     * - Have an embedding from a different model
     * 
     * @param assistantId The assistant ID to regenerate embeddings for
     * @return Pair of (successCount, failureCount)
     */
    suspend fun regenerateEmbeddings(
        assistantId: String,
        onProgress: (Int, Int) -> Unit
    ): Pair<Int, Int> {
        val allMemories = memoryDAO.getMemoriesOfAssistant(assistantId)
        val allEpisodes = chatEpisodeDAO.getEpisodesOfAssistant(assistantId)
        
        // Get current embedding model ID
        val currentModelId = embeddingService.getEmbeddingModelId(assistantId)
        
        // Filter to only memories that need embedding
        val memoriesNeedingEmbedding = allMemories.filter {
            needsEmbeddingBackfill(it.content, it.embedding, it.embeddingModelId, currentModelId)
        }
        val episodesNeedingEmbedding = allEpisodes.filter {
            needsEmbeddingBackfill(it.content, it.embedding, it.embeddingModelId, currentModelId)
        }
        
        val total = memoriesNeedingEmbedding.size + episodesNeedingEmbedding.size
        var current = 0
        var successCount = 0
        var failureCount = 0

        onProgress(0, total)
        if (total == 0) return 0 to 0

        // Process Core Memories that need embedding
        memoriesNeedingEmbedding.forEach { memory ->
            current++
            try {
                val embedding = embeddingService.embed(
                    text = memory.content,
                    assistantId = assistantId,
                    source = AIRequestSource.MEMORY_EMBEDDING,
                )
                val embeddingJson = JsonInstant.encodeToString(embedding)
                // Store in entity for backward compatibility
                memoryDAO.updateMemory(memory.copy(embedding = embeddingJson, embeddingModelId = currentModelId))
                // Store in cache for model-based persistence
                embeddingCacheDAO.insertEmbedding(
                    EmbeddingCacheEntity(
                        memoryId = memory.id,
                        memoryType = MemoryType.CORE,
                        modelId = currentModelId,
                        embedding = embeddingJson
                    )
                )
                successCount++
            } catch (e: Exception) {
                e.printStackTrace()
                failureCount++
            }
            onProgress(current, total)
        }

        // Process Episodes that need embedding
        episodesNeedingEmbedding.forEach { episode ->
            current++
            try {
                val embedding = embeddingService.embed(
                    text = episode.content,
                    assistantId = assistantId,
                    source = AIRequestSource.MEMORY_EMBEDDING,
                )
                val embeddingJson = JsonInstant.encodeToString(embedding)
                // Store in entity for backward compatibility
                chatEpisodeDAO.insertEpisode(episode.copy(embedding = embeddingJson, embeddingModelId = currentModelId))
                // Store in cache for model-based persistence
                embeddingCacheDAO.insertEmbedding(
                    EmbeddingCacheEntity(
                        memoryId = episode.id,
                        memoryType = MemoryType.EPISODIC,
                        modelId = currentModelId,
                        embedding = embeddingJson
                    )
                )
                successCount++
            } catch (e: Exception) {
                e.printStackTrace()
                failureCount++
            }
            onProgress(current, total)
        }
        
        return successCount to failureCount
    }

    /**
     * Embed only memories that are missing embeddings or have wrong model.
     * Called during consolidation to fix any gaps without regenerating everything.
     * 
     * @param assistantId The assistant ID to fix embeddings for
     * @return Pair of (successCount, failureCount)
     */
    suspend fun embedMissingMemories(
        assistantId: String,
        includeCore: Boolean = true,
        includeEpisodes: Boolean = true,
    ): Pair<Int, Int> = embeddingBackfillMutex.withLock {
        val memories = if (includeCore) memoryDAO.getMemoriesOfAssistant(assistantId) else emptyList()
        val episodes = if (includeEpisodes) chatEpisodeDAO.getEpisodesOfAssistant(assistantId) else emptyList()
        val currentModelId = embeddingService.getEmbeddingModelId(assistantId)

        val targets = memories.mapNotNull { memory ->
            if (needsEmbeddingBackfill(memory.content, memory.embedding, memory.embeddingModelId, currentModelId)) {
                EmbeddingBackfillTarget(
                    id = memory.id,
                    memoryType = MemoryType.CORE,
                    content = memory.content,
                    originalModelId = memory.embeddingModelId,
                )
            } else {
                null
            }
        } + episodes.mapNotNull { episode ->
            if (needsEmbeddingBackfill(episode.content, episode.embedding, episode.embeddingModelId, currentModelId)) {
                EmbeddingBackfillTarget(
                    id = episode.id,
                    memoryType = MemoryType.EPISODIC,
                    content = episode.content,
                    originalModelId = episode.embeddingModelId,
                )
            } else {
                null
            }
        }
        if (targets.isEmpty()) return@withLock 0 to 0

        val cachedEmbeddings = targets.groupBy { it.memoryType }
            .flatMap { (memoryType, typedTargets) ->
                batchLoadEmbeddingCache(
                    memoryIds = typedTargets.map { it.id },
                    memoryType = memoryType,
                    modelId = currentModelId,
                ).map { (memoryId, cache) -> (memoryType to memoryId) to cache }
            }
            .toMap()

        var successCount = 0
        var failureCount = 0

        suspend fun persist(
            target: EmbeddingBackfillTarget,
            embedding: List<Float>,
            modelId: String,
        ) {
            val embeddingJson = JsonInstant.encodeToString(embedding)
            runCatching {
                persistBackfilledEmbedding(
                    target = target,
                    embeddingJson = embeddingJson,
                    modelId = modelId,
                )
            }.onSuccess { persisted ->
                if (persisted) successCount++
            }.onFailure { error ->
                if (error is CancellationException) throw error
                failureCount++
            }
        }

        val targetsNeedingRequest = mutableListOf<EmbeddingBackfillTarget>()
        targets.forEach { target ->
            val cached = cachedEmbeddings[target.memoryType to target.id]
                ?.embedding
                ?.let { encoded ->
                    runCatching { JsonInstant.decodeFromString<List<Float>>(encoded) }.getOrNull()
                }
            if (cached != null) {
                persist(target, cached, currentModelId)
            } else {
                targetsNeedingRequest.add(target)
            }
        }

        suspend fun requestAndPersist(chunk: List<EmbeddingBackfillTarget>) {
            val batchResult = try {
                embeddingService.embedBatch(
                    texts = chunk.map { it.content },
                    assistantId = assistantId,
                    source = AIRequestSource.MEMORY_EMBEDDING,
                ).also { result ->
                    check(result.embeddings.size == chunk.size) {
                        "Embedding batch size mismatch: expected ${chunk.size}, got ${result.embeddings.size}"
                    }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (chunk.size > 1 && error.isSplittableEmbeddingBatchFailure()) {
                    val midpoint = chunk.size / 2
                    requestAndPersist(chunk.subList(0, midpoint))
                    requestAndPersist(chunk.subList(midpoint, chunk.size))
                } else {
                    failureCount += chunk.size
                }
                return
            }

            chunk.zip(batchResult.embeddings).forEach { (target, embedding) ->
                persist(target, embedding, batchResult.modelId)
            }
        }

        targetsNeedingRequest.chunked(EMBEDDING_BACKFILL_BATCH_SIZE).forEach { chunk ->
            requestAndPersist(chunk)
        }

        successCount to failureCount
    }

    private suspend fun persistBackfilledEmbedding(
        target: EmbeddingBackfillTarget,
        embeddingJson: String,
        modelId: String,
    ): Boolean {
        return database.withTransaction {
            val updated = when (target.memoryType) {
                MemoryType.CORE -> memoryDAO.updateEmbeddingIfContentMatches(
                    id = target.id,
                    expectedContent = target.content,
                    expectedModelId = target.originalModelId,
                    embedding = embeddingJson,
                    modelId = modelId,
                )

                MemoryType.EPISODIC -> chatEpisodeDAO.updateEmbeddingIfContentMatches(
                    id = target.id,
                    expectedContent = target.content,
                    expectedModelId = target.originalModelId,
                    embedding = embeddingJson,
                    modelId = modelId,
                )

                else -> 0
            }
            if (updated <= 0) return@withTransaction false

            embeddingCacheDAO.insertEmbedding(
                EmbeddingCacheEntity(
                    memoryId = target.id,
                    memoryType = target.memoryType,
                    modelId = modelId,
                    embedding = embeddingJson,
                )
            )
            true
        }
    }

    /**
     * Count how many memories need embedding (no embedding or wrong model).
     * Used to determine if the regenerate button should be shown.
     */
    suspend fun countMemoriesNeedingEmbedding(assistantId: String): Int {
        val memories = memoryDAO.getMemoriesOfAssistant(assistantId)
        val episodes = chatEpisodeDAO.getEpisodesOfAssistant(assistantId)
        val currentModelId = embeddingService.getEmbeddingModelId(assistantId)
        
        val memoriesNeedingEmbedding = memories.count {
            needsEmbeddingBackfill(it.content, it.embedding, it.embeddingModelId, currentModelId)
        }
        val episodesNeedingEmbedding = episodes.count {
            needsEmbeddingBackfill(it.content, it.embedding, it.embeddingModelId, currentModelId)
        }
        
        return memoriesNeedingEmbedding + episodesNeedingEmbedding
    }
}

internal fun Throwable.isSplittableEmbeddingBatchFailure(): Boolean {
    val messages = generateSequence(this) { it.cause }
        .mapNotNull { it.message }
        .joinToString(" ")
    return messages.contains("Embedding batch size mismatch") ||
        Regex("""(?:embedding:|with)\s*(?:400|413|422)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(messages)
}

internal fun needsEmbeddingBackfill(
    content: String,
    embedding: String?,
    embeddingModelId: String?,
    currentModelId: String,
): Boolean {
    if (content.isBlank()) return false
    if (embedding.isNullOrBlank() || embeddingModelId != currentModelId) return true
    return runCatching { JsonInstant.decodeFromString<List<Float>>(embedding) }
        .getOrNull()
        .isNullOrEmpty()
}
