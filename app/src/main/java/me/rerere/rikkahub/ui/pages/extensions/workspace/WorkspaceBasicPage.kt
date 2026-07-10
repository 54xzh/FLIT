package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.SandboxRootfsStatus
import me.rerere.rikkahub.data.db.entity.WorkspaceType
import me.rerere.rikkahub.data.db.entity.toolDefaultNeedsApproval
import me.rerere.rikkahub.data.repository.Workspace
import me.rerere.rikkahub.ui.pages.setting.components.SettingGroupInputItem
import me.rerere.rikkahub.ui.pages.setting.components.SettingGroupItem
import me.rerere.rikkahub.ui.pages.setting.components.SettingsGroup
import me.rerere.rikkahub.workspace.SandboxRootfsInstallProgress
import me.rerere.rikkahub.workspace.SandboxRootfsInstallStage

@Composable
fun WorkspaceBasicPage(
    workspace: Workspace?,
    toolApprovals: Map<String, Boolean>,
    toolNames: List<String>,
    friendlyRootPath: String?,
    installProgress: SandboxRootfsInstallProgress?,
    installingRootfs: Boolean,
    onRequestInstallRootfs: () -> Unit,
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
                // 按钮禁用只看内存安装态；DB 的 INSTALLING 可能因进程被杀残留，不能用来永久灰掉按钮
                val rootfsReady = status == SandboxRootfsStatus.READY
                val installButtonText = when {
                    installingRootfs -> stringResource(R.string.workspace_sandbox_status_installing)
                    rootfsReady -> stringResource(R.string.workspace_sandbox_reinstall_rootfs)
                    else -> stringResource(R.string.workspace_sandbox_install_rootfs)
                }
                SettingsGroup(title = stringResource(R.string.workspace_sandbox_rootfs_title)) {
                    SettingGroupInputItem(
                        title = stringResource(R.string.workspace_sandbox_rootfs_status),
                        subtitle = sandboxStatusText(status),
                    ) {
                        Button(
                            enabled = !installingRootfs,
                            onClick = onRequestInstallRootfs,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(installButtonText)
                        }
                        installProgress?.let { progress ->
                            RootfsProgress(
                                progress = progress,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
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

@Composable
fun InstallRootfsDialog(
    workspaceName: String,
    initialUrl: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var url by rememberSaveable(workspaceName, initialUrl) {
        mutableStateOf(initialUrl?.takeIf { it.isNotBlank() } ?: defaultRootfsUrl())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_sandbox_install_rootfs)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.workspace_sandbox_install_rootfs_desc, workspaceName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.workspace_sandbox_rootfs_url)) },
                    supportingText = { Text(stringResource(R.string.workspace_sandbox_rootfs_unverified)) },
                    maxLines = 5,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(url.trim()) },
                enabled = url.trim().isNotBlank(),
            ) {
                Text(stringResource(R.string.workspace_sandbox_install_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun RootfsProgress(
    progress: SandboxRootfsInstallProgress,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val fraction = progress.totalBytes?.takeIf { it > 0 }?.let {
            (progress.bytesRead.toFloat() / it).coerceIn(0f, 1f)
        }
        if (fraction != null && progress.stage == SandboxRootfsInstallStage.DOWNLOADING) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            text = when (progress.stage) {
                SandboxRootfsInstallStage.DOWNLOADING -> {
                    val total = progress.totalBytes?.let { " / ${formatBytes(it)}" }.orEmpty()
                    stringResource(
                        R.string.workspace_sandbox_downloading,
                        formatBytes(progress.bytesRead),
                        total,
                    )
                }
                SandboxRootfsInstallStage.EXTRACTING -> {
                    val entry = progress.currentEntry?.let { " · $it" }.orEmpty()
                    stringResource(
                        R.string.workspace_sandbox_extracting,
                        progress.entriesExtracted,
                        entry,
                    )
                }
                SandboxRootfsInstallStage.INSTALLED -> stringResource(R.string.workspace_sandbox_install_complete)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun defaultRootfsUrl(): String = if (Build.SUPPORTED_ABIS.firstOrNull() == "x86_64") {
    "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-amd64.tar.gz"
} else {
    "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz"
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return "%.1f %s".format(value, units[unitIndex])
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
