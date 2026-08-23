package me.rerere.rikkahub.ui.pages.setting

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Input
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.ui.components.ui.ToastType
import me.rerere.rikkahub.ui.components.ui.AppToasterState
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.ScanQRCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.codex.CodexDeviceCode
import me.rerere.ai.provider.providers.codex.CodexProtocolException
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.ProviderViewMode
import me.rerere.rikkahub.data.ai.codex.CodexAuthService
import me.rerere.rikkahub.data.ai.codex.CodexLoginService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.components.ui.AutoProviderIcon
import me.rerere.rikkahub.ui.components.ui.ProviderIcon
import me.rerere.rikkahub.ui.components.ui.ItemPosition
import me.rerere.rikkahub.ui.components.ui.PhysicsSwipeToDelete
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.components.ui.decodeProviderSetting
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.pages.setting.components.PROVIDER_PRESETS
import me.rerere.rikkahub.ui.pages.setting.components.ProviderConfigure
import me.rerere.rikkahub.ui.pages.setting.components.toProviderSetting
import me.rerere.rikkahub.ui.theme.AppShapes
import me.rerere.rikkahub.utils.ImageUtils
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import me.rerere.rikkahub.data.model.Tag as DataTag

@Composable
fun SettingProviderPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val codexAuthService = koinInject<CodexAuthService>()
    
    // Search query state
    var searchQuery by remember { mutableStateOf("") }
    
    // View mode comes from settings for persistence
    val viewMode = settings.displaySetting.providerViewMode
    
    // Tag filter state
    var selectedTagIds by remember { mutableStateOf(emptySet<kotlin.uuid.Uuid>()) }
    
    // Filter providers based on search and tags
    val filteredProviders = remember(settings.providers, searchQuery, selectedTagIds) {
        var result = settings.providers
        
        // Filter by search query
        if (searchQuery.isNotBlank()) {
            result = result.filter { provider ->
                provider.name.contains(searchQuery, ignoreCase = true)
            }
        }
        
        // Filter by tags
        if (selectedTagIds.isNotEmpty()) {
            result = result.filter { provider ->
                provider.tags.containsAll(selectedTagIds)
            }
        }
        
        result
    }
    
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val haptics = rememberPremiumHaptics(enabled = settings.displaySetting.enableUIHaptics)
    var providerAddScrollTrigger by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            OneUITopAppBar(
                title = stringResource(R.string.setting_provider_page_title),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    ImportProviderButton {
                        vm.updateSettings { currentSettings ->
                            currentSettings.copy(
                                providers = listOf(it) + currentSettings.providers
                            )
                        }
                        providerAddScrollTrigger++
                    }
                    AddButton(
                        enableHaptics = settings.displaySetting.enableUIHaptics,
                        onCommitCodexProvider = { provider ->
                            vm.addProvider(provider)
                            providerAddScrollTrigger++
                        },
                        onAdd = { provider ->
                            vm.updateSettings { currentSettings ->
                                currentSettings.copy(
                                    providers = listOf(provider) + currentSettings.providers
                                )
                            }
                            providerAddScrollTrigger++
                        },
                    )
                }
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Delete confirmation dialog state
            var showDeleteDialog by remember { mutableStateOf(false) }
            var providerToDelete by remember { mutableStateOf<ProviderSetting?>(null) }
            
            // Search bar at top with view mode toggle
            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.setting_provider_page_search_placeholder)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                shape = me.rerere.rikkahub.ui.theme.AppShapes.SearchField,
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = if (searchQuery.isNotEmpty()) {
                    {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.a11y_clear))
                        }
                    }
                } else null
            )
            
            // Tag filter row - only show when there are tags
            if (settings.providerTags.isNotEmpty()) {
                ProviderTagsFilterRow(
                    providerTags = settings.providerTags,
                    selectedTagIds = selectedTagIds,
                    onUpdateSelectedTagIds = { selectedTagIds = it }
                )
            }
            
            // Provider list/grid view with AnimatedContent
            // Provider list view
            ProviderListView(
                providers = filteredProviders,
                allProviders = settings.providers,
                settings = settings,
                haptics = haptics,
                searchQuery = searchQuery,
                addScrollTrigger = providerAddScrollTrigger,
                onNavigateToDetail = { provider ->
                    navController.navigate(Screen.SettingProviderDetail(providerId = provider.id.toString()))
                },
                onDeleteRequest = { provider ->
                    providerToDelete = provider
                    showDeleteDialog = true
                },
                onReorder = { from, to ->
                    val newProviders = settings.providers.toMutableList().apply {
                        add(to, removeAt(from))
                    }
                    vm.updateSettings(settings.copy(providers = newProviders))
                },
                onAddProvider = { provider ->
                    vm.updateSettings { latest ->
                        latest.copy(providers = listOf(provider) + latest.providers)
                    }
                    providerAddScrollTrigger++
                }
            )
            
            // Delete confirmation dialog
            if (showDeleteDialog && providerToDelete != null) {
                AlertDialog(
                    onDismissRequest = { 
                        showDeleteDialog = false
                        providerToDelete = null
                    },
                    title = {
                        Text(stringResource(R.string.confirm_delete))
                    },
                    text = {
                        Text(stringResource(R.string.setting_provider_page_delete_dialog_text))
                    },
                    dismissButton = {
                        TextButton(onClick = { 
                            showDeleteDialog = false
                            providerToDelete = null
                        }) {
                            Text(stringResource(R.string.cancel))
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                val providerToRemove = providerToDelete
                                if (providerToRemove != null) {
                                    scope.launch {
                                        if (providerToRemove is ProviderSetting.OpenAICodex) {
                                            codexAuthService.logout(providerToRemove.id) {
                                                vm.removeProvider(providerToRemove.id)
                                            }
                                        } else {
                                            vm.removeProvider(providerToRemove.id)
                                        }
                                    }
                                }
                                showDeleteDialog = false
                                providerToDelete = null
                            }
                        ) {
                            Text(stringResource(R.string.delete))
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun SearchBarWithToggle(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    viewMode: ProviderViewMode,
    onToggleViewMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = { Text(stringResource(R.string.setting_provider_page_search_placeholder)) },
            modifier = Modifier.weight(1f),
            shape = AppShapes.SearchField,
            singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = if (searchQuery.isNotEmpty()) {
                {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.a11y_clear))
                    }
                }
            } else null
        )
        IconButton(onClick = onToggleViewMode) {
            Icon(
                imageVector = if (viewMode == ProviderViewMode.LIST) Icons.Rounded.GridView else Icons.AutoMirrored.Rounded.ViewList,
                contentDescription = stringResource(R.string.a11y_toggle_view_mode)
            )
        }
    }
}

@Composable
private fun ProviderListView(
    providers: List<ProviderSetting>,
    allProviders: List<ProviderSetting>,
    settings: me.rerere.rikkahub.data.datastore.Settings,
    haptics: me.rerere.rikkahub.ui.hooks.PremiumHaptics,
    searchQuery: String,
    addScrollTrigger: Int,
    onNavigateToDetail: (ProviderSetting) -> Unit,
    onDeleteRequest: (ProviderSetting) -> Unit,
    onReorder: (Int, Int) -> Unit,
    onAddProvider: (ProviderSetting) -> Unit
) {
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        onReorder(from.index, to.index)
    }
    LaunchedEffect(addScrollTrigger, providers.size) {
        if (addScrollTrigger > 0 && providers.isNotEmpty()) {
            lazyListState.animateScrollToItem(0)
        }
    }
    
    val canDelete = allProviders.size > 1
    
    // Check for matching preset when no providers found
    val matchingPreset = remember(searchQuery, providers) {
        if (providers.isEmpty() && searchQuery.isNotBlank()) {
            PROVIDER_PRESETS.find { preset ->
                !preset.requiresCodexLogin && (
                    preset.name.contains(searchQuery, ignoreCase = true) ||
                        preset.description.contains(searchQuery, ignoreCase = true)
                    )
            }
        } else null
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        state = lazyListState,
    ) {
        // Show preset suggestion if no providers match but preset exists
        if (matchingPreset != null) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.setting_provider_page_no_providers_but_preset),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                    
                    Surface(
                        onClick = {
                            val provider = matchingPreset.toProviderSetting()
                            onAddProvider(provider)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        color = if (isSystemInDarkTheme()) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AutoProviderIcon(
                                name = matchingPreset.name,
                                baseUrl = matchingPreset.baseUrl,
                                modifier = Modifier.size(40.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = matchingPreset.name,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = matchingPreset.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
        
        itemsIndexed(providers, key = { _, it -> it.id }) { index, provider ->
                val position = when {
                    providers.size == 1 -> ItemPosition.ONLY
                    index == 0 -> ItemPosition.FIRST
                    index == providers.lastIndex -> ItemPosition.LAST
                    else -> ItemPosition.MIDDLE
                }
                

                ReorderableItem(
                    state = reorderableState,
                    key = provider.id
                ) { isDragging ->
                    val itemModifier = Modifier
                        .scale(if (isDragging) 0.95f else 1f)
                        .fillMaxWidth()
                    val itemContent: @Composable () -> Unit = {
                        ProviderItemContent(
                            provider = provider,
                            providerTags = settings.providerTags,
                            haptics = haptics,
                            dragHandle = {
                                IconButton(
                                    onClick = {},
                                    modifier = Modifier
                                        .longPressDraggableHandle(
                                            onDragStarted = {
                                                haptics.perform(HapticPattern.Pop)
                                            },
                                            onDragStopped = {
                                                haptics.perform(HapticPattern.Thud)
                                            }
                                        )
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.DragIndicator,
                                        contentDescription = null
                                    )
                                }
                            },
                            onClick = {
                                onNavigateToDetail(provider)
                            }
                        )
                    }
                    androidx.compose.runtime.key(canDelete) {
                        PhysicsSwipeToDelete(
                            position = position,
                            deleteEnabled = canDelete,
                            onDelete = {
                                onDeleteRequest(provider)
                            },
                            modifier = itemModifier
                        ) {
                            itemContent()
                        }
                    }
            }
        }
    }
}

@Composable
private fun ImportProviderButton(
    onAdd: (ProviderSetting) -> Unit
) {
    val toaster = LocalToaster.current
    val context = LocalContext.current
    var showImportDialog by remember { mutableStateOf(false) }

    val scanQrCodeLauncher = rememberLauncherForActivityResult(ScanQRCode()) { result ->
        handleQRResult(result, onAdd, toaster, context)
    }

    val pickImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let {
            handleImageQRCode(it, onAdd, toaster, context)
        }
    }

    IconButton(
        onClick = {
            showImportDialog = true
        }
    ) {
        Icon(Icons.AutoMirrored.Rounded.Input, null)
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.setting_provider_page_import_dialog_title),
                    style = MaterialTheme.typography.headlineSmall
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    Text(
                        text = stringResource(R.string.setting_provider_page_import_dialog_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Button(
                            onClick = {
                                showImportDialog = false
                                scanQrCodeLauncher.launch(null)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = MaterialTheme.shapes.large
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CameraAlt,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = stringResource(R.string.setting_provider_page_scan_qr_code),
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                showImportDialog = false
                                pickImageLauncher.launch(
                                    androidx.activity.result.PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = MaterialTheme.shapes.large
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Image,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = stringResource(R.string.setting_provider_page_select_from_gallery),
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = { showImportDialog = false },
                    shape = MaterialTheme.shapes.large
                ) {
                    Text(
                        text = stringResource(R.string.cancel),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        )
    }
}

private fun handleQRResult(
    result: QRResult,
    onAdd: (ProviderSetting) -> Unit,
    toaster: AppToasterState,
    context: android.content.Context
) {
    runCatching {
        when (result) {
            is QRResult.QRError -> {
                toaster.show(
                    context.getString(
                        R.string.setting_provider_page_scan_error,
                        result
                    ), type = ToastType.Error
                )
            }

            QRResult.QRMissingPermission -> {
                toaster.show(
                    context.getString(R.string.setting_provider_page_no_permission),
                    type = ToastType.Error
                )
            }

            is QRResult.QRSuccess -> {
                val setting = decodeProviderSetting(result.content.rawValue ?: "")
                onAdd(setting)
                toaster.show(
                    context.getString(R.string.setting_provider_page_import_success),
                    type = ToastType.Success
                )
            }

            QRResult.QRUserCanceled -> {}
        }
    }.onFailure { error ->
        toaster.show(
            context.getString(R.string.setting_provider_page_qr_decode_failed, error.message ?: ""),
            type = ToastType.Error
        )
    }
}

private fun handleImageQRCode(
    uri: Uri,
    onAdd: (ProviderSetting) -> Unit,
    toaster: AppToasterState,
    context: android.content.Context
) {
    runCatching {
        val qrContent = ImageUtils.decodeQRCodeFromUri(context, uri)

        if (qrContent.isNullOrEmpty()) {
            toaster.show(
                context.getString(R.string.setting_provider_page_no_qr_found),
                type = ToastType.Error
            )
            return
        }

        val setting = decodeProviderSetting(qrContent)
        onAdd(setting)
        toaster.show(
            context.getString(R.string.setting_provider_page_import_success),
            type = ToastType.Success
        )
    }.onFailure { error ->
        toaster.show(
            context.getString(R.string.setting_provider_page_image_qr_decode_failed, error.message ?: ""),
            type = ToastType.Error
        )
    }
}


@Composable
private fun AddButton(
    enableHaptics: Boolean,
    onCommitCodexProvider: suspend (ProviderSetting.OpenAICodex) -> Unit,
    onAdd: (ProviderSetting) -> Unit,
) {
    var showBottomSheet by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showCustomProviderDialog by remember { mutableStateOf(false) }
    var codexLoginProvider by remember { mutableStateOf<ProviderSetting.OpenAICodex?>(null) }
    val navController = LocalNavController.current
    
    // Custom provider dialog state
    val customDialogState = useEditState<ProviderSetting> {
        onAdd(it)
    }

    IconButton(
        onClick = {
            searchQuery = ""
            showBottomSheet = true
        }
    ) {
        Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.add))
    }

    val haptics = rememberPremiumHaptics(enabled = enableHaptics)

    // Provider selection bottom sheet
    if (showBottomSheet) {
        val bottomSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val scope = rememberCoroutineScope()
        
        ModalBottomSheet(
            onDismissRequest = {
                showBottomSheet = false
            },
            sheetState = bottomSheetState,
            sheetGesturesEnabled = false,
            dragHandle = {
                IconButton(
                    onClick = {
                        scope.launch {
                            bottomSheetState.hide()
                            showBottomSheet = false
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
                    .padding(horizontal = 16.dp)
                    .fillMaxHeight(0.85f)
                    .clipToBounds()
            ) {
                // Title
                Text(
                    text = stringResource(R.string.setting_provider_page_choose_provider),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                )
                
                // Search bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(stringResource(R.string.setting_provider_page_search_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = AppShapes.SearchField,
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = if (searchQuery.isNotEmpty()) {
                        {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.a11y_clear))
                            }
                        }
                    } else null
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Filter presets based on search
                val filteredPresets = remember(searchQuery) {
                    if (searchQuery.isBlank()) {
                        PROVIDER_PRESETS
                    } else {
                        PROVIDER_PRESETS.filter { preset ->
                            preset.name.contains(searchQuery, ignoreCase = true) ||
                            preset.description.contains(searchQuery, ignoreCase = true)
                        }
                    }
                }
                
                CompositionLocalProvider(
                    LocalOverscrollFactory provides null
                ) {
                    val lazyListState = rememberLazyListState()
                    // Consume scroll events to prevent sheet from closing when scrolling
                    val nestedScrollConnection = remember {
                        object : NestedScrollConnection {
                            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                                // Only consume if we're not at the top and scrolling up
                                if (lazyListState.firstVisibleItemIndex > 0 || lazyListState.firstVisibleItemScrollOffset > 0) {
                                    return Offset.Zero // Let the list handle it
                                }
                                return Offset.Zero
                            }
                        }
                    }
                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier
                            .weight(1f)
                            .clipToBounds()
                            .nestedScroll(nestedScrollConnection),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                    // Add Custom Provider card at the top
                    item {
                        Card(
                            onClick = {
                                haptics.perform(HapticPattern.Pop)
                                showBottomSheet = false
                                customDialogState.open(ProviderSetting.OpenAI())
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            ),
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.setting_provider_page_add_custom_provider),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Text(
                                        text = stringResource(R.string.setting_provider_page_add_custom_provider_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    
                    // Provider presets
                    itemsIndexed(filteredPresets, key = { _, preset -> preset.name }) { index, preset ->
                        val position = when {
                            filteredPresets.size == 1 -> ItemPosition.ONLY
                            index == 0 -> ItemPosition.FIRST
                            index == filteredPresets.lastIndex -> ItemPosition.LAST
                            else -> ItemPosition.MIDDLE
                        }
                        
                        val shape = when (position) {
                            ItemPosition.FIRST -> RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 10.dp, bottomEnd = 10.dp)
                            ItemPosition.LAST -> RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp, bottomStart = 24.dp, bottomEnd = 24.dp)
                            ItemPosition.MIDDLE -> RoundedCornerShape(10.dp)
                            ItemPosition.ONLY -> RoundedCornerShape(24.dp)
                        }
                        
                        Surface(
                            onClick = {
                                haptics.perform(HapticPattern.Pop)
                                val provider = preset.toProviderSetting()
                                showBottomSheet = false
                                if (preset.requiresCodexLogin && provider is ProviderSetting.OpenAICodex) {
                                    codexLoginProvider = provider
                                } else {
                                    onAdd(provider)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = shape,
                            color = if (me.rerere.rikkahub.ui.theme.LocalDarkMode.current) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AutoProviderIcon(
                                    name = preset.name,
                                    baseUrl = preset.baseUrl,
                                    modifier = Modifier.size(40.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (preset.requiresCodexLogin) {
                                            stringResource(R.string.codex_provider_name)
                                        } else {
                                            preset.name
                                        },
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        text = if (preset.requiresCodexLogin) {
                                            stringResource(R.string.codex_provider_preset_description)
                                        } else {
                                            preset.description
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                    
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                    }
                }
            }
        }
    }

    codexLoginProvider?.let { provider ->
        CodexLoginDialog(
            provider = provider,
            onDismiss = { codexLoginProvider = null },
            commitAssociatedState = { onCommitCodexProvider(provider) },
            onSuccess = {
                codexLoginProvider = null
                navController.navigate(
                    Screen.SettingProviderDetail(providerId = provider.id.toString())
                )
            },
        )
    }
    
    // Custom provider dialog (old behavior)
    if (customDialogState.isEditing) {
        StablePositionAlertDialog(
            onDismissRequest = {
                customDialogState.dismiss()
            },
            title = {
                Text(stringResource(R.string.setting_provider_page_add_provider))
            },
            text = {
                customDialogState.currentState?.let {
                    ProviderConfigure(it) { newState ->
                        customDialogState.currentState = newState
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        customDialogState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.setting_provider_page_add))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        customDialogState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
internal fun CodexLoginDialog(
    provider: ProviderSetting.OpenAICodex,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit,
    commitAssociatedState: suspend () -> Unit = {},
    authService: CodexLoginService = koinInject<CodexAuthService>(),
    browserLauncher: (Context, Uri) -> Unit = { context, uri ->
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    },
) {
    val context = LocalContext.current
    val haptics = rememberPremiumHaptics()
    var deviceCode by remember { mutableStateOf<CodexDeviceCode?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var remainingSeconds by remember { mutableIntStateOf(0) }
    val currentOnSuccess by rememberUpdatedState(onSuccess)
    val currentCommitAssociatedState by rememberUpdatedState(commitAssociatedState)

    LaunchedEffect(provider.id, attempt) {
        deviceCode = null
        errorMessage = null
        try {
            val code = authService.startDeviceLogin(provider)
            deviceCode = code
            authService.completeDeviceLogin(provider, code) {
                currentCommitAssociatedState()
            }
            haptics.perform(HapticPattern.Success)
            currentOnSuccess()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            errorMessage = if (error is CodexProtocolException && error.statusCode == 404) {
                context.getString(R.string.codex_device_login_not_enabled)
            } else {
                error.message ?: context.getString(R.string.codex_login_failed)
            }
        }
    }

    LaunchedEffect(deviceCode) {
        val code = deviceCode ?: return@LaunchedEffect
        while (true) {
            remainingSeconds = ((code.expiresAtEpochMillis - System.currentTimeMillis()) / 1_000L)
                .coerceAtLeast(0L)
                .toInt()
            if (remainingSeconds == 0) break
            delay(1_000L)
        }
    }

    fun copyCode() {
        val code = deviceCode ?: return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("OpenAI Codex device code", code.userCode))
        haptics.perform(HapticPattern.Pop)
    }

    fun openBrowser() {
        val code = deviceCode ?: return
        haptics.perform(HapticPattern.Pop)
        runCatching {
            browserLauncher(context, Uri.parse(code.verificationUrl))
        }.onFailure {
            Toast.makeText(
                context,
                context.getString(R.string.toast_failed_to_open_url, code.verificationUrl),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    fun dismissLogin() {
        authService.cancelDeviceLogin(provider.id)
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = ::dismissLogin,
        title = { Text(stringResource(R.string.codex_login_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.codex_login_description),
                    style = MaterialTheme.typography.bodyMedium,
                )
                when {
                    errorMessage != null -> {
                        Text(
                            text = errorMessage.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    deviceCode == null -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                        Text(
                            text = stringResource(R.string.codex_requesting_device_code),
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                        )
                    }

                    else -> {
                        Text(stringResource(R.string.codex_device_code_label))
                        Surface(
                            shape = AppShapes.CardMedium,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = deviceCode?.userCode.orEmpty(),
                                    style = MaterialTheme.typography.headlineSmall,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = ::copyCode) {
                                    Icon(
                                        Icons.Rounded.ContentCopy,
                                        contentDescription = stringResource(R.string.copy),
                                    )
                                }
                            }
                        }
                        Text(
                            stringResource(
                                R.string.codex_waiting_for_authorization,
                                remainingSeconds / 60,
                                remainingSeconds % 60,
                            )
                        )
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                haptics.perform(HapticPattern.Pop)
                dismissLogin()
            }) {
                Text(stringResource(R.string.cancel))
            }
        },
        confirmButton = {
            if (errorMessage != null) {
                TextButton(onClick = {
                    haptics.perform(HapticPattern.Pop)
                    attempt++
                }) {
                    Text(stringResource(R.string.codex_retry))
                }
            } else if (deviceCode != null) {
                TextButton(onClick = ::openBrowser) {
                    Text(stringResource(R.string.webview_page_open_in_browser))
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StablePositionAlertDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
) {
    var dialogBounds by remember { mutableStateOf<Rect?>(null) }

    BasicAlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.fillMaxSize(),
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(dialogBounds, onDismissRequest) {
                    detectTapGestures { offset ->
                        if (dialogBounds?.contains(offset) == false) {
                            onDismissRequest()
                        }
                    }
                }
                .safeDrawingPadding()
                .imePadding()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(min = 280.dp, max = 560.dp)
                    .onGloballyPositioned { coordinates ->
                        val position = coordinates.positionInRoot()
                        dialogBounds = Rect(
                            left = position.x,
                            top = position.y,
                            right = position.x + coordinates.size.width,
                            bottom = position.y + coordinates.size.height,
                        )
                    },
                shape = AlertDialogDefaults.shape,
                color = AlertDialogDefaults.containerColor,
                tonalElevation = AlertDialogDefaults.TonalElevation,
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    CompositionLocalProvider(
                        LocalContentColor provides AlertDialogDefaults.titleContentColor,
                    ) {
                        ProvideTextStyle(MaterialTheme.typography.headlineSmall) {
                            title()
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    CompositionLocalProvider(
                        LocalContentColor provides AlertDialogDefaults.textContentColor,
                    ) {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                            Box(
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                text()
                            }
                        }
                    }

                    Spacer(Modifier.height(24.dp))

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        dismissButton()
                        confirmButton()
                    }
                }
            }
        }
    }
}


@Composable
private fun ProviderItemContent(
    provider: ProviderSetting,
    providerTags: List<DataTag>,
    haptics: me.rerere.rikkahub.ui.hooks.PremiumHaptics,
    dragHandle: @Composable () -> Unit,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(0.dp))
            .background(
                if (provider.enabled) {
                    if (me.rerere.rikkahub.ui.theme.LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    MaterialTheme.colorScheme.errorContainer
                }
            )
            .clickable {
                haptics.perform(HapticPattern.Pop)
                onClick()
            }
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProviderIcon(
            provider = provider,
            modifier = Modifier.size(40.dp)
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = provider.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // Non-interactive tag row with fixed height - fades to card background at right edge
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .clipToBounds()
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.wrapContentWidth(align = Alignment.Start, unbounded = true)
                ) {
                    // Show disabled tag only for disabled providers
                    if (!provider.enabled) {
                        Tag(type = TagType.WARNING) {
                            Text(stringResource(R.string.setting_provider_page_disabled))
                        }
                    }
                    Tag(type = TagType.INFO) {
                        Text(
                            stringResource(
                                R.string.setting_provider_page_model_count,
                                provider.models.size
                            )
                        )
                    }
                    if (provider.name == "AiHubMix") {
                        Tag(type = TagType.INFO) {
                            Text(stringResource(R.string.setting_provider_page_discount_10_off))
                        }
                    }
                    // Show provider's assigned tags
                    provider.tags.forEach { tagId ->
                        providerTags.find { it.id == tagId }?.let { tag ->
                            Tag(type = TagType.DEFAULT) {
                                Text(tag.name)
                            }
                        }
                    }
                }
                // Fade gradient overlay to card background color (matches enabled/disabled state)
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(width = 40.dp, height = 24.dp)
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    if (provider.enabled) {
                                        if (me.rerere.rikkahub.ui.theme.LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh
                                    } else {
                                        MaterialTheme.colorScheme.errorContainer
                                    }
                                )
                            )
                        )
                )
            }
        }
        dragHandle()
    }
}

@Composable
private fun ProviderTagsFilterRow(
    providerTags: List<DataTag>,
    selectedTagIds: Set<kotlin.uuid.Uuid>,
    onUpdateSelectedTagIds: (Set<kotlin.uuid.Uuid>) -> Unit
) {
    val scrollState = rememberLazyListState()
    val canScrollBackward by remember { derivedStateOf { scrollState.canScrollBackward } }
    val canScrollForward by remember { derivedStateOf { scrollState.canScrollForward } }
    
    LazyRow(
        state = scrollState,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .drawWithContent {
                drawContent()
                // Left edge fade (only if can scroll backward)
                if (canScrollBackward) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Black, Color.Transparent),
                            startX = 0f,
                            endX = 24.dp.toPx()
                        ),
                        blendMode = BlendMode.DstOut
                    )
                }
                // Right edge fade (only if can scroll forward)
                if (canScrollForward) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, Color.Black),
                            startX = size.width - 24.dp.toPx(),
                            endX = size.width
                        ),
                        blendMode = BlendMode.DstOut
                    )
                }
            }
    ) {
        items(providerTags) { tag ->
            FilterChip(
                selected = tag.id in selectedTagIds,
                onClick = {
                    val newSelection = if (tag.id in selectedTagIds) {
                        selectedTagIds - tag.id
                    } else {
                        selectedTagIds + tag.id
                    }
                    onUpdateSelectedTagIds(newSelection)
                },
                label = { Text(tag.name) }
            )
        }
    }
}
