package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.repository.SandboxMountDraft
import me.rerere.rikkahub.ui.theme.AppShapes

@Composable
fun WorkspaceMountSheet(
    draft: SandboxMountDraft,
    submitting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (parentPath: String, name: String) -> Unit,
) {
    var parentPath by remember(draft) { mutableStateOf(draft.parentPath) }
    var name by remember(draft) { mutableStateOf(draft.suggestedName) }
    val normalizedParent = parentPath.trim().trimEnd('/')
    val targetPreview = if (normalizedParent.isBlank()) name.trim() else "$normalizedParent/${name.trim()}"
    val valid = name.isNotBlank() && (normalizedParent == "/workspace" || normalizedParent.startsWith("/workspace/"))

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.workspace_mount_title), style = MaterialTheme.typography.headlineSmall)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(draft.source.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    draft.source.sourcePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            OutlinedTextField(
                value = parentPath,
                onValueChange = { parentPath = it },
                label = { Text(stringResource(R.string.workspace_mount_parent_path)) },
                supportingText = { Text(stringResource(R.string.workspace_mount_parent_path_desc)) },
                singleLine = true,
                shape = AppShapes.InputField,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.workspace_mount_folder_name)) },
                singleLine = true,
                shape = AppShapes.InputField,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.workspace_mount_target_preview, targetPreview),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.workspace_mount_write_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss, enabled = !submitting) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = { onConfirm(parentPath, name) },
                    enabled = valid && !submitting,
                    shape = AppShapes.ButtonPill,
                ) {
                    Text(stringResource(R.string.workspace_mount_confirm))
                }
            }
        }
    }
}
