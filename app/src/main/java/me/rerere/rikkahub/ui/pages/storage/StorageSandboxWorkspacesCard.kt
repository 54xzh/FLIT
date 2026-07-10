package me.rerere.rikkahub.ui.pages.storage

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.SandboxRootfsStatus
import me.rerere.rikkahub.data.repository.SandboxWorkspaceUsage
import me.rerere.rikkahub.data.repository.StorageCategoryUsage
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.theme.AppShapes
import me.rerere.rikkahub.utils.UiState

@Composable
fun StorageSandboxWorkspacesCard(
    usageState: UiState<StorageCategoryUsage>,
    workspacesState: UiState<List<SandboxWorkspaceUsage>>,
    onCleanRootfs: (String) -> Unit,
) {
    val context = LocalContext.current
    val haptics = rememberPremiumHaptics()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 总览卡片：显示所有沙盒工作区合计占用。
        Card(
            shape = AppShapes.CardLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.storage_sandbox_title),
                    style = MaterialTheme.typography.titleMedium,
                )

                when (usageState) {
                    UiState.Idle,
                    UiState.Loading,
                    -> Text(
                        text = stringResource(R.string.storage_manager_loading_placeholder),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    is UiState.Error -> Text(
                        text = usageState.error.message ?: "Error",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )

                    is UiState.Success -> {
                        val bytes = usageState.data.bytes
                        val sizeText = runCatching { Formatter.formatShortFileSize(context, bytes) }.getOrNull()
                            ?: "${bytes} B"
                        // 文件数动辄上万，对用户没有参考价值；这里改显示沙盒工作区的个数。
                        // 个数取自工作区明细列表，加载完成前只显示占用大小。
                        val workspaceCount = (workspacesState as? UiState.Success)?.data?.size
                        Text(
                            text = if (workspaceCount != null) {
                                stringResource(R.string.storage_sandbox_overview_value, sizeText, workspaceCount)
                            } else {
                                sizeText
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.storage_sandbox_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 每个工作区一张卡：名字 + 状态 + 三块占用 + 清理按钮。
        when (workspacesState) {
            UiState.Idle -> Unit

            UiState.Loading -> Card(
                shape = AppShapes.CardLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Text(
                    modifier = Modifier.padding(16.dp),
                    text = stringResource(R.string.storage_manager_loading_placeholder),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            is UiState.Error -> Card(
                shape = AppShapes.CardLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Text(
                    modifier = Modifier.padding(16.dp),
                    text = workspacesState.error.message ?: "Error",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            is UiState.Success -> {
                if (workspacesState.data.isEmpty()) {
                    Card(
                        shape = AppShapes.CardLarge,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Text(
                            modifier = Modifier.padding(16.dp),
                            text = stringResource(R.string.storage_sandbox_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    workspacesState.data.forEach { workspace ->
                        SandboxWorkspaceRow(
                            workspace = workspace,
                            onCleanRootfs = onCleanRootfs,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SandboxWorkspaceRow(
    workspace: SandboxWorkspaceUsage,
    onCleanRootfs: (String) -> Unit,
) {
    val context = LocalContext.current
    val haptics = rememberPremiumHaptics()
    var showConfirm by rememberSaveable { mutableStateOf(false) }

    fun formatSize(bytes: Long): String = runCatching { Formatter.formatShortFileSize(context, bytes) }.getOrNull() ?: "${bytes} B"

    Card(
        shape = AppShapes.CardLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = workspace.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                RootfsStatusBadge(status = workspace.rootfsStatus)
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DirUsageLine(
                    label = stringResource(R.string.storage_sandbox_dir_files),
                    sizeText = formatSize(workspace.filesUsage.bytes),
                )
                DirUsageLine(
                    label = stringResource(R.string.storage_sandbox_dir_linux),
                    sizeText = formatSize(workspace.linuxUsage.bytes),
                )
                DirUsageLine(
                    label = stringResource(R.string.storage_sandbox_dir_tmp),
                    sizeText = formatSize(workspace.tmpUsage.bytes),
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CleanRootfsButton(
                    enabled = workspace.linuxUsage.bytes > 0L || workspace.tmpUsage.bytes > 0L,
                    onClick = {
                        haptics.perform(HapticPattern.Pop)
                        showConfirm = true
                    },
                )
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.storage_confirm_clear_sandbox_rootfs_title)) },
            text = {
                val desc = stringResource(R.string.storage_confirm_clear_sandbox_rootfs_desc)
                val warn = stringResource(R.string.storage_confirm_clear_sandbox_rootfs_warn)
                Text(
                    text = buildAnnotatedString {
                        append(desc)
                        append("\n\n")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(warn)
                        }
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptics.perform(HapticPattern.Thud)
                        showConfirm = false
                        onCleanRootfs(workspace.workspaceId)
                    }
                ) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun DirUsageLine(
    label: String,
    sizeText: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = sizeText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun RootfsStatusBadge(status: SandboxRootfsStatus) {
    val (textRes, color) = when (status) {
        SandboxRootfsStatus.READY -> R.string.storage_sandbox_status_ready to MaterialTheme.colorScheme.primary
        SandboxRootfsStatus.INSTALLING -> R.string.storage_sandbox_status_installing to MaterialTheme.colorScheme.tertiary
        SandboxRootfsStatus.BROKEN -> R.string.storage_sandbox_status_broken to MaterialTheme.colorScheme.error
        SandboxRootfsStatus.DISABLED -> R.string.storage_sandbox_status_disabled to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = color,
        maxLines = 1,
    )
}

@Composable
private fun CleanRootfsButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val haptics = rememberPremiumHaptics()

    FilledTonalButton(
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        onClick = {
            haptics.perform(HapticPattern.Pop)
            onClick()
        },
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Icon(Icons.Rounded.DeleteForever, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.storage_action_clear_sandbox_rootfs))
    }
}