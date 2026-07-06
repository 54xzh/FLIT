package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity

@Dao
interface WorkspaceDao {
    @Query("SELECT * FROM workspaces ORDER BY updated_at DESC")
    fun listFlow(): Flow<List<WorkspaceEntity>>

    @Query("SELECT * FROM workspaces WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): WorkspaceEntity?

    @Query("SELECT * FROM workspaces WHERE tree_uri = :treeUri LIMIT 1")
    suspend fun getByTreeUri(treeUri: String): WorkspaceEntity?

    @Query("SELECT * FROM workspaces")
    suspend fun getAll(): List<WorkspaceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(workspace: WorkspaceEntity)

    @Query("DELETE FROM workspaces WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("UPDATE workspaces SET tool_approvals = :toolApprovals, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateToolApprovals(id: String, toolApprovals: String, updatedAt: Long): Int
}