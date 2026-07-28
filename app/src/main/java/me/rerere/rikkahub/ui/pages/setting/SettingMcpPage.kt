package me.rerere.rikkahub.ui.pages.setting

import me.rerere.rikkahub.ui.theme.LocalDarkMode

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import me.rerere.rikkahub.ui.components.ui.HapticSwitch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CommentsDisabled
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.core.InputSchema
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.McpClaudeDesktopCodec
import me.rerere.rikkahub.data.ai.mcp.McpOAuthClient
import me.rerere.rikkahub.data.ai.mcp.McpOAuthState
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.McpStatus
import me.rerere.rikkahub.data.db.entity.WorkspaceType
import me.rerere.rikkahub.ui.components.ai.WorkspaceSelectSheet
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.components.ui.PhysicsSwipeToDelete
import me.rerere.rikkahub.ui.components.ui.ItemPosition
import me.rerere.rikkahub.ui.hooks.EditState
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.theme.extendColors
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun SettingMcpPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val mcpConfigs = settings.mcpServers
    val context = LocalContext.current
    val navController = me.rerere.rikkahub.ui.context.LocalNavController.current
    val scope = rememberCoroutineScope()
    val workspaceRepository = koinInject<me.rerere.rikkahub.data.repository.WorkspaceRepository>()
    val workspaces by remember(workspaceRepository) { workspaceRepository.listFlow() }
        .collectAsStateWithLifecycle(emptyList())
    val sandboxWorkspaces = workspaces.filter { it.type == WorkspaceType.SANDBOX }
    var pendingClaudeJson by remember { mutableStateOf<String?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                            ?: error("Cannot read the selected file")
                    }
                }.onSuccess { pendingClaudeJson = it }
                    .onFailure { importError = it.message }
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val json = McpClaudeDesktopCodec.exportStdioServers(mcpConfigs)
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(json) }
                            ?: error("Cannot write the selected file")
                    }
                }.onFailure { importError = it.message }
            }
        }
    }
    val creationState = useEditState<McpServerConfig> {
        vm.saveMcpConfig(it)
    }
    val editState = useEditState<McpServerConfig> { newConfig ->
        vm.saveMcpConfig(newConfig)
    }
    
    // Delete confirmation state - at function level so accessible by dialog
    var showDeleteDialog by remember { mutableStateOf(false) }
    var mcpToDelete by remember { mutableStateOf<McpServerConfig?>(null) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val lazyListState = rememberLazyListState()
    
    Scaffold(
        topBar = {
            OneUITopAppBar(
                title = stringResource(R.string.setting_mcp_page_title),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    IconButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/json")) }) {
                        Icon(Icons.Rounded.FileUpload, stringResource(R.string.setting_mcp_page_import_claude))
                    }
                    IconButton(
                        onClick = { exportLauncher.launch("claude_desktop_config.json") },
                        enabled = mcpConfigs.any { it is McpServerConfig.StdioServer },
                    ) {
                        Icon(Icons.Rounded.FileDownload, stringResource(R.string.setting_mcp_page_export_claude))
                    }
                    IconButton(
                        onClick = {
                            creationState.open(McpServerConfig.SseTransportServer())
                        }
                    ) {
                        Icon(Icons.Rounded.Add, null)
                    }
                }
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { innerPadding ->
        val mcpManager = koinInject<McpManager>()
        val status by mcpManager.syncingStatus.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        val state = rememberPullToRefreshState()
        val loading = status.values.any { it == McpStatus.Connecting }
        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = {
                scope.launch {
                    mcpManager.syncAll()
                }
            },
            state = state,
            modifier = Modifier.padding(innerPadding)
        ) {
            // Track which item is being dragged and its offset
            var draggingIndex by remember { mutableStateOf(-1) }
            var dragOffset by remember { mutableFloatStateOf(0f) }
            var isUnlocked by remember { mutableStateOf(false) }
            var neighborsUnlocked by remember { mutableStateOf(false) }
            
            // Reset neighborsUnlocked when offset returns to 0 (entry back in place)
            if (dragOffset == 0f && neighborsUnlocked) {
                neighborsUnlocked = false
            }
            
            // Screen-level fade on left edge
            val density = androidx.compose.ui.platform.LocalDensity.current
            val unlockThresholdPx = with(density) { 35.dp.toPx() }
            val fadeProgress = (kotlin.math.abs(dragOffset) / unlockThresholdPx).coerceIn(0f, 1f)
            val backgroundColor = MaterialTheme.colorScheme.surfaceContainerLowest
            
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize(),
                    state = lazyListState,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    itemsIndexed(mcpConfigs, key = { _, it -> it.id }) { index, mcpConfig ->
                        val position = when {
                            mcpConfigs.size == 1 -> ItemPosition.ONLY
                            index == 0 -> ItemPosition.FIRST
                            index == mcpConfigs.lastIndex -> ItemPosition.LAST
                            else -> ItemPosition.MIDDLE
                        }
                        
                        // Calculate neighbor offset based on distance from dragging item
                        val thresholdPx = with(density) { 35.dp.toPx() }
                        
                        // Check if we just crossed the threshold
                        if (draggingIndex >= 0 && !neighborsUnlocked && kotlin.math.abs(dragOffset) >= thresholdPx) {
                            neighborsUnlocked = true
                        }
                        
                        // Neighbors only follow if we haven't unlocked yet
                        val shouldNeighborFollow = draggingIndex >= 0 && 
                            draggingIndex != index && 
                            !isUnlocked && 
                            !neighborsUnlocked
                        
                        val neighborOffset = if (shouldNeighborFollow) {
                            val distance = kotlin.math.abs(index - draggingIndex)
                            when (distance) {
                                1 -> dragOffset * 0.35f  // Direct neighbors get 35%
                                2 -> dragOffset * 0.12f  // Neighbors of neighbors get 12%
                                else -> 0f
                            }
                        } else {
                            0f
                        }
                        
                        McpServerItem(
                            item = mcpConfig,
                            workspaceAvailable = mcpConfig !is McpServerConfig.StdioServer ||
                                sandboxWorkspaces.any { it.id == mcpConfig.workspaceId },
                            workspaceReady = mcpConfig !is McpServerConfig.StdioServer ||
                                sandboxWorkspaces.firstOrNull { it.id == mcpConfig.workspaceId }
                                    ?.let { workspace ->
                                        workspace.sandboxStatus ==
                                            me.rerere.rikkahub.data.db.entity.SandboxRootfsStatus.READY
                                    } == true,
                            position = position,
                            neighborOffset = neighborOffset,
                            onDragProgress = { offset, unlocked ->
                                draggingIndex = index
                                dragOffset = offset
                                isUnlocked = unlocked
                            },
                            onDragEnd = {
                                if (draggingIndex == index) {
                                    draggingIndex = -1
                                    dragOffset = 0f
                                }
                            },
                            onEdit = {
                                editState.open(mcpConfig)
                            },
                            onDelete = {
                                mcpToDelete = mcpConfig
                                showDeleteDialog = true
                            },
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }

            if (mcpConfigs.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(text = stringResource(R.string.setting_mcp_page_no_mcp_servers_found))
                    Text(
                        text = stringResource(R.string.setting_mcp_page_add_one_to_get_started),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
    
    // Delete confirmation dialog
    if (showDeleteDialog && mcpToDelete != null) {
        AlertDialog(
            onDismissRequest = { 
                showDeleteDialog = false
                mcpToDelete = null
            },
            title = {
                Text(stringResource(R.string.confirm_delete))
            },
            text = {
                Text(stringResource(R.string.setting_mcp_page_delete_confirm))
            },
            dismissButton = {
                TextButton(onClick = { 
                    showDeleteDialog = false
                    mcpToDelete = null
                }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        mcpToDelete?.let { mcp ->
                            vm.deleteMcpConfig(mcp)
                        }
                        showDeleteDialog = false
                        mcpToDelete = null
                    }
                ) {
                    Text(stringResource(R.string.delete))
                }
            }
        )
    }
    McpServerConfigModal(creationState)
    McpServerConfigModal(editState)

    if (pendingClaudeJson != null) {
        WorkspaceSelectSheet(
            selectedWorkspaceId = null,
            workspaces = sandboxWorkspaces,
            onSelect = { workspaceId ->
                val json = pendingClaudeJson
                if (workspaceId != null && json != null) {
                    runCatching { McpClaudeDesktopCodec.importStdioServers(json, workspaceId) }
                        .onSuccess(vm::importMcpConfigs)
                        .onFailure { importError = it.message }
                    pendingClaudeJson = null
                }
            },
            onManage = { navController.navigate(me.rerere.rikkahub.Screen.Workspaces) },
            onDismiss = { pendingClaudeJson = null },
            noneOptionTitle = stringResource(R.string.setting_mcp_page_stdio_select_workspace),
        )
    }

    importError?.let { message ->
        AlertDialog(
            onDismissRequest = { importError = null },
            title = { Text(stringResource(R.string.setting_mcp_page_import_error)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { importError = null }) { Text(stringResource(R.string.confirm)) }
            },
        )
    }
}

@Composable
private fun McpServerItem(
    item: McpServerConfig,
    workspaceAvailable: Boolean,
    workspaceReady: Boolean,
    position: ItemPosition,
    neighborOffset: Float = 0f,
    onDragProgress: ((Float, Boolean) -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    onDelete: () -> Unit,
    onEdit: (McpServerConfig) -> Unit,
) {
    val mcpManager = koinInject<McpManager>()
    val status by mcpManager.getStatus(item).collectAsStateWithLifecycle(McpStatus.Idle)
    var showErrorDetail by remember { mutableStateOf(false) }
    
    PhysicsSwipeToDelete(
        onDelete = onDelete,
        position = position,
        neighborOffset = neighborOffset,
        onDragProgress = onDragProgress,
        onDragEnd = onDragEnd,
        modifier = modifier
    ) {
        Card(
            shape = me.rerere.rikkahub.ui.theme.AppShapes.CardLarge,
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = if (LocalDarkMode.current) androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow else androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (status) {
                    McpStatus.Idle -> Icon(Icons.Rounded.CommentsDisabled, null)
                    McpStatus.Connecting -> CircularProgressIndicator(
                        modifier = Modifier.size(
                            24.dp
                        )
                    )

                    McpStatus.Connected -> Icon(Icons.Rounded.Terminal, null)
                    McpStatus.Ready -> Icon(Icons.Rounded.Terminal, null)
                    is McpStatus.Reconnecting -> CircularProgressIndicator(
                        modifier = Modifier.size(24.dp)
                    )
                    McpStatus.NeedsAuthorization -> Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                    )
                    McpStatus.Authorizing -> CircularProgressIndicator(
                        modifier = Modifier.size(24.dp)
                    )
                    is McpStatus.Error -> Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = item.commonOptions.name,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        val dotColor =
                            if (item.commonOptions.enable) MaterialTheme.extendColors.green6 else MaterialTheme.extendColors.red6
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .drawWithContent {
                                    drawCircle(
                                        color = dotColor
                                    )
                                }
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Tag(type = TagType.SUCCESS) {
                            when (item) {
                                is McpServerConfig.SseTransportServer -> Text(stringResource(R.string.setting_mcp_page_transport_sse))
                                is McpServerConfig.StreamableHTTPServer -> Text(stringResource(R.string.setting_mcp_page_transport_streamable_http))
                                is McpServerConfig.StdioServer -> Text(stringResource(R.string.setting_mcp_page_transport_stdio))
                            }
                        }
                        when (status) {
                            is McpStatus.Reconnecting -> Tag(type = TagType.WARNING) {
                                val s = status as McpStatus.Reconnecting
                                Text(stringResource(R.string.mcp_status_reconnecting_format, s.attempt, s.maxAttempts))
                            }
                            McpStatus.NeedsAuthorization -> Tag(type = TagType.ERROR) {
                                Text(stringResource(R.string.mcp_status_needs_authorization))
                            }
                            McpStatus.Authorizing -> Tag(type = TagType.WARNING) {
                                Text(stringResource(R.string.mcp_status_authorizing))
                            }
                            is McpStatus.Error -> Unit
                            else -> Unit
                        }
                        if (!workspaceAvailable) {
                            Tag(type = TagType.ERROR) {
                                Text(stringResource(R.string.mcp_status_workspace_missing))
                            }
                        } else if (!workspaceReady) {
                            Tag(type = TagType.WARNING) {
                                Text(stringResource(R.string.mcp_status_rootfs_not_ready))
                            }
                        }
                    }
                    (status as? McpStatus.Error)?.let { error ->
                        val message = error.messageResId?.let {
                            stringResource(it, *error.messageArgs.toTypedArray())
                        } ?: error.message
                        McpErrorSummary(
                            message = stringResource(R.string.mcp_status_error_format, message),
                            hasDetails = error.detail != null,
                            onShowDetails = { showErrorDetail = true },
                        )
                    }
                    val canAuthorize = status == McpStatus.NeedsAuthorization ||
                        (status as? McpStatus.Error)?.canRetryAuthorization == true
                    if (canAuthorize) {
                        val context = LocalContext.current
                        Button(
                            onClick = { mcpManager.startAuthorization(item, context) },
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        ) {
                            Text(stringResource(R.string.mcp_oauth_authorize))
                        }
                    }
                    if (status == McpStatus.Authorizing) {
                        TextButton(
                            onClick = { mcpManager.cancelAuthorization(item) },
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        ) {
                            Text(stringResource(R.string.mcp_oauth_cancel))
                        }
                    }
                }

                IconButton(
                    onClick = {
                        onEdit(item)
                    }
                ) {
                    Icon(Icons.Rounded.Settings, null)
                }
            }
        }
    }
    val errorDetail = (status as? McpStatus.Error)?.detail
    if (showErrorDetail && errorDetail != null) {
        AlertDialog(
            onDismissRequest = { showErrorDetail = false },
            title = { Text(stringResource(R.string.setting_mcp_page_error_details)) },
            text = {
                SelectionContainer {
                    Text(
                        text = errorDetail,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showErrorDetail = false }) { Text(stringResource(R.string.confirm)) }
            },
        )
    }
}

@Composable
private fun McpErrorSummary(
    message: String,
    hasDetails: Boolean,
    onShowDetails: () -> Unit,
) {
    val haptics = rememberPremiumHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .let { modifier ->
                if (hasDetails) {
                    modifier.clickable {
                        haptics.perform(HapticPattern.Pop)
                        onShowDetails()
                    }
                } else {
                    modifier
                }
            }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = message,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (hasDetails) {
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = stringResource(R.string.setting_mcp_page_error_details),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun McpServerConfigModal(state: EditState<McpServerConfig>) {
    state.EditStateContent { config, updateValue ->
        val pagerState = rememberPagerState { 2 }
        val scope = rememberCoroutineScope()
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = {
                state.dismiss()
            },
            sheetState = sheetState,
            sheetGesturesEnabled = false,
            dragHandle = {
                IconButton(
                    onClick = {
                        scope.launch {
                            sheetState.hide()
                            state.dismiss()
                        }
                    }
                ) {
                    Icon(Icons.Rounded.KeyboardArrowDown, null)
                }
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.8f)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SecondaryTabRow(
                    selectedTabIndex = pagerState.currentPage,
                    containerColor = Color.Transparent
                ) {
                    Tab(
                        selected = pagerState.currentPage == 0,
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(0)
                            }
                        },
                        text = {
                            Text(stringResource(R.string.setting_mcp_page_basic_settings))
                        }
                    )
                    Tab(
                        selected = pagerState.currentPage == 1,
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(1)
                            }
                        },
                        text = {
                            Text(stringResource(R.string.setting_mcp_page_tools))
                        }
                    )
                }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) { page ->
                    when (page) {
                        0 -> {
                            McpCommonOptionsConfigure(
                                config = config,
                                update = updateValue
                            )
                        }

                        1 -> {
                            McpToolsConfigure(
                                config = config,
                                update = updateValue,
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    val canSave = config.commonOptions.name.isNotBlank() && when (config) {
                        is McpServerConfig.StdioServer ->
                            config.workspaceId.isNotBlank() &&
                                config.command.isNotBlank() &&
                                config.workingDirectory.startsWith('/')
                        else -> true
                    }
                    TextButton(
                        onClick = {
                            if (canSave) {
                                state.confirm()
                            }
                        },
                        enabled = canSave,
                    ) {
                        Text(stringResource(R.string.setting_mcp_page_save_and_sync))
                    }
                }
            }
        }
    }
}

@Composable
private fun McpCommonOptionsConfigure(
    config: McpServerConfig,
    update: (McpServerConfig) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 启用/禁用开关
        FormItem(
            label = {
                Text(stringResource(R.string.setting_mcp_page_enable))
            },
            description = {
                Text(stringResource(R.string.setting_mcp_page_enable_desc))
            }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.setting_mcp_page_enable))
                Spacer(Modifier.weight(1f))
                HapticSwitch(
                    checked = config.commonOptions.enable,
                    onCheckedChange = { enabled ->
                        update(config.clone(commonOptions = config.commonOptions.copy(enable = enabled)))
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 名称输入框
        FormItem(
            label = {
                Text(stringResource(R.string.setting_mcp_page_name))
            },
            description = {
                Text(stringResource(R.string.setting_mcp_page_name_desc))
            }
        ) {
            OutlinedTextField(
                value = config.commonOptions.name,
                onValueChange = { name ->
                    update(config.clone(commonOptions = config.commonOptions.copy(name = name)))
                },
                label = { Text(stringResource(R.string.setting_mcp_page_name)) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.setting_mcp_page_name_placeholder)) }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 传输类型选择
        FormItem(
            label = {
                Text(stringResource(R.string.setting_mcp_page_transport_type))
            },
            description = {
                Text(stringResource(R.string.setting_mcp_page_transport_type_desc))
            }
        ) {
            val transportTypes = listOf(
                "SSE",
                "HTTP",
                "STDIO",
            )
            val currentTypeIndex = when (config) {
                is McpServerConfig.SseTransportServer -> 0
                is McpServerConfig.StreamableHTTPServer -> 1
                is McpServerConfig.StdioServer -> 2
            }

            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth()
            ) {
                transportTypes.forEachIndexed { index, type ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index, transportTypes.size),
                        onClick = {
                            if (index != currentTypeIndex) {
                                val newConfig = when (index) {
                                    0 -> McpServerConfig.SseTransportServer(
                                        id = config.id,
                                        commonOptions = config.commonOptions,
                                        url = when (config) {
                                            is McpServerConfig.SseTransportServer -> config.url
                                            is McpServerConfig.StreamableHTTPServer -> config.url
                                            is McpServerConfig.StdioServer -> ""
                                        }
                                    )

                                    1 -> McpServerConfig.StreamableHTTPServer(
                                        id = config.id,
                                        commonOptions = config.commonOptions,
                                        url = when (config) {
                                            is McpServerConfig.SseTransportServer -> config.url
                                            is McpServerConfig.StreamableHTTPServer -> config.url
                                            is McpServerConfig.StdioServer -> ""
                                        }
                                    )

                                    2 -> McpServerConfig.StdioServer(
                                        id = config.id,
                                        commonOptions = config.commonOptions.copy(
                                            headers = emptyList(),
                                            oauth = null,
                                            tools = emptyList(),
                                        ),
                                    )

                                    else -> config
                                }
                                update(newConfig)
                            }
                        },
                        selected = index == currentTypeIndex
                    ) {
                        Text(type)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (config is McpServerConfig.StdioServer) {
            McpStdioOptionsConfigure(config, update)
        } else {
        // 服务器地址配置
        FormItem(
            label = {
                Text(stringResource(R.string.setting_mcp_page_server_url))
            },
            description = {
                Text(
                    when (config) {
                        is McpServerConfig.SseTransportServer -> stringResource(R.string.setting_mcp_page_sse_url_desc)
                        is McpServerConfig.StreamableHTTPServer -> stringResource(R.string.setting_mcp_page_streamable_http_url_desc)
                        is McpServerConfig.StdioServer -> ""
                    }
                )
            }
        ) {
            OutlinedTextField(
                value = when (config) {
                    is McpServerConfig.SseTransportServer -> config.url
                    is McpServerConfig.StreamableHTTPServer -> config.url
                    is McpServerConfig.StdioServer -> ""
                },
                onValueChange = { url ->
                    update(
                        when (config) {
                            is McpServerConfig.SseTransportServer -> config.copy(
                                url = url,
                                commonOptions = config.commonOptions.copy(oauth = null),
                            )
                            is McpServerConfig.StreamableHTTPServer -> config.copy(
                                url = url,
                                commonOptions = config.commonOptions.copy(oauth = null),
                            )
                            is McpServerConfig.StdioServer -> config
                        }
                    )
                },
                label = { Text(stringResource(R.string.setting_mcp_page_url_label)) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        when (config) {
                            is McpServerConfig.SseTransportServer -> stringResource(R.string.setting_mcp_page_sse_url_placeholder)
                            is McpServerConfig.StreamableHTTPServer -> stringResource(R.string.setting_mcp_page_streamable_http_url_placeholder)
                            is McpServerConfig.StdioServer -> ""
                        }
                    )
                }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        FormItem(
            label = {
                Text(stringResource(R.string.setting_mcp_page_oauth_client))
            },
            description = {
                Text(stringResource(R.string.setting_mcp_page_oauth_client_desc))
            }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = config.commonOptions.oauth?.clientId.orEmpty(),
                    onValueChange = { clientId ->
                        update(
                            config.withOAuthClientCredentials(
                                clientId = clientId,
                                clientSecret = config.commonOptions.oauth?.clientSecret.orEmpty(),
                                tokenEndpointAuthMethod = config.commonOptions.oauth?.tokenEndpointAuthMethod
                                    ?: McpOAuthClient.TOKEN_ENDPOINT_AUTH_NONE,
                            )
                        )
                    },
                    label = { Text(stringResource(R.string.setting_mcp_page_oauth_client_id)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = config.commonOptions.oauth?.clientSecret.orEmpty(),
                    onValueChange = { clientSecret ->
                        val currentMethod = config.commonOptions.oauth?.tokenEndpointAuthMethod
                        val authMethod = if (clientSecret.isBlank()) {
                            McpOAuthClient.TOKEN_ENDPOINT_AUTH_NONE
                        } else {
                            currentMethod?.takeUnless { it == McpOAuthClient.TOKEN_ENDPOINT_AUTH_NONE }
                                ?: McpOAuthClient.TOKEN_ENDPOINT_AUTH_BASIC
                        }
                        update(
                            config.withOAuthClientCredentials(
                                clientId = config.commonOptions.oauth?.clientId.orEmpty(),
                                clientSecret = clientSecret,
                                tokenEndpointAuthMethod = authMethod,
                            )
                        )
                    },
                    label = { Text(stringResource(R.string.setting_mcp_page_oauth_client_secret)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation(),
                )

                val authMethods = listOf(
                    McpOAuthClient.TOKEN_ENDPOINT_AUTH_NONE to
                        stringResource(R.string.setting_mcp_page_oauth_auth_public),
                    McpOAuthClient.TOKEN_ENDPOINT_AUTH_BASIC to
                        stringResource(R.string.setting_mcp_page_oauth_auth_basic),
                    McpOAuthClient.TOKEN_ENDPOINT_AUTH_POST to
                        stringResource(R.string.setting_mcp_page_oauth_auth_post),
                )
                val selectedAuthMethod = config.commonOptions.oauth?.tokenEndpointAuthMethod
                    ?: McpOAuthClient.TOKEN_ENDPOINT_AUTH_NONE
                Text(stringResource(R.string.setting_mcp_page_oauth_auth_method))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    authMethods.forEachIndexed { index, (method, label) ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(index, authMethods.size),
                            onClick = {
                                update(
                                    config.withOAuthClientCredentials(
                                        clientId = config.commonOptions.oauth?.clientId.orEmpty(),
                                        clientSecret = config.commonOptions.oauth?.clientSecret.orEmpty(),
                                        tokenEndpointAuthMethod = method,
                                    )
                                )
                            },
                            selected = method == selectedAuthMethod,
                        ) {
                            Text(label)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 请求头配置
        FormItem(
            label = {
                Text(stringResource(R.string.setting_mcp_page_custom_headers))
            },
            description = {
                Text(stringResource(R.string.setting_mcp_page_custom_headers_desc))
            }
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                config.commonOptions.headers.forEachIndexed { index, header ->
                    var headerName by remember(header.first) { mutableStateOf(header.first) }
                    var headerValue by remember(header.second) { mutableStateOf(header.second) }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            OutlinedTextField(
                                value = headerName,
                                onValueChange = {
                                    headerName = it
                                    val updatedHeaders =
                                        config.commonOptions.headers.toMutableList()
                                    updatedHeaders[index] =
                                        it.trim() to updatedHeaders[index].second
                                    update(
                                        when (config) {
                                            is McpServerConfig.SseTransportServer -> config.copy(
                                                commonOptions = config.commonOptions.copy(headers = updatedHeaders)
                                            )

                                            is McpServerConfig.StreamableHTTPServer -> config.copy(
                                                commonOptions = config.commonOptions.copy(headers = updatedHeaders)
                                            )
                                            is McpServerConfig.StdioServer -> config
                                        }
                                    )
                                },
                                label = { Text(stringResource(R.string.setting_mcp_page_header_name)) },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text(stringResource(R.string.setting_mcp_page_header_name_placeholder)) }
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = headerValue,
                                onValueChange = {
                                    headerValue = it
                                    val updatedHeaders =
                                        config.commonOptions.headers.toMutableList()
                                    updatedHeaders[index] = updatedHeaders[index].first to it.trim()
                                    update(
                                        when (config) {
                                            is McpServerConfig.SseTransportServer -> config.copy(
                                                commonOptions = config.commonOptions.copy(headers = updatedHeaders)
                                            )

                                            is McpServerConfig.StreamableHTTPServer -> config.copy(
                                                commonOptions = config.commonOptions.copy(headers = updatedHeaders)
                                            )
                                            is McpServerConfig.StdioServer -> config
                                        }
                                    )
                                },
                                label = { Text(stringResource(R.string.setting_mcp_page_header_value)) },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text(stringResource(R.string.setting_mcp_page_header_value_placeholder)) }
                            )
                        }
                        IconButton(onClick = {
                            val updatedHeaders = config.commonOptions.headers.toMutableList()
                            updatedHeaders.removeAt(index)
                            update(
                                when (config) {
                                    is McpServerConfig.SseTransportServer -> config.copy(
                                        commonOptions = config.commonOptions.copy(headers = updatedHeaders)
                                    )

                                    is McpServerConfig.StreamableHTTPServer -> config.copy(
                                        commonOptions = config.commonOptions.copy(headers = updatedHeaders)
                                    )
                                    is McpServerConfig.StdioServer -> config
                                }
                            )
                        }) {
                            Icon(
                                Icons.Rounded.Delete,
                                contentDescription = stringResource(R.string.setting_mcp_page_delete_header)
                            )
                        }
                    }
                }

                Button(
                    onClick = {
                        val updatedHeaders = config.commonOptions.headers.toMutableList()
                        updatedHeaders.add("" to "")
                        update(
                            when (config) {
                                is McpServerConfig.SseTransportServer -> config.copy(
                                    commonOptions = config.commonOptions.copy(headers = updatedHeaders)
                                )

                                is McpServerConfig.StreamableHTTPServer -> config.copy(
                                    commonOptions = config.commonOptions.copy(headers = updatedHeaders)
                                )
                                is McpServerConfig.StdioServer -> config
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Rounded.Add,
                        contentDescription = stringResource(R.string.setting_mcp_page_add_header)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.setting_mcp_page_add_header))
                }
            }
        }
        }
    }
}

@Composable
private fun McpStdioOptionsConfigure(
    config: McpServerConfig.StdioServer,
    update: (McpServerConfig) -> Unit,
) {
    val workspaceRepository = koinInject<me.rerere.rikkahub.data.repository.WorkspaceRepository>()
    val navController = me.rerere.rikkahub.ui.context.LocalNavController.current
    val workspaces by remember(workspaceRepository) { workspaceRepository.listFlow() }
        .collectAsStateWithLifecycle(emptyList())
    val sandboxes = workspaces.filter { it.type == WorkspaceType.SANDBOX }
    val selectedWorkspace = sandboxes.firstOrNull { it.id == config.workspaceId }
    var showWorkspacePicker by remember { mutableStateOf(false) }

    FormItem(
        label = { Text(stringResource(R.string.setting_mcp_page_stdio_workspace)) },
        description = { Text(stringResource(R.string.setting_mcp_page_stdio_trust_warning)) },
    ) {
        Button(
            onClick = { showWorkspacePicker = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(selectedWorkspace?.name ?: stringResource(R.string.setting_mcp_page_stdio_select_workspace))
        }
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_mcp_page_stdio_command)) },
        description = { Text(stringResource(R.string.setting_mcp_page_stdio_command_desc)) },
    ) {
        OutlinedTextField(
            value = config.command,
            onValueChange = { update(config.copy(command = it)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_mcp_page_stdio_args)) },
        description = { Text(stringResource(R.string.setting_mcp_page_stdio_args_desc)) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            config.args.forEachIndexed { index, argument ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = argument,
                        onValueChange = { value ->
                            update(config.copy(args = config.args.toMutableList().apply { this[index] = value }))
                        },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text(stringResource(R.string.setting_mcp_page_stdio_arg, index + 1)) },
                    )
                    IconButton(onClick = {
                        update(config.copy(args = config.args.toMutableList().apply { removeAt(index) }))
                    }) {
                        Icon(Icons.Rounded.Delete, stringResource(R.string.delete))
                    }
                }
            }
            Button(
                onClick = { update(config.copy(args = config.args + "")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Add, null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.setting_mcp_page_stdio_add_arg))
            }
        }
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_mcp_page_stdio_working_directory)) },
        description = { Text(stringResource(R.string.setting_mcp_page_stdio_working_directory_desc)) },
    ) {
        OutlinedTextField(
            value = config.workingDirectory,
            onValueChange = { update(config.copy(workingDirectory = it)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_mcp_page_stdio_environment)) },
        description = { Text(stringResource(R.string.setting_mcp_page_stdio_environment_desc)) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            config.environment.entries.toList().forEachIndexed { index, entry ->
                val entries = config.environment.entries.toList()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = entry.key,
                            onValueChange = { name ->
                                val updated = linkedMapOf<String, String>()
                                entries.forEachIndexed { currentIndex, current ->
                                    updated[if (currentIndex == index) name else current.key] = current.value
                                }
                                update(config.copy(environment = updated))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(stringResource(R.string.setting_mcp_page_stdio_env_name)) },
                        )
                        OutlinedTextField(
                            value = entry.value,
                            onValueChange = { value ->
                                update(config.copy(environment = config.environment + (entry.key to value)))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(stringResource(R.string.setting_mcp_page_stdio_env_value)) },
                            visualTransformation = PasswordVisualTransformation(),
                        )
                    }
                    IconButton(onClick = {
                        update(config.copy(environment = config.environment - entry.key))
                    }) {
                        Icon(Icons.Rounded.Delete, stringResource(R.string.delete))
                    }
                }
            }
            Button(
                onClick = {
                    var index = config.environment.size + 1
                    var name = "VARIABLE_$index"
                    while (name in config.environment) {
                        index++
                        name = "VARIABLE_$index"
                    }
                    update(config.copy(environment = config.environment + (name to "")))
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Add, null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.setting_mcp_page_stdio_add_env))
            }
        }
    }

    FormItem(
        label = { Text(stringResource(R.string.setting_mcp_page_stdio_startup_timeout)) },
        description = { Text(stringResource(R.string.setting_mcp_page_stdio_startup_timeout_desc)) },
    ) {
        OutlinedTextField(
            value = config.startupTimeoutSeconds.toString(),
            onValueChange = { value ->
                value.toIntOrNull()?.coerceIn(1, 900)?.let {
                    update(config.copy(startupTimeoutSeconds = it))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }

    if (showWorkspacePicker) {
        WorkspaceSelectSheet(
            selectedWorkspaceId = config.workspaceId.ifBlank { null },
            workspaces = sandboxes,
            onSelect = { workspaceId ->
                if (workspaceId != null) update(config.copy(workspaceId = workspaceId))
                showWorkspacePicker = false
            },
            onManage = { navController.navigate(me.rerere.rikkahub.Screen.Workspaces) },
            onDismiss = { showWorkspacePicker = false },
            noneOptionTitle = stringResource(R.string.setting_mcp_page_stdio_select_workspace),
        )
    }
}

private fun McpServerConfig.withOAuthClientCredentials(
    clientId: String,
    clientSecret: String,
    tokenEndpointAuthMethod: String,
): McpServerConfig {
    val normalizedClientId = clientId.trim().takeIf { it.isNotEmpty() }
    val normalizedClientSecret = clientSecret.takeIf { it.isNotBlank() }
    val oldOAuth = commonOptions.oauth
    if (oldOAuth == null && normalizedClientId == null && normalizedClientSecret == null) return this

    val credentialsChanged = oldOAuth?.clientId != normalizedClientId ||
        oldOAuth?.clientSecret != normalizedClientSecret ||
        oldOAuth?.tokenEndpointAuthMethod != tokenEndpointAuthMethod
    val updatedOAuth = (oldOAuth ?: McpOAuthState()).copy(
        enabled = true,
        resource = if (credentialsChanged) null else oldOAuth?.resource,
        issuer = if (credentialsChanged) null else oldOAuth?.issuer,
        clientId = normalizedClientId,
        clientSecret = normalizedClientSecret,
        tokenEndpointAuthMethod = tokenEndpointAuthMethod,
        authorizationEndpoint = if (credentialsChanged) null else oldOAuth?.authorizationEndpoint,
        tokenEndpoint = if (credentialsChanged) null else oldOAuth?.tokenEndpoint,
        registrationEndpoint = if (credentialsChanged) null else oldOAuth?.registrationEndpoint,
        scope = if (credentialsChanged) null else oldOAuth?.scope,
        accessToken = if (credentialsChanged) null else oldOAuth?.accessToken,
        refreshToken = if (credentialsChanged) null else oldOAuth?.refreshToken,
        expiresAt = if (credentialsChanged) 0L else oldOAuth?.expiresAt ?: 0L,
    )
    return clone(commonOptions = commonOptions.copy(oauth = updatedOAuth))
}

@Composable
private fun McpToolsConfigure(
    config: McpServerConfig,
    update: (McpServerConfig) -> Unit,
) {
    val mcpManager = koinInject<McpManager>()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (mcpManager.getClient(config) == null && config.commonOptions.tools.isEmpty()) {
            item {
                Text(stringResource(R.string.setting_mcp_page_tools_unavailable_message))
            }
        }
        items(config.commonOptions.tools) { tool ->
            Card(
                shape = me.rerere.rikkahub.ui.theme.AppShapes.CardLarge,
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = if (LocalDarkMode.current) androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow else androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = tool.name,
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = tool.description ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            tool.inputSchema?.let { it as InputSchema.Obj }?.let { schema ->
                                schema.properties.forEach { (key, _) ->
                                    Tag(
                                        type = if (schema.required?.contains(key) == true) TagType.INFO else TagType.DEFAULT
                                    ) {
                                        Text(
                                            text = key,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.setting_mcp_page_enable),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                            )
                            HapticSwitch(
                                checked = tool.enable,
                                onCheckedChange = { newVal ->
                                    update(
                                        config.clone(
                                            commonOptions = config.commonOptions.copy(
                                                tools = config.commonOptions.tools.map {
                                                    if (tool.name == it.name) {
                                                        it.copy(enable = newVal)
                                                    } else {
                                                        it
                                                    }
                                                }
                                            )
                                        )
                                    )
                                }
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.setting_mcp_page_tool_require_approval),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                            )
                            HapticSwitch(
                                checked = tool.requireApproval,
                                onCheckedChange = { newVal ->
                                    update(
                                        config.clone(
                                            commonOptions = config.commonOptions.copy(
                                                tools = config.commonOptions.tools.map {
                                                    if (tool.name == it.name) {
                                                        it.copy(requireApproval = newVal)
                                                    } else {
                                                        it
                                                    }
                                                }
                                            )
                                        )
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
