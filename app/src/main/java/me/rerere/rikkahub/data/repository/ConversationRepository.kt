package me.rerere.rikkahub.data.repository

import android.content.Context
import android.util.Log
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkahub.data.db.dao.ChatSearchResultRow
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.DailyActivityDAO
import me.rerere.rikkahub.data.db.dao.EmbeddingCacheDAO
import me.rerere.rikkahub.data.db.dao.ToolResultArchiveDao
import me.rerere.rikkahub.data.db.dao.ToolResultArchiveChunkDao
import me.rerere.rikkahub.data.db.dao.UsageStatsDAO
import me.rerere.rikkahub.data.db.entity.UsageStatsEntity
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.DailyActivityEntity
import me.rerere.rikkahub.data.db.entity.MemoryType
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.SessionMemory
import me.rerere.rikkahub.data.model.SessionMemoryPlacement
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.deleteChatFiles
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.uuid.Uuid

private const val HISTORY_STATS_SCAN_BATCH_SIZE = 32
private const val HISTORY_STATS_MAX_NODE_CHARS = 512 * 1024

class ConversationRepository(
    private val context: Context,
    private val conversationDAO: ConversationDAO,
    private val chatEpisodeDAO: me.rerere.rikkahub.data.db.dao.ChatEpisodeDAO,
    private val toolResultArchiveDao: ToolResultArchiveDao,
    private val toolResultArchiveChunkDao: ToolResultArchiveChunkDao,
    private val embeddingCacheDAO: EmbeddingCacheDAO,
    private val dailyActivityDAO: DailyActivityDAO,
    private val usageStatsDAO: UsageStatsDAO,
) {
    companion object {
        private const val TAG = "ConversationRepository"
        private const val PAGE_SIZE = 20
        private const val INITIAL_LOAD_SIZE = 40
        private const val MAX_LOADED_MESSAGE_NODES_FOR_HUGE_CHAT = 320
        private val ISO_DATE_REGEX = Regex("\\d{4}-\\d{2}-\\d{2}")
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

    suspend fun searchChatContentOfAssistant(
        assistantId: Uuid,
        searchText: String,
        limit: Int,
    ): List<ChatSearchResultRow> {
        return conversationDAO.searchChatContentOfAssistant(assistantId.toString(), searchText, limit)
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
                searchText = entity.searchText,
                searchTextVersion = entity.searchTextVersion,
                createAt = entity.createAt,
                updateAt = entity.updateAt,
                truncateIndex = entity.truncateIndex,
                chatSuggestions = entity.chatSuggestions,
                isPinned = entity.isPinned,
                isConsolidated = entity.isConsolidated,
                enabledModeIds = entity.enabledModeIds,
                explicitSkillContextIds = entity.explicitSkillContextIds,
                contextSummary = entity.contextSummary,
                contextSummaryUpToIndex = entity.contextSummaryUpToIndex,
                lastPruneTime = entity.lastPruneTime,
                lastPruneMessageCount = entity.lastPruneMessageCount,
                lastRefreshTime = entity.lastRefreshTime,
                contextSummaryBoundaries = entity.contextSummaryBoundaries,
                sessionMemories = entity.sessionMemories,
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
        val existingConversation = conversationDAO.getConversationById(conversation.id.toString())
            ?.let { entity -> conversationEntityToConversation(entity) }
        val shouldInvalidateConsolidation = conversationToStore.isConsolidated &&
            existingConversation != null &&
            hasConversationContentChanged(existingConversation, conversationToStore)

        // Invalidation Logic: If a consolidated conversation is updated (e.g. new message),
        // we must invalidate the old memory episode to allow re-consolidation.
        if (shouldInvalidateConsolidation) {
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

    private fun hasConversationContentChanged(
        previous: Conversation,
        current: Conversation,
    ): Boolean {
        return previous.assistantId != current.assistantId ||
            previous.messageNodes != current.messageNodes ||
            previous.truncateIndex != current.truncateIndex
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
            searchText = buildConversationVisibleSearchText(conversation.messageNodes),
            searchTextVersion = CONVERSATION_SEARCH_TEXT_VERSION,
            createAt = conversation.createAt.toEpochMilli(),
            updateAt = conversation.updateAt.toEpochMilli(),
            assistantId = conversation.assistantId.toString(),
            truncateIndex = conversation.truncateIndex,
            chatSuggestions = JsonInstant.encodeToString(conversation.chatSuggestions),
            isPinned = conversation.isPinned,
            isConsolidated = conversation.isConsolidated,
            enabledModeIds = JsonInstant.encodeToString(conversation.enabledModeIds.map { it.toString() }),
            explicitSkillContextIds = JsonInstant.encodeToString(conversation.explicitSkillContextIds.map { it.toString() }),
            contextSummary = conversation.contextSummary.orEmpty(),
            contextSummaryUpToIndex = conversation.contextSummaryUpToIndex,
            lastPruneTime = conversation.lastPruneTime,
            lastPruneMessageCount = conversation.lastPruneMessageCount,
            lastRefreshTime = conversation.lastRefreshTime,
            contextSummaryBoundaries = JsonInstant.encodeToString(normalizedSummaryBoundaries),
            sessionMemories = JsonInstant.encodeToString(conversation.sessionMemories),
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
        val explicitSkillContextIds = try {
            JsonInstant.decodeFromString<List<String>>(conversationEntity.explicitSkillContextIds)
                .mapNotNull { value -> runCatching { Uuid.parse(value) }.getOrNull() }
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
        val sessionMemories = decodeSessionMemories(conversationEntity.sessionMemories)
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
            explicitSkillContextIds = explicitSkillContextIds,
            contextSummary = conversationEntity.contextSummary.takeIf { it.isNotBlank() },
            contextSummaryUpToIndex = conversationEntity.contextSummaryUpToIndex,
            lastPruneTime = conversationEntity.lastPruneTime,
            lastPruneMessageCount = conversationEntity.lastPruneMessageCount,
            lastRefreshTime = conversationEntity.lastRefreshTime,
            contextSummaryBoundaries = summaryBoundaries,
            sessionMemories = sessionMemories,
            loadedNodeStartIndex = decodedWindow.startIndex,
            totalMessageNodeCount = decodedWindow.totalCount,
        )
    }

    private fun decodeSessionMemories(raw: String): List<SessionMemory> {
        return runCatching {
            JsonInstant.decodeFromString<List<SessionMemory>>(raw)
        }.getOrElse {
            val array = runCatching { JsonInstant.parseToJsonElement(raw) as? JsonArray }
                .getOrNull()
                ?: return emptyList()
            array.mapIndexedNotNull { index, element ->
                val obj = element as? JsonObject ?: return@mapIndexedNotNull null
                val content = obj["content"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?: return@mapIndexedNotNull null
                if (content.isBlank()) return@mapIndexedNotNull null
                val createdAt = obj["createdAt"]?.jsonPrimitive?.longOrNull ?: 0L
                SessionMemory(
                    id = index + 1,
                    content = content,
                    createdAt = createdAt,
                    updatedAt = obj["updatedAt"]?.jsonPrimitive?.longOrNull ?: createdAt,
                    placement = SessionMemoryPlacement.fromToolValue(
                        obj["placement"]?.jsonPrimitive?.contentOrNull
                    ),
                )
            }
        }
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
     * Reconstruct missing historical activity days from conversation history.
     * This is safe to run repeatedly and fills gaps caused by imports/restores.
     */
    suspend fun backfillDailyActivityFromConversationHistoryIfNeeded() {
        val total = conversationDAO.getConversationCount()
        if (total <= 0) return

        val existingDates = dailyActivityDAO.getAllDatesFlow().first().toHashSet()
        val dateCounts = mutableMapOf<String, Int>()
        val formatter = DateTimeFormatter.ISO_LOCAL_DATE

        var offset = 0
        while (offset < total) {
            val batch = conversationDAO.getHistoryBatchForStats(
                limit = HISTORY_STATS_SCAN_BATCH_SIZE,
                offset = offset,
                maxNodeChars = HISTORY_STATS_MAX_NODE_CHARS
            )
            if (batch.isEmpty()) break

            batch.forEach { entity ->
                val fallbackDate = Instant.ofEpochMilli(entity.createAt)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .format(formatter)

                val selectedDates = extractSelectedMessageDates(entity.nodes)
                if (selectedDates.isEmpty()) {
                    dateCounts[fallbackDate] = (dateCounts[fallbackDate] ?: 0) + 1
                } else {
                    selectedDates.forEach { date ->
                        dateCounts[date] = (dateCounts[date] ?: 0) + 1
                    }
                }
            }

            offset += batch.size
        }

        if (dateCounts.isEmpty()) return
        if (dateCounts.keys.all { it in existingDates }) return

        dateCounts.forEach { (date, count) ->
            val timestamp = runCatching {
                LocalDate.parse(date, formatter)
                    .atStartOfDay()
                    .toEpochSecond(java.time.ZoneOffset.UTC) * 1000
            }.getOrDefault(System.currentTimeMillis())
            dailyActivityDAO.insertBackfilledActivityIfMissing(
                date = date,
                count = count,
                timestamp = timestamp
            )
            dailyActivityDAO.mergeBackfilledActivity(
                date = date,
                count = count,
                timestamp = timestamp
            )
        }
    }

    // ===== Persistent Usage Stats =====

    suspend fun initUsageStats() {
        usageStatsDAO.initIfEmpty()
    }

    fun getUsageStatsFlow(): Flow<UsageStatsEntity?> = usageStatsDAO.getStatsFlow()

    fun getUsageStatsLast12MonthsFlow(): Flow<UsageStatsEntity> {
        val today = LocalDate.now()
        val windowStart = today.withDayOfMonth(1).minusMonths(11)
        val zoneId = ZoneId.systemDefault()
        val windowStartMs = windowStart.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val windowEndMs = today.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val formatter = DateTimeFormatter.ISO_LOCAL_DATE

        return combine(
            conversationDAO.getConversationCountCreatedBetweenFlow(windowStartMs, windowEndMs),
            dailyActivityDAO.getAllActivityFlow(),
            usageStatsDAO.getStatsFlow()
        ) { conversationCountInWindow, allActivity, persistedStats ->
            val messagesFromActivityInWindow = allActivity.sumOf { entity ->
                val date = runCatching { LocalDate.parse(entity.date, formatter) }.getOrNull()
                if (date != null && !date.isBefore(windowStart) && !date.isAfter(today)) {
                    entity.messageCount.toLong()
                } else {
                    0L
                }
            }

            val fallbackMessageCount = persistedStats?.totalMessages ?: 0L
            val messageCount = if (messagesFromActivityInWindow > 0L) {
                messagesFromActivityInWindow
            } else {
                fallbackMessageCount
            }

            // Avoid parsing all conversation JSON on stats page entry. Large histories can exceed
            // CursorWindow or heap limits, so token cards use the persistent accumulator instead.
            UsageStatsEntity(
                id = 1,
                totalConversations = conversationCountInWindow.toLong(),
                totalMessages = messageCount,
                inputTokens = persistedStats?.inputTokens ?: 0L,
                outputTokens = persistedStats?.outputTokens ?: 0L,
                cachedTokens = persistedStats?.cachedTokens ?: 0L,
                appLaunches = persistedStats?.appLaunches ?: 0L
            )
        }
    }

    suspend fun backfillUsageStatsFromHistoryIfNeeded() {
        usageStatsDAO.initIfEmpty()
        val currentStats = usageStatsDAO.getStats() ?: return
        val conversationCount = conversationDAO.getConversationCountFlow().first().toLong()

        if (conversationCount <= 0L) return

        val needsConversationBackfill = currentStats.totalConversations < conversationCount
        val hasNoTokenHistory = currentStats.inputTokens <= 0L &&
            currentStats.outputTokens <= 0L &&
            currentStats.cachedTokens <= 0L

        if (!needsConversationBackfill && !hasNoTokenHistory) return

        var historicalTotals = HistoricalUsageTotals()
        var offset = 0
        while (offset.toLong() < conversationCount) {
            val batch = conversationDAO.getHistoryBatchForStats(
                limit = HISTORY_STATS_SCAN_BATCH_SIZE,
                offset = offset,
                maxNodeChars = HISTORY_STATS_MAX_NODE_CHARS
            )
            if (batch.isEmpty()) break

            batch.forEach { entity ->
                historicalTotals += extractHistoricalUsage(entity.nodes)
            }

            offset += batch.size
        }

        val messagesFromActivity = runCatching { dailyActivityDAO.getTotalMessageCountFlow().first() }
            .getOrDefault(0L)
        val bestMessageCount = maxOf(messagesFromActivity, historicalTotals.selectedMessageCount.toLong())

        usageStatsDAO.overwriteCoreStats(
            totalConversations = maxOf(currentStats.totalConversations, conversationCount),
            totalMessages = maxOf(currentStats.totalMessages, bestMessageCount),
            inputTokens = maxOf(currentStats.inputTokens, historicalTotals.inputTokens),
            outputTokens = maxOf(currentStats.outputTokens, historicalTotals.outputTokens),
            cachedTokens = maxOf(currentStats.cachedTokens, historicalTotals.cachedTokens)
        )
    }

    fun getAllDailyActivityFlow() = dailyActivityDAO.getAllActivityFlow()

    suspend fun incrementConversationCount() {
        usageStatsDAO.incrementConversations()
    }

    suspend fun addTokenUsage(inputTokens: Long, outputTokens: Long, cachedTokens: Long) {
        usageStatsDAO.addTokenUsage(inputTokens, outputTokens, cachedTokens)
    }

    suspend fun incrementMessageCount(count: Int = 1) {
        usageStatsDAO.incrementMessages(count)
    }

    suspend fun incrementAppLaunches() {
        usageStatsDAO.incrementAppLaunches()
    }

    private fun extractHistoricalUsage(
        nodesJson: String,
        windowStart: LocalDate? = null,
        windowEnd: LocalDate? = null,
        fallbackDate: LocalDate? = null
    ): HistoricalUsageTotals {
        val root = runCatching { JsonInstant.parseToJsonElement(nodesJson) }.getOrNull()
        if (root !is JsonArray) return HistoricalUsageTotals()

        var inputTokens = 0L
        var outputTokens = 0L
        var cachedTokens = 0L
        var selectedMessageCount = 0

        root.forEach { nodeElement ->
            val node = nodeElement as? JsonObject ?: return@forEach
            val messages = node["messages"] as? JsonArray ?: return@forEach
            if (messages.isEmpty()) return@forEach

            val selectedIndex = node["selectIndex"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
            val selectedMessage = (messages.getOrNull(selectedIndex) ?: messages.lastOrNull()) as? JsonObject
                ?: return@forEach

            val messageDate = parseDateString(
                selectedMessage["createdAt"]?.jsonPrimitiveOrNull?.contentOrNull
            )?.let { runCatching { LocalDate.parse(it, DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull() }
                ?: fallbackDate
            if (windowStart != null) {
                if (messageDate == null || messageDate.isBefore(windowStart)) return@forEach
            }
            if (windowEnd != null) {
                if (messageDate == null || messageDate.isAfter(windowEnd)) return@forEach
            }

            selectedMessageCount += 1

            val usage = selectedMessage["usage"] as? JsonObject ?: return@forEach
            inputTokens += usage.readUsageValue("promptTokens", "inputTokens", "prompt_tokens", "input_tokens")
            outputTokens += usage.readUsageValue(
                "completionTokens",
                "outputTokens",
                "completion_tokens",
                "output_tokens"
            )
            cachedTokens += usage.readUsageValue("cachedTokens", "cached_tokens")
        }

        return HistoricalUsageTotals(
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            cachedTokens = cachedTokens,
            selectedMessageCount = selectedMessageCount
        )
    }

    private fun JsonObject.readUsageValue(vararg keys: String): Long {
        keys.forEach { key ->
            val value = this[key]?.jsonPrimitiveOrNull?.contentOrNull?.toLongOrNull()
            if (value != null) return value
        }
        return 0L
    }

    private fun extractSelectedMessageDates(nodesJson: String): List<String> {
        val root = runCatching { JsonInstant.parseToJsonElement(nodesJson) }.getOrNull()
        if (root !is JsonArray) return emptyList()

        return root.mapNotNull { nodeElement ->
            val node = nodeElement as? JsonObject ?: return@mapNotNull null
            val messages = node["messages"] as? JsonArray ?: return@mapNotNull null
            if (messages.isEmpty()) return@mapNotNull null

            val selectedIndex = node["selectIndex"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
            val selectedMessage = (messages.getOrNull(selectedIndex) ?: messages.lastOrNull()) as? JsonObject
                ?: return@mapNotNull null
            parseDateString(selectedMessage["createdAt"]?.jsonPrimitiveOrNull?.contentOrNull)
        }
    }

    private fun parseDateString(raw: String?): String? {
        val match = raw?.let { ISO_DATE_REGEX.find(it)?.value } ?: return null
        return runCatching { LocalDate.parse(match, DateTimeFormatter.ISO_LOCAL_DATE).format(DateTimeFormatter.ISO_LOCAL_DATE) }
            .getOrNull()
    }

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

    suspend fun backfillConversationSearchTextIfNeeded(batchSize: Int = 50) = withContext(Dispatchers.IO) {
        while (true) {
            val rows = conversationDAO.getSearchIndexBackfillBatch(
                version = CONVERSATION_SEARCH_TEXT_VERSION,
                limit = batchSize.coerceAtLeast(1),
            )
            if (rows.isEmpty()) break

            rows.forEach { row ->
                val searchText = runCatching {
                    buildConversationVisibleSearchText(decodeMessageNodesFromJson(row.nodes))
                }.onFailure { error ->
                    Log.w(TAG, "Failed to rebuild conversation search text for ${row.id}", error)
                }.getOrDefault("")

                conversationDAO.updateSearchText(
                    id = row.id,
                    searchText = searchText,
                    version = CONVERSATION_SEARCH_TEXT_VERSION,
                )
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
        if (!json.contains("me.rerere.ai.ui.UIMessagePart.Thinking")) return json

        try {
            val element = JsonInstant.parseToJsonElement(json)
            if (element !is JsonArray) return json

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
                                                            val type = part["type"]?.jsonPrimitive?.content
                                                            if (type == "me.rerere.ai.ui.UIMessagePart.Thinking") {
                                                                add(buildJsonObject {
                                                                    put("type", "me.rerere.ai.ui.UIMessagePart.Reasoning")
                                                                    part.entries.forEach { (partKey, partValue) ->
                                                                        when (partKey) {
                                                                            "type" -> { /* skip, already added */ }
                                                                            "thinking" -> put("reasoning", partValue)
                                                                            else -> put(partKey, partValue)
                                                                        }
                                                                    }
                                                                })
                                                            } else {
                                                                add(part)
                                                            }
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
            return newArray.toString()
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
    @SerialName("search_text")
    val searchText: String,
    @SerialName("search_text_version")
    val searchTextVersion: Int,
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
    @SerialName("explicit_skill_context_ids")
    val explicitSkillContextIds: String = "[]",
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
    @SerialName("session_memories")
    val sessionMemories: String = "[]",
)

/**
 * 轻量级的会话查询结果，不包含 nodes 和 suggestions 字段
 */
private data class HistoricalUsageTotals(
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val cachedTokens: Long = 0L,
    val selectedMessageCount: Int = 0
) {
    operator fun plus(other: HistoricalUsageTotals): HistoricalUsageTotals {
        return HistoricalUsageTotals(
            inputTokens = inputTokens + other.inputTokens,
            outputTokens = outputTokens + other.outputTokens,
            cachedTokens = cachedTokens + other.cachedTokens,
            selectedMessageCount = selectedMessageCount + other.selectedMessageCount
        )
    }
}

data class LightConversationEntity(
    val id: String,
    val assistantId: String,
    val title: String,
    val isPinned: Boolean,
    val createAt: Long,
    val updateAt: Long,
    val isConsolidated: Boolean,
)
