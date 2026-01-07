package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.ChatEpisodeEntity

@Dao
interface ChatEpisodeDAO {
    @Query("SELECT * FROM ChatEpisodeEntity WHERE assistant_id = :assistantId ORDER BY end_time DESC")
    suspend fun getEpisodesOfAssistant(assistantId: String): List<ChatEpisodeEntity>

    @Query("SELECT * FROM ChatEpisodeEntity WHERE assistant_id = :assistantId ORDER BY end_time DESC LIMIT :limit")
    suspend fun getRecentEpisodesOfAssistant(assistantId: String, limit: Int): List<ChatEpisodeEntity>

    @Query("SELECT * FROM ChatEpisodeEntity WHERE assistant_id = :assistantId ORDER BY end_time DESC")
    fun getEpisodesOfAssistantFlow(assistantId: String): Flow<List<ChatEpisodeEntity>>

    @Query("SELECT * FROM ChatEpisodeEntity WHERE assistant_id = :assistantId AND archived_at IS NULL ORDER BY end_time DESC")
    suspend fun getActiveEpisodesOfAssistant(assistantId: String): List<ChatEpisodeEntity>

    @Query("SELECT * FROM ChatEpisodeEntity WHERE assistant_id = :assistantId AND archived_at IS NULL ORDER BY end_time DESC LIMIT :limit")
    suspend fun getRecentActiveEpisodesOfAssistant(assistantId: String, limit: Int): List<ChatEpisodeEntity>

    @Query("SELECT * FROM ChatEpisodeEntity WHERE assistant_id = :assistantId AND archived_at IS NULL ORDER BY end_time DESC")
    fun getActiveEpisodesOfAssistantFlow(assistantId: String): Flow<List<ChatEpisodeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEpisode(episode: ChatEpisodeEntity): Long

    @Query("UPDATE ChatEpisodeEntity SET last_accessed_at = :now, access_count = access_count + 1 WHERE id IN (:ids)")
    suspend fun reinforceEpisodes(ids: List<Int>, now: Long)

    @Query("UPDATE ChatEpisodeEntity SET archived_at = :archivedAt WHERE id IN (:ids)")
    suspend fun archiveEpisodes(ids: List<Int>, archivedAt: Long)

    @Query("UPDATE ChatEpisodeEntity SET archived_at = NULL WHERE id IN (:ids)")
    suspend fun unarchiveEpisodes(ids: List<Int>)

    @Query("SELECT id FROM ChatEpisodeEntity WHERE assistant_id = :assistantId AND archived_at IS NOT NULL AND archived_at <= :archivedBefore")
    suspend fun getPurgableEpisodeIds(assistantId: String, archivedBefore: Long): List<Int>

    @Query("DELETE FROM ChatEpisodeEntity WHERE id IN (:ids)")
    suspend fun deleteEpisodesByIds(ids: List<Int>): Int

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
