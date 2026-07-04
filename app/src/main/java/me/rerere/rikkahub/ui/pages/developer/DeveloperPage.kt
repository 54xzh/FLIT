package me.rerere.rikkahub.ui.pages.developer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FontDownload
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.AILogging
import me.rerere.rikkahub.data.repository.ModelCapabilityRepository
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.HapticSwitch
import me.rerere.rikkahub.ui.components.ui.ToastType
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.hooks.useThrottle
import me.rerere.rikkahub.ui.pages.setting.components.SettingGroupInputItem
import me.rerere.rikkahub.ui.pages.setting.components.SettingGroupItem
import me.rerere.rikkahub.ui.theme.AppShapes
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.SoCInfo
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.rikkahub.utils.UpdateDownload
import me.rerere.rikkahub.utils.UpdateSource
import me.rerere.rikkahub.utils.toLocalDateTime
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext

@Composable
fun DeveloperPage(vm: DeveloperVM = koinViewModel()) {
    val pager = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.developer_page_title),
                        maxLines = 1,
                    )
                }
            )
        },
        bottomBar = {
            BottomAppBar {
                NavigationBarItem(
                    selected = pager.currentPage == 0,
                    onClick = { scope.launch { pager.animateScrollToPage(0) } },
                    label = {
                        Text(text = stringResource(R.string.developer_page_tab_debug_tools))
                    },
                    icon = {
                        Icon(Icons.Rounded.BugReport, null)
                    }
                )
                NavigationBarItem(
                    selected = pager.currentPage == 1,
                    onClick = { scope.launch { pager.animateScrollToPage(1) } },
                    label = {
                        Text(text = stringResource(R.string.developer_page_tab_request_logs))
                    },
                    icon = {
                        Icon(Icons.Rounded.History, null)
                    }
                )
            }
        }
    ) { innerPadding ->
        HorizontalPager(
            state = pager,
            contentPadding = innerPadding
        ) { page ->
            when (page) {
                0 -> {
                    DeveloperToolsPage(vm = vm)
                }

                1 -> {
                    LoggingPaging(vm = vm)
                }
            }
        }
    }
}

@OptIn(ExperimentalTime::class)
@Composable
private fun DeveloperToolsPage(vm: DeveloperVM) {
    val navController = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val ipv6DebugState by vm.ipv6DebugState.collectAsStateWithLifecycle()
    val updateState by vm.updateState.collectAsStateWithLifecycle()
    val selectedSource by vm.selectedSource.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val haptics = rememberPremiumHaptics()
    val scope = rememberCoroutineScope()
    val modelCapabilityRepository = koinInject<ModelCapabilityRepository>()
    var showUpdateDetail by remember { mutableStateOf(false) }
    var isRefreshingModelCapabilities by remember { mutableStateOf(false) }
    val updateInfo = (updateState as? UiState.Success)?.data

    LaunchedEffect(updateState) {
        if (updateState is UiState.Success) {
            val info = (updateState as UiState.Success).data
            if (info != null && info.downloads.isNotEmpty()) {
                showUpdateDetail = true
            }
        }
    }

    val downloadStartedText = stringResource(R.string.update_card_download_started)
    val updateChecker = koinInject<UpdateChecker>()
    val downloadHandler = useThrottle<UpdateDownload>(500) { item ->
        updateChecker.downloadUpdate(context, item)
        showUpdateDetail = false
        toaster.show(downloadStartedText, type = ToastType.Info)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
        item {
            SettingGroupItem(
                title = stringResource(R.string.developer_option_markdown_font_debug_title),
                subtitle = stringResource(R.string.developer_option_markdown_font_debug_desc),
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.FontDownload,
                        contentDescription = null,
                    )
                },
                trailing = {
                    HapticSwitch(
                        checked = settings.showMarkdownFontDebugInfo,
                        onCheckedChange = { enabled ->
                            vm.updateSettings { current ->
                                current.copy(showMarkdownFontDebugInfo = enabled)
                            }
                        }
                    )
                },
                onClick = null
            )
        }

        item {
            // SoC 代号诊断：展示本机能读到的 SoC 信息，便于后续性能白名单判定采数。
            val socSubtitle = buildString {
                appendLine("Manufacturer: ${SoCInfo.manufacturer}")
                appendLine("Model (codename): ${SoCInfo.model}")
                appendLine("Board platform: ${SoCInfo.boardPlatform}")
                appendLine("Chip name: ${SoCInfo.chipName}")
                append("Hardware: ${SoCInfo.hardware}")
            }
            SettingGroupItem(
                title = stringResource(R.string.developer_option_soc_info_title),
                subtitle = socSubtitle,
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.Memory,
                        contentDescription = null,
                    )
                },
                onClick = null
            )
        }

        item {
            // SoC 性能档位判定：展示白名单规则对本机的判定结果，便于验证默认模糊开关策略。
            val tierText = when (SoCInfo.tier) {
                SoCInfo.Tier.SUFFICIENT ->
                    stringResource(R.string.developer_option_soc_tier_sufficient)
                SoCInfo.Tier.INSUFFICIENT_DEFAULT_OFF ->
                    stringResource(R.string.developer_option_soc_tier_insufficient)
                SoCInfo.Tier.UNKNOWN ->
                    stringResource(R.string.developer_option_soc_tier_unknown)
            }
            SettingGroupItem(
                title = stringResource(R.string.developer_option_soc_tier_title),
                subtitle = tierText,
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.Speed,
                        contentDescription = null,
                    )
                },
                onClick = null
            )
        }

        item {
            SettingGroupItem(
                title = stringResource(R.string.developer_option_auto_continue_on_truncation_title),
                subtitle = stringResource(R.string.developer_option_auto_continue_on_truncation_desc),
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.History,
                        contentDescription = null,
                    )
                },
                trailing = {
                    HapticSwitch(
                        checked = settings.autoContinueOnTruncation,
                        onCheckedChange = { enabled ->
                            vm.updateSettings { current ->
                                current.copy(autoContinueOnTruncation = enabled)
                            }
                        }
                    )
                },
                onClick = null
            )
        }

        item {
            SettingGroupInputItem(
                title = stringResource(R.string.developer_option_ipv6_probe_title),
                subtitle = stringResource(R.string.developer_option_ipv6_probe_desc),
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.Public,
                        contentDescription = null,
                    )
                },
            ) {
                FilledTonalButton(
                    onClick = { vm.detectIpv6() },
                    enabled = !ipv6DebugState.isLoading,
                ) {
                    Text(
                        text = stringResource(
                            if (ipv6DebugState.isLoading) {
                                R.string.developer_option_ipv6_probe_loading
                            } else {
                                R.string.developer_option_ipv6_probe_action
                            }
                        )
                    )
                }

                if (ipv6DebugState.hasChecked) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SettingGroupItem(
                            title = stringResource(R.string.developer_option_ipv6_probe_http_title),
                            subtitle = ipv6DebugState.httpIpv6
                                ?: stringResource(R.string.developer_option_ipv6_probe_not_found),
                            onClick = null,
                        )
                        SettingGroupItem(
                            title = stringResource(R.string.developer_option_ipv6_probe_system_title),
                            subtitle = ipv6DebugState.systemIpv6
                                ?: stringResource(R.string.developer_option_ipv6_probe_not_found),
                            onClick = null,
                        )
                    }
                }
            }
        }

        item {
            SettingGroupInputItem(
                title = stringResource(R.string.developer_option_manual_update_title),
                subtitle = stringResource(R.string.developer_option_manual_update_desc),
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.SystemUpdate,
                        contentDescription = null,
                    )
                },
            ) {
                val sources = UpdateSource.entries
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    sources.forEachIndexed { index, source ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(index, sources.size),
                            selected = selectedSource == source,
                            onClick = { vm.selectSource(source) },
                            label = {
                                Text(
                                    text = stringResource(
                                        when (source) {
                                            UpdateSource.GITHUB -> R.string.developer_option_manual_update_source_github
                                            UpdateSource.CLOUDFLARE -> R.string.developer_option_manual_update_source_cloudflare
                                        }
                                    )
                                )
                            }
                        )
                    }
                }

                val isLoading = updateState is UiState.Loading
                FilledTonalButton(
                    onClick = {
                        haptics.perform(HapticPattern.Pop)
                        vm.checkForUpdates(selectedSource)
                    },
                    enabled = !isLoading,
                ) {
                    Text(
                        text = stringResource(
                            if (isLoading) R.string.developer_option_manual_update_loading
                            else R.string.developer_option_manual_update_action
                        )
                    )
                }

                when (val state = updateState) {
                    is UiState.Error -> {
                        Text(
                            text = stringResource(
                                R.string.developer_option_manual_update_error_prefix,
                                state.error.message ?: "Unknown"
                            ),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    is UiState.Success -> {
                        val info = state.data
                        if (info == null || info.downloads.isEmpty()) {
                            Text(
                                text = stringResource(R.string.developer_option_manual_update_no_result),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }

        item {
            SettingGroupInputItem(
                title = stringResource(R.string.developer_option_model_capability_refresh_title),
                subtitle = stringResource(R.string.developer_option_model_capability_refresh_desc),
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                    )
                },
            ) {
                FilledTonalButton(
                    onClick = {
                        haptics.perform(HapticPattern.Pop)
                        scope.launch {
                            isRefreshingModelCapabilities = true
                            try {
                                modelCapabilityRepository.refreshOpenRouterIfStale(force = true)
                                    .onSuccess {
                                        val refreshedProviders = settings.providers.map { provider ->
                                            modelCapabilityRepository.applyOpenRouterCapabilitiesToProvider(provider)
                                        }
                                        vm.updateSettings { current ->
                                            current.copy(
                                                providers = current.providers.map { currentProvider ->
                                                    refreshedProviders.firstOrNull { it.id == currentProvider.id }
                                                        ?: currentProvider
                                                }
                                            )
                                        }
                                        toaster.show(
                                            context.getString(R.string.developer_option_model_capability_refresh_success),
                                            type = ToastType.Success,
                                        )
                                    }
                                    .onFailure {
                                        toaster.show(
                                            context.getString(
                                                R.string.developer_option_model_capability_refresh_failed,
                                                it.message ?: "Unknown",
                                            ),
                                            type = ToastType.Error,
                                        )
                                    }
                            } finally {
                                isRefreshingModelCapabilities = false
                            }
                        }
                    },
                    enabled = !isRefreshingModelCapabilities,
                ) {
                    Text(
                        text = stringResource(
                            if (isRefreshingModelCapabilities) {
                                R.string.developer_option_model_capability_refresh_loading
                            } else {
                                R.string.developer_option_model_capability_refresh_action
                            }
                        )
                    )
                }
            }
        }

        item {
            SettingGroupItem(
                title = stringResource(R.string.developer_option_request_logs_title),
                subtitle = stringResource(R.string.setting_request_logs_desc),
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.Description,
                        contentDescription = null,
                    )
                },
                onClick = {
                    navController.navigate(Screen.RequestLogs)
                }
            )
        }

        item {
            Card(shape = AppShapes.CardMedium) {
                Text(
                    text = stringResource(R.string.developer_option_markdown_font_debug_tip),
                    modifier = Modifier.padding(14.dp),
                )
            }
        }
    }

    if (showUpdateDetail && updateInfo != null) {
        ModalBottomSheet(
            onDismissRequest = { showUpdateDetail = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = updateInfo.version,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = Instant.parse(updateInfo.publishedAt).toJavaInstant().toLocalDateTime(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                MarkdownBlock(
                    content = updateInfo.changelog,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodyMedium,
                )
                updateInfo.downloads.fastForEach { downloadItem ->
                    OutlinedCard(
                        onClick = {
                            downloadHandler(downloadItem)
                        },
                    ) {
                        ListItem(
                            headlineContent = {
                                Text(text = downloadItem.name)
                            },
                            supportingContent = {
                                Text(text = downloadItem.size)
                            },
                            leadingContent = {
                                Icon(
                                    Icons.Rounded.Download,
                                    contentDescription = null,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
    }
}

@Composable
fun LoggingPaging(vm: DeveloperVM) {
    val logs by vm.logs.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(logs) { log ->
            when (log) {
                is AILogging.Generation -> {
                    Card {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {

                        }
                    }
                }
            }
        }
    }
}
