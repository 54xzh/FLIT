package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.SafWorkspaceEntity
import me.rerere.rikkahub.data.db.entity.SandboxRootfsStatus
import me.rerere.rikkahub.data.db.entity.SandboxWorkspaceEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity

@Dao
interface WorkspaceDao {
    @Query("SELECT * FROM workspaces ORDER BY updated_at DESC")
    fun listFlow(): Flow<List<WorkspaceEntity>>

    @Query("SELECT * FROM workspaces WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): WorkspaceEntity?

    @Query("SELECT * FROM workspaces")
    suspend fun getAll(): List<WorkspaceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(workspace: WorkspaceEntity)

    @Query("DELETE FROM workspaces WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("UPDATE workspaces SET tool_approvals = :toolApprovals, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateToolApprovals(id: String, toolApprovals: String, updatedAt: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSafDetail(detail: SafWorkspaceEntity)

    @Query("SELECT * FROM workspace_saf_details WHERE workspace_id = :workspaceId LIMIT 1")
    suspend fun getSafDetail(workspaceId: String): SafWorkspaceEntity?

    @Query("SELECT * FROM workspace_saf_details WHERE tree_uri = :treeUri LIMIT 1")
    suspend fun getSafDetailByTreeUri(treeUri: String): SafWorkspaceEntity?

    @Query("DELETE FROM workspace_saf_details WHERE workspace_id = :workspaceId")
    suspend fun deleteSafDetail(workspaceId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSandboxDetail(detail: SandboxWorkspaceEntity)

    @Query("SELECT * FROM workspace_sandbox_details WHERE workspace_id = :workspaceId LIMIT 1")
    suspend fun getSandboxDetail(workspaceId: String): SandboxWorkspaceEntity?

    @Query("UPDATE workspace_sandbox_details SET rootfs_status = :status, rootfs_source_url = :sourceUrl, rootfs_version = :version, rootfs_installed_at = :installedAt WHERE workspace_id = :workspaceId")
    suspend fun updateSandboxRootfs(
        workspaceId: String,
        status: SandboxRootfsStatus,
        sourceUrl: String?,
        version: String?,
        installedAt: Long?,
    ): Int

    @Query("DELETE FROM workspace_sandbox_details WHERE workspace_id = :workspaceId")
    suspend fun deleteSandboxDetail(workspaceId: String): Int
}
