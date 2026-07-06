package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import me.rerere.rikkahub.utils.JsonInstant

/**
 * 工作区记录。
 *
 * 这一阶段工作区 = 一个 SAF 授权目录（[treeUri]），绑定在助手上。
 * [shellStatus] 为沙盒预留字段，当前恒为 DISABLED；未来移植沙盒时会启用。
 * [toolApprovals] 是每个工具名的审批覆盖（toolName -> needsApproval），未覆盖的工具走默认审批。
 */
@Entity(
    tableName = "workspaces",
    indices = [
        Index(value = ["tree_uri"], unique = true),
        Index(value = ["updated_at"]),
    ],
)
data class WorkspaceEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "tree_uri")
    val treeUri: String,
    @ColumnInfo(name = "shell_status", defaultValue = "DISABLED")
    val shellStatus: String = SHELL_STATUS_DISABLED,
    @ColumnInfo(name = "tool_approvals", defaultValue = "{}")
    val toolApprovals: String = "{}",
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "last_access_at")
    val lastAccessAt: Long? = null,
) {
    fun toolApprovalOverrides(): Map<String, Boolean> = runCatching {
        JsonInstant.decodeFromString<Map<String, Boolean>>(toolApprovals)
    }.getOrDefault(emptyMap())

    companion object {
        const val SHELL_STATUS_DISABLED = "DISABLED"
    }
}