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
    // LEFT JOIN 两张详情表仅用于让 Room 的失效跟踪覆盖详情表变更：
    // 沙盒安装状态只写入 workspace_sandbox_details，主表本身不变，
    // 不 JOIN 的话 listFlow 不会在状态变更时重新发射。仍只 SELECT 主表列。
    @Query(
        "SELECT workspaces.* FROM workspaces " +
            "LEFT JOIN workspace_saf_details saf ON saf.workspace_id = workspaces.id " +
            "LEFT JOIN workspace_sandbox_details sb ON sb.workspace_id = workspaces.id " +
            "ORDER BY workspaces.updated_at DESC"
    )
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
