package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.SandboxRootfsStatus
import me.rerere.rikkahub.data.db.entity.WorkspaceType
import me.rerere.rikkahub.data.db.entity.toolDefaultNeedsApproval
import me.rerere.rikkahub.data.repository.Workspace
import me.rerere.rikkahub.ui.pages.setting.components.SettingGroupItem
import me.rerere.rikkahub.ui.pages.setting.components.SettingsGroup

@Composable
fun WorkspaceBasicPage(
    workspace: Workspace?,
    toolApprovals: Map<String, Boolean>,
    toolNames: List<String>,
    friendlyRootPath: String?,
    installingRootfs: Boolean,
    onInstallRootfs: (String) -> Unit,
    onSetToolApproval: (String, Boolean) -> Unit,
    onSetAll: (Boolean) -> Unit,
) {
    val ws = workspace ?: return
    val allNeedApproval = toolNames.all { toolApprovals[it] ?: toolDefaultNeedsApproval(it) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            SettingsGroup(title = stringResource(R.string.workspace_page_root_label)) {
                SettingGroupItem(
                    title = ws.name,
                    subtitle = when (ws.type) {
                        WorkspaceType.LIGHTWEIGHT -> friendlyRootPath ?: ws.treeUri.orEmpty()
                        WorkspaceType.SANDBOX -> stringResource(R.string.workspace_type_sandbox)
                    },
                )
            }
        }
        if (ws.type == WorkspaceType.SANDBOX) {
            item {
                val status = ws.sandboxStatus ?: SandboxRootfsStatus.DISABLED
                var sourceUrl by remember(ws.id, ws.sandbox?.rootfsSourceUrl) {
                    mutableStateOf(ws.sandbox?.rootfsSourceUrl ?: defaultRootfsUrl())
                }
                SettingsGroup(title = stringResource(R.string.workspace_sandbox_rootfs_title)) {
                    SettingGroupItem(
                        title = stringResource(R.string.workspace_sandbox_rootfs_status),
                        subtitle = sandboxStatusText(status),
                    )
                    OutlinedTextField(
                        value = sourceUrl,
                        onValueChange = { sourceUrl = it },
                        label = { Text(stringResource(R.string.workspace_sandbox_rootfs_url)) },
                        supportingText = { Text(stringResource(R.string.workspace_sandbox_rootfs_unverified)) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                    TextButton(
                        enabled = !installingRootfs && sourceUrl.isNotBlank(),
                        onClick = { onInstallRootfs(sourceUrl.trim()) },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        Text(stringResource(if (status == SandboxRootfsStatus.READY) R.string.workspace_sandbox_reinstall_rootfs else R.string.workspace_sandbox_install_rootfs))
                    }
                }
            }
        }
        item {
            SettingsGroup(title = stringResource(R.string.workspace_detail_tool_approvals)) {
                SettingGroupItem(
                    title = stringResource(R.string.workspace_detail_tool_approve_all),
                    subtitle = stringResource(R.string.workspace_detail_tool_approve_all_desc),
                    trailing = { Switch(checked = allNeedApproval, onCheckedChange = onSetAll) },
                )
                toolNames.forEach { toolName ->
                    val needsApproval = toolApprovals[toolName] ?: toolDefaultNeedsApproval(toolName)
                    SettingGroupItem(
                        title = toolName,
                        subtitle = stringResource(toolNameToDesc(toolName)),
                        trailing = { Switch(checked = needsApproval, onCheckedChange = { onSetToolApproval(toolName, it) }) },
                    )
                }
            }
        }
    }
}

private fun defaultRootfsUrl(): String = if (Build.SUPPORTED_ABIS.any { it == "x86_64" } && Build.SUPPORTED_ABIS.firstOrNull() == "x86_64") {
    "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-amd64.tar.gz"
} else {
    "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz"
}

private fun toolNameToDesc(toolName: String): Int = when (toolName) {
    "workspace_list" -> R.string.workspace_detail_tool_workspace_list_desc
    "workspace_read_file" -> R.string.workspace_detail_tool_workspace_read_file_desc
    "workspace_write_file" -> R.string.workspace_detail_tool_workspace_write_file_desc
    "workspace_mkdir" -> R.string.workspace_detail_tool_workspace_mkdir_desc
    "workspace_delete" -> R.string.workspace_detail_tool_workspace_delete_desc
    "workspace_rename" -> R.string.workspace_detail_tool_workspace_rename_desc
    "eval_python" -> R.string.workspace_detail_tool_eval_python_desc
    "run_skill_script" -> R.string.workspace_detail_tool_run_skill_script_desc
    "sandbox_read_file" -> R.string.workspace_sandbox_tool_read_desc
    "sandbox_write_file" -> R.string.workspace_sandbox_tool_write_desc
    "sandbox_edit_file" -> R.string.workspace_sandbox_tool_edit_desc
    "sandbox_shell" -> R.string.workspace_sandbox_tool_shell_desc
    else -> R.string.workspace_detail_tool_workspace_list_desc
}
