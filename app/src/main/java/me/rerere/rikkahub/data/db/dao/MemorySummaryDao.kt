package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.MemorySummaryChangeEntity
import me.rerere.rikkahub.data.db.entity.MemorySummaryVersionEntity

@Dao
interface MemorySummaryDao {
    @Query("SELECT * FROM memory_summary_versions WHERE assistant_id = :assistantId ORDER BY generated_at DESC, id DESC LIMIT 1")
    suspend fun getActiveVersion(assistantId: String): MemorySummaryVersionEntity?

    @Query("SELECT * FROM memory_summary_versions WHERE assistant_id = :assistantId ORDER BY generated_at DESC, id DESC")
    fun observeVersions(assistantId: String): Flow<List<MemorySummaryVersionEntity>>

    @Query("SELECT * FROM memory_summary_versions WHERE assistant_id = :assistantId ORDER BY generated_at DESC, id DESC")
    suspend fun getVersions(assistantId: String): List<MemorySummaryVersionEntity>

    @Insert
    suspend fun insertVersion(version: MemorySummaryVersionEntity): Long

    @Query("DELETE FROM memory_summary_versions WHERE id IN (:ids)")
    suspend fun deleteVersions(ids: List<Long>)

    @Query("DELETE FROM memory_summary_versions WHERE assistant_id = :assistantId")
    suspend fun deleteVersionsOfAssistant(assistantId: String)

    @Query("SELECT * FROM memory_summary_changes WHERE assistant_id = :assistantId ORDER BY changed_at ASC")
    suspend fun getChanges(assistantId: String): List<MemorySummaryChangeEntity>

    @Query("SELECT * FROM memory_summary_changes WHERE assistant_id = :assistantId AND memory_type = :memoryType AND memory_id = :memoryId LIMIT 1")
    suspend fun getChange(assistantId: String, memoryType: Int, memoryId: Int): MemorySummaryChangeEntity?

    @Query("SELECT COUNT(*) FROM memory_summary_changes WHERE assistant_id = :assistantId")
    fun observeChangeCount(assistantId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM memory_summary_changes WHERE assistant_id = :assistantId")
    suspend fun getChangeCount(assistantId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChange(change: MemorySummaryChangeEntity)

    @Query("DELETE FROM memory_summary_changes WHERE assistant_id = :assistantId AND memory_type = :memoryType AND memory_id = :memoryId")
    suspend fun deleteChange(assistantId: String, memoryType: Int, memoryId: Int)

    @Query("DELETE FROM memory_summary_changes WHERE assistant_id = :assistantId AND memory_type = :memoryType AND memory_id = :memoryId AND change_token = :changeToken")
    suspend fun deleteChangeIfTokenMatches(assistantId: String, memoryType: Int, memoryId: Int, changeToken: String)

    @Query("DELETE FROM memory_summary_changes WHERE assistant_id = :assistantId")
    suspend fun deleteChangesOfAssistant(assistantId: String)
}
