package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import me.rerere.rikkahub.utils.JsonInstant

enum class WorkspaceType {
    LIGHTWEIGHT,
    SANDBOX,
}

enum class SandboxRootfsStatus {
    DISABLED,
    INSTALLING,
    READY,
    BROKEN,
}

/** 轻量模式现有的工具集合。 */
val LIGHTWEIGHT_WORKSPACE_TOOL_NAMES: List<String> = listOf(
    "workspace_list",
    "workspace_read_file",
    "workspace_write_file",
    "workspace_mkdir",
    "workspace_delete",
    "workspace_rename",
)

/** 沙盒模式只会暴露这一组独立协议的工具。 */
val SANDBOX_WORKSPACE_TOOL_NAMES: List<String> = listOf(
    "sandbox_read_file",
    "sandbox_write_file",
    "sandbox_edit_file",
    "sandbox_shell",
)

/** 兼容既有设置页与迁移调用；新代码应按 [WorkspaceType] 取工具。 */
val WORKSPACE_TOOL_NAMES: List<String> = LIGHTWEIGHT_WORKSPACE_TOOL_NAMES

private val DEFAULT_NEEDS_APPROVAL_TOOLS: Set<String> = setOf(
    "workspace_write_file",
    "workspace_delete",
    "sandbox_shell",
)

fun workspaceToolNames(type: WorkspaceType): List<String> = when (type) {
    WorkspaceType.LIGHTWEIGHT -> LIGHTWEIGHT_WORKSPACE_TOOL_NAMES
    WorkspaceType.SANDBOX -> SANDBOX_WORKSPACE_TOOL_NAMES
}

fun toolDefaultNeedsApproval(toolName: String): Boolean = toolName in DEFAULT_NEEDS_APPROVAL_TOOLS

/**
 * 工作区统一登记记录。
 *
 * 类型一经创建不可改变；实际存储位置分别由 workspace_saf_details 与
 * workspace_sandbox_details 保存，避免把 SAF URI 与内部 Rootfs 混为一谈。
 */
@Entity(
    tableName = "workspaces",
    indices = [Index(value = ["updated_at"]), Index(value = ["type"])],
)
data class WorkspaceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: WorkspaceType,
    @ColumnInfo(name = "tool_approvals", defaultValue = "{}")
    val toolApprovals: String = "{}",
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "last_access_at") val lastAccessAt: Long? = null,
) {
    fun toolApprovalOverrides(): Map<String, Boolean> = runCatching {
        JsonInstant.decodeFromString<Map<String, Boolean>>(toolApprovals)
    }.getOrDefault(emptyMap())
}

@Entity(
    tableName = "workspace_saf_details",
    indices = [Index(value = ["tree_uri"], unique = true)],
)
data class SafWorkspaceEntity(
    @PrimaryKey @ColumnInfo(name = "workspace_id") val workspaceId: String,
    @ColumnInfo(name = "tree_uri") val treeUri: String,
)

@Entity(tableName = "workspace_sandbox_details")
data class SandboxWorkspaceEntity(
    @PrimaryKey @ColumnInfo(name = "workspace_id") val workspaceId: String,
    @ColumnInfo(name = "rootfs_status") val rootfsStatus: SandboxRootfsStatus = SandboxRootfsStatus.DISABLED,
    @ColumnInfo(name = "rootfs_source_url") val rootfsSourceUrl: String? = null,
    @ColumnInfo(name = "rootfs_version") val rootfsVersion: String? = null,
    @ColumnInfo(name = "rootfs_installed_at") val rootfsInstalledAt: Long? = null,
)
