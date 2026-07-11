package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.workspace.WorkspaceTransferStage

@Composable
fun WorkspaceTransferDialog(
    state: WorkspaceTransferUiState,
    onCancel: () -> Unit,
) {
    if (!state.active) return
    val context = LocalContext.current
    val haptics = rememberPremiumHaptics()
    val progress = state.progress
    val fraction = progress?.totalBytes?.takeIf { it > 0 }?.let { total ->
        (progress.processedBytes.toFloat() / total).coerceIn(0f, 1f)
    }
    AlertDialog(
        onDismissRequest = {},
        title = {
            Text(
                stringResource(
                    if (state.operation == WorkspaceTransferOperation.IMPORT) {
                        R.string.workspace_transfer_import_title
                    } else {
                        R.string.workspace_transfer_export_title
                    }
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (fraction == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                }
                Text(
                    text = when (progress?.stage) {
                        WorkspaceTransferStage.SCANNING -> stringResource(R.string.workspace_transfer_scanning)
                        WorkspaceTransferStage.EXPORTING -> stringResource(R.string.workspace_transfer_exporting)
                        WorkspaceTransferStage.READING -> stringResource(R.string.workspace_transfer_reading)
                        WorkspaceTransferStage.IMPORTING -> stringResource(R.string.workspace_transfer_importing)
                        WorkspaceTransferStage.FINALIZING -> stringResource(R.string.workspace_transfer_finalizing)
                        null -> stringResource(R.string.workspace_transfer_preparing)
                    }
                )
                if (progress != null && progress.totalBytes != null) {
                    Text(
                        text = stringResource(
                            R.string.workspace_transfer_size_progress,
                            Formatter.formatShortFileSize(context, progress.processedBytes),
                            Formatter.formatShortFileSize(context, progress.totalBytes),
                        )
                    )
                }
                progress?.currentEntry?.takeIf { it.isNotBlank() }?.let { current ->
                    Text(current, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    haptics.perform(HapticPattern.Pop)
                    onCancel()
                }
            ) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
