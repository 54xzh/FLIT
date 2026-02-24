package me.rerere.rikkahub.data.repository

import android.content.Context
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.DailyActivityDAO
import me.rerere.rikkahub.data.db.dao.EmbeddingCacheDAO
import me.rerere.rikkahub.data.db.dao.ToolResultArchiveDao
import me.rerere.rikkahub.data.db.dao.ToolResultArchiveChunkDao
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.DailyActivityEntity
import me.rerere.rikkahub.data.db.entity.MemoryType
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.deleteChatFiles
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.uuid.Uuid

class ConversationRepository(
    private val context: Context,
    private val conversationDAO: ConversationDAO,
    private val chatEpisodeDAO: me.rerere.rikkahub.data.db.dao.ChatEpisodeDAO,
    private val toolResultArchiveDao: ToolResultArchiveDao,
    private val toolResultArchiveChunkDao: ToolResultArchiveChunkDao,
    private val embeddingCacheDAO: EmbeddingCacheDAO,
    private val dailyActivityDAO: DailyActivityDAO,
) {
    companion object {
        private const val PAGE_SIZE = 20
        private const val INITIAL_LOAD_SIZE = 40
        private const val MAX_LOADED_MESSAGE_NODES_FOR_HUGE_CHAT = 320
    }

    data class MessageNodeChunk(
        val nodes: List<MessageNode>,
        val startIndex: Int,
        val endExclusive: Int,
        val totalCount: Int,
    )

    private data class DecodedNodeWindow(
        val nodes: List<MessageNode>,
        val startIndex: Int,
        val totalCount: Int,
    )

    suspend fun getRecentConversations(assistantId: Uuid, limit: Int = 10): List<Conversation> {
        return conversationDAO.getRecentConversationsOfAssistant(
            assistantId = assistantId.toString(),
            limit = limit
        ).map { conversationEntityToConversation(it) }
    }

    suspend fun getTopConversationIdOfAssistant(assistantId: Uuid): Uuid? {
        val id = conversationDAO.getTopConversationIdOfAssistant(assistantId.toString()) ?: return null
        return runCatching { Uuid.parse(id) }.getOrNull()
    }

    fun getConversationsOfAssistant(assistantId: Uuid): Flow<List<Conversation>> {
        return conversationDAO
            .getConversationsOfAssistant(assistantId.toString())
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity)
                }
            }
    }

    fun getAllLightConversations(): Flow<List<Conversation>> {
        return conversationDAO.getAllLight()
            .map { list ->
                list.map { conversationSummaryToConversation(it) }
            }
    }

    fun getConversationsOfAssistantPaging(assistantId: Uuid): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getConversationsOfAssistantPaging(assistantId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    suspend fun getConversationsOfAssistantPage(
        assistantId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        val pagingSource = conversationDAO.getConversationsOfAssistantPaging(assistantId.toString())
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    suspend fun searchConversationsOfAssistantPage(
        assistantId: Uuid,
        titleKeyword: String,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        val pagingSource = conversationDAO.searchConversationsOfAssistantPaging(
            assistantId = assistantId.toString(),
            searchText = titleKeyword
        )
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    fun searchConversations(titleKeyword: String): Flow<List<Conversation>> {
        return conversationDAO
            .searchConversations(titleKeyword)
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity)
                }
            }
    }

    fun searchConversationsPaging(titleKeyword: String): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.searchConversationsPaging(titleKeyword) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun searchConversationsOfAssistant(assistantId: Uuid, titleKeyword: String): Flow<List<Conversation>> {
        return conversationDAO
            .searchConversationsOfAssistant(assistantId.toString(), titleKeyword)
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity)
                }
            }
    }

    fun searchConversationsOfAssistantPaging(assistantId: Uuid, titleKeyword: String): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.searchConversationsOfAssistantPaging(assistantId.toString(), titleKeyword) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    suspend fun getConversationByIdCatching(uuid: Uuid): Result<Conversation?> {
        return try {
            val entity = conversationDAO.getConversationById(uuid.toString())
            Result.success(entity?.let { conversationEntityToConversation(it) })
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun getConversationById(uuid: Uuid): Conversation? {
        return getConversationByIdCatching(uuid).getOrNull()
    }

    suspend fun exportConversationRawJson(conversationId: Uuid): String? = withContext(Dispatchers.IO) {
        val entity = conversationDAO.getConversationById(conversationId.toString()) ?: return@withContext null
        val payload = ConversationRawJsonExport(
            conversation = RawConversationEntity(
                id = entity.id,
                assistantId = entity.assistantId,
                title = entity.title,
                nodes = entity.nodes,
                createAt = entity.createAt,
                updateAt = entity.updateAt,
                truncateIndex = entity.truncateIndex,
                chatSuggestions = entity.chatSuggestions,
                isPinned = entity.isPinned,
                isConsolidated = entity.isConsolidated,
                enabledModeIds = entity.enabledModeIds,
                contextSummary = entity.contextSummary,
                contextSummaryUpToIndex = entity.contextSummaryUpToIndex,
                lastPruneTime = entity.lastPruneTime,
                lastPruneMessageCount = entity.lastPruneMessageCount,
                lastRefreshTime = entity.lastRefreshTime,
                contextSummaryBoundaries = entity.contextSummaryBoundaries,
            )
        )
        JsonInstantPretty.encodeToString(payload)
    }

    suspend fun insertConversation(conversation: Conversation) {
        val conversationToStore = prepareConversationForStorage(conversation)
        conversationDAO.insert(
            conversationToConversationEntity(conversationToStore)
        )
    }

    suspend fun updateConversation(conversation: Conversation) {
        val conversationToStore = prepareConversationForStorage(conversation)
        // Invalidation Logic: If a consolidated conversation is updated (e.g. new message),
        // we must invalidate the old memory episode to allow re-consolidation.
        if (conversationToStore.isConsolidated) {
            val updatedConversation = conversationToStore.copy(isConsolidated = false)

            conversationDAO.update(
                conversationToConversationEntity(updatedConversation)
            )

            // Delete the old episode based on conversation ID if possible.
            // If deletion by ID returns 0 (e.g. legacy episode without conversationId),
            // fallback to best-effort deletion based on time range.
            val deletedCount = chatEpisodeDAO.deleteEpisodeByConversationId(conversationToStore.id.toString())
            if (deletedCount == 0) {
                chatEpisodeDAO.deleteEpisodeByTimeRange(
                    assistantId = conversationToStore.assistantId.toString(),
                    startTime = conversationToStore.createAt.toEpochMilli(),
                    endTime = Long.MAX_VALUE
                )
            }
        } else {
            conversationDAO.update(
                conversationToConversationEntity(conversationToStore)
            )
        }
    }

    suspend fun deleteConversation(conversation: Conversation, deleteFiles: Boolean = true) {
        conversationDAO.delete(
            conversationToConversationEntity(conversation)
        )
        chatEpisodeDAO.deleteEpisodeByConversationId(conversation.id.toString())

        val toolResultIds = toolResultArchiveDao.getIdsByConversationId(conversation.id.toString())
        toolResultArchiveDao.deleteByConversationId(conversation.id.toString())
        if (toolResultIds.isNotEmpty()) {
            embeddingCacheDAO.deleteByMemoryIds(MemoryType.TOOL_RESULT, toolResultIds)
        }

        val toolResultChunkIds = toolResultArchiveChunkDao.getIdsByConversationId(conversation.id.toString())
        toolResultArchiveChunkDao.deleteByConversationId(conversation.id.toString())
        if (toolResultChunkIds.isNotEmpty()) {
            embeddingCacheDAO.deleteByMemoryIds(MemoryType.TOOL_RESULT_CHUNK, toolResultChunkIds)
        }

        if (deleteFiles) {
            context.deleteChatFiles(conversation.files)
        }
    }

    suspend fun deleteConversationById(conversationId: String, deleteFiles: Boolean = true) {
        val entity = if (deleteFiles) {
            conversationDAO.getConversationById(conversationId)
        } else {
            null
        }

        conversationDAO.deleteById(conversationId)
        chatEpisodeDAO.deleteEpisodeByConversationId(conversationId)

        val toolResultIds = toolResultArchiveDao.getIdsByConversationId(conversationId)
        toolResultArchiveDao.deleteByConversationId(conversationId)
        if (toolResultIds.isNotEmpty()) {
            embeddingCacheDAO.deleteByMemoryIds(MemoryType.TOOL_RESULT, toolResultIds)
        }

        val toolResultChunkIds = toolResultArchiveChunkDao.getIdsByConversationId(conversationId)
        toolResultArchiveChunkDao.deleteByConversationId(conversationId)
        if (toolResultChunkIds.isNotEmpty()) {
            embeddingCacheDAO.deleteByMemoryIds(MemoryType.TOOL_RESULT_CHUNK, toolResultChunkIds)
        }

        if (deleteFiles && entity != null) {
            val conversation = conversationEntityToConversation(entity)
            context.deleteChatFiles(conversation.files)
        }
    }

    suspend fun deleteConversationOfAssistant(assistantId: Uuid, deleteFiles: Boolean = true) {
        getConversationsOfAssistant(assistantId).first().forEach { conversation ->
            deleteConversation(conversation, deleteFiles = deleteFiles)
        }
    }

    fun conversationToConversationEntity(conversation: Conversation): ConversationEntity {
        val normalizedSummaryBoundaries = normalizeContextSummaryBoundaries(
            conversation.contextSummaryBoundaries
        )
        return ConversationEntity(
            id = conversation.id.toString(),
            title = conversation.title,
            nodes = JsonInstant.encodeToString(conversation.messageNodes),
            createAt = conversation.createAt.toEpochMilli(),
            updateAt = conversation.updateAt.toEpochMilli(),
            assistantId = conversation.assistantId.toString(),
            truncateIndex = conversation.truncateIndex,
            chatSuggestions = JsonInstant.encodeToString(conversation.chatSuggestions),
            isPinned = conversation.isPinned,
            isConsolidated = conversation.isConsolidated,
            enabledModeIds = JsonInstant.encodeToString(conversation.enabledModeIds.map { it.toString() }),
            contextSummary = conversation.contextSummary.orEmpty(),
            contextSummaryUpToIndex = conversation.contextSummaryUpToIndex,
            lastPruneTime = conversation.lastPruneTime,
            lastPruneMessageCount = conversation.lastPruneMessageCount,
            lastRefreshTime = conversation.lastRefreshTime,
            contextSummaryBoundaries = JsonInstant.encodeToString(normalizedSummaryBoundaries),
        )
    }

    fun conversationEntityToConversation(conversationEntity: ConversationEntity): Conversation {
        val decodedWindow = decodeMessageNodesSafely(conversationEntity.nodes)
        val messageNodes = decodedWindow.nodes
        val enabledModeIds = try {
            JsonInstant.decodeFromString<List<String>>(conversationEntity.enabledModeIds)
                .map { Uuid.parse(it) }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }
        val parsedSummaryBoundaries = try {
            JsonInstant.decodeFromString<List<Int>>(conversationEntity.contextSummaryBoundaries)
        } catch (_: Exception) {
            emptyList()
        }
        val summaryBoundaries = normalizeContextSummaryBoundaries(parsedSummaryBoundaries)
        return Conversation(
            id = Uuid.parse(conversationEntity.id),
            title = conversationEntity.title,
            messageNodes = messageNodes,
            createAt = Instant.ofEpochMilli(conversationEntity.createAt),
            updateAt = Instant.ofEpochMilli(conversationEntity.updateAt),
            assistantId = Uuid.parse(conversationEntity.assistantId),
            truncateIndex = conversationEntity.truncateIndex,
            chatSuggestions = JsonInstant.decodeFromString(conversationEntity.chatSuggestions),
            isPinned = conversationEntity.isPinned,
            isConsolidated = conversationEntity.isConsolidated,
            enabledModeIds = enabledModeIds,
            contextSummary = conversationEntity.contextSummary.takeIf { it.isNotBlank() },
            contextSummaryUpToIndex = conversationEntity.contextSummaryUpToIndex,
            lastPruneTime = conversationEntity.lastPruneTime,
            lastPruneMessageCount = conversationEntity.lastPruneMessageCount,
            lastRefreshTime = conversationEntity.lastRefreshTime,
            contextSummaryBoundaries = summaryBoundaries,
            loadedNodeStartIndex = decodedWindow.startIndex,
            totalMessageNodeCount = decodedWindow.totalCount,
        )
    }

    suspend fun loadOlderMessageNodeChunk(
        conversationId: Uuid,
        beforeIndexExclusive: Int,
        limit: Int,
    ): MessageNodeChunk? {
        val safeLimit = limit.coerceAtLeast(1)
        val safeBefore = beforeIndexExclusive.coerceAtLeast(0)
        val start = (safeBefore - safeLimit).coerceAtLeast(0)
        return loadMessageNodeChunk(
            conversationId = conversationId,
            startInclusive = start,
            endExclusive = safeBefore,
        )
    }

    suspend fun loadMessageNodeChunk(
        conversationId: Uuid,
        startInclusive: Int,
        endExclusive: Int,
    ): MessageNodeChunk? = withContext(Dispatchers.IO) {
        val entity = conversationDAO.getConversationById(conversationId.toString()) ?: return@withContext null
        val nodesJson = entity.nodes
        val ranges = parseJsonArrayElementRanges(nodesJson)
        if (ranges != null) {
            val total = ranges.size
            val safeStart = startInclusive.coerceIn(0, total)
            val safeEnd = endExclusive.coerceIn(safeStart, total)
            val selectedRanges = if (safeStart < safeEnd) {
                ranges.subList(safeStart, safeEnd)
            } else {
                emptyList()
            }
            val sliceJson = buildJsonArrayFromRanges(nodesJson, selectedRanges)
            val nodes = decodeMessageNodesFromJson(sliceJson)
            return@withContext MessageNodeChunk(
                nodes = nodes,
                startIndex = safeStart,
                endExclusive = safeEnd,
                totalCount = total,
            )
        }

        val allNodes = decodeMessageNodesFromJson(nodesJson)
        val total = allNodes.size
        val safeStart = startInclusive.coerceIn(0, total)
        val safeEnd = endExclusive.coerceIn(safeStart, total)
        val slicedNodes = if (safeStart < safeEnd) {
            allNodes.subList(safeStart, safeEnd)
        } else {
            emptyList()
        }
        return@withContext MessageNodeChunk(
            nodes = slicedNodes,
            startIndex = safeStart,
            endExclusive = safeEnd,
            totalCount = total,
        )
    }

    fun getPinnedConversations(): Flow<List<Conversation>> {
        return conversationDAO
            .getPinnedConversations()
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity)
                }
            }
    }

    suspend fun togglePinStatus(conversationId: Uuid, currentIsPinned: Boolean) {
        conversationDAO.updatePinStatus(
            id = conversationId.toString(),
            isPinned = !currentIsPinned
        )
    }

    suspend fun markAsConsolidated(conversationId: Uuid) {
        conversationDAO.updateConsolidatedStatus(
            id = conversationId.toString(),
            isConsolidated = true
        )
    }

    suspend fun markAsNotConsolidated(conversationId: Uuid) {
        conversationDAO.updateConsolidatedStatus(
            id = conversationId.toString(),
            isConsolidated = false
        )
    }

    suspend fun getEpisodeCount(): Int {
        return chatEpisodeDAO.getCount()
    }

    fun getEpisodeCountFlow(): Flow<Int> {
        return chatEpisodeDAO.getCountFlow()
    }

    fun getAllConversations(): Flow<List<Conversation>> {
        return conversationDAO.getAll()
            .map { list ->
                list.map { conversationEntityToConversation(it) }
            }
    }

    // Optimized stats queries - delegate to SQL for performance
    fun getConversationCountFlow(): Flow<Int> = conversationDAO.getConversationCountFlow()

    fun getDistinctUpdateDatesFlow(): Flow<List<String>> = conversationDAO.getDistinctUpdateDatesFlow()

    fun getMostActiveAssistantIdFlow(): Flow<String?> = conversationDAO.getMostActiveAssistantFlow()
        .map { it?.assistantId }

    // ===== Daily Activity Tracking (for persistent streaks) =====

    /**
     * Record that the user was active today (sent a message).
     * This persists independently of conversations, so streak data
     * is preserved even when chats are deleted.
     */
    suspend fun recordDailyActivity() = withContext(Dispatchers.IO) {
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        dailyActivityDAO.recordActivity(today)
    }

    /**
     * Get all activity dates for streak calculation.
     * Returns dates in ISO format (YYYY-MM-DD), ordered most recent first.
     */
    fun getDailyActivityDatesFlow(): Flow<List<String>> = dailyActivityDAO.getAllDatesFlow()

    /**
     * Get all daily activity entries (most recent first).
     */
    fun getDailyActivitiesFlow(): Flow<List<DailyActivityEntity>> = dailyActivityDAO.getAllActivitiesFlow()

    /**
     * Migrate existing conversation dates to the daily activity table.
     * Called once during app initialization to preserve existing streaks.
     */
    suspend fun migrateConversationDatesToActivity() = withContext(Dispatchers.IO) {
        val existingDates = conversationDAO.getDistinctUpdateDatesFlow().first()
        for (dateStr in existingDates) {
            try {
                val date = LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE)
                val isoDate = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
                val timestamp = date.atStartOfDay().toEpochSecond(ZoneOffset.UTC) * 1000
                dailyActivityDAO.insertDateIfNotExists(isoDate, timestamp)
            } catch (e: Exception) {
                // Skip invalid dates
            }
        }
    }

    private fun conversationSummaryToConversation(entity: LightConversationEntity): Conversation {
        return Conversation(
            id = Uuid.parse(entity.id),
            assistantId = Uuid.parse(entity.assistantId),
            title = entity.title,
            isPinned = entity.isPinned,
            createAt = Instant.ofEpochMilli(entity.createAt),
            updateAt = Instant.ofEpochMilli(entity.updateAt),
            messageNodes = emptyList(),
            isConsolidated = entity.isConsolidated,
        )
    }
    fun getAverageMessageLength(assistantId: Uuid): Flow<Int> {
        return conversationDAO.getLightConversationsOfAssistant(assistantId.toString())
            .map { list ->
                when {
                    list.isEmpty() -> 100
                    list.size < 5 -> 120
                    else -> 150
                }
            }
    }

    private fun normalizeContextSummaryBoundaries(boundaries: List<Int>): List<Int> {
        return boundaries.asSequence()
            .filter { it >= 0 }
            .distinct()
            .sorted()
            .toList()
    }

    private suspend fun prepareConversationForStorage(conversation: Conversation): Conversation {
        if (conversation.loadedNodeStartIndex <= 0) return conversation
        val prefixChunk = loadMessageNodeChunk(
            conversationId = conversation.id,
            startInclusive = 0,
            endExclusive = conversation.loadedNodeStartIndex,
        ) ?: return conversation
        if (prefixChunk.endExclusive <= 0) return conversation

        val mergedNodes = prefixChunk.nodes + conversation.messageNodes
        return conversation.copy(
            messageNodes = mergedNodes,
            loadedNodeStartIndex = 0,
            totalMessageNodeCount = maxOf(prefixChunk.totalCount, mergedNodes.size),
        )
    }

    private fun decodeMessageNodesSafely(nodesJson: String): DecodedNodeWindow {
        val ranges = parseJsonArrayElementRanges(nodesJson)
        if (ranges != null) {
            val total = ranges.size
            val start = (total - MAX_LOADED_MESSAGE_NODES_FOR_HUGE_CHAT).coerceAtLeast(0)
            if (start <= 0) {
                val nodes = decodeMessageNodesFromJson(nodesJson)
                return DecodedNodeWindow(
                    nodes = nodes,
                    startIndex = 0,
                    totalCount = maxOf(total, nodes.size),
                )
            }

            val selectedRanges = ranges.subList(start, total)
            val safeJson = buildJsonArrayFromRanges(nodesJson, selectedRanges)
            val nodes = decodeMessageNodesFromJson(safeJson)
            return DecodedNodeWindow(
                nodes = nodes,
                startIndex = start,
                totalCount = total,
            )
        } else {
            val nodes = decodeMessageNodesFromJson(nodesJson)
            val total = nodes.size
            if (total <= MAX_LOADED_MESSAGE_NODES_FOR_HUGE_CHAT) {
                return DecodedNodeWindow(
                    nodes = nodes,
                    startIndex = 0,
                    totalCount = total,
                )
            }
            val start = total - MAX_LOADED_MESSAGE_NODES_FOR_HUGE_CHAT
            return DecodedNodeWindow(
                nodes = nodes.subList(start, total),
                startIndex = start,
                totalCount = total,
            )
        }
    }

    private fun decodeMessageNodesFromJson(nodesJson: String): List<MessageNode> {
        val migrated = migrateLegacyNodesJson(nodesJson)
        val decoded = JsonInstant.decodeFromString<List<MessageNode>>(migrated)
        return decoded.mapNotNull { node ->
            if (node.messages.isEmpty()) return@mapNotNull null
            val safeSelectIndex = node.selectIndex.coerceIn(0, node.messages.lastIndex)
            if (safeSelectIndex == node.selectIndex) {
                node
            } else {
                node.copy(selectIndex = safeSelectIndex)
            }
        }
    }

    private fun parseJsonArrayElementRanges(json: String): List<IntRange>? {
        val start = json.indexOfFirst { !it.isWhitespace() }
        val end = json.indexOfLast { !it.isWhitespace() }
        if (start < 0 || end <= start || json[start] != '[' || json[end] != ']') {
            return null
        }

        val ranges = mutableListOf<IntRange>()
        var inString = false
        var escaped = false
        var depth = 0
        var elementStart = -1

        for (index in (start + 1) until end) {
            val char = json[index]

            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == '"') {
                    inString = false
                }
                continue
            }

            when (char) {
                '"' -> inString = true
                '{', '[' -> {
                    if (elementStart < 0) {
                        elementStart = index
                    }
                    depth += 1
                }

                '}', ']' -> {
                    if (depth > 0) {
                        depth -= 1
                    }
                }

                ',' -> {
                    if (depth == 0) {
                        if (elementStart >= 0) {
                            var elementEnd = index - 1
                            while (elementEnd >= elementStart && json[elementEnd].isWhitespace()) {
                                elementEnd -= 1
                            }
                            if (elementEnd >= elementStart) {
                                ranges.add(elementStart..elementEnd)
                            }
                        }
                        elementStart = -1
                    }
                }

                else -> {
                    if (!char.isWhitespace() && elementStart < 0) {
                        elementStart = index
                    }
                }
            }
        }

        if (elementStart >= 0) {
            var elementEnd = end - 1
            while (elementEnd >= elementStart && json[elementEnd].isWhitespace()) {
                elementEnd -= 1
            }
            if (elementEnd >= elementStart) {
                ranges.add(elementStart..elementEnd)
            }
        }

        return ranges
    }

    private fun buildJsonArrayFromRanges(json: String, ranges: List<IntRange>): String {
        if (ranges.isEmpty()) return "[]"

        val builder = StringBuilder()
        builder.append('[')
        ranges.forEachIndexed { index, range ->
            if (index > 0) {
                builder.append(',')
            }
            builder.append(json, range.first, range.last + 1)
        }
        builder.append(']')
        return builder.toString()
    }

    private fun migrateLegacyNodesJson(json: String): String {
        // Fast path: most conversations are already in the new format.
        // Avoid parsing + rebuilding huge JSON blobs on every load.
        if (!json.contains("me.rerere.ai.ui.UIMessagePart.")) return json

        val legacyTypeMapping = mapOf(
            "me.rerere.ai.ui.UIMessagePart.Text" to "text",
            "me.rerere.ai.ui.UIMessagePart.Image" to "image",
            "me.rerere.ai.ui.UIMessagePart.Video" to "video",
            "me.rerere.ai.ui.UIMessagePart.Audio" to "audio",
            "me.rerere.ai.ui.UIMessagePart.Document" to "document",
            "me.rerere.ai.ui.UIMessagePart.Reasoning" to "reasoning",
            "me.rerere.ai.ui.UIMessagePart.Search" to "search",
            "me.rerere.ai.ui.UIMessagePart.ToolCall" to "tool_call",
            "me.rerere.ai.ui.UIMessagePart.ToolResult" to "tool_result",
        )

        try {
            val element = JsonInstant.parseToJsonElement(json)
            if (element !is JsonArray) return json

            var changed = false
            val newArray = buildJsonArray {
                element.jsonArray.forEach { node ->
                    if (node !is JsonObject) {
                        add(node)
                        return@forEach
                    }
                    add(buildJsonObject {
                        node.entries.forEach { (key, value) ->
                            if (key == "messages" && value is JsonArray) {
                                put("messages", buildJsonArray {
                                    value.jsonArray.forEach { message ->
                                        if (message !is JsonObject) {
                                            add(message)
                                            return@forEach
                                        }
                                        add(buildJsonObject {
                                            message.entries.forEach { (msgKey, msgValue) ->
                                                if (msgKey == "parts" && msgValue is JsonArray) {
                                                    put("parts", buildJsonArray {
                                                        msgValue.jsonArray.forEach { part ->
                                                            if (part !is JsonObject) {
                                                                add(part)
                                                                return@forEach
                                                            }
                                                            val type = runCatching {
                                                                part["type"]?.jsonPrimitive?.content
                                                            }.getOrNull()
                                                            val mappedType = when (type) {
                                                                "me.rerere.ai.ui.UIMessagePart.Thinking" -> "reasoning"
                                                                else -> type?.let { legacyTypeMapping[it] }
                                                            }
                                                            val shouldRenameThinkingField =
                                                                type == "me.rerere.ai.ui.UIMessagePart.Thinking"

                                                            if (mappedType == null && !shouldRenameThinkingField) {
                                                                add(part)
                                                                return@forEach
                                                            }

                                                            changed = true
                                                            add(buildJsonObject {
                                                                part.entries.forEach { (partKey, partValue) ->
                                                                    when {
                                                                        partKey == "type" -> {
                                                                            if (mappedType != null) {
                                                                                put("type", mappedType)
                                                                            } else {
                                                                                put("type", partValue)
                                                                            }
                                                                        }

                                                                        shouldRenameThinkingField && partKey == "thinking" -> {
                                                                            put("reasoning", partValue)
                                                                        }

                                                                        else -> {
                                                                            put(partKey, partValue)
                                                                        }
                                                                    }
                                                                }
                                                            })
                                                        }
                                                    })
                                                } else {
                                                    put(msgKey, msgValue)
                                                }
                                            }
                                        })
                                    }
                                })
                            } else {
                                put(key, value)
                            }
                        }
                    })
                }
            }
            return if (changed) newArray.toString() else json
        } catch (e: Exception) {
            e.printStackTrace()
            return json
        }
    }
}

@Serializable
private data class ConversationRawJsonExport(
    @SerialName("export_type")
    val exportType: String = "lastchat_conversation_raw",
    @SerialName("export_version")
    val exportVersion: Int = 1,
    @SerialName("exported_at")
    val exportedAt: Long = System.currentTimeMillis(),
    val conversation: RawConversationEntity,
)

@Serializable
private data class RawConversationEntity(
    val id: String,
    @SerialName("assistant_id")
    val assistantId: String,
    val title: String,
    val nodes: String,
    @SerialName("create_at")
    val createAt: Long,
    @SerialName("update_at")
    val updateAt: Long,
    @SerialName("truncate_index")
    val truncateIndex: Int,
    @SerialName("suggestions")
    val chatSuggestions: String,
    @SerialName("is_pinned")
    val isPinned: Boolean,
    @SerialName("is_consolidated")
    val isConsolidated: Boolean,
    @SerialName("enabled_mode_ids")
    val enabledModeIds: String,
    @SerialName("context_summary")
    val contextSummary: String,
    @SerialName("context_summary_up_to_index")
    val contextSummaryUpToIndex: Int,
    @SerialName("last_prune_time")
    val lastPruneTime: Long,
    @SerialName("last_prune_message_count")
    val lastPruneMessageCount: Int,
    @SerialName("last_refresh_time")
    val lastRefreshTime: Long,
    @SerialName("context_summary_boundaries")
    val contextSummaryBoundaries: String,
)

/**
 * 轻量级的会话查询结果，不包含 nodes 和 suggestions 字段
 */
data class LightConversationEntity(
    val id: String,
    val assistantId: String,
    val title: String,
    val isPinned: Boolean,
    val createAt: Long,
    val updateAt: Long,
    val isConsolidated: Boolean,
)

data class ConversationPageResult(
    val items: List<Conversation>,
    val nextOffset: Int?,
)
