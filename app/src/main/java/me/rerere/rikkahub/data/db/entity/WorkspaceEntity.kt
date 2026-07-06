package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import me.rerere.rikkahub.utils.JsonInstant

/**
 * 默认即需要审批的工具集合（未在 toolApprovals 覆盖中时生效）。
 *
 * 仅放「有副作用 / 不可逆」的工具：写文件、删除、执行 python、执行脚本。
 * 列目录、读文件、建目录、重命名默认免审批，降低交互噪音。
 */
val DEFAULT_NEEDS_APPROVAL_TOOLS: Set<String> = setOf(
    "workspace_write_file",
    "workspace_delete",
    "eval_python",
    "run_skill_script",
)

/**
 * 取某工具的默认「是否需要审批」。供未覆盖时的回退判断。
 */
fun toolDefaultNeedsApproval(toolName: String): Boolean = toolName in DEFAULT_NEEDS_APPROVAL_TOOLS

/**
 * 工作区涉及的全部工具名，固定顺序（设置页列表与迁移都按此顺序）。
 * 放在数据层，供 [WorkspaceEntity]、Repository 迁移、UI 共用。
 */
val WORKSPACE_TOOL_NAMES: List<String> = listOf(
    "workspace_list",
    "workspace_read_file",
    "workspace_write_file",
    "workspace_mkdir",
    "workspace_delete",
    "workspace_rename",
    "eval_python",
    "run_skill_script",
)

/**
 * 工作区记录。
 *
 * 这一阶段工作区 = 一个 SAF 授权目录（[treeUri]），绑定在助手上。
 * [shellStatus] 为沙盒预留字段，当前恒为 DISABLED；未来移植沙盒时会启用。
 * [toolApprovals] 是每个工具名的审批覆盖（toolName -> needsApproval，true=需要审批）。
 * 未覆盖的工具走 [DEFAULT_NEEDS_APPROVAL_TOOLS] 的默认值。
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