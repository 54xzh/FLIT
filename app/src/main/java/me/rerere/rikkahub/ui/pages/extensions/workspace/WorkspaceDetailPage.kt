package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.pages.setting.components.SettingsGroup
import me.rerere.rikkahub.ui.pages.setting.components.SettingGroupItem
import me.rerere.rikkahub.ui.theme.AppShapes
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun WorkspaceDetailPage(
    workspaceId: String,
    vm: WorkspaceDetailVM = koinViewModel { parametersOf(workspaceId) },
) {
    val workspace by vm.workspace.collectAsStateWithLifecycle()
    val toolApprovals by vm.toolApprovals.collectAsStateWithLifecycle()
    val friendlyRootPath by vm.friendlyRootPath.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val haptics = rememberPremiumHaptics()
    val context = LocalContext.current

    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    val ws = workspace
    val allowAll = WorkspaceDetailVM.WORKSPACE_TOOL_NAMES.all { toolApprovals[it] == false }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            OneUITopAppBar(
                title = ws?.name ?: stringResource(R.string.workspace_detail_title),
                scrollBehavior = scrollBehavior,
                expandedTitleHorizontalPadding = 32.dp,
                navigationIcon = { BackButton() },
                actions = {
                    if (ws != null) {
                        IconButton(onClick = { haptics.perform(HapticPattern.Pop); renaming = true }) {
                            Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.workspace_page_rename))
                        }
                        IconButton(onClick = { haptics.perform(HapticPattern.Thud); deleting = true }) {
                            Icon(
                                Icons.Rounded.Delete,
                                contentDescription = stringResource(R.string.workspace_page_delete),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (ws != null) {
                item {
                    SettingsGroup(title = stringResource(R.string.workspace_page_root_label)) {
                        SettingGroupItem(
                            title = ws.name,
                            subtitle = friendlyRootPath ?: ws.treeUri,
                        )
                    }
                }
                item {
                    SettingsGroup(title = stringResource(R.string.workspace_detail_tool_approvals)) {
                        SettingGroupItem(
                            title = stringResource(R.string.workspace_detail_tool_allow_all),
                            subtitle = stringResource(R.string.workspace_detail_tool_allow_all_desc),
                            trailing = {
                                Switch(
                                    checked = allowAll,
                                    onCheckedChange = { v ->
                                        haptics.perform(HapticPattern.Pop)
                                        vm.setAll(allowAll = v)
                                    },
                                )
                            },
                        )
                        WorkspaceDetailVM.WORKSPACE_TOOL_NAMES.forEach { toolName ->
                            SettingGroupItem(
                                title = stringResource(toolNameToLabel(toolName)),
                                trailing = {
                                    val needsApproval = toolApprovals[toolName] ?: true
                                    Switch(
                                        checked = !needsApproval,
                                        onCheckedChange = { v ->
                                            haptics.perform(HapticPattern.Pop)
                                            vm.setToolApproval(toolName, needsApproval = !v)
                                        },
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (renaming && ws != null) {
        var name by remember { mutableStateOf(ws.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.workspace_page_rename)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    shape = AppShapes.InputField,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.rename(name.trim().ifBlank { ws.name })
                    haptics.perform(HapticPattern.Pop)
                    renaming = false
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (deleting && ws != null) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.workspace_page_delete)) },
            text = { Text(stringResource(R.string.workspace_page_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    haptics.perform(HapticPattern.Thud)
                    vm.delete {
                        toaster.show(message = context.getString(R.string.workspace_page_deleted))
                        navController.popBackStack()
                    }
                    deleting = false
                }) { Text(stringResource(R.string.workspace_page_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

private fun toolNameToLabel(toolName: String): Int = when (toolName) {
    "workspace_list" -> R.string.workspace_detail_tool_workspace_list
    "workspace_read_file" -> R.string.workspace_detail_tool_workspace_read_file
    "workspace_write_file" -> R.string.workspace_detail_tool_workspace_write_file
    "workspace_mkdir" -> R.string.workspace_detail_tool_workspace_mkdir
    "workspace_delete" -> R.string.workspace_detail_tool_workspace_delete
    "workspace_rename" -> R.string.workspace_detail_tool_workspace_rename
    "eval_python" -> R.string.workspace_detail_tool_eval_python
    "run_skill_script" -> R.string.workspace_detail_tool_run_skill_script
    else -> R.string.workspace_detail_tool_workspace_list
}