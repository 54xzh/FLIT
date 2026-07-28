package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.ScheduledTaskEntity

@Dao
interface ScheduledTaskDao {
    @Query(
        """
        SELECT * FROM scheduled_tasks
        WHERE assistant_id = :assistantId
        ORDER BY enabled DESC, (next_run_at IS NULL) ASC, next_run_at ASC
        """
    )
    fun observeTasksOfAssistant(assistantId: String): Flow<List<ScheduledTaskEntity>>

    @Query("SELECT * FROM scheduled_tasks WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ScheduledTaskEntity?

    @Query("SELECT * FROM scheduled_tasks WHERE enabled = 1")
    suspend fun getAllEnabled(): List<ScheduledTaskEntity>

    @Query("SELECT id FROM scheduled_tasks WHERE assistant_id = :assistantId")
    suspend fun getTaskIdsOfAssistant(assistantId: String): List<String>

    @Query("SELECT id FROM scheduled_tasks WHERE assistant_id = :assistantId AND enabled = 1")
    suspend fun getEnabledTaskIdsOfAssistant(assistantId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: ScheduledTaskEntity)

    @Query("DELETE FROM scheduled_tasks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM scheduled_tasks WHERE assistant_id = :assistantId")
    suspend fun deleteByAssistantId(assistantId: String)

    @Query("UPDATE scheduled_tasks SET enabled = :enabled WHERE id = :id")
    suspend fun updateEnabled(id: String, enabled: Boolean)

    @Query("UPDATE scheduled_tasks SET enabled = 0 WHERE assistant_id = :assistantId")
    suspend fun disableByAssistantId(assistantId: String)

    @Query(
        """
        UPDATE scheduled_tasks
        SET mcp_server_id = NULLIF(
            TRIM(REPLACE(',' || COALESCE(mcp_server_id, '') || ',', ',' || :serverId || ',', ','), ','),
            ''
        )
        WHERE ',' || COALESCE(mcp_server_id, '') || ',' LIKE '%,' || :serverId || ',%'
        """
    )
    suspend fun removeMcpServerOverride(serverId: String)

    @Query(
        """
        UPDATE scheduled_tasks
        SET last_run_at = :lastRunAt,
            last_scheduled_for = :lastScheduledFor,
            next_run_at = :nextRunAt,
            last_error_code = :lastErrorCode,
            last_error_at = :lastErrorAt
        WHERE id = :id
        """
    )
    suspend fun updateRunFields(
        id: String,
        lastRunAt: Long?,
        lastScheduledFor: Long?,
        nextRunAt: Long?,
        lastErrorCode: String?,
        lastErrorAt: Long?,
    )
}
