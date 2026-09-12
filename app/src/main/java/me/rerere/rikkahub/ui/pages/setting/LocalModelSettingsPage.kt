package me.rerere.rikkahub.ui.pages.setting

import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.ExtensionOff
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ViewModule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.localai.LocalModelRecord
import me.rerere.rikkahub.data.localai.LocalModelRepository
import me.rerere.rikkahub.data.localai.LocalModelState
import me.rerere.rikkahub.data.localai.LocalRuntimeManager
import me.rerere.rikkahub.data.localai.LocalRuntimeState
import me.rerere.rikkahub.data.localai.RuntimePackageInstaller
import me.rerere.rikkahub.data.model.Tag as DataTag
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.ItemPosition
import me.rerere.rikkahub.ui.components.ui.ModelIcon
import me.rerere.rikkahub.ui.components.ui.PhysicsSwipeToDelete
import me.rerere.rikkahub.ui.components.ui.ProviderIcon
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.components.ui.TagsInput
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.pages.setting.components.ProviderConfigure
import me.rerere.rikkahub.ui.theme.AppShapes
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.io.File
import kotlin.uuid.Uuid

private data class InstalledRuntimeInfo(
    val version: String,
    val abi: String,
    val librarySizeBytes: Long,
)

private fun getInstalledRuntimeInfo(context: Context): InstalledRuntimeInfo? {
    val runtimeDir = File(context.noBackupFilesDir, "local-ai/runtime")
    val installedFile = File(runtimeDir, "installed.json")
    if (!installedFile.isFile) return null
    return runCatching {
        val text = installedFile.readText()
        val version = Regex("\"version\"\\s*:\\s*\"([A-Za-z0-9._-]+)\"").find(text)?.groupValues?.getOrNull(1) ?: return null
        val abi = Regex("\"abi\"\\s*:\\s*\"([A-Za-z0-9._-]+)\"").find(text)?.groupValues?.getOrNull(1) ?: return null
        val libFile = File(runtimeDir, "$version/lib/libflit_local_llama.so")
        val size = if (libFile.isFile) libFile.length() else 0L
        InstalledRuntimeInfo(version = version, abi = abi, librarySizeBytes = size)
    }.getOrNull()
}

private fun formatFileSize(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}

private fun LocalModelRecord.toModel(): Model = Model(
    id = Uuid.parse(entity.modelId),
    modelId = entity.modelId,
    displayName = entity.displayName,
    abilities = if (entity.supportsTools) listOf(ModelAbility.TOOL) else emptyList(),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalModelSettingsPage(
    provider: ProviderSetting.Local? = null,
    onEdit: ((ProviderSetting) -> Unit)? = null,
    providerTags: List<DataTag> = emptyList(),
    onUpdateTags: ((ProviderSetting, List<DataTag>) -> Unit)? = null,
    vm: SettingVM = koinViewModel(),
) {
    val context = LocalContext.current
    val repository = koinInject<LocalModelRepository>()
    val runtime = koinInject<LocalRuntimeManager>()
    val runtimeInstaller = koinInject<RuntimePackageInstaller>()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val haptics = rememberPremiumHaptics()

    val settings by vm.settings.collectAsStateWithLifecycle()
    val currentProvider = provider ?: settings.providers.filterIsInstance<ProviderSetting.Local>().firstOrNull() ?: ProviderSetting.Local()
    val currentOnEdit = onEdit ?: { updated ->
        val newSettings = settings.copy(
            providers = settings.providers.map { if (it.id == updated.id) updated else it }
        )
        vm.updateSettings(newSettings)
    }
    val currentTags = providerTags.ifEmpty { settings.providerTags }
    val currentOnUpdateTags = onUpdateTags ?: { providerWithNewTags, updatedTags ->
        val updatedProviders = settings.providers.map {
            if (it.id == providerWithNewTags.id) providerWithNewTags else it
        }
        val usedTagIds = updatedProviders.flatMap { it.tags }.toSet()
        val cleanedTags = updatedTags.filter { tag -> tag.id in usedTagIds }
        val newSettings = settings.copy(
            providers = updatedProviders,
            providerTags = cleanedTags
        )
        vm.updateSettings(newSettings)
    }

    val localModels by repository.observeModels().collectAsStateWithLifecycle(emptyList())
    val runtimeState by runtime.state.collectAsStateWithLifecycle()
    val runtimeReady = runtimeState is LocalRuntimeState.Ready || runtimeState is LocalRuntimeState.Loaded
    val pagerState = rememberPagerState { 2 }

    var runtimeInfoVersion by remember { mutableStateOf(0) }
    val runtimeInfo = remember(runtimeState, runtimeInfoVersion) { getInstalledRuntimeInfo(context) }

    val runtimeImportedText = stringResource(R.string.local_models_runtime_imported)
    val memoryReleasedText = stringResource(R.string.local_models_memory_released)

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            repository.importModel(uri).onSuccess {
                toaster.show(it.displayName)
            }.onFailure {
                toaster.show(it.message.orEmpty())
            }
        }
    }

    val runtimeImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { runtimeInstaller.installFromUri(uri) }
                .onSuccess {
                    runtime.refresh()
                    runtimeInfoVersion++
                    toaster.show(runtimeImportedText)
                }
                .onFailure { toaster.show(it.message.orEmpty()) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ProviderIcon(provider = currentProvider, modifier = Modifier.size(24.dp))
                        Text(text = currentProvider.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = { BackButton() },
                actions = {
                    if (runtimeState is LocalRuntimeState.Loaded) {
                        IconButton(
                            onClick = {
                                haptics.perform(HapticPattern.Thud)
                                scope.launch {
                                    runtime.release()
                                    toaster.show(memoryReleasedText)
                                }
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PowerSettingsNew,
                                contentDescription = stringResource(R.string.local_models_release),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            ) {
                Surface(
                    modifier = Modifier.align(Alignment.Center),
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        // Configuration tab
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .then(
                                    if (pagerState.currentPage == 0)
                                        Modifier.background(MaterialTheme.colorScheme.primaryContainer)
                                    else Modifier.clickable {
                                        haptics.perform(HapticPattern.Tick)
                                        scope.launch { pagerState.animateScrollToPage(0) }
                                    }
                                )
                                .padding(12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Settings,
                                contentDescription = stringResource(R.string.setting_provider_page_configuration),
                                tint = if (pagerState.currentPage == 0)
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp),
                            )
                        }

                        // Models tab
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .then(
                                    if (pagerState.currentPage == 1)
                                        Modifier.background(MaterialTheme.colorScheme.primaryContainer)
                                    else Modifier.clickable {
                                        haptics.perform(HapticPattern.Tick)
                                        scope.launch { pagerState.animateScrollToPage(1) }
                                    }
                                )
                                .padding(12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ViewModule,
                                contentDescription = stringResource(R.string.setting_provider_page_models),
                                tint = if (pagerState.currentPage == 1)
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }
            }
        },
    ) { contentPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(contentPadding),
        ) { page ->
            when (page) {
                0 -> LocalConfigurationPage(
                    provider = currentProvider,
                    runtimeState = runtimeState,
                    runtimeReady = runtimeReady,
                    runtimeInfo = runtimeInfo,
                    providerTags = currentTags,
                    onEdit = currentOnEdit,
                    onUpdateTags = currentOnUpdateTags,
                    onImportRuntime = {
                        haptics.perform(HapticPattern.Pop)
                        runtimeImporter.launch(arrayOf("application/zip", "application/x-zip-compressed"))
                    },
                    onReleaseMemory = {
                        haptics.perform(HapticPattern.Thud)
                        scope.launch {
                            runtime.release()
                            toaster.show(memoryReleasedText)
                        }
                    },
                    contentPadding = contentPadding,
                )

                1 -> LocalModelsPage(
                    records = localModels,
                    currentProvider = currentProvider,
                    onUpdateProvider = currentOnEdit,
                    onDeleteModel = { modelId ->
                        haptics.perform(HapticPattern.Thud)
                        scope.launch { repository.remove(modelId) }
                    },
                    onRenameModel = { modelId, newName ->
                        scope.launch { repository.renameModel(modelId, newName) }
                    },
                    onImportModel = {
                        haptics.perform(HapticPattern.Pop)
                        importer.launch(arrayOf("application/octet-stream", "*/*"))
                    },
                    contentPadding = contentPadding,
                )
            }
        }
    }
}

@Composable
private fun LocalConfigurationPage(
    provider: ProviderSetting.Local,
    runtimeState: LocalRuntimeState,
    runtimeReady: Boolean,
    runtimeInfo: InstalledRuntimeInfo?,
    providerTags: List<DataTag>,
    onEdit: (ProviderSetting) -> Unit,
    onUpdateTags: (ProviderSetting, List<DataTag>) -> Unit,
    onImportRuntime: () -> Unit,
    onReleaseMemory: () -> Unit,
    contentPadding: PaddingValues,
) {
    var internalProvider by remember(provider) { mutableStateOf(provider) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Runtime Support Card
        RuntimeSupportCard(
            runtimeState = runtimeState,
            runtimeReady = runtimeReady,
            runtimeInfo = runtimeInfo,
            onImportRuntime = onImportRuntime,
            onReleaseMemory = onReleaseMemory,
        )

        // Provider Configure Card
        Card(
            shape = AppShapes.CardLarge,
            colors = CardDefaults.cardColors(
                containerColor = if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        ) {
            ProviderConfigure(
                provider = internalProvider,
                modifier = Modifier.padding(16.dp),
                onEdit = {
                    internalProvider = it as ProviderSetting.Local
                    onEdit(it)
                },
            )
        }

        // Tags Section Card
        Card(
            shape = AppShapes.CardLarge,
            colors = CardDefaults.cardColors(
                containerColor = if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FormItem(
                    label = { Text(stringResource(R.string.assistant_page_tags)) },
                ) {
                    TagsInput(
                        value = internalProvider.tags,
                        tags = providerTags,
                        onValueChange = { tagIds, updatedTags ->
                            val updatedProvider = internalProvider.copy(tags = tagIds)
                            internalProvider = updatedProvider
                            onUpdateTags(updatedProvider, updatedTags)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RuntimeSupportCard(
    runtimeState: LocalRuntimeState,
    runtimeReady: Boolean,
    runtimeInfo: InstalledRuntimeInfo?,
    onImportRuntime: () -> Unit,
    onReleaseMemory: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.CardLarge,
        colors = CardDefaults.cardColors(
            containerColor = if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header Row with Icon, Title, Description, and Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Leading Status Icon Container
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                runtimeState is LocalRuntimeState.Loaded -> MaterialTheme.colorScheme.primaryContainer
                                runtimeReady -> MaterialTheme.colorScheme.secondaryContainer
                                runtimeState is LocalRuntimeState.Loading -> MaterialTheme.colorScheme.tertiaryContainer
                                runtimeState is LocalRuntimeState.Failed -> MaterialTheme.colorScheme.errorContainer
                                else -> MaterialTheme.colorScheme.surfaceContainerHighest
                            }
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = when {
                            runtimeState is LocalRuntimeState.Loaded -> Icons.Rounded.RocketLaunch
                            runtimeReady -> Icons.Rounded.Memory
                            runtimeState is LocalRuntimeState.Loading -> Icons.Rounded.HourglassTop
                            runtimeState is LocalRuntimeState.Failed -> Icons.Rounded.ExtensionOff
                            else -> Icons.Rounded.CloudDownload
                        },
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = when {
                            runtimeState is LocalRuntimeState.Loaded -> MaterialTheme.colorScheme.onPrimaryContainer
                            runtimeReady -> MaterialTheme.colorScheme.onSecondaryContainer
                            runtimeState is LocalRuntimeState.Loading -> MaterialTheme.colorScheme.onTertiaryContainer
                            runtimeState is LocalRuntimeState.Failed -> MaterialTheme.colorScheme.onErrorContainer
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }

                // Title & Subtitle
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.local_models_runtime),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = when (runtimeState) {
                            is LocalRuntimeState.Loaded -> stringResource(R.string.local_models_runtime_loaded_desc)
                            is LocalRuntimeState.Loading -> stringResource(R.string.loading)
                            is LocalRuntimeState.Ready -> stringResource(R.string.local_models_runtime_ready_desc)
                            is LocalRuntimeState.Failed -> runtimeState.message
                            is LocalRuntimeState.Missing -> stringResource(R.string.local_models_runtime_missing)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Status Tag Badge
                when (runtimeState) {
                    is LocalRuntimeState.Loaded -> Tag(type = TagType.INFO) {
                        Text(stringResource(R.string.local_models_runtime_loaded))
                    }
                    is LocalRuntimeState.Loading -> Tag(type = TagType.WARNING) {
                        Text(stringResource(R.string.loading))
                    }
                    is LocalRuntimeState.Ready -> Tag(type = TagType.SUCCESS) {
                        Text(stringResource(R.string.local_models_runtime_ready))
                    }
                    is LocalRuntimeState.Failed -> Tag(type = TagType.ERROR) {
                        Text(stringResource(R.string.local_models_runtime_error))
                    }
                    is LocalRuntimeState.Missing -> Tag(type = TagType.WARNING) {
                        Text(stringResource(R.string.local_models_runtime_missing).take(4))
                    }
                }
            }

            // Specs / Details Grid (shown when installed)
            if (runtimeReady && runtimeInfo != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = AppShapes.CardMedium,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = stringResource(R.string.local_models_runtime_version),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "v${runtimeInfo.version}",
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = stringResource(R.string.local_models_runtime_abi),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = runtimeInfo.abi,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = stringResource(R.string.local_models_runtime_format),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "GGUF",
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }

                        if (runtimeInfo.librarySizeBytes > 0L) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = stringResource(R.string.local_models_runtime_size),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = formatFileSize(runtimeInfo.librarySizeBytes),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                    }
                }
            }

            // Action Buttons
            if (!runtimeReady) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onImportRuntime,
                        modifier = Modifier.fillMaxWidth(),
                        shape = AppShapes.ButtonPill,
                    ) {
                        Icon(Icons.Rounded.FileDownload, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.local_models_import_runtime))
                    }
                    Text(
                        text = stringResource(R.string.local_models_runtime_release_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onImportRuntime,
                        modifier = Modifier.weight(1f),
                        shape = AppShapes.ButtonPill,
                    ) {
                        Text(stringResource(R.string.local_models_runtime_reinstall))
                    }

                    if (runtimeState is LocalRuntimeState.Loaded) {
                        Button(
                            onClick = onReleaseMemory,
                            shape = AppShapes.ButtonPill,
                        ) {
                            Icon(Icons.Rounded.PowerSettingsNew, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.local_models_release))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalModelsPage(
    records: List<LocalModelRecord>,
    currentProvider: ProviderSetting.Local,
    onUpdateProvider: (ProviderSetting) -> Unit,
    onDeleteModel: (Uuid) -> Unit,
    onRenameModel: (Uuid, String) -> Unit,
    onImportModel: () -> Unit,
    contentPadding: PaddingValues,
) {
    val haptics = rememberPremiumHaptics()
    val lazyListState = rememberLazyListState()

    val sortedRecords = remember(records, currentProvider.models) {
        if (currentProvider.models.isEmpty()) {
            records
        } else {
            val orderMap = currentProvider.models.mapIndexed { index, model -> model.id.toString() to index }.toMap()
            records.sortedBy { orderMap[it.entity.modelId] ?: Int.MAX_VALUE }
        }
    }

    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        haptics.perform(HapticPattern.Tick)
        val updatedModels = currentProvider.models.toMutableList().apply {
            if (from.index in indices && to.index in indices) {
                add(to.index, removeAt(from.index))
            }
        }
        onUpdateProvider(currentProvider.copy(models = updatedModels))
    }

    var editingRecord by remember { mutableStateOf<LocalModelRecord?>(null) }

    editingRecord?.let { record ->
        EditLocalModelDialog(
            record = record,
            onDismiss = { editingRecord = null },
            onConfirm = { newName ->
                onRenameModel(Uuid.parse(record.entity.modelId), newName)
                editingRecord = null
            },
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp) + PaddingValues(bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(sortedRecords, key = { _, item -> item.entity.modelId }) { index, item ->
                val position = when {
                    sortedRecords.size == 1 -> ItemPosition.ONLY
                    index == 0 -> ItemPosition.FIRST
                    index == sortedRecords.lastIndex -> ItemPosition.LAST
                    else -> ItemPosition.MIDDLE
                }

                ReorderableItem(
                    state = reorderableState,
                    key = item.entity.modelId,
                ) { isDragging ->
                    PhysicsSwipeToDelete(
                        position = position,
                        deleteEnabled = true,
                        onDelete = { onDeleteModel(Uuid.parse(item.entity.modelId)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                scaleX = if (isDragging) 0.95f else 1f
                                scaleY = if (isDragging) 0.95f else 1f
                            },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    color = if (LocalDarkMode.current)
                                        MaterialTheme.colorScheme.surfaceContainerLow
                                    else
                                        MaterialTheme.colorScheme.surfaceContainerHigh,
                                )
                                .clickable { editingRecord = item }
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ModelIcon(
                                model = item.toModel(),
                                provider = currentProvider,
                                modifier = Modifier.size(36.dp),
                            )

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = item.entity.displayName,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )

                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Tag(type = TagType.INFO) {
                                        Text(item.format.extension.uppercase())
                                    }
                                    if (item.entity.sizeBytes > 0L) {
                                        Tag(type = TagType.DEFAULT) {
                                            Text(formatFileSize(item.entity.sizeBytes))
                                        }
                                    }
                                    Tag(
                                        type = if (item.state == LocalModelState.READY) TagType.SUCCESS else TagType.WARNING,
                                    ) {
                                        Text(
                                            if (item.state == LocalModelState.READY)
                                                stringResource(R.string.local_models_ready)
                                            else
                                                item.state.name
                                        )
                                    }
                                }
                            }

                            IconButton(
                                onClick = {},
                                modifier = Modifier.longPressDraggableHandle(
                                    onDragStarted = { haptics.perform(HapticPattern.Pop) },
                                    onDragStopped = { haptics.perform(HapticPattern.Thud) },
                                ),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DragIndicator,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // Empty State
            if (sortedRecords.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillParentMaxHeight(0.7f)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.local_models_no_models),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.local_models_no_models_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp),
                        )
                    }
                }
            }
        }

        // Bottom Gradient Overlay
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(120.dp)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            MaterialTheme.colorScheme.background,
                        ),
                    ),
                ),
        )

        // Floating Action Button to Import Model
        ExtendedFloatingActionButton(
            onClick = onImportModel,
            shape = AppShapes.ButtonPill,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .offset(y = (-80).dp),
            icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
            text = { Text(stringResource(R.string.local_models_import)) },
        )
    }
}

@Composable
private fun EditLocalModelDialog(
    record: LocalModelRecord,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(record) { mutableStateOf(record.entity.displayName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.local_models_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.local_models_model_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank()) {
                        onConfirm(name.trim())
                    }
                },
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
