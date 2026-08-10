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

/** Lightweight row used by lexical memory retrieval. It deliberately excludes embeddings. */
data class MemoryRetrievalRow(
    val id: Int,
    val assistantId: String,
    val content: String,
    val type: Int,
    val pinned: Boolean,
    val timestamp: Long,
    val significance: Int?,
)

@Dao
interface MemoryDAO {
    @Query(
        """
        SELECT
            id AS id,
            assistant_id AS assistantId,
            content AS content,
            type AS type,
            pinned AS pinned,
            created_at AS timestamp,
            CAST(NULL AS INTEGER) AS significance
        FROM memoryentity
        WHERE assistant_id = :assistantId AND :includeCore = 1

        UNION ALL

        SELECT
            -id AS id,
            assistant_id AS assistantId,
            content AS content,
            1 AS type,
            0 AS pinned,
            CASE WHEN end_time > start_time THEN end_time ELSE start_time END AS timestamp,
            significance AS significance
        FROM chatepisodeentity
        WHERE assistant_id = :assistantId AND :includeEpisodes = 1
        """
    )
    suspend fun getMemoryRetrievalRows(
        assistantId: String,
        includeCore: Boolean,
        includeEpisodes: Boolean,
    ): List<MemoryRetrievalRow>

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

    @Query(
        """
        SELECT
            CASE WHEN :includeCore THEN (
                SELECT COUNT(*) FROM memoryentity
                WHERE assistant_id = :assistantId
                  AND trim(content) != ''
                  AND (
                      embedding IS NULL OR trim(embedding) = ''
                      OR embedding_model_id IS NULL OR embedding_model_id != :modelId
                  )
            ) ELSE 0 END
            +
            CASE WHEN :includeEpisodes THEN (
                SELECT COUNT(*) FROM chatepisodeentity
                WHERE assistant_id = :assistantId
                  AND trim(content) != ''
                  AND (
                      embedding IS NULL OR trim(embedding) = ''
                      OR embedding_model_id IS NULL OR embedding_model_id != :modelId
                  )
            ) ELSE 0 END
        """
    )
    suspend fun getPendingEmbeddingCount(
        assistantId: String,
        modelId: String,
        includeCore: Boolean,
        includeEpisodes: Boolean,
    ): Int

    @Query("SELECT AVG(LENGTH(content)) FROM memoryentity WHERE assistant_id = :assistantId")
    fun getAverageMemoryContentLengthFlow(assistantId: String): Flow<Double?>

    @Query("SELECT * FROM memoryentity WHERE id = :id")
    suspend fun getMemoryById(id: Int): MemoryEntity?

    @Query("SELECT * FROM memoryentity")
    suspend fun getAllMemoriesSuspend(): List<MemoryEntity>

    @Insert
    suspend fun insertMemory(memory: MemoryEntity): Long

    @Update
    suspend fun updateMemory(memory: MemoryEntity)

    @Query(
        """
        UPDATE memoryentity
        SET embedding = :embedding, embedding_model_id = :modelId
        WHERE id = :id
          AND content = :expectedContent
          AND (
              embedding_model_id = :expectedModelId
              OR (embedding_model_id IS NULL AND :expectedModelId IS NULL)
          )
        """
    )
    suspend fun updateEmbeddingIfContentMatches(
        id: Int,
        expectedContent: String,
        expectedModelId: String?,
        embedding: String,
        modelId: String,
    ): Int

    /**
     * 批量写回访问时间，避免为改一个字段逐行重写整行（含 embedding 大列）。
     */
    @Query("UPDATE memoryentity SET last_accessed_at = :timestamp WHERE id IN (:ids)")
    suspend fun updateLastAccessedAt(ids: List<Int>, timestamp: Long)

    @Query("DELETE FROM memoryentity WHERE id = :id")
    suspend fun deleteMemory(id: Int)

    @Query("DELETE FROM memoryentity WHERE assistant_id = :assistantId")
    suspend fun deleteMemoriesOfAssistant(assistantId: String)
}
