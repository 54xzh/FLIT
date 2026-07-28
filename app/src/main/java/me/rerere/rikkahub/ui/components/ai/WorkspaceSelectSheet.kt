package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowRight
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.WorkspaceType
import me.rerere.rikkahub.data.repository.Workspace
import me.rerere.rikkahub.ui.pages.extensions.workspace.sandboxStatusText

/**
 * 工作区选择底部弹窗：列出工作区、可选的“不绑定”项和“管理工作区”。
 *
 * @param selectedWorkspaceId 当前选中的 workspaceId（助手场景=助手绑定；会话场景=会话覆写；null = 未绑定/跟随助手）
 * @param workspaces 所有可选工作区
 * @param onSelect 选择回调，参数为 workspaceId（null = 解除绑定/跟随助手）
 * @param onManage 跳转管理工作区页
 * @param onDismiss 关闭
 * @param noneOptionTitle 「不绑定」那一行的标题文案。助手场景默认为 [R.string.workspace_no_binding]；
 *   会话覆写场景应传「跟随助手」，让 null 的语义与「跟随助手绑定」对齐，避免「不绑定」产生歧义。
 * @param showNoneOption 是否显示“不绑定”选项；必选场景应关闭。
 */
@Composable
fun WorkspaceSelectSheet(
    selectedWorkspaceId: String?,
    workspaces: List<Workspace>,
    onSelect: (String?) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
    noneOptionTitle: String = stringResource(R.string.workspace_no_binding),
    showNoneOption: Boolean = true,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.workspace_select),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(vertical = 8.dp),
            )

            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (showNoneOption) {
                    WorkspaceSelectRow(
                        title = noneOptionTitle,
                        selected = selectedWorkspaceId == null,
                        onClick = { onSelect(null) },
                    )
                }
                workspaces.forEach { workspace ->
                    WorkspaceSelectRow(
                        title = workspace.name,
                        subtitle = stringResource(
                            if (workspace.type == WorkspaceType.LIGHTWEIGHT) R.string.workspace_type_lightweight
                            else R.string.workspace_type_sandbox
                        ) + workspace.sandboxStatus?.let { " · ${sandboxStatusText(it)}" }.orEmpty(),
                        selected = workspace.id == selectedWorkspaceId,
                        onClick = { onSelect(workspace.id) },
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            ListItem(
                leadingContent = { Icon(Icons.Rounded.Folder, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.workspace_manage)) },
                trailingContent = {
                    Icon(
                        imageVector = Icons.Rounded.ArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier
                    .clip(MaterialTheme.shapes.large)
                    .clickable { onManage() },
            )
        }
    }
}

@Composable
private fun WorkspaceSelectRow(
    title: String,
    subtitle: String? = null,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        leadingContent = { Icon(Icons.Rounded.Folder, contentDescription = null) },
        headlineContent = {
            Column {
                Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        },
        trailingContent = if (selected) {
            {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        } else null,
        colors = ListItemDefaults.colors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.surfaceContainerHigh
            } else {
                Color.Transparent
            },
        ),
        modifier = Modifier
            .clip(MaterialTheme.shapes.large)
            .clickable { onClick() },
    )
}
