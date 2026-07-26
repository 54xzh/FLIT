package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.ChatEpisodeEntity

data class ChatEpisodeUiEntity(
    val id: Int,
    val assistantId: String,
    val content: String,
    val embeddingModelId: String?,
    val startTime: Long,
    val endTime: Long,
    val significance: Int,
    val conversationId: String?,
    val hasEmbedding: Boolean,
)

data class ChatEpisodeOverviewStats(
    val totalEpisodes: Int,
    val averageSignificance: Double?,
)

@Dao
interface ChatEpisodeDAO {
    @Query("SELECT * FROM ChatEpisodeEntity WHERE assistant_id = :assistantId ORDER BY end_time DESC")
    suspend fun getEpisodesOfAssistant(assistantId: String): List<ChatEpisodeEntity>

    @Query("SELECT * FROM ChatEpisodeEntity WHERE assistant_id = :assistantId ORDER BY end_time DESC LIMIT :limit")
    suspend fun getRecentEpisodesOfAssistant(assistantId: String, limit: Int): List<ChatEpisodeEntity>

    @Query("SELECT * FROM ChatEpisodeEntity WHERE assistant_id = :assistantId ORDER BY end_time DESC")
    fun getEpisodesOfAssistantFlow(assistantId: String): Flow<List<ChatEpisodeEntity>>

    @Query(
        """
        SELECT
            id,
            assistant_id AS assistantId,
            substr(content, 1, :contentPreviewLimit) AS content,
            embedding_model_id AS embeddingModelId,
            start_time AS startTime,
            end_time AS endTime,
            significance,
            conversation_id AS conversationId,
            CASE
                WHEN embedding_model_id IS NULL OR embedding_model_id = '' THEN 0
                ELSE 1
            END AS hasEmbedding
        FROM ChatEpisodeEntity
        WHERE assistant_id = :assistantId
        ORDER BY end_time DESC
        LIMIT :limit
        """
    )
    fun getEpisodesForUiFlow(
        assistantId: String,
        limit: Int,
        contentPreviewLimit: Int,
    ): Flow<List<ChatEpisodeUiEntity>>

    @Query(
        """
        SELECT
            COUNT(*) AS totalEpisodes,
            AVG(significance) AS averageSignificance
        FROM ChatEpisodeEntity
        WHERE assistant_id = :assistantId
        """
    )
    fun getEpisodeOverviewStatsFlow(assistantId: String): Flow<ChatEpisodeOverviewStats>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEpisode(episode: ChatEpisodeEntity): Long

    /**
     * 批量写回访问时间，避免为改一个字段逐行重写整行（含 embedding 大列）。
     */
    @Query("UPDATE ChatEpisodeEntity SET last_accessed_at = :timestamp WHERE id IN (:ids)")
    suspend fun updateLastAccessedAt(ids: List<Int>, timestamp: Long)

    @Query("DELETE FROM ChatEpisodeEntity WHERE id = :id")
    suspend fun deleteEpisode(id: Int)

    @Query("DELETE FROM ChatEpisodeEntity WHERE assistant_id = :assistantId")
    suspend fun deleteEpisodesOfAssistant(assistantId: String)

    @Query("DELETE FROM ChatEpisodeEntity WHERE assistant_id = :assistantId AND start_time >= :startTime AND end_time <= :endTime")
    suspend fun deleteEpisodeByTimeRange(assistantId: String, startTime: Long, endTime: Long)

    @Query("SELECT COUNT(*) FROM ChatEpisodeEntity")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM ChatEpisodeEntity")
    fun getCountFlow(): Flow<Int>
    @Query("DELETE FROM chatepisodeentity WHERE conversation_id = :conversationId")
    suspend fun deleteEpisodeByConversationId(conversationId: String): Int

    @Query("SELECT * FROM chatepisodeentity WHERE conversation_id = :conversationId LIMIT 1")
    suspend fun getEpisodeByConversationId(conversationId: String): ChatEpisodeEntity?

    @Query("SELECT * FROM chatepisodeentity WHERE conversation_id = :conversationId AND assistant_id = :assistantId LIMIT 1")
    suspend fun getEpisodeByConversationIdAndAssistantId(conversationId: String, assistantId: String): ChatEpisodeEntity?

    @Query("SELECT * FROM chatepisodeentity WHERE id = :id")
    suspend fun getEpisodeById(id: Int): ChatEpisodeEntity?
}
