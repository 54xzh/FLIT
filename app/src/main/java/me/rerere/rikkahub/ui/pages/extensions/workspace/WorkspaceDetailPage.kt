package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.AddLink
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.db.entity.SandboxRootfsStatus
import me.rerere.rikkahub.data.repository.WorkspaceFileEntry
import me.rerere.rikkahub.data.repository.SandboxMountDraft
import me.rerere.rikkahub.data.db.entity.WorkspaceType
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.theme.AppShapes
import me.rerere.rikkahub.utils.WorkspaceFileClassifier
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import me.rerere.rikkahub.workspace.WORKSPACE_TRANSFER_MIME
import me.rerere.rikkahub.workspace.SandboxStorageArea

@Composable
fun WorkspaceDetailPage(
    workspaceId: String,
    vm: WorkspaceDetailVM = koinViewModel { parametersOf(workspaceId) },
) {
    val workspace by vm.workspace.collectAsStateWithLifecycle()
    val toolApprovals by vm.toolApprovals.collectAsStateWithLifecycle()
    val friendlyRootPath by vm.friendlyRootPath.collectAsStateWithLifecycle()
    val filesState by vm.filesState.collectAsStateWithLifecycle()
    val installState by vm.installState.collectAsStateWithLifecycle()
    val transferState by vm.transferState.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val haptics = rememberPremiumHaptics()
    val context = LocalContext.current
    val pagerState = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()

    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var deleteFileTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    var exportFileTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    var showInstallDialog by remember { mutableStateOf(false) }
    var showMountPermissionDialog by remember { mutableStateOf(false) }
    var mountDraft by remember { mutableStateOf<SandboxMountDraft?>(null) }
    var mountSubmitting by remember { mutableStateOf(false) }

    // 工作区文件查看器：文本类与 .skill 文件在应用内打开，其他类型回退到原逻辑。
    val fileViewerState = remember { WorkspaceFileViewerState() }

    val ws = workspace

    val mountFolderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        vm.prepareMount(uri.toString()) { result ->
            result.onSuccess { mountDraft = it }
                .onFailure { error ->
                    haptics.perform(HapticPattern.Error)
                    toaster.show(context.getString(R.string.workspace_mount_failed, error.message.orEmpty()))
                }
        }
    }

    val allFilesPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        if (vm.hasAllFilesAccess()) {
            mountFolderPicker.launch(null)
        } else {
            toaster.show(context.getString(R.string.workspace_mount_permission_not_granted))
        }
    }

    val requestMount: () -> Unit = {
        haptics.perform(HapticPattern.Pop)
        if (vm.hasAllFilesAccess()) {
            mountFolderPicker.launch(null)
        } else {
            showMountPermissionDialog = true
        }
    }

    // 导入文件：SAF OpenDocument -> 查显示名 -> 开流 -> vm.importFile
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val displayName = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst() && c.getColumnIndex(OpenableColumns.DISPLAY_NAME) >= 0) {
                    c.getString(c.getColumnIndex(OpenableColumns.DISPLAY_NAME))
                } else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "imported_file"
        val stream = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
        if (stream != null) {
            vm.importFile(stream, displayName)
        } else {
            toaster.show(context.getString(R.string.workspace_detail_export_failed))
        }
    }

    val exportFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val target = exportFileTarget
        exportFileTarget = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        val output = runCatching { context.contentResolver.openOutputStream(uri) }.getOrNull()
        if (output == null) {
            toaster.show(context.getString(R.string.workspace_detail_import_failed, ""))
        } else {
            vm.exportFile(target, output)
        }
    }

    val exportWorkspacePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(WORKSPACE_TRANSFER_MIME),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val output = runCatching { context.contentResolver.openOutputStream(uri) }.getOrNull()
        if (output == null) {
            toaster.show(context.getString(R.string.workspace_transfer_export_failed, ""))
            return@rememberLauncherForActivityResult
        }
        vm.exportWorkspace(output) { result ->
            result.onSuccess {
                haptics.perform(HapticPattern.Success)
                toaster.show(context.getString(R.string.workspace_transfer_export_success))
            }.onFailure { error ->
                runCatching { context.contentResolver.delete(uri, null, null) }
                if (error !is CancellationException) {
                    haptics.perform(HapticPattern.Error)
                    toaster.show(context.getString(R.string.workspace_transfer_export_failed, error.message.orEmpty()))
                }
            }
        }
    }

    // 原有"用其他应用打开"逻辑，作为非内置查看类型的兜底。
    val openFileExternal: (WorkspaceFileEntry) -> Unit = { entry ->
        scope.launch {
            val uri = vm.resolveFileUri(entry)
            if (uri == null) {
                toaster.show(context.getString(R.string.workspace_detail_open_failed))
                return@launch
            }
            val mime = runCatching {
                context.contentResolver.getType(uri)
            }.getOrNull() ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching {
                context.startActivity(Intent.createChooser(intent, null))
            }.onFailure {
                toaster.show(context.getString(R.string.workspace_detail_open_failed))
            }
        }
    }

    // 文件页打开文件：先按类型分流，文本/.skill 走应用内查看器，其他回退到外部打开。
    val openFile: (WorkspaceFileEntry) -> Unit = { entry ->
        if (WorkspaceFileClassifier.shouldUseBuiltInViewer(entry.name) && workspace?.id != null) {
            haptics.perform(HapticPattern.Pop)
            fileViewerState.showWorkspaceEntry(
                workspaceId = workspace!!.id,
                entry = entry,
                area = filesState.area,
            )
        } else {
            openFileExternal(entry)
        }
    }

    // 返回键：文件页非根先 goUp；根则切回设置页；设置页交给系统出栈
    BackHandler(enabled = pagerState.currentPage == 1 && filesState.path.isNotBlank()) {
        vm.goUp()
    }
    BackHandler(enabled = pagerState.currentPage == 1 && filesState.path.isBlank()) {
        scope.launch { pagerState.animateScrollToPage(0) }
    }

    // 切到文件页时自动刷新一次（处理授权恢复 / 外部变更）
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage == 1) vm.refreshFiles()
    }

    Scaffold(
        topBar = {
            // 设置页与文件页统一用标准 TopAppBar：高度一致、无展开大标题、右滑不抖动
            TopAppBar(
                title = {
                    Text(
                        if (pagerState.currentPage == 1) {
                            stringResource(R.string.workspace_detail_tab_files)
                        } else {
                            ws?.name ?: stringResource(R.string.workspace_detail_title)
                        },
                    )
                },
                navigationIcon = { BackButton() },
                actions = {
                    if (pagerState.currentPage == 1) {
                        IconButton(onClick = { haptics.perform(HapticPattern.Pop); vm.refreshFiles() }) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null)
                        }
                        if (ws?.type == WorkspaceType.SANDBOX) {
                            IconButton(onClick = requestMount) {
                                Icon(
                                    Icons.Rounded.AddLink,
                                    contentDescription = stringResource(R.string.workspace_mount_action),
                                )
                            }
                        }
                    }
                    if (ws != null && pagerState.currentPage == 0) {
                        if (
                            ws.type == WorkspaceType.SANDBOX &&
                            ws.sandboxStatus == SandboxRootfsStatus.READY
                        ) {
                            IconButton(
                                onClick = {
                                    haptics.perform(HapticPattern.Pop)
                                    navController.navigate(Screen.WorkspaceTerminal(workspaceId))
                                }
                            ) {
                                Icon(
                                    Icons.Rounded.Terminal,
                                    contentDescription = stringResource(R.string.workspace_terminal_open),
                                )
                            }
                        }
                        if (ws.type == WorkspaceType.SANDBOX) {
                            IconButton(
                                enabled = !transferState.active,
                                onClick = {
                                    haptics.perform(HapticPattern.Pop)
                                    val timestamp = LocalDateTime.now()
                                        .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                                    exportWorkspacePicker.launch(
                                        "FLIT_workspace_${workspaceTransferSafeName(ws.name)}_$timestamp.flitspace"
                                    )
                                }
                            ) {
                                Icon(
                                    Icons.Rounded.FileUpload,
                                    contentDescription = stringResource(R.string.workspace_transfer_export_action),
                                )
                            }
                        }
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
        bottomBar = {
            androidx.compose.material3.BottomAppBar {
                NavigationBarItem(
                    selected = pagerState.currentPage == 0,
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                    label = { Text(stringResource(R.string.workspace_detail_tab_basic)) },
                    icon = { Icon(Icons.Rounded.Settings, null) },
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 1,
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                    label = { Text(stringResource(R.string.workspace_detail_tab_files)) },
                    icon = { Icon(Icons.Rounded.FileUpload, null) },
                )
            }
        },
        floatingActionButton = {
            val canImport = ws != null && (
                filesState.area == SandboxStorageArea.FILES ||
                    ws.sandboxStatus == SandboxRootfsStatus.READY ||
                    ws.sandboxStatus == SandboxRootfsStatus.BROKEN
                )
            if (pagerState.currentPage == 1 && canImport) {
                FloatingActionButton(
                    onClick = { haptics.perform(HapticPattern.Pop); filePicker.launch(arrayOf("*/*")) },
                    shape = AppShapes.ButtonPill,
                ) {
                    Icon(Icons.Rounded.FileDownload, contentDescription = stringResource(R.string.workspace_detail_import_file))
                }
            }
        },
    ) { paddingValues ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            when (page) {
                0 -> androidx.compose.foundation.layout.Box(
                    modifier = Modifier.padding(paddingValues),
                ) {
                    WorkspaceBasicPage(
                        workspace = ws,
                        toolApprovals = toolApprovals,
                        toolNames = vm.toolNames(),
                        friendlyRootPath = friendlyRootPath,
                        installProgress = installState.progress,
                        installingRootfs = installState.installing,
                        onRequestInstallRootfs = {
                            haptics.perform(HapticPattern.Pop)
                            showInstallDialog = true
                        },
                        onSetToolApproval = { tool, needsApproval ->
                            haptics.perform(HapticPattern.Pop)
                            vm.setToolApproval(tool, needsApproval)
                        },
                        onSetAll = { allowAll ->
                            haptics.perform(HapticPattern.Pop)
                            vm.setAll(allowAll)
                        },
                    )
                }
                1 -> androidx.compose.foundation.layout.Box(
                    modifier = Modifier.padding(paddingValues),
                ) {
                    WorkspaceFilesPage(
                        state = filesState,
                        showAreaSelector = ws?.type == WorkspaceType.SANDBOX,
                        onSelectArea = vm::selectArea,
                        onGoUp = vm::goUp,
                        onOpen = vm::open,
                        onDelete = { deleteFileTarget = it },
                        onUnmount = vm::unmount,
                        onOpenFile = openFile,
                        onExport = { entry -> exportFileTarget = entry; exportFilePicker.launch(entry.name) },
                    )
                }
            }
        }
    }

    // 重命名工作区
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

    // 删除工作区
    if (deleting && ws != null) {
        AlertDialog(
            onDismissRequest = { deleting = false },
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

    if (showMountPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showMountPermissionDialog = false },
            title = { Text(stringResource(R.string.workspace_mount_permission_title)) },
            text = { Text(stringResource(R.string.workspace_mount_permission_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showMountPermissionDialog = false
                        val intent = Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        )
                        allFilesPermissionLauncher.launch(intent)
                    }
                ) { Text(stringResource(R.string.workspace_mount_go_authorize)) }
            },
            dismissButton = {
                TextButton(onClick = { showMountPermissionDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    mountDraft?.let { draft ->
        WorkspaceMountSheet(
            draft = draft,
            submitting = mountSubmitting,
            onDismiss = {
                if (!mountSubmitting) {
                    vm.cancelMountDraft(draft)
                    mountDraft = null
                }
            },
            onConfirm = { parentPath, name ->
                mountSubmitting = true
                vm.createMount(draft, parentPath, name) { result ->
                    mountSubmitting = false
                    result.onSuccess {
                        haptics.perform(HapticPattern.Success)
                        toaster.show(context.getString(R.string.workspace_mount_success))
                        mountDraft = null
                    }.onFailure { error ->
                        haptics.perform(HapticPattern.Error)
                        toaster.show(context.getString(R.string.workspace_mount_failed, error.message.orEmpty()))
                    }
                }
            },
        )
    }

    if (showInstallDialog && ws != null && ws.type == WorkspaceType.SANDBOX) {
        InstallRootfsDialog(
            workspaceName = ws.name,
            initialUrl = ws.sandbox?.rootfsSourceUrl,
            onDismiss = { showInstallDialog = false },
            onConfirm = { url ->
                haptics.perform(HapticPattern.Pop)
                vm.installRootfs(url)
                showInstallDialog = false
            },
        )
    }

    installState.error?.let { message ->
        AlertDialog(
            onDismissRequest = vm::dismissInstallError,
            title = { Text(stringResource(R.string.workspace_sandbox_rootfs_failed)) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = vm::dismissInstallError) { Text(stringResource(R.string.confirm)) } },
        )
    }

    // 删除文件/文件夹确认
    val target = deleteFileTarget
    if (target != null) {
        AlertDialog(
            onDismissRequest = { deleteFileTarget = null },
            title = { Text(stringResource(R.string.workspace_detail_delete_file_or_dir)) },
            text = {
                Text(
                    if (filesState.area == SandboxStorageArea.ROOTFS) {
                        stringResource(R.string.workspace_rootfs_delete_warning, target.path)
                    } else if (target.mountId != null) {
                        stringResource(R.string.workspace_mount_delete_external_warning, target.path)
                    } else {
                        stringResource(R.string.workspace_detail_will_delete, target.path)
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    haptics.perform(HapticPattern.Thud)
                    vm.deleteFile(target)
                    deleteFileTarget = null
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteFileTarget = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    WorkspaceTransferDialog(state = transferState, onCancel = vm::cancelTransfer)

    // 工作区文件查看器：处理非 OTHER 分类的文件；OTHER 回退到 openFileExternal。
    val wsId = workspace?.id
    WorkspaceFileViewerSheet(
        state = fileViewerState,
        resolveFileUri = { target ->
            when (target) {
                is ViewerTarget.WorkspaceEntry -> vm.resolveFileUri(target.entry)
                is ViewerTarget.Reference -> {
                    // 工作区详情页只处理 entry 形式；reference 不应在此出现。
                    null
                }
            }
        },
        onNotHandled = {
            // 分类为 OTHER 的文件：走原外部打开逻辑。
            val t = fileViewerState.current
            if (t is ViewerTarget.WorkspaceEntry && wsId != null) {
                openFileExternal(t.entry)
            }
        },
    )
}
