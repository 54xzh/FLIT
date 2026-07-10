package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.db.entity.WorkspaceType
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.repository.Workspace
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.theme.AppShapes
import org.koin.compose.koinInject
import org.koin.androidx.compose.koinViewModel

@Composable
fun WorkspacePage(vm: WorkspaceVM = koinViewModel()) {
    val workspaces by vm.workspaces.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val haptics = rememberPremiumHaptics()
    val context = LocalContext.current
    val repository: WorkspaceRepository = koinInject()

    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var createType by remember { mutableStateOf<WorkspaceType?>(null) }
    var showTypeDialog by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var renamingWorkspace by remember { mutableStateOf<Workspace?>(null) }
    var deletingWorkspace by remember { mutableStateOf<Workspace?>(null) }

    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        pendingUri = uri
        createType = WorkspaceType.LIGHTWEIGHT
        showCreateDialog = true
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            OneUITopAppBar(
                title = stringResource(R.string.workspace_page_title),
                scrollBehavior = scrollBehavior,
                expandedTitleHorizontalPadding = 32.dp,
                navigationIcon = { BackButton() },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    haptics.perform(HapticPattern.Pop)
                    pendingUri = null
                    createType = null
                    showTypeDialog = true
                },
                shape = AppShapes.CardLarge,
            ) {
                Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.workspace_page_create))
            }
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (workspaces.isEmpty()) {
                item { EmptyWorkspaceState() }
            } else {
                items(workspaces, key = { it.id }) { workspace ->
                    WorkspaceCard(
                        workspace = workspace,
                        rootLabel = when (workspace.type) {
                            WorkspaceType.LIGHTWEIGHT -> workspace.treeUri?.let(repository::friendlyShortPath).orEmpty()
                            WorkspaceType.SANDBOX -> sandboxStatusText(workspace.sandboxStatus)
                        },
                        onOpen = { navController.navigate(Screen.WorkspaceDetail(workspace.id)) },
                        onRename = { renamingWorkspace = workspace },
                        onDelete = { deletingWorkspace = workspace },
                    )
                }
            }
        }
    }

    if (showTypeDialog) {
        WorkspaceTypeDialog(
            onDismiss = { showTypeDialog = false },
            onLightweight = {
                showTypeDialog = false
                createType = WorkspaceType.LIGHTWEIGHT
                folderLauncher.launch(null)
            },
            onSandbox = {
                showTypeDialog = false
                createType = WorkspaceType.SANDBOX
                showCreateDialog = true
            },
        )
    }

    if (showCreateDialog && createType != null && (createType == WorkspaceType.SANDBOX || pendingUri != null)) {
        val uri = pendingUri
        val defaultName = if (createType == WorkspaceType.LIGHTWEIGHT) {
            runCatching { repository.friendlyName(uri!!.toString(), "Workspace") }.getOrDefault("Workspace")
        } else "Sandbox"
        CreateWorkspaceDialog(
            defaultName = defaultName,
            onDismiss = { showCreateDialog = false; pendingUri = null; createType = null },
            onConfirm = { name ->
                val onResult: (Result<Workspace>) -> Unit = { result ->
                    result
                        .onSuccess {
                            haptics.perform(HapticPattern.Success)
                            toaster.show(message = context.getString(R.string.workspace_page_created))
                            if (it.type == WorkspaceType.SANDBOX) {
                                navController.navigate(Screen.WorkspaceDetail(it.id))
                            }
                        }
                        .onFailure {
                            haptics.perform(HapticPattern.Error)
                            toaster.show(message = context.getString(R.string.workspace_page_create_failed, it.message ?: ""))
                        }
                }
                if (createType == WorkspaceType.LIGHTWEIGHT) vm.createLightweight(name, uri!!.toString(), onResult)
                else vm.createSandbox(name, onResult)
                showCreateDialog = false
                pendingUri = null
                createType = null
            },
        )
    }

    renamingWorkspace?.let { ws ->
        EditNameDialog(
            title = stringResource(R.string.workspace_page_rename),
            initial = ws.name,
            onDismiss = { renamingWorkspace = null },
            onConfirm = { name ->
                vm.rename(ws, name)
                haptics.perform(HapticPattern.Pop)
                renamingWorkspace = null
            },
        )
    }

    deletingWorkspace?.let { ws ->
        AlertDialog(
            onDismissRequest = { deletingWorkspace = null },
            title = { Text(stringResource(R.string.workspace_page_delete)) },
            text = {
                Text(
                    stringResource(
                        if (ws.type == WorkspaceType.SANDBOX) R.string.workspace_sandbox_delete_confirm
                        else R.string.workspace_page_delete_confirm,
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptics.perform(HapticPattern.Thud)
                        vm.delete(ws)
                        toaster.show(message = context.getString(R.string.workspace_page_deleted))
                        deletingWorkspace = null
                    }
                ) { Text(stringResource(R.string.workspace_page_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deletingWorkspace = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun EmptyWorkspaceState() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                Icons.Rounded.Folder,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.workspace_page_empty),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.workspace_page_empty_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WorkspaceCard(
    workspace: Workspace,
    rootLabel: String,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val haptics = rememberPremiumHaptics()
    var menuExpanded by remember { mutableStateOf(false) }
    Card(
        onClick = { haptics.perform(HapticPattern.Pop); onOpen() },
        shape = AppShapes.CardLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Rounded.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = workspace.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${stringResource(if (workspace.type == WorkspaceType.LIGHTWEIGHT) R.string.workspace_type_lightweight else R.string.workspace_type_sandbox)} · $rootLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { haptics.perform(HapticPattern.Pop); menuExpanded = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = null)
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.workspace_page_rename)) },
                        leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                        onClick = { menuExpanded = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.workspace_page_delete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menuExpanded = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkspaceTypeDialog(
    onDismiss: () -> Unit,
    onLightweight: () -> Unit,
    onSandbox: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_page_choose_type)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onLightweight, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.workspace_type_lightweight))
                        Text(stringResource(R.string.workspace_type_lightweight_desc), style = MaterialTheme.typography.bodySmall)
                    }
                }
                TextButton(onClick = onSandbox, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.workspace_type_sandbox))
                        Text(stringResource(R.string.workspace_type_sandbox_desc), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun CreateWorkspaceDialog(
    defaultName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(defaultName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_page_create)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.workspace_page_name), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    shape = AppShapes.InputField,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim().ifBlank { defaultName }) }) {
                Text(stringResource(R.string.workspace_page_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun EditNameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
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
            TextButton(onClick = { onConfirm(name.trim().ifBlank { initial }) }) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
