package me.rerere.rikkahub.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.MemoryEntity

data class AssistantMemoryPagingRow(
    val id: Int,
    val content: String,
    val type: Int,
    val hasEmbedding: Boolean,
    val embeddingModelId: String?,
    val timestamp: Long,
    val significance: Int?,
    val pinned: Boolean,
)

data class AssistantMemoryStatsRow(
    val coreCount: Int,
    val episodicCount: Int,
    val embeddedCount: Int,
)

@Dao
interface MemoryDAO {
    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId")
    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId")
    suspend fun getMemoriesOfAssistant(assistantId: String): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId AND pinned = 1 ORDER BY created_at ASC, id ASC")
    suspend fun getPinnedMemoriesOfAssistant(assistantId: String): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId ORDER BY created_at DESC LIMIT :limit")
    suspend fun getRecentMemoriesOfAssistant(assistantId: String, limit: Int): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId ORDER BY created_at DESC LIMIT :limit")
    fun getRecentMemoriesOfAssistantFlow(assistantId: String, limit: Int): Flow<List<MemoryEntity>>

    @Query(
        """
        SELECT * FROM (
            SELECT
                id AS id,
                content AS content,
                type AS type,
                CASE
                    WHEN embedding_model_id IS NULL OR embedding_model_id = '' THEN 0
                    ELSE 1
                END AS hasEmbedding,
                embedding_model_id AS embeddingModelId,
                created_at AS timestamp,
                NULL AS significance,
                pinned AS pinned
            FROM memoryentity
            WHERE assistant_id = :assistantId

            UNION ALL

            SELECT
                -id AS id,
                substr(content, 1, :episodeContentPreviewLimit) AS content,
                1 AS type,
                CASE
                    WHEN embedding_model_id IS NULL OR embedding_model_id = '' THEN 0
                    ELSE 1
                END AS hasEmbedding,
                embedding_model_id AS embeddingModelId,
                start_time AS timestamp,
                significance AS significance,
                0 AS pinned
            FROM chatepisodeentity
            WHERE assistant_id = :assistantId
        )
        WHERE (:memoryType < 0 OR type = :memoryType)
          AND (:searchQuery = '' OR content LIKE '%' || :searchQuery || '%')
        ORDER BY
            pinned DESC,
            CASE WHEN pinned = 1 THEN timestamp END ASC,
            CASE WHEN pinned = 1 THEN id END ASC,
            CASE WHEN pinned = 0 AND :sortOrder = 0 THEN timestamp END DESC,
            CASE WHEN pinned = 0 AND :sortOrder = 1 THEN timestamp END ASC,
            CASE WHEN pinned = 0 AND :sortOrder = 2 THEN content END COLLATE NOCASE ASC
        """
    )
    fun getAssistantMemoriesPaging(
        assistantId: String,
        memoryType: Int,
        searchQuery: String,
        sortOrder: Int,
        episodeContentPreviewLimit: Int,
    ): PagingSource<Int, AssistantMemoryPagingRow>

    @Query(
        """
        SELECT
            (SELECT COUNT(*) FROM memoryentity WHERE assistant_id = :assistantId) AS coreCount,
            (SELECT COUNT(*) FROM chatepisodeentity WHERE assistant_id = :assistantId) AS episodicCount,
            (
                (SELECT COUNT(*) FROM memoryentity WHERE assistant_id = :assistantId AND embedding_model_id IS NOT NULL AND embedding_model_id != '')
                +
                (SELECT COUNT(*) FROM chatepisodeentity WHERE assistant_id = :assistantId AND embedding_model_id IS NOT NULL AND embedding_model_id != '')
            ) AS embeddedCount
        """
    )
    fun getAssistantMemoryStatsFlow(assistantId: String): Flow<AssistantMemoryStatsRow>

    @Query(
        """
        SELECT
            (
                (SELECT COUNT(*) FROM memoryentity WHERE assistant_id = :assistantId AND trim(content) != '' AND (embedding_model_id IS NULL OR embedding_model_id = ''))
                +
                (SELECT COUNT(*) FROM chatepisodeentity WHERE assistant_id = :assistantId AND trim(content) != '' AND (embedding_model_id IS NULL OR embedding_model_id = ''))
            )
        """
    )
    fun getPendingEmbeddingCountFlow(assistantId: String): Flow<Int>

    @Query("SELECT AVG(LENGTH(content)) FROM memoryentity WHERE assistant_id = :assistantId")
    fun getAverageMemoryContentLengthFlow(assistantId: String): Flow<Double?>

    @Query("SELECT * FROM memoryentity WHERE id = :id")
    suspend fun getMemoryById(id: Int): MemoryEntity?

    @Insert
    suspend fun insertMemory(memory: MemoryEntity): Long

    @Update
    suspend fun updateMemory(memory: MemoryEntity)

    @Query("DELETE FROM memoryentity WHERE id = :id")
    suspend fun deleteMemory(id: Int)

    @Query("DELETE FROM memoryentity WHERE assistant_id = :assistantId")
    suspend fun deleteMemoriesOfAssistant(assistantId: String)
}
