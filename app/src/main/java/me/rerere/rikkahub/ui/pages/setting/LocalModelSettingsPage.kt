package me.rerere.rikkahub.ui.pages.setting

import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.ExtensionOff
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ViewModule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingToolbarDefaults.ScreenOffset
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.localai.LocalModelCatalogEntry
import me.rerere.rikkahub.data.localai.LocalModelCatalogRepository
import me.rerere.rikkahub.data.localai.LocalModelDownloadManager
import me.rerere.rikkahub.data.localai.LocalModelDownloadState
import me.rerere.rikkahub.data.localai.LocalModelFormat
import me.rerere.rikkahub.data.localai.LocalModelRecord
import me.rerere.rikkahub.data.localai.LocalModelRepository
import me.rerere.rikkahub.data.localai.LocalModelState
import me.rerere.rikkahub.data.localai.LocalRuntimeManager
import me.rerere.rikkahub.data.localai.LocalRuntimePackage
import me.rerere.rikkahub.data.localai.LocalRuntimeState
import me.rerere.rikkahub.data.localai.RuntimeDownloadManager
import me.rerere.rikkahub.data.localai.RuntimeDownloadState
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

private suspend fun getInstalledRuntimeInfo(
    context: Context,
    runtimePackage: LocalRuntimePackage,
): InstalledRuntimeInfo? = withContext(Dispatchers.IO) {
    runCatching {
        val installedDirectory = runtimePackage.installedDirectory(context) ?: return@runCatching null
        val libraryFile = runtimePackage.libraryFile(context)
        if (!installedDirectory.isDirectory && libraryFile?.isFile != true) return@runCatching null

        // GGUF packages carry their ABI in installed.json. LiteRT-LM packages use the current
        // device ABI and expose their version through the activated directory name.
        val marker = File(runtimePackage.root(context), "installed.json")
        val markerText = marker.takeIf(File::isFile)?.readText().orEmpty()
        val version = Regex("\"version\"\\s*:\\s*\"([A-Za-z0-9._-]+)\"")
            .find(markerText)?.groupValues?.getOrNull(1)
            ?: installedDirectory.name.takeIf { it.isNotBlank() }
            ?: return@runCatching null
        val abi = Regex("\"abi\"\\s*:\\s*\"([A-Za-z0-9._-]+)\"")
            .find(markerText)?.groupValues?.getOrNull(1)
            ?: Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        InstalledRuntimeInfo(
            version = version,
            abi = abi,
            librarySizeBytes = libraryFile?.takeIf(File::isFile)?.length() ?: 0L,
        )
    }.getOrNull()
}

private fun LocalRuntimePackage.formatLabel(): String = when (this) {
    LocalRuntimePackage.GGUF -> "GGUF"
    LocalRuntimePackage.LITERT_LM -> "LiteRT-LM"
}

private fun LocalModelFormat.toRuntimePackage(): LocalRuntimePackage = when (this) {
    LocalModelFormat.GGUF -> LocalRuntimePackage.GGUF
    LocalModelFormat.LITERT_LM -> LocalRuntimePackage.LITERT_LM
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
    val runtimeDownload = koinInject<RuntimeDownloadManager>()
    val catalogRepository = koinInject<LocalModelCatalogRepository>()
    val modelDownload = koinInject<LocalModelDownloadManager>()
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
    val partialDownloadBytes by repository.observePartialDownloadBytes().collectAsStateWithLifecycle(emptyMap())
    val runtimeState by runtime.state.collectAsStateWithLifecycle()
    val installedRuntimePackages by runtime.installedPackages.collectAsStateWithLifecycle()
    val ggufDownloadState by runtimeDownload
        .observe(LocalRuntimePackage.GGUF)
        .collectAsStateWithLifecycle(RuntimeDownloadState.Idle)
    val liteRtDownloadState by runtimeDownload
        .observe(LocalRuntimePackage.LITERT_LM)
        .collectAsStateWithLifecycle(RuntimeDownloadState.Idle)
    val modelDownloadStates by modelDownload.observe().collectAsStateWithLifecycle(emptyMap())
    val pagerState = rememberPagerState { 2 }

    var recommendedModels by remember { mutableStateOf<List<LocalModelCatalogEntry>>(emptyList()) }
    var catalogLoading by remember { mutableStateOf(true) }
    var catalogError by remember { mutableStateOf<String?>(null) }
    var runtimeInfos by remember {
        mutableStateOf<Map<LocalRuntimePackage, InstalledRuntimeInfo?>>(emptyMap())
    }

    // Runtime metadata can involve marker and library file reads. Keep them off the Compose
    // thread and refresh when the manager's installed package set changes.
    LaunchedEffect(installedRuntimePackages) {
        val refreshedInfos = mutableMapOf<LocalRuntimePackage, InstalledRuntimeInfo?>()
        for (runtimePackage in LocalRuntimePackage.entries) {
            refreshedInfos[runtimePackage] = getInstalledRuntimeInfo(context, runtimePackage)
        }
        runtimeInfos = refreshedInfos
    }

    LaunchedEffect(Unit) {
        runtime.refresh()
    }

    val activeRuntimePackage = remember(runtimeState, localModels) {
        val modelId = when (val state = runtimeState) {
            is LocalRuntimeState.Loading -> state.modelId
            is LocalRuntimeState.Loaded -> state.modelId
            else -> null
        }
        modelId?.let { id ->
            localModels.firstOrNull { it.entity.modelId == id.toString() }?.format?.toRuntimePackage()
        }
    }

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

    suspend fun refreshCatalog() {
        catalogLoading = true
        catalogError = null
        runCatching { catalogRepository.fetch() }
            .onSuccess { recommendedModels = it.models }
            .onFailure { catalogError = it.message ?: "Unable to load recommended models" }
        catalogLoading = false
    }

    LaunchedEffect(Unit) { refreshCatalog() }

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
                    installedRuntimePackages = installedRuntimePackages,
                    runtimeInfos = runtimeInfos,
                    ggufDownloadState = ggufDownloadState,
                    liteRtDownloadState = liteRtDownloadState,
                    activeRuntimePackage = activeRuntimePackage,
                    providerTags = currentTags,
                    onEdit = currentOnEdit,
                    onUpdateTags = currentOnUpdateTags,
                    onDownloadRuntime = { runtimePackage ->
                        haptics.perform(HapticPattern.Pop)
                        runtimeDownload.download(runtimePackage)
                    },
                    onPauseRuntimeDownload = { runtimePackage ->
                        haptics.perform(HapticPattern.Tick)
                        scope.launch { runtimeDownload.pause(runtimePackage) }
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
                    partialDownloadBytes = partialDownloadBytes,
                    recommendations = recommendedModels,
                    downloadStates = modelDownloadStates,
                    catalogLoading = catalogLoading,
                    catalogError = catalogError,
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
                    onRefreshCatalog = { scope.launch { refreshCatalog() } },
                    onDownloadRecommended = { entry ->
                        haptics.perform(HapticPattern.Pop)
                        scope.launch {
                            modelDownload.download(entry).onFailure { toaster.show(it.message.orEmpty()) }
                        }
                    },
                    onPauseRecommended = { entry ->
                        haptics.perform(HapticPattern.Tick)
                        scope.launch {
                            modelDownload.pause(entry).onFailure { toaster.show(it.message.orEmpty()) }
                        }
                    },
                    onCancelRecommended = { entry ->
                        haptics.perform(HapticPattern.Thud)
                        scope.launch {
                            modelDownload.cancel(entry).onFailure { toaster.show(it.message.orEmpty()) }
                        }
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
    installedRuntimePackages: Set<LocalRuntimePackage>,
    runtimeInfos: Map<LocalRuntimePackage, InstalledRuntimeInfo?>,
    ggufDownloadState: RuntimeDownloadState,
    liteRtDownloadState: RuntimeDownloadState,
    activeRuntimePackage: LocalRuntimePackage?,
    providerTags: List<DataTag>,
    onEdit: (ProviderSetting) -> Unit,
    onUpdateTags: (ProviderSetting, List<DataTag>) -> Unit,
    onDownloadRuntime: (LocalRuntimePackage) -> Unit,
    onPauseRuntimeDownload: (LocalRuntimePackage) -> Unit,
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

        // Keep the two runtime packages independent: each package has its own install marker,
        // download worker and status. The loaded model state is shared by the runtime manager,
        // so only the package matching the loaded model shows the active state.
        RuntimeSupportCard(
            runtimePackage = LocalRuntimePackage.GGUF,
            runtimeState = runtimeState,
            installed = LocalRuntimePackage.GGUF in installedRuntimePackages,
            runtimeDownloadState = ggufDownloadState,
            runtimeInfo = runtimeInfos[LocalRuntimePackage.GGUF],
            activeRuntimePackage = activeRuntimePackage,
            onDownloadRuntime = onDownloadRuntime,
            onPauseRuntimeDownload = onPauseRuntimeDownload,
            onReleaseMemory = onReleaseMemory,
        )
        RuntimeSupportCard(
            runtimePackage = LocalRuntimePackage.LITERT_LM,
            runtimeState = runtimeState,
            installed = LocalRuntimePackage.LITERT_LM in installedRuntimePackages,
            runtimeDownloadState = liteRtDownloadState,
            runtimeInfo = runtimeInfos[LocalRuntimePackage.LITERT_LM],
            activeRuntimePackage = activeRuntimePackage,
            onDownloadRuntime = onDownloadRuntime,
            onPauseRuntimeDownload = onPauseRuntimeDownload,
            onReleaseMemory = onReleaseMemory,
        )

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
    runtimePackage: LocalRuntimePackage,
    runtimeState: LocalRuntimeState,
    installed: Boolean,
    runtimeDownloadState: RuntimeDownloadState,
    runtimeInfo: InstalledRuntimeInfo?,
    activeRuntimePackage: LocalRuntimePackage?,
    onDownloadRuntime: (LocalRuntimePackage) -> Unit,
    onPauseRuntimeDownload: (LocalRuntimePackage) -> Unit,
    onReleaseMemory: () -> Unit,
) {
    val packageState = when {
        activeRuntimePackage == runtimePackage && runtimeState is LocalRuntimeState.Loading -> runtimeState
        activeRuntimePackage == runtimePackage && runtimeState is LocalRuntimeState.Loaded -> runtimeState
        runtimeState is LocalRuntimeState.Failed &&
            runtimeState.format?.toRuntimePackage() == runtimePackage -> runtimeState
        installed -> LocalRuntimeState.Ready
        else -> LocalRuntimeState.Missing
    }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val buttonScale by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "runtime_button_scale",
    )

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
                                packageState is LocalRuntimeState.Loaded -> MaterialTheme.colorScheme.primaryContainer
                                packageState is LocalRuntimeState.Ready -> MaterialTheme.colorScheme.secondaryContainer
                                packageState is LocalRuntimeState.Loading -> MaterialTheme.colorScheme.tertiaryContainer
                                packageState is LocalRuntimeState.Failed -> MaterialTheme.colorScheme.errorContainer
                                else -> MaterialTheme.colorScheme.surfaceContainerHighest
                            }
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = when {
                            packageState is LocalRuntimeState.Loaded -> Icons.Rounded.RocketLaunch
                            packageState is LocalRuntimeState.Ready -> Icons.Rounded.Memory
                            packageState is LocalRuntimeState.Loading -> Icons.Rounded.HourglassTop
                            packageState is LocalRuntimeState.Failed -> Icons.Rounded.ExtensionOff
                            else -> Icons.Rounded.CloudDownload
                        },
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = when {
                            packageState is LocalRuntimeState.Loaded -> MaterialTheme.colorScheme.onPrimaryContainer
                            packageState is LocalRuntimeState.Ready -> MaterialTheme.colorScheme.onSecondaryContainer
                            packageState is LocalRuntimeState.Loading -> MaterialTheme.colorScheme.onTertiaryContainer
                            packageState is LocalRuntimeState.Failed -> MaterialTheme.colorScheme.onErrorContainer
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }

                // Title & Subtitle
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = runtimePackage.displayName,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = when (packageState) {
                            is LocalRuntimeState.Loaded -> stringResource(R.string.local_models_runtime_loaded_desc)
                            is LocalRuntimeState.Loading -> stringResource(R.string.loading)
                            is LocalRuntimeState.Ready -> stringResource(R.string.local_models_runtime_ready_desc)
                            is LocalRuntimeState.Failed -> packageState.message
                            is LocalRuntimeState.Missing -> stringResource(
                                R.string.local_models_runtime_required,
                                runtimePackage.displayName,
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Status Tag Badge
                when (packageState) {
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
                        Text(stringResource(R.string.local_models_runtime_missing_status))
                    }
                }
            }

            if (runtimePackage == LocalRuntimePackage.LITERT_LM) {
                Text(
                    text = stringResource(R.string.local_models_runtime_litert_cpu_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Specs / Details Grid (shown when installed)
            if (installed && runtimeInfo != null) {
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
                                text = runtimePackage.formatLabel(),
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
            if (!installed) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = if (runtimeDownloadState is RuntimeDownloadState.Downloading ||
                            runtimeDownloadState is RuntimeDownloadState.Installing) {
                            { onPauseRuntimeDownload(runtimePackage) }
                        } else {
                            { onDownloadRuntime(runtimePackage) }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                scaleX = buttonScale
                                scaleY = buttonScale
                            },
                        shape = AppShapes.ButtonPill,
                        interactionSource = interactionSource,
                    ) {
                        Icon(Icons.Rounded.CloudDownload, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when (runtimeDownloadState) {
                                is RuntimeDownloadState.Downloading -> stringResource(
                                    R.string.local_models_runtime_downloading,
                                    formatFileSize(runtimeDownloadState.downloadedBytes),
                                    runtimeDownloadState.totalBytes?.let(::formatFileSize) ?: "…",
                                )
                                RuntimeDownloadState.Installing -> stringResource(R.string.local_models_runtime_installing)
                                RuntimeDownloadState.Paused -> stringResource(R.string.local_models_runtime_resume)
                                is RuntimeDownloadState.Failed -> stringResource(R.string.local_models_runtime_retry)
                                else -> stringResource(R.string.local_models_download_runtime)
                            },
                        )
                    }
                    if (runtimeDownloadState is RuntimeDownloadState.Failed) {
                        Text(
                            text = stringResource(
                                R.string.local_models_runtime_download_failed,
                                runtimeDownloadState.message,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
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
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (packageState is LocalRuntimeState.Loaded) {
                        Button(
                            onClick = onReleaseMemory,
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    scaleX = buttonScale
                                    scaleY = buttonScale
                                },
                            shape = AppShapes.ButtonPill,
                            interactionSource = interactionSource,
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
    partialDownloadBytes: Map<String, Long>,
    recommendations: List<LocalModelCatalogEntry>,
    downloadStates: Map<String, LocalModelDownloadState>,
    catalogLoading: Boolean,
    catalogError: String?,
    currentProvider: ProviderSetting.Local,
    onUpdateProvider: (ProviderSetting) -> Unit,
    onDeleteModel: (Uuid) -> Unit,
    onRenameModel: (Uuid, String) -> Unit,
    onImportModel: () -> Unit,
    onRefreshCatalog: () -> Unit,
    onDownloadRecommended: (LocalModelCatalogEntry) -> Unit,
    onPauseRecommended: (LocalModelCatalogEntry) -> Unit,
    onCancelRecommended: (LocalModelCatalogEntry) -> Unit,
    contentPadding: PaddingValues,
) {
    val haptics = rememberPremiumHaptics()
    val lazyListState = rememberLazyListState()

    val sortedRecords = remember(records, currentProvider.models) {
        val readyRecords = records.filter { it.state == LocalModelState.READY }
        if (currentProvider.models.isEmpty()) {
            readyRecords
        } else {
            val orderMap = currentProvider.models.mapIndexed { index, model -> model.id.toString() to index }.toMap()
            readyRecords.sortedBy { orderMap[it.entity.modelId] ?: Int.MAX_VALUE }
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
    var showRecommendedModels by remember { mutableStateOf(false) }

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

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .offset(y = -ScreenOffset),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FloatingActionButton(
                onClick = { showRecommendedModels = true },
                shape = AppShapes.CardLarge,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Icon(Icons.Rounded.ViewModule, contentDescription = stringResource(R.string.local_models_recommended))
            }
            FloatingActionButton(onClick = onImportModel, shape = AppShapes.ButtonPill) {
                Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.local_models_import))
            }
        }
    }

    if (showRecommendedModels) {
        RecommendedModelsSheet(
            recommendations = recommendations,
            records = records,
            partialDownloadBytes = partialDownloadBytes,
            downloadStates = downloadStates,
            catalogLoading = catalogLoading,
            catalogError = catalogError,
            provider = currentProvider,
            onDismiss = { showRecommendedModels = false },
            onRefresh = onRefreshCatalog,
            onDownload = onDownloadRecommended,
            onPause = onPauseRecommended,
            onCancel = onCancelRecommended,
        )
    }
}

@Composable
private fun RecommendedModelsSheet(
    recommendations: List<LocalModelCatalogEntry>,
    records: List<LocalModelRecord>,
    partialDownloadBytes: Map<String, Long>,
    downloadStates: Map<String, LocalModelDownloadState>,
    catalogLoading: Boolean,
    catalogError: String?,
    provider: ProviderSetting.Local,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onDownload: (LocalModelCatalogEntry) -> Unit,
    onPause: (LocalModelCatalogEntry) -> Unit,
    onCancel: (LocalModelCatalogEntry) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var filterText by remember { mutableStateOf("") }
    val keywords = filterText.split(' ').filter(String::isNotBlank)
    val filtered = remember(recommendations, keywords) {
        recommendations.filter { entry ->
            keywords.all { keyword -> entry.displayName.contains(keyword, ignoreCase = true) || entry.parameterSize.contains(keyword, ignoreCase = true) }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(500.dp)
                .padding(8.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.local_models_recommended), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onRefresh, enabled = !catalogLoading) {
                    Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.local_models_refresh_recommended))
                }
            }
            if (catalogError != null) {
                Text(catalogError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                if (catalogLoading && recommendations.isEmpty()) {
                    item { Text(stringResource(R.string.loading), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                itemsIndexed(filtered, key = { _, entry -> entry.id }) { _, entry ->
                    RecommendedModelPickerItem(
                        entry = entry,
                        record = records.firstOrNull { it.entity.catalogId == entry.id },
                        partialBytes = partialDownloadBytes[entry.id] ?: 0L,
                        downloadState = downloadStates[entry.id],
                        provider = provider,
                        onDownload = { onDownload(entry) },
                        onPause = { onPause(entry) },
                        onCancel = { onCancel(entry) },
                    )
                }
            }
            OutlinedTextField(
                value = filterText,
                onValueChange = { filterText = it },
                label = { Text(stringResource(R.string.setting_provider_page_filter_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
    }
}

@Composable
private fun RecommendedModelPickerItem(
    entry: LocalModelCatalogEntry,
    record: LocalModelRecord?,
    partialBytes: Long,
    downloadState: LocalModelDownloadState?,
    provider: ProviderSetting.Local,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onCancel: () -> Unit,
) {
    val installed = record?.state == LocalModelState.READY
    val activeDownload = downloadState as? LocalModelDownloadState.Downloading
    val paused = record?.state == LocalModelState.PAUSED
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.CardLarge,
        colors = CardDefaults.cardColors(
            containerColor = if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModelIcon(
                model = Model(
                    modelId = entry.id,
                    displayName = entry.displayName,
                    abilities = if (entry.supportsTools) listOf(ModelAbility.TOOL) else emptyList(),
                ),
                provider = provider,
                modifier = Modifier.size(32.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Tag(type = TagType.INFO) { Text(entry.format) }
                    Tag(type = TagType.DEFAULT) { Text(entry.parameterSize) }
                    Tag(type = TagType.DEFAULT) { Text(formatFileSize(entry.sizeBytes)) }
                    if (installed) Tag(type = TagType.SUCCESS) { Text(stringResource(R.string.local_models_ready)) }
                    if (downloadState is LocalModelDownloadState.Failed) Tag(type = TagType.ERROR) { Text(stringResource(R.string.local_models_runtime_error)) }
                }
                if (activeDownload != null || paused) {
                    val downloadedBytes = activeDownload?.downloadedBytes ?: partialBytes
                    val totalBytes = activeDownload?.totalBytes ?: entry.sizeBytes
                    Text(
                        text = stringResource(
                            R.string.local_models_model_downloading,
                            formatFileSize(downloadedBytes),
                            formatFileSize(totalBytes),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val fraction = totalBytes.takeIf { it > 0L }
                        ?.let { downloadedBytes.toFloat() / it }
                        ?.coerceIn(0f, 1f)
                    if (fraction == null) {
                        LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearWavyProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            if (activeDownload != null) {
                IconButton(onClick = onPause) {
                    Icon(Icons.Rounded.Pause, contentDescription = stringResource(R.string.local_models_pause))
                }
            } else if (paused) {
                Row {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cancel))
                    }
                    IconButton(onClick = onDownload) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(R.string.local_models_resume))
                    }
                }
            } else {
                IconButton(onClick = onDownload, enabled = !installed) {
                    Icon(
                        imageVector = if (installed) Icons.Rounded.CheckCircle else Icons.Rounded.FileDownload,
                        contentDescription = stringResource(
                            if (installed) R.string.local_models_ready else R.string.local_models_download,
                        ),
                        tint = if (installed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
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
