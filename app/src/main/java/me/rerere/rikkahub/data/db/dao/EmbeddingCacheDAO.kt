package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.EmbeddingCacheEntity

@Dao
interface EmbeddingCacheDAO {
    
    /**
     * Get a cached embedding for a specific memory and model.
     */
    @Query("SELECT * FROM embedding_cache WHERE memory_id = :memoryId AND memory_type = :memoryType AND model_id = :modelId LIMIT 1")
    suspend fun getEmbedding(memoryId: Int, memoryType: Int, modelId: String): EmbeddingCacheEntity?
    
    /**
     * Batch load cached embeddings for a set of memories of one type and model.
     * 检索路径批量预取用，替代每条记忆一次的单点查询。
     */
    @Query("SELECT * FROM embedding_cache WHERE memory_type = :memoryType AND model_id = :modelId AND memory_id IN (:memoryIds)")
    suspend fun getEmbeddings(memoryIds: List<Int>, memoryType: Int, modelId: String): List<EmbeddingCacheEntity>

    /**
     * Insert or replace an embedding in the cache.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEmbedding(embedding: EmbeddingCacheEntity)
    
    /**
     * Check if an embedding exists for a specific memory and model.
     */
    @Query("SELECT COUNT(*) > 0 FROM embedding_cache WHERE memory_id = :memoryId AND memory_type = :memoryType AND model_id = :modelId")
    suspend fun hasEmbedding(memoryId: Int, memoryType: Int, modelId: String): Boolean
    
    /**
     * Get all embeddings for a specific model (useful for cleanup when model is deleted).
     */
    @Query("SELECT * FROM embedding_cache WHERE model_id = :modelId")
    suspend fun getEmbeddingsByModel(modelId: String): List<EmbeddingCacheEntity>
    
    /**
     * Delete all embeddings for a specific model.
     */
    @Query("DELETE FROM embedding_cache WHERE model_id = :modelId")
    suspend fun deleteByModelId(modelId: String)
    
    /**
     * Delete all embeddings for a specific memory.
     */
    @Query("DELETE FROM embedding_cache WHERE memory_id = :memoryId AND memory_type = :memoryType")
    suspend fun deleteByMemoryId(memoryId: Int, memoryType: Int)

    /**
     * Delete embeddings by a batch of memory IDs for a specific type.
     */
    @Query("DELETE FROM embedding_cache WHERE memory_type = :memoryType AND memory_id IN (:memoryIds)")
    suspend fun deleteByMemoryIds(memoryType: Int, memoryIds: List<Int>)
    
    /**
     * Count embeddings for a specific model (for statistics).
     */
    @Query("SELECT COUNT(*) FROM embedding_cache WHERE model_id = :modelId")
    suspend fun countEmbeddingsByModel(modelId: String): Int
    
    /**
     * Get all cached embeddings (for debugging).
     */
    @Query("SELECT * FROM embedding_cache")
    suspend fun getAllEmbeddings(): List<EmbeddingCacheEntity>
}
