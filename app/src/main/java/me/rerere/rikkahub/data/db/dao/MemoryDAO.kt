package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.MemoryEntity

@Dao
interface MemoryDAO {
    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId")
    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId")
    suspend fun getMemoriesOfAssistant(assistantId: String): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId AND type = :type AND archived_at IS NULL")
    fun getActiveMemoriesOfAssistantByTypeFlow(assistantId: String, type: Int): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId AND type = :type AND archived_at IS NULL")
    suspend fun getActiveMemoriesOfAssistantByType(assistantId: String, type: Int): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId AND type = :type AND archived_at IS NULL ORDER BY created_at DESC LIMIT :limit")
    suspend fun getRecentActiveMemoriesOfAssistantByType(assistantId: String, type: Int, limit: Int): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId AND type = :type AND archived_at IS NOT NULL ORDER BY archived_at DESC")
    suspend fun getArchivedMemoriesOfAssistantByType(assistantId: String, type: Int): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId ORDER BY created_at DESC LIMIT :limit")
    suspend fun getRecentMemoriesOfAssistant(assistantId: String, limit: Int): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity WHERE id = :id")
    suspend fun getMemoryById(id: Int): MemoryEntity?

    @Insert
    suspend fun insertMemory(memory: MemoryEntity): Long

    @Update
    suspend fun updateMemory(memory: MemoryEntity)

    @Query("UPDATE memoryentity SET last_accessed_at = :now, access_count = access_count + 1 WHERE id IN (:ids)")
    suspend fun reinforceMemories(ids: List<Int>, now: Long)

    @Query("UPDATE memoryentity SET archived_at = :archivedAt WHERE id IN (:ids)")
    suspend fun archiveMemories(ids: List<Int>, archivedAt: Long)

    @Query("UPDATE memoryentity SET archived_at = NULL WHERE id IN (:ids)")
    suspend fun unarchiveMemories(ids: List<Int>)

    @Query("SELECT id FROM memoryentity WHERE assistant_id = :assistantId AND type = :type AND archived_at IS NOT NULL AND archived_at <= :archivedBefore AND is_pinned = 0")
    suspend fun getPurgableMemoryIds(assistantId: String, type: Int, archivedBefore: Long): List<Int>

    @Query("DELETE FROM memoryentity WHERE id IN (:ids)")
    suspend fun deleteMemoriesByIds(ids: List<Int>): Int

    @Query("DELETE FROM memoryentity WHERE id = :id")
    suspend fun deleteMemory(id: Int)

    @Query("DELETE FROM memoryentity WHERE assistant_id = :assistantId")
    suspend fun deleteMemoriesOfAssistant(assistantId: String)
}
