package me.rerere.rikkahub.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.ai.AIRequestSource
import me.rerere.rikkahub.data.ai.rag.EmbeddingService
import me.rerere.rikkahub.data.ai.rag.VectorEngine
import me.rerere.rikkahub.data.db.dao.ChatEpisodeDAO
import me.rerere.rikkahub.data.db.dao.EmbeddingCacheDAO
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.entity.ChatEpisodeEntity
import me.rerere.rikkahub.data.db.entity.EmbeddingCacheEntity
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.db.entity.MemoryType
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.utils.JsonInstant

data class AssistantMemoryStats(
    val coreCount: Int = 0,
    val episodicCount: Int = 0,
    val embeddedCount: Int = 0,
    val totalCount: Int = 0,
)

class MemoryRepository(
    private val memoryDAO: MemoryDAO,
    private val chatEpisodeDAO: ChatEpisodeDAO,
    private val embeddingService: EmbeddingService,
    private val embeddingCacheDAO: EmbeddingCacheDAO
) {
    companion object {
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
     * Get or create an embedding for a memory/episode content.
     * First checks the cache, then generates if not found.
     * @return The embedding if successful, null otherwise
     */
    private suspend fun getOrCreateEmbedding(
        memoryId: Int,
        memoryType: Int,
        content: String,
        assistantId: String,
        existingEmbedding: String? = null,
        existingModelId: String? = null
    ): List<Float>? {
        val normalizedContent = content.trim()
        if (normalizedContent.isEmpty()) {
            return null
        }
        val modelId = embeddingService.getEmbeddingModelId(assistantId)
        
        // Check cache first
        val cached = embeddingCacheDAO.getEmbedding(memoryId, memoryType, modelId)
        if (cached != null) {
            return try {
                JsonInstant.decodeFromString<List<Float>>(cached.embedding)
            } catch (e: Exception) {
                null
            }
        }
        
        // Check existing embedding in entity (Fallback / Optimization)
        if (existingEmbedding != null && existingModelId == modelId) {
             try {
                val emb = JsonInstant.decodeFromString<List<Float>>(existingEmbedding)
                // Backfill cache for future performance
                embeddingCacheDAO.insertEmbedding(
                    EmbeddingCacheEntity(
                        memoryId = memoryId,
                        memoryType = memoryType,
                        modelId = modelId,
                        embedding = existingEmbedding
                    )
                )
                return emb
             } catch (e: Exception) {
                 e.printStackTrace()
             }
        }

        // Generate new embedding
        return try {
            val embedding = embeddingService.embed(
                text = normalizedContent,
                assistantId = assistantId,
                source = AIRequestSource.MEMORY_EMBEDDING,
            )
            // Cache it
            embeddingCacheDAO.insertEmbedding(
                EmbeddingCacheEntity(
                    memoryId = memoryId,
                    memoryType = memoryType,
                    modelId = modelId,
                    embedding = JsonInstant.encodeToString(embedding)
                )
            )
            embedding
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Check if an embedding exists in cache for the current model.
     */
    suspend fun hasEmbeddingForCurrentModel(memoryId: Int, memoryType: Int, assistantId: String): Boolean {
        val modelId = embeddingService.getEmbeddingModelId(assistantId)
        return embeddingCacheDAO.hasEmbedding(memoryId, memoryType, modelId)
    }

    suspend fun deleteMemoriesOfAssistant(assistantId: String) {
        memoryDAO.deleteMemoriesOfAssistant(assistantId)
        chatEpisodeDAO.deleteEpisodesOfAssistant(assistantId)
    }

    suspend fun updateCoreMemory(id: Int, content: String, pinned: Boolean): AssistantMemory {
        val memory = memoryDAO.getMemoryById(id) ?: error("Memory not found")
        val normalizedContent = content.trim()
        require(normalizedContent.isNotEmpty()) { "Memory content cannot be blank" }
        val contentChanged = memory.content != normalizedContent
        val newMemory = memory.copy(
            content = normalizedContent,
            pinned = pinned,
            embedding = if (contentChanged) null else memory.embedding,
        )
        memoryDAO.updateMemory(newMemory)

        if (contentChanged) {
            embeddingCacheDAO.deleteByMemoryId(id, MemoryType.CORE)
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

    suspend fun updateContent(id: Int, content: String): AssistantMemory {
        val memory = memoryDAO.getMemoryById(id) ?: error("Memory not found")
        val normalizedContent = content.trim()
        require(normalizedContent.isNotEmpty()) { "Memory content cannot be blank" }
        val newMemory = memory.copy(content = normalizedContent, embedding = null) // Invalidate embedding
        memoryDAO.updateMemory(newMemory)

        // Invalidate cache
        embeddingCacheDAO.deleteByMemoryId(id, MemoryType.CORE)

        return AssistantMemory(
            id = newMemory.id,
            content = newMemory.content,
            type = newMemory.type,
            hasEmbedding = false,
            timestamp = newMemory.createdAt,
            pinned = newMemory.pinned,
        )
    }

    suspend fun updateEpisodeContent(id: Int, content: String): AssistantMemory {
        val episode = chatEpisodeDAO.getEpisodeById(id) ?: error("Episode not found")
        val normalizedContent = content.trim()
        require(normalizedContent.isNotEmpty()) { "Memory content cannot be blank" }
        val newEpisode = episode.copy(content = normalizedContent, embedding = null) // Invalidate embedding
        chatEpisodeDAO.insertEpisode(newEpisode)

        // Invalidate cache
        embeddingCacheDAO.deleteByMemoryId(id, MemoryType.EPISODIC)

        return AssistantMemory(
            id = -newEpisode.id,
            content = newEpisode.content,
            type = MemoryType.EPISODIC,
            hasEmbedding = false,
            timestamp = newEpisode.startTime,
            significance = newEpisode.significance
        )
    }

    suspend fun addMemory(assistantId: String, content: String, pinned: Boolean = false): AssistantMemory {
        val normalizedContent = content.trim()
        require(normalizedContent.isNotEmpty()) { "Memory content cannot be blank" }

        val embeddingResult = try {
            embeddingService.embedWithModelId(
                text = normalizedContent,
                assistantId = assistantId,
                source = AIRequestSource.MEMORY_EMBEDDING,
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }

        val entity = MemoryEntity(
            assistantId = assistantId,
            content = normalizedContent,
            embedding = embeddingResult?.embeddings?.firstOrNull()?.let { JsonInstant.encodeToString(it) },
            embeddingModelId = embeddingResult?.modelId,
            type = MemoryType.CORE,
            pinned = pinned,
            createdAt = System.currentTimeMillis(),
            lastAccessedAt = System.currentTimeMillis()
        )
        
        val id = memoryDAO.insertMemory(entity)
        
        // Add to cache immediately if available
        if (embeddingResult != null && embeddingResult.embeddings.isNotEmpty()) {
             embeddingCacheDAO.insertEmbedding(
                EmbeddingCacheEntity(
                    memoryId = id.toInt(),
                    memoryType = MemoryType.CORE,
                    modelId = embeddingResult.modelId,
                    embedding = JsonInstant.encodeToString(embeddingResult.embeddings.first())
                )
             )
        }

        return AssistantMemory(
            id = id.toInt(),
            content = normalizedContent,
            type = MemoryType.CORE,
            hasEmbedding = embeddingResult != null,
            embeddingModelId = embeddingResult?.modelId,
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

    suspend fun retrieveRelevantMemoriesWithScoresByEmbedding(
        assistantId: String,
        queryEmbedding: List<Float>,
        limit: Int = 5,
        similarityThreshold: Float = 0.5f,
        includeCore: Boolean = true,
        includeEpisodes: Boolean = true
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

        val scoredCore = memories.mapNotNull { memory ->
            if (memory.pinned) {
                val score = runCatching {
                    val embedding = getOrCreateEmbedding(
                        memoryId = memory.id,
                        memoryType = MemoryType.CORE,
                        content = memory.content,
                        assistantId = assistantId,
                        existingEmbedding = memory.embedding,
                        existingModelId = memory.embeddingModelId
                    )
                    if (embedding != null) {
                        val similarity = VectorEngine.cosineSimilarity(queryEmbedding, embedding)
                        similarity * 1.05f
                    } else {
                        1f
                    }
                }.getOrDefault(1f)
                ScoredCandidate(item = memory, score = score, isMemory = true, isPinned = true)
            } else {
                val embedding = getOrCreateEmbedding(
                    memoryId = memory.id,
                    memoryType = MemoryType.CORE,
                    content = memory.content,
                    assistantId = assistantId,
                    existingEmbedding = memory.embedding,
                    existingModelId = memory.embeddingModelId
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

        val scoredEpisodes = episodes.mapNotNull { episode ->
            val embedding = getOrCreateEmbedding(
                memoryId = episode.id,
                memoryType = MemoryType.EPISODIC,
                content = episode.content,
                assistantId = assistantId,
                existingEmbedding = episode.embedding,
                existingModelId = episode.embeddingModelId
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
        finalCandidates.forEach { candidate ->
            if (candidate.isMemory) {
                val memory = candidate.item as MemoryEntity
                memoryDAO.updateMemory(memory.copy(lastAccessedAt = System.currentTimeMillis()))
            } else {
                val episode = candidate.item as ChatEpisodeEntity
                chatEpisodeDAO.insertEpisode(episode.copy(lastAccessedAt = System.currentTimeMillis()))
            }
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

        val pinnedCoreMemories = if (includeCore) {
            memoryDAO.getPinnedMemoriesOfAssistant(assistantId)
        } else {
            emptyList()
        }

        val queryEmbedding = try {
            embeddingService.embed(
                text = query,
                assistantId = assistantId,
                source = AIRequestSource.MEMORY_RETRIEVAL,
            )
        } catch (e: Exception) {
            e.printStackTrace()
            pinnedCoreMemories.forEach { memory ->
                memoryDAO.updateMemory(memory.copy(lastAccessedAt = System.currentTimeMillis()))
            }
            return pinnedCoreMemories.map { memory ->
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
                    1f
                )
            }
        }

        val limitInt = limit.coerceAtLeast(0)

        // Get both core memories and episodes
        val memories = if (includeCore) memoryDAO.getMemoriesOfAssistant(assistantId) else emptyList()
        val episodes = if (includeEpisodes) chatEpisodeDAO.getEpisodesOfAssistant(assistantId) else emptyList()

        val scoredCore = memories.mapNotNull { memory ->
            if (memory.pinned) {
                val score = runCatching {
                    val embedding = getOrCreateEmbedding(
                        memoryId = memory.id,
                        memoryType = MemoryType.CORE,
                        content = memory.content,
                        assistantId = assistantId,
                        existingEmbedding = memory.embedding,
                        existingModelId = memory.embeddingModelId
                    )
                    if (embedding != null) {
                        val similarity = VectorEngine.cosineSimilarity(queryEmbedding, embedding)
                        similarity * 1.05f
                    } else {
                        1f
                    }
                }.getOrDefault(1f)
                ScoredCandidate(item = memory, score = score, isMemory = true, isPinned = true)
            } else {
                val embedding = getOrCreateEmbedding(
                    memoryId = memory.id,
                    memoryType = MemoryType.CORE,
                    content = memory.content,
                    assistantId = assistantId,
                    existingEmbedding = memory.embedding,
                    existingModelId = memory.embeddingModelId
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

        val scoredEpisodes = episodes.mapNotNull { episode ->
            val embedding = getOrCreateEmbedding(
                memoryId = episode.id,
                memoryType = MemoryType.EPISODIC,
                content = episode.content,
                assistantId = assistantId,
                existingEmbedding = episode.embedding,
                existingModelId = episode.embeddingModelId
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
        finalCandidates.forEach { candidate ->
            if (candidate.isMemory) {
                val memory = candidate.item as MemoryEntity
                memoryDAO.updateMemory(memory.copy(lastAccessedAt = System.currentTimeMillis()))
            } else {
                val episode = candidate.item as ChatEpisodeEntity
                chatEpisodeDAO.insertEpisode(episode.copy(lastAccessedAt = System.currentTimeMillis()))
            }
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
            it.content.trim().isNotEmpty() && (it.embedding == null || it.embeddingModelId != currentModelId)
        }
        val episodesNeedingEmbedding = allEpisodes.filter { 
            it.content.trim().isNotEmpty() && (it.embedding == null || it.embeddingModelId != currentModelId)
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
    suspend fun embedMissingMemories(assistantId: String): Pair<Int, Int> {
        val memories = memoryDAO.getMemoriesOfAssistant(assistantId)
        val episodes = chatEpisodeDAO.getEpisodesOfAssistant(assistantId)
        val currentModelId = embeddingService.getEmbeddingModelId(assistantId)
        
        var successCount = 0
        var failureCount = 0

        // Filter to only memories that need embedding
        val memoriesNeedingEmbedding = memories.filter { 
            it.content.trim().isNotEmpty() && (it.embedding == null || it.embeddingModelId != currentModelId)
        }
        val episodesNeedingEmbedding = episodes.filter { 
            it.content.trim().isNotEmpty() && (it.embedding == null || it.embeddingModelId != currentModelId)
        }

        // Process Core Memories that need embedding
        memoriesNeedingEmbedding.forEach { memory ->
            try {
                val embedding = embeddingService.embed(
                    text = memory.content,
                    assistantId = assistantId,
                    source = AIRequestSource.MEMORY_EMBEDDING,
                )
                val embeddingJson = JsonInstant.encodeToString(embedding)
                memoryDAO.updateMemory(memory.copy(
                    embedding = embeddingJson,
                    embeddingModelId = currentModelId
                ))
                // Also cache
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
        }

        // Process Episodes that need embedding
        episodesNeedingEmbedding.forEach { episode ->
            try {
                val embedding = embeddingService.embed(
                    text = episode.content,
                    assistantId = assistantId,
                    source = AIRequestSource.MEMORY_EMBEDDING,
                )
                val embeddingJson = JsonInstant.encodeToString(embedding)
                chatEpisodeDAO.insertEpisode(episode.copy(
                    embedding = embeddingJson,
                    embeddingModelId = currentModelId
                ))
                // Also cache
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
        }
        
        return successCount to failureCount
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
            it.content.trim().isNotEmpty() && (it.embedding == null || it.embeddingModelId != currentModelId)
        }
        val episodesNeedingEmbedding = episodes.count { 
            it.content.trim().isNotEmpty() && (it.embedding == null || it.embeddingModelId != currentModelId)
        }
        
        return memoriesNeedingEmbedding + episodesNeedingEmbedding
    }
}
