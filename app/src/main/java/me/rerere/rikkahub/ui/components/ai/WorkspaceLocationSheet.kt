package me.rerere.rikkahub.ui.components.ai

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.WorkspaceType
import me.rerere.rikkahub.data.model.computeWorkspaceLocationOverride
import me.rerere.rikkahub.data.model.normalizeWorkspaceCwd
import me.rerere.rikkahub.data.repository.Workspace
import me.rerere.rikkahub.data.repository.WorkspaceFileEntry
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.components.ui.ToastType
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.pages.extensions.workspace.sandboxStatusText
import me.rerere.rikkahub.ui.theme.AppShapes

private enum class WorkspaceLocationSheetMode {
    DIRECTORY,
    WORKSPACE_PICKER,
}

private enum class LocationNavDirection {
    Forward,
    Back,
    None,
}

private data class DirectorySnapshot(
    val workspaceId: String?,
    val path: String,
    val entries: List<WorkspaceFileEntry>,
    val error: String?,
)

/**
 * 统一的工作目录与工作区底部选择器（参考旧版 WorkDirPicker 视觉与交互规范）：
 * - 单层 surfaceContainerLow 底部弹窗 + 顶部居中下拉手柄；
 * - 第一层直接浏览当前工作区子目录，仅在根目录首项显示「选择其他工作区」；
 * - 高度变化仅由外层 mode 切换的单一 SizeTransform（临界阻尼弹簧）驱动，无多层动画打架；
 * - 失效目录与读取错误通过应用内顶部提示（LocalToaster）反馈，不改变弹窗高度。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceLocationSheet(
    title: String = stringResource(R.string.workspace_location_title),
    inheritedWorkspaceId: String?,
    inheritedCwd: String,
    inheritedLabel: String,
    selectedWorkspaceId: String?,
    selectedCwd: String?,
    workspaces: List<Workspace>,
    workspaceRepository: WorkspaceRepository,
    onApply: (workspaceId: String?, cwd: String?) -> Unit,
    onManageWorkspaces: () -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = rememberPremiumHaptics()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val focusManager = LocalFocusManager.current

    val validSelectedWorkspaceId = selectedWorkspaceId?.takeIf { id -> workspaces.any { it.id == id } }
    val validInheritedWorkspaceId = inheritedWorkspaceId?.takeIf { id -> workspaces.any { it.id == id } }
    val initialWorkspaceId = validSelectedWorkspaceId ?: validInheritedWorkspaceId
    val initialPath = when {
        validSelectedWorkspaceId != null -> normalizeWorkspaceCwd(selectedCwd).orEmpty()
        validInheritedWorkspaceId != null -> normalizeWorkspaceCwd(selectedCwd) ?: normalizeWorkspaceCwd(inheritedCwd).orEmpty()
        else -> ""
    }
    val hasExplicitOverride = selectedWorkspaceId != null || selectedCwd != null

    var mode by remember {
        mutableStateOf(
            if (initialWorkspaceId != null) WorkspaceLocationSheetMode.DIRECTORY
            else WorkspaceLocationSheetMode.WORKSPACE_PICKER
        )
    }
    var modeDirection by remember { mutableStateOf(LocationNavDirection.None) }
    var folderDirection by remember { mutableStateOf(LocationNavDirection.None) }
    var browsingWorkspaceId by remember { mutableStateOf(initialWorkspaceId) }
    var browsingPath by remember { mutableStateOf(initialPath) }
    var reloadNonce by remember { mutableIntStateOf(0) }

    var snapshot by remember { mutableStateOf<DirectorySnapshot?>(null) }
    var isInitialLoading by remember { mutableStateOf(initialWorkspaceId != null) }

    var creatingFolder by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var createFolderError by remember { mutableStateOf<String?>(null) }
    var isCreatingFolder by remember { mutableStateOf(false) }
    val newFolderFocusRequester = remember { FocusRequester() }

    val browsingWorkspace = remember(workspaces, browsingWorkspaceId) {
        browsingWorkspaceId?.let { id -> workspaces.firstOrNull { it.id == id } }
    }
    val segments = remember(browsingPath) {
        if (browsingPath.isBlank()) emptyList() else browsingPath.split('/').filter { it.isNotBlank() }
    }

    suspend fun loadDirectorySnapshot(wsId: String, requestedPath: String): Pair<String, DirectorySnapshot> {
        val resolved = workspaceRepository.resolveExistingWorkspaceDirectory(wsId, requestedPath)
        val targetPath = resolved.resolvedCwd
        if (resolved.fellBack) {
            toaster.show(
                message = context.getString(R.string.workspace_location_missing_cwd_hint),
                type = ToastType.Warning,
            )
        }
        val result = runCatching { workspaceRepository.listWorkspaceDirectories(wsId, targetPath) }
        val errorMsg = result.exceptionOrNull()?.message
        if (errorMsg != null) {
            toaster.show(
                message = errorMsg.ifBlank { context.getString(R.string.workspace_location_load_failed) },
                type = ToastType.Error,
            )
        }
        return targetPath to DirectorySnapshot(
            workspaceId = wsId,
            path = targetPath,
            entries = result.getOrDefault(emptyList()),
            error = errorMsg,
        )
    }

    LaunchedEffect(creatingFolder) {
        if (creatingFolder) {
            focusManager.clearFocus(force = true)
            runCatching { newFolderFocusRequester.requestFocus() }
        }
    }

    LaunchedEffect(browsingWorkspaceId, browsingPath, reloadNonce, mode) {
        if (mode != WorkspaceLocationSheetMode.DIRECTORY) return@LaunchedEffect
        val wsId = browsingWorkspaceId ?: return@LaunchedEffect
        // 若已在切换工作区时预加载了同位置快照且未手动触发刷新，跳过重复 IO
        val existing = snapshot
        if (reloadNonce == 0 && existing != null && existing.workspaceId == wsId && existing.path == browsingPath) {
            isInitialLoading = false
            return@LaunchedEffect
        }
        if (snapshot == null) {
            isInitialLoading = true
        }
        try {
            val (targetPath, loaded) = loadDirectorySnapshot(wsId, browsingPath)
            if (targetPath != browsingPath) {
                folderDirection = LocationNavDirection.Back
                browsingPath = targetPath
            }
            snapshot = loaded
        } finally {
            isInitialLoading = false
        }
    }

    if (creatingFolder && browsingWorkspace != null) {
        AlertDialog(
            onDismissRequest = { if (!isCreatingFolder) creatingFolder = false },
            title = { Text(stringResource(R.string.workspace_location_new_folder)) },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = {
                        newFolderName = it
                        createFolderError = null
                    },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.workspace_location_new_folder_hint)) },
                    isError = createFolderError != null,
                    supportingText = createFolderError?.let { err -> { Text(err) } },
                    shape = AppShapes.InputField,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(newFolderFocusRequester),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !isCreatingFolder && newFolderName.trim().isNotEmpty(),
                    onClick = {
                        val name = newFolderName.trim()
                        if (name.isEmpty()) {
                            haptics.perform(HapticPattern.Error)
                            return@TextButton
                        }
                        isCreatingFolder = true
                        scope.launch {
                            val res = runCatching {
                                workspaceRepository.createWorkspaceDirectory(
                                    id = browsingWorkspace.id,
                                    parentPath = browsingPath,
                                    folderName = name,
                                )
                            }
                            isCreatingFolder = false
                            res.onSuccess { created ->
                                haptics.perform(HapticPattern.Thud)
                                creatingFolder = false
                                newFolderName = ""
                                createFolderError = null
                                folderDirection = LocationNavDirection.Forward
                                browsingPath = created.path
                                reloadNonce++
                            }.onFailure { error ->
                                haptics.perform(HapticPattern.Error)
                                createFolderError = error.message ?: "Failed to create folder"
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isCreatingFolder,
                    onClick = {
                        creatingFolder = false
                        createFolderError = null
                    },
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    fun hideAndDismiss() {
        scope.launch {
            runCatching { sheetState.hide() }
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        shape = AppShapes.BottomSheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = {
            IconButton(
                onClick = {
                    haptics.perform(HapticPattern.Pop)
                    hideAndDismiss()
                },
            ) {
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null)
            }
        },
    ) {
        val currentError = snapshot?.error
        val canConfirmDirectory = browsingWorkspace != null && currentError == null && !isInitialLoading

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 顶部栏（固定高度，不随模式切换跳动）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (mode == WorkspaceLocationSheetMode.DIRECTORY && browsingWorkspace?.type == WorkspaceType.SANDBOX) {
                        Icons.Rounded.Terminal
                    } else {
                        Icons.Rounded.FolderOpen
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = if (mode == WorkspaceLocationSheetMode.DIRECTORY) {
                        title
                    } else {
                        stringResource(R.string.workspace_location_choose_workspace)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (hasExplicitOverride) {
                    TextButton(
                        onClick = {
                            haptics.perform(HapticPattern.Pop)
                            onApply(null, null)
                            hideAndDismiss()
                        },
                    ) {
                        Text(
                            text = stringResource(R.string.workspace_location_reset_to_inherited, inheritedLabel),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                val canGoBack = when (mode) {
                    WorkspaceLocationSheetMode.DIRECTORY -> segments.isNotEmpty()
                    WorkspaceLocationSheetMode.WORKSPACE_PICKER -> browsingWorkspace != null
                }
                IconButton(
                    enabled = canGoBack,
                    onClick = {
                        haptics.perform(HapticPattern.Pop)
                        when (mode) {
                            WorkspaceLocationSheetMode.DIRECTORY -> {
                                folderDirection = LocationNavDirection.Back
                                browsingPath = segments.dropLast(1).joinToString("/")
                            }
                            WorkspaceLocationSheetMode.WORKSPACE_PICKER -> {
                                modeDirection = LocationNavDirection.Back
                                mode = WorkspaceLocationSheetMode.DIRECTORY
                            }
                        }
                    },
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            }

            // 副标题 / 纯文字面包屑（固定 26.dp 高度，确保两个模式顶区像素级对齐）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (mode == WorkspaceLocationSheetMode.DIRECTORY) {
                    val rootLabel = browsingWorkspace?.name?.takeIf { it.isNotBlank() }
                        ?: if (browsingWorkspace?.type == WorkspaceType.SANDBOX) "/workspace" else "/"
                    LocationBreadcrumb(
                        rootLabel = rootLabel,
                        segments = segments,
                        onSelectDepth = { depth ->
                            if (depth == segments.size) return@LocationBreadcrumb
                            haptics.perform(HapticPattern.Pop)
                            folderDirection = LocationNavDirection.Back
                            browsingPath = segments.take(depth).joinToString("/")
                        },
                    )
                } else {
                    Text(
                        text = if (workspaces.isEmpty()) {
                            stringResource(R.string.workspace_location_no_workspace_desc)
                        } else {
                            stringResource(R.string.assistant_page_workspace_binding_desc)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // 唯一负责高度变化的过渡层：切换「目录页（列表+底栏）」与「工作区列表」时使用临界阻尼弹簧平滑形变
            AnimatedContent(
                targetState = mode,
                transitionSpec = {
                    val offset = { fullWidth: Int -> (fullWidth * 0.15f).toInt() }
                    val slideSpec = spring<IntOffset>(dampingRatio = 0.85f, stiffness = 380f)
                    val fadeSpec = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 420f)
                    val sizeSpec = spring<IntSize>(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    )

                    val enter = when (modeDirection) {
                        LocationNavDirection.Forward ->
                            slideInHorizontally(animationSpec = slideSpec, initialOffsetX = { offset(it) }) +
                                fadeIn(animationSpec = fadeSpec)
                        LocationNavDirection.Back ->
                            slideInHorizontally(animationSpec = slideSpec, initialOffsetX = { -offset(it) }) +
                                fadeIn(animationSpec = fadeSpec)
                        LocationNavDirection.None ->
                            fadeIn(animationSpec = spring(stiffness = 2000f))
                    }
                    val exit = when (modeDirection) {
                        LocationNavDirection.Forward ->
                            slideOutHorizontally(animationSpec = slideSpec, targetOffsetX = { -offset(it) }) +
                                fadeOut(animationSpec = fadeSpec)
                        LocationNavDirection.Back ->
                            slideOutHorizontally(animationSpec = slideSpec, targetOffsetX = { offset(it) }) +
                                fadeOut(animationSpec = fadeSpec)
                        LocationNavDirection.None ->
                            fadeOut(animationSpec = spring(stiffness = 2000f))
                    }
                    enter togetherWith exit using SizeTransform(clip = true) { _, _ -> sizeSpec }
                },
                label = "workspace_location_mode_transition",
            ) { activeMode ->
                when (activeMode) {
                    WorkspaceLocationSheetMode.WORKSPACE_PICKER -> {
                        WorkspacePickerList(
                            workspaces = workspaces,
                            selectedWorkspaceId = browsingWorkspaceId,
                            inheritedWorkspaceId = validInheritedWorkspaceId,
                            inheritedLabel = inheritedLabel,
                            onSelectWorkspace = { workspace ->
                                haptics.perform(HapticPattern.Pop)
                                val changed = browsingWorkspaceId != workspace.id
                                if (!changed && snapshot?.workspaceId == workspace.id) {
                                    modeDirection = LocationNavDirection.Back
                                    mode = WorkspaceLocationSheetMode.DIRECTORY
                                } else {
                                    // 先预加载目标工作区根目录快照，再一次性平滑滑回目录页，避免闪现加载态
                                    scope.launch {
                                        val (targetPath, loaded) = loadDirectorySnapshot(workspace.id, "")
                                        browsingWorkspaceId = workspace.id
                                        browsingPath = targetPath
                                        snapshot = loaded
                                        reloadNonce = 0
                                        folderDirection = LocationNavDirection.None
                                        modeDirection = LocationNavDirection.Back
                                        mode = WorkspaceLocationSheetMode.DIRECTORY
                                    }
                                }
                            },
                            onManageWorkspaces = {
                                haptics.perform(HapticPattern.Pop)
                                hideAndDismiss()
                                onManageWorkspaces()
                            },
                        )
                    }

                    WorkspaceLocationSheetMode.DIRECTORY -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // 子目录进出：固定 360.dp 高度，SizeTransform = null，仅做纯弹性横滑，不引发高度抖动
                            val dirState: Any = snapshot ?: "loading"
                            AnimatedContent(
                                targetState = dirState,
                                transitionSpec = {
                                    val offset = { fullWidth: Int -> (fullWidth * 0.15f).toInt() }
                                    val slideSpec = spring<IntOffset>(dampingRatio = 0.8f, stiffness = 350f)
                                    val fadeSpec = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 400f)

                                    val enter = when (folderDirection) {
                                        LocationNavDirection.Forward ->
                                            slideInHorizontally(animationSpec = slideSpec, initialOffsetX = { offset(it) }) +
                                                fadeIn(animationSpec = fadeSpec)
                                        LocationNavDirection.Back ->
                                            slideInHorizontally(animationSpec = slideSpec, initialOffsetX = { -offset(it) }) +
                                                fadeIn(animationSpec = fadeSpec)
                                        LocationNavDirection.None ->
                                            fadeIn(animationSpec = spring(stiffness = 2000f))
                                    }
                                    val exit = when (folderDirection) {
                                        LocationNavDirection.Forward ->
                                            slideOutHorizontally(animationSpec = slideSpec, targetOffsetX = { -offset(it) }) +
                                                fadeOut(animationSpec = fadeSpec)
                                        LocationNavDirection.Back ->
                                            slideOutHorizontally(animationSpec = slideSpec, targetOffsetX = { offset(it) }) +
                                                fadeOut(animationSpec = fadeSpec)
                                        LocationNavDirection.None ->
                                            fadeOut(animationSpec = spring(stiffness = 2000f))
                                    }
                                    (enter togetherWith exit).using(null)
                                },
                                label = "workspace_location_folder_transition",
                            ) { state ->
                                when (state) {
                                    is DirectorySnapshot -> {
                                        DirectoryList(
                                            isAtRoot = state.path.isEmpty(),
                                            entries = state.entries,
                                            hasError = state.error != null,
                                            onSwitchWorkspace = {
                                                haptics.perform(HapticPattern.Pop)
                                                modeDirection = LocationNavDirection.Forward
                                                mode = WorkspaceLocationSheetMode.WORKSPACE_PICKER
                                            },
                                            onOpenFolder = { entry ->
                                                haptics.perform(HapticPattern.Pop)
                                                folderDirection = LocationNavDirection.Forward
                                                browsingPath = entry.path
                                            },
                                        )
                                    }

                                    else -> {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(360.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.5.dp)
                                        }
                                    }
                                }
                            }

                            // 底部单行操作栏归属目录页整体，随 mode 一起平滑收放，无额外间距突跳
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                TextButton(
                                    enabled = canConfirmDirectory,
                                    onClick = {
                                        haptics.perform(HapticPattern.Pop)
                                        createFolderError = null
                                        newFolderName = ""
                                        creatingFolder = true
                                    },
                                ) {
                                    Icon(Icons.Rounded.CreateNewFolder, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(stringResource(R.string.workspace_location_new_folder))
                                }

                                Button(
                                    enabled = canConfirmDirectory,
                                    shape = AppShapes.ButtonPill,
                                    onClick = {
                                        val wsId = browsingWorkspace?.id ?: return@Button
                                        val (finalWorkspaceId, finalCwd) = computeWorkspaceLocationOverride(
                                            inheritedWorkspaceId = validInheritedWorkspaceId,
                                            inheritedCwd = inheritedCwd,
                                            targetWorkspaceId = wsId,
                                            targetCwd = browsingPath,
                                        )
                                        haptics.perform(HapticPattern.Success)
                                        onApply(finalWorkspaceId, finalCwd)
                                        hideAndDismiss()
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(
                                        text = if (browsingPath.isEmpty()) {
                                            stringResource(R.string.workspace_location_use_root_dir)
                                        } else {
                                            stringResource(R.string.workspace_location_use_current_dir)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DirectoryList(
    isAtRoot: Boolean,
    entries: List<WorkspaceFileEntry>,
    hasError: Boolean,
    onSwitchWorkspace: () -> Unit,
    onOpenFolder: (WorkspaceFileEntry) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .height(360.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 仅在根目录首项显示「选择其他工作区」入口
        if (isAtRoot) {
            item(key = "switch_other_workspace") {
                ListItem(
                    colors = ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Rounded.SwapHoriz,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.workspace_location_switch_workspace),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingContent = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(AppShapes.CardMedium)
                        .clickable(onClick = onSwitchWorkspace),
                )
            }
        }

        if (entries.isEmpty() && !hasError) {
            item(key = "empty_hint") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.workspace_location_empty_folders),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(entries, key = { it.path }) { entry ->
                ListItem(
                    colors = ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                    leadingContent = {
                        Box(modifier = Modifier.size(28.dp)) {
                            Icon(
                                imageVector = Icons.Rounded.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .size(24.dp)
                                    .align(Alignment.CenterStart),
                            )
                            if (entry.isMountRoot) {
                                Icon(
                                    imageVector = Icons.Rounded.Link,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier
                                        .size(13.dp)
                                        .align(Alignment.BottomEnd),
                                )
                            }
                        }
                    },
                    headlineContent = {
                        Text(
                            text = entry.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    supportingContent = if (entry.isMountRoot) {
                        {
                            Text(
                                text = stringResource(R.string.workspace_location_mounted_folder),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(AppShapes.CardMedium)
                        .clickable { onOpenFolder(entry) },
                )
            }
        }
    }
}

@Composable
private fun WorkspacePickerList(
    workspaces: List<Workspace>,
    selectedWorkspaceId: String?,
    inheritedWorkspaceId: String?,
    inheritedLabel: String,
    onSelectWorkspace: (Workspace) -> Unit,
    onManageWorkspaces: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (workspaces.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.workspace_location_no_workspace_yet),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            workspaces.forEach { workspace ->
                val isSelected = workspace.id == selectedWorkspaceId
                val isInherited = workspace.id == inheritedWorkspaceId
                val subtitle = stringResource(
                    if (workspace.type == WorkspaceType.LIGHTWEIGHT) R.string.workspace_type_lightweight
                    else R.string.workspace_type_sandbox
                ) + workspace.sandboxStatus?.let { " · ${sandboxStatusText(it)}" }.orEmpty() +
                    if (isInherited) " · $inheritedLabel" else ""

                ListItem(
                    colors = ListItemDefaults.colors(
                        containerColor = if (isSelected) {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        },
                    ),
                    leadingContent = {
                        Icon(
                            imageVector = if (workspace.type == WorkspaceType.SANDBOX) {
                                Icons.Rounded.Terminal
                            } else {
                                Icons.Rounded.Folder
                            },
                            contentDescription = null,
                            tint = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                    headlineContent = {
                        Text(
                            text = workspace.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    supportingContent = {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingContent = if (isSelected) {
                        {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(AppShapes.CardMedium)
                        .clickable { onSelectWorkspace(workspace) },
                )
            }
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            modifier = Modifier.padding(vertical = 4.dp),
        )

        ListItem(
            colors = ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
            leadingContent = {
                Icon(
                    imageVector = Icons.Rounded.Folder,
                    contentDescription = null,
                )
            },
            headlineContent = {
                Text(text = stringResource(R.string.workspace_manage))
            },
            trailingContent = {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .clip(AppShapes.CardMedium)
                .clickable(onClick = onManageWorkspaces),
        )
    }
}

@Composable
private fun LocationBreadcrumb(
    rootLabel: String,
    segments: List<String>,
    onSelectDepth: (Int) -> Unit,
) {
    val scrollState = rememberScrollState()
    LaunchedEffect(segments) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = rootLabel,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { onSelectDepth(0) },
        )
        segments.forEachIndexed { index, name ->
            Text(
                text = " / ",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = name,
                style = MaterialTheme.typography.labelLarge,
                color = if (index == segments.lastIndex) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.primary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { onSelectDepth(index + 1) },
            )
        }
    }
}
