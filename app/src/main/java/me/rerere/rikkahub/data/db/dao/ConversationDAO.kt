package me.rerere.rikkahub.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.repository.LightConversationEntity

data class AssistantCountResult(
    val assistantId: String,
    val count: Int
)

data class ConversationNodesScanRow(
    val id: String,
    val assistantId: String,
    val nodes: String,
)

data class ConversationHistoryScanRow(
    val id: String,
    val createAt: Long,
    val nodes: String,
)

data class ConversationSearchIndexRow(
    val id: String,
    val nodes: String,
)

data class ChatSearchResultRow(
    val id: String,
    val title: String,
    val searchText: String,
    val updateAt: Long,
    val isPinned: Boolean,
)

data class ConversationMonthCount(
    val yearMonth: String,
    val count: Int,
)

@Dao
interface ConversationDAO {
    @Query("SELECT * FROM conversationentity ORDER BY is_pinned DESC, update_at DESC")
    fun getAll(): Flow<List<ConversationEntity>>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, is_consolidated as isConsolidated FROM conversationentity ORDER BY update_at DESC")
    fun getAllLight(): Flow<List<LightConversationEntity>>

    @Query("SELECT * FROM conversationentity ORDER BY is_pinned DESC, update_at DESC")
    fun getAllPaging(): PagingSource<Int, ConversationEntity>

    @Query("SELECT * FROM conversationentity WHERE assistant_id = :assistantId ORDER BY is_pinned DESC, update_at DESC")
    fun getConversationsOfAssistant(assistantId: String): Flow<List<ConversationEntity>>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, is_consolidated as isConsolidated FROM conversationentity WHERE assistant_id = :assistantId ORDER BY is_pinned DESC, update_at DESC")
    fun getLightConversationsOfAssistant(assistantId: String): Flow<List<LightConversationEntity>>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, is_consolidated as isConsolidated FROM conversationentity WHERE assistant_id = :assistantId ORDER BY is_pinned DESC, update_at DESC")
    fun getConversationsOfAssistantPaging(assistantId: String): PagingSource<Int, LightConversationEntity>

    @Query("SELECT * FROM conversationentity WHERE assistant_id = :assistantId ORDER BY is_pinned DESC, update_at DESC LIMIT :limit")
    suspend fun getRecentConversationsOfAssistant(assistantId: String, limit: Int): List<ConversationEntity>

    @Query("SELECT id FROM conversationentity WHERE assistant_id = :assistantId ORDER BY is_pinned DESC, update_at DESC LIMIT 1")
    suspend fun getTopConversationIdOfAssistant(assistantId: String): String?

    @Query("SELECT COUNT(*) FROM conversationentity")
    suspend fun getConversationCount(): Int

    @Query("SELECT COUNT(*) FROM conversationentity WHERE assistant_id = :assistantId")
    suspend fun getConversationCountOfAssistant(assistantId: String): Int

    @Query("SELECT id, assistant_id as assistantId, nodes FROM conversationentity ORDER BY update_at DESC LIMIT :limit OFFSET :offset")
    suspend fun getNodesBatchForScan(limit: Int, offset: Int): List<ConversationNodesScanRow>

    /**
     * Single-pass variant of [getNodesBatchForScan]. One full-table SELECT is cheaper than many
     * OFFSET-page queries, because SQLite OFFSET is a linear scan that gets slower as offset grows.
     */
    @Query("SELECT id, assistant_id as assistantId, nodes FROM conversationentity ORDER BY update_at DESC")
    suspend fun getNodesForScan(): List<ConversationNodesScanRow>

    @Query("""
        SELECT
            id,
            create_at as createAt,
            CASE WHEN length(nodes) <= :maxNodeChars THEN nodes ELSE '' END as nodes
        FROM conversationentity
        ORDER BY update_at DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getHistoryBatchForStats(
        limit: Int,
        offset: Int,
        maxNodeChars: Int
    ): List<ConversationHistoryScanRow>

    @Query("SELECT * FROM conversationentity WHERE (title LIKE '%' || :searchText || '%' OR search_text LIKE '%' || :searchText || '%') ORDER BY is_pinned DESC, update_at DESC")
    fun searchConversations(searchText: String): Flow<List<ConversationEntity>>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, is_consolidated as isConsolidated FROM conversationentity WHERE (title LIKE '%' || :searchText || '%' OR search_text LIKE '%' || :searchText || '%') ORDER BY is_pinned DESC, update_at DESC")
    fun searchConversationsPaging(searchText: String): PagingSource<Int, LightConversationEntity>

    @Query("SELECT * FROM conversationentity WHERE assistant_id = :assistantId AND (title LIKE '%' || :searchText || '%' OR search_text LIKE '%' || :searchText || '%') ORDER BY is_pinned DESC, update_at DESC")
    fun searchConversationsOfAssistant(assistantId: String, searchText: String): Flow<List<ConversationEntity>>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, is_consolidated as isConsolidated FROM conversationentity WHERE assistant_id = :assistantId AND (title LIKE '%' || :searchText || '%' OR search_text LIKE '%' || :searchText || '%') ORDER BY is_pinned DESC, update_at DESC")
    fun searchConversationsOfAssistantPaging(assistantId: String, searchText: String): PagingSource<Int, LightConversationEntity>

    @Query("SELECT id, title, search_text as searchText, update_at as updateAt, is_pinned as isPinned FROM conversationentity WHERE assistant_id = :assistantId AND search_text LIKE '%' || :searchText || '%' ORDER BY is_pinned DESC, update_at DESC LIMIT :limit")
    suspend fun searchChatContentOfAssistant(assistantId: String, searchText: String, limit: Int): List<ChatSearchResultRow>

    @Query("SELECT id, nodes FROM conversationentity WHERE search_text_version < :version ORDER BY update_at DESC LIMIT :limit")
    suspend fun getSearchIndexBackfillBatch(version: Int, limit: Int): List<ConversationSearchIndexRow>

    @Query("UPDATE conversationentity SET search_text = :searchText, search_text_version = :version WHERE id = :id")
    suspend fun updateSearchText(id: String, searchText: String, version: Int)

    @Query("SELECT * FROM conversationentity WHERE id = :id")
    fun getConversationFlowById(id: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversationentity WHERE id = :id")
    suspend fun getConversationById(id: String): ConversationEntity?

    @Query("SELECT strftime('%Y-%m', update_at / 1000, 'unixepoch', 'localtime') as yearMonth, COUNT(*) as count FROM conversationentity GROUP BY yearMonth ORDER BY yearMonth DESC")
    suspend fun getConversationMonthCounts(): List<ConversationMonthCount>

    @Query("SELECT strftime('%Y-%m', update_at / 1000, 'unixepoch', 'localtime') as yearMonth, COUNT(*) as count FROM conversationentity WHERE assistant_id = :assistantId GROUP BY yearMonth ORDER BY yearMonth DESC")
    suspend fun getConversationMonthCountsOfAssistant(assistantId: String): List<ConversationMonthCount>

    @Query("SELECT id FROM conversationentity WHERE update_at >= :startMs AND update_at < :endMs")
    suspend fun getConversationIdsByUpdateAtRange(startMs: Long, endMs: Long): List<String>

    @Query("SELECT id FROM conversationentity WHERE assistant_id = :assistantId AND update_at >= :startMs AND update_at < :endMs")
    suspend fun getConversationIdsOfAssistantByUpdateAtRange(assistantId: String, startMs: Long, endMs: Long): List<String>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, is_consolidated as isConsolidated FROM conversationentity WHERE update_at >= :startMs AND update_at < :endMs ORDER BY is_pinned DESC, update_at DESC")
    suspend fun getLightConversationsByUpdateAtRange(startMs: Long, endMs: Long): List<LightConversationEntity>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, is_consolidated as isConsolidated FROM conversationentity WHERE assistant_id = :assistantId AND update_at >= :startMs AND update_at < :endMs ORDER BY is_pinned DESC, update_at DESC")
    suspend fun getLightConversationsOfAssistantByUpdateAtRange(assistantId: String, startMs: Long, endMs: Long): List<LightConversationEntity>

    @Insert
    suspend fun insert(conversation: ConversationEntity)

    @Update
    suspend fun update(conversation: ConversationEntity)

    @Delete
    suspend fun delete(conversation: ConversationEntity)

    @Query("DELETE FROM conversationentity WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM conversationentity")
    suspend fun deleteAll()

    @Query("SELECT * FROM conversationentity WHERE is_pinned = 1 ORDER BY update_at DESC")
    fun getPinnedConversations(): Flow<List<ConversationEntity>>

    @Query("UPDATE conversationentity SET is_pinned = :isPinned WHERE id = :id")
    suspend fun updatePinStatus(id: String, isPinned: Boolean)

    @Query("UPDATE conversationentity SET is_consolidated = :isConsolidated WHERE id = :id")
    suspend fun updateConsolidatedStatus(id: String, isConsolidated: Boolean)

    // Stats queries for MenuVM optimization
    @Query("SELECT COUNT(*) FROM conversationentity")
    fun getConversationCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM conversationentity WHERE create_at >= :startMs AND create_at < :endMs")
    fun getConversationCountCreatedBetweenFlow(startMs: Long, endMs: Long): Flow<Int>

    @Query("SELECT DISTINCT date(update_at / 1000, 'unixepoch', 'localtime') as updateDate FROM conversationentity ORDER BY updateDate DESC")
    fun getDistinctUpdateDatesFlow(): Flow<List<String>>

    @Query("SELECT assistant_id as assistantId, COUNT(*) as count FROM conversationentity GROUP BY assistant_id ORDER BY count DESC LIMIT 1")
    fun getMostActiveAssistantFlow(): Flow<AssistantCountResult?>
}
