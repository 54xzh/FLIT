package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.WORKSPACE_TOOL_NAMES
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.db.entity.toolDefaultNeedsApproval
import me.rerere.rikkahub.ui.pages.setting.components.SettingsGroup
import me.rerere.rikkahub.ui.pages.setting.components.SettingGroupItem

/**
 * 工作区详情 - 设置页（page 0）。
 *
 * 纯展示组件，展示工作区根目录信息 + 工具授权开关。
 * 重命名/删除工作区的对话框由壳 WorkspaceDetailPage 持有。
 *
 * 开关语义：on = 需要审批（弹审批卡片），off = 免审批。与底层数据
 * [WorkspaceEntity.toolApprovals]（true=需要审批）一致，无需取反。
 */
@Composable
fun WorkspaceBasicPage(
    workspace: WorkspaceEntity?,
    toolApprovals: Map<String, Boolean>,
    friendlyRootPath: String?,
    onSetToolApproval: (String, Boolean) -> Unit,
    onSetAll: (Boolean) -> Unit,
) {
    if (workspace == null) return
    val allNeedApproval = WORKSPACE_TOOL_NAMES.all { needsApproval(toolApprovals, it) }

    LazyColumn(
        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            SettingsGroup(title = stringResource(R.string.workspace_page_root_label)) {
                SettingGroupItem(
                    title = workspace.name,
                    subtitle = friendlyRootPath ?: workspace.treeUri,
                )
            }
        }
        item {
            SettingsGroup(title = stringResource(R.string.workspace_detail_tool_approvals)) {
                SettingGroupItem(
                    title = stringResource(R.string.workspace_detail_tool_approve_all),
                    subtitle = stringResource(R.string.workspace_detail_tool_approve_all_desc),
                    trailing = {
                        Switch(
                            checked = allNeedApproval,
                            onCheckedChange = { v -> onSetAll(v) },
                        )
                    },
                )
                WORKSPACE_TOOL_NAMES.forEach { toolName ->
                    val needsApproval = needsApproval(toolApprovals, toolName)
                    SettingGroupItem(
                        title = toolName,
                        subtitle = stringResource(toolNameToDesc(toolName)),
                        trailing = {
                            Switch(
                                checked = needsApproval,
                                onCheckedChange = { v -> onSetToolApproval(toolName, v) },
                            )
                        },
                    )
                }
            }
        }
    }
}

/**
 * 取某工具当前是否需要审批：有覆盖用覆盖，否则用默认（仅写/删/python/脚本默认需审批）。
 */
private fun needsApproval(toolApprovals: Map<String, Boolean>, toolName: String): Boolean =
    toolApprovals[toolName] ?: toolDefaultNeedsApproval(toolName)

private fun toolNameToDesc(toolName: String): Int = when (toolName) {
    "workspace_list" -> R.string.workspace_detail_tool_workspace_list_desc
    "workspace_read_file" -> R.string.workspace_detail_tool_workspace_read_file_desc
    "workspace_write_file" -> R.string.workspace_detail_tool_workspace_write_file_desc
    "workspace_mkdir" -> R.string.workspace_detail_tool_workspace_mkdir_desc
    "workspace_delete" -> R.string.workspace_detail_tool_workspace_delete_desc
    "workspace_rename" -> R.string.workspace_detail_tool_workspace_rename_desc
    "eval_python" -> R.string.workspace_detail_tool_eval_python_desc
    "run_skill_script" -> R.string.workspace_detail_tool_run_skill_script_desc
    else -> R.string.workspace_detail_tool_workspace_list_desc
}
