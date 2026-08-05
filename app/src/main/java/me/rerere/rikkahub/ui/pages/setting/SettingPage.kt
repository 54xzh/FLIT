package me.rerere.rikkahub.ui.pages.setting

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import me.rerere.rikkahub.ui.components.ui.HapticSwitch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.InvertColors
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.isNotConfigured
import me.rerere.rikkahub.data.repository.StorageManagerRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberColorMode
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.theme.ColorMode
import me.rerere.rikkahub.utils.countChatFiles
import me.rerere.rikkahub.utils.countChatUploadFiles
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.ui.pages.setting.components.SettingsGroup
import me.rerere.rikkahub.ui.pages.setting.components.SettingGroupItem
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun SettingPage(vm: SettingVM = koinViewModel()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val lazyListState = rememberLazyListState()
    val storageRepo: StorageManagerRepository = koinInject()

    LaunchedEffect(Unit) {
        // Best-effort warm-up for StorageManagerPage (heavy IO/DB scan).
        // The repository handles IO dispatching + cache dedupe/TTL.
        runCatching { storageRepo.loadOverview(forceRefresh = false) }
    }
    
    Scaffold(
        topBar = {
            OneUITopAppBar(
                title = stringResource(R.string.settings),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    BackButton()
                }
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = lazyListState,
            contentPadding = innerPadding,
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            if (settings.isNotConfigured()) {
                item {
                    ProviderConfigWarningCard(navController)
                }
            }

            // Update Available Banner
            item {
                UpdateAvailableBanner(
                    checkForUpdates = settings.displaySetting.checkForUpdates,
                    navController = navController
                )
            }

            // General Settings Section
            item {
                SettingsGroup(
                    title = stringResource(R.string.setting_page_general_settings)
                ) {
                    var colorMode by rememberColorMode()
                    SettingGroupItem(
                        title = stringResource(R.string.setting_page_color_mode),
                        icon = { Icon(Icons.Rounded.InvertColors, null, modifier = Modifier.size(20.dp)) },
                        trailing = {
                            Select(
                                options = ColorMode.entries,
                                selectedOption = colorMode,
                                onOptionSelected = {
                                    colorMode = it
                                    navController.navigate(Screen.Setting) {
                                        launchSingleTop = true
                                        popUpTo(Screen.Setting) { inclusive = true }
                                    }
                                },
                                optionToString = {
                                    when (it) {
                                        ColorMode.SYSTEM -> stringResource(R.string.setting_page_color_mode_system)
                                        ColorMode.LIGHT -> stringResource(R.string.setting_page_color_mode_light)
                                        ColorMode.DARK -> stringResource(R.string.setting_page_color_mode_dark)
                                    }
                                },
                                modifier = Modifier.width(130.dp)
                            )
                        }
                    )
                    SettingGroupItem(
                        title = stringResource(R.string.setting_page_display_setting),
                        subtitle = stringResource(R.string.setting_page_display_setting_desc),
                        icon = { Icon(Icons.Rounded.DesktopWindows, null, modifier = Modifier.size(20.dp)) },
                        onClick = { navController.navigate(Screen.SettingDisplay) }
                    )
                    SettingGroupItem(
                        title = stringResource(R.string.setting_page_advanced_settings),
                        subtitle = stringResource(R.string.setting_page_advanced_settings_desc),
                        icon = { Icon(Icons.Rounded.Settings, null, modifier = Modifier.size(20.dp)) },
                        onClick = { navController.navigate(Screen.SettingAdvanced) }
                    )

                    SettingGroupItem(
                        title = stringResource(R.string.setting_page_assistant),
                        subtitle = stringResource(R.string.setting_page_assistant_desc),
                        icon = { Icon(Icons.Rounded.Group, null, modifier = Modifier.size(20.dp)) },
                        onClick = { navController.navigate(Screen.Assistant) }
                    )

                    SettingGroupItem(
                        title = stringResource(R.string.extensions_page_title),
                        subtitle = stringResource(R.string.setting_extensions_subtitle),
                        icon = { Icon(Icons.Rounded.Extension, null, modifier = Modifier.size(20.dp)) },
                        onClick = { navController.navigate(Screen.Extensions) }
                    )
                }
            }

            // Models & Services Section
            item {
                SettingsGroup(
                    title = stringResource(R.string.setting_page_model_and_services)
                ) {
                    SettingGroupItem(
                        title = stringResource(R.string.setting_page_default_model),
                        subtitle = stringResource(R.string.setting_page_default_model_desc),
                        icon = { Icon(Icons.Rounded.AccountTree, null, modifier = Modifier.size(20.dp)) },
                        onClick = { navController.navigate(Screen.SettingModels) }
                    )

                    SettingGroupItem(
                        title = stringResource(R.string.setting_page_providers),
                        subtitle = stringResource(R.string.setting_page_providers_desc),
                        icon = { Icon(Icons.Rounded.Cloud, null, modifier = Modifier.size(20.dp)) },
                        onClick = { navController.navigate(Screen.SettingProvider) }
                    )

                    SettingGroupItem(
                        title = stringResource(R.string.setting_page_search_service),
                        subtitle = stringResource(R.string.setting_page_search_service_desc),
                        icon = { Icon(Icons.Rounded.Public, null, modifier = Modifier.size(20.dp)) },
                        onClick = { navController.navigate(Screen.SettingSearch) }
                    )

                    SettingGroupItem(
                        title = stringResource(R.string.setting_page_tts_service),
                        subtitle = stringResource(R.string.setting_page_tts_service_desc),
                        icon = { Icon(Icons.Rounded.RecordVoiceOver, null, modifier = Modifier.size(20.dp)) },
                        onClick = { navController.navigate(Screen.SettingTTS) }
                    )

                    SettingGroupItem(
                        title = stringResource(R.string.setting_page_mcp),
                        subtitle = stringResource(R.string.setting_page_mcp_desc),
                        icon = { Icon(Icons.Rounded.Code, null, modifier = Modifier.size(20.dp)) },
                        onClick = { navController.navigate(Screen.SettingMcp) }
                    )

                    SettingGroupItem(
                        title = stringResource(R.string.setting_page_web_server),
                        subtitle = stringResource(R.string.setting_page_web_server_desc),
                        icon = { Icon(Icons.Rounded.Language, null, modifier = Modifier.size(20.dp)) },
                        onClick = { navController.navigate(Screen.SettingWeb) }
                    )

                    SettingGroupItem(
                        title = stringResource(R.string.setting_android_integration),
                        subtitle = stringResource(R.string.setting_android_integration_desc),
                        icon = { Icon(Icons.Rounded.PhoneAndroid, null, modifier = Modifier.size(20.dp)) },
                        onClick = { navController.navigate(Screen.SettingAndroidIntegration) }
                    )
                }
            }

            // Data Section
            item {
                SettingsGroup(
                    title = stringResource(R.string.setting_page_data_settings)
                ) {
                    SettingGroupItem(
                        title = stringResource(R.string.setting_page_data_backup),
                        subtitle = stringResource(R.string.setting_page_data_backup_desc),
                        icon = { Icon(Icons.Rounded.CloudUpload, null, modifier = Modifier.size(20.dp)) },
                        onClick = { navController.navigate(Screen.Backup) }
                    )
                    val context = LocalContext.current
                    val storageState by produceState(-1 to 0L) {
                        // 旧 upload 目录与会话上传目录（chat_uploads）合并统计
                        val legacy = context.countChatFiles()
                        val uploads = context.countChatUploadFiles()
                        value = Pair(legacy.first + uploads.first, legacy.second + uploads.second)
                    }
                    SettingGroupItem(
                        title = stringResource(R.string.setting_page_chat_storage),
                        subtitle = if (storageState.first == -1) {
                            stringResource(R.string.calculating)
                        } else {
                            stringResource(
                                R.string.setting_page_chat_storage_desc,
                                storageState.first,
                                storageState.second / 1024 / 1024.0
                            )
                        },
                        icon = { Icon(Icons.Rounded.Storage, null, modifier = Modifier.size(20.dp)) },
                        onClick = { navController.navigate(Screen.StorageManager) }
                    )
                }
            }

            // About Section
            item {
                SettingsGroup(
                    title = stringResource(R.string.setting_page_about)
                ) {
                    SettingGroupItem(
                        title = stringResource(R.string.setting_page_about),
                        subtitle = stringResource(R.string.setting_page_about_desc),
                        icon = { Icon(Icons.Rounded.Info, null, modifier = Modifier.size(20.dp)) },
                        onClick = { navController.navigate(Screen.SettingAbout) }
                    )
                    
                    SettingGroupItem(
                        title = stringResource(R.string.developer_page_tab_request_logs),
                        subtitle = stringResource(R.string.setting_request_logs_desc),
                        icon = { Icon(Icons.Rounded.History, null, modifier = Modifier.size(20.dp)) },
                        onClick = {
                            navController.navigate(Screen.RequestLogs)
                        }
                    )
                }
            }
        }
    }
}


@Composable
private fun ProviderConfigWarningCard(navController: NavHostController) {
    Card(
        modifier = Modifier.padding(8.dp),
        shape = me.rerere.rikkahub.ui.theme.AppShapes.CardMedium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalAlignment = Alignment.End
        ) {
            ListItem(
                headlineContent = {
                    Text(stringResource(R.string.setting_page_config_api_title))
                },
                supportingContent = {
                    Text(stringResource(R.string.setting_page_config_api_desc))
                },
                leadingContent = {
                    Icon(Icons.Rounded.Warning, null)
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent
                )
            )

            TextButton(
                onClick = {
                    navController.navigate(Screen.SettingProvider)
                }
            ) {
                Text(stringResource(R.string.setting_page_config))
            }
        }
    }
}

@Composable
fun SettingItem(
    navController: NavHostController,
    title: @Composable () -> Unit,
    description: @Composable () -> Unit,
    icon: @Composable () -> Unit,
    link: Screen? = null,
    onClick: () -> Unit = {}
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val haptics = rememberPremiumHaptics()
    
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 400f),
        label = "setting_scale"
    )
    val alpha by animateFloatAsState(
        targetValue = if (isPressed) 0.8f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "setting_alpha"
    )
    
    Surface(
        onClick = {
            haptics.perform(HapticPattern.Tick)
            if (link != null) navController.navigate(link)
            onClick()
        },
        interactionSource = interactionSource,
        modifier = Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
            this.alpha = alpha
        }
    ) {
        ListItem(
            headlineContent = {
                title()
            },
            supportingContent = {
                description()
            },
            leadingContent = {
                icon()
            }
        )
    }
}

@Composable
private fun UpdateAvailableBanner(
    checkForUpdates: Boolean,
    navController: NavHostController
) {
    if (!checkForUpdates) return
    
    val updateChecker = org.koin.compose.koinInject<me.rerere.rikkahub.utils.UpdateChecker>()
    // Remember the flow to prevent creating a new one on each recomposition
    val updateFlow = remember(updateChecker) { updateChecker.checkUpdate() }
    val updateState by updateFlow.collectAsStateWithLifecycle(initialValue = me.rerere.rikkahub.utils.UiState.Loading)
    var showUpdateDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    
    when (val state = updateState) {
        is me.rerere.rikkahub.utils.UiState.Success -> {
            val updateInfo = state.data
            val currentVersion = me.rerere.rikkahub.BuildConfig.VERSION_NAME
            val isNewer = me.rerere.rikkahub.utils.Version(updateInfo.version) > me.rerere.rikkahub.utils.Version(currentVersion)
            
            if (isNewer && updateInfo.downloads.isNotEmpty()) {
                Card(
                    onClick = { showUpdateDialog = true },
                    shape = me.rerere.rikkahub.ui.theme.AppShapes.CardLarge,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.setting_page_update_available_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = stringResource(R.string.setting_page_update_available_version_desc, updateInfo.version),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                        Icon(
                            imageVector = Icons.Rounded.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                
                if (showUpdateDialog) {
                    AlertDialog(
                        onDismissRequest = { showUpdateDialog = false },
                        title = { Text(stringResource(R.string.setting_page_update_dialog_title, updateInfo.version)) },
                        text = {
                            Column(
                                modifier = Modifier.verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.setting_page_update_dialog_changelog_label),
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = updateInfo.changelog.ifEmpty { stringResource(R.string.setting_page_update_dialog_no_changelog) },
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    updateInfo.downloads.firstOrNull()?.let { download ->
                                        updateChecker.downloadUpdate(context, download)
                                    }
                                    showUpdateDialog = false
                                }
                            ) {
                                Text(stringResource(R.string.setting_page_update_dialog_download))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showUpdateDialog = false }) {
                                Text(stringResource(R.string.setting_page_update_dialog_later))
                            }
                        }
                    )
                }
            }
        }
        else -> { /* Loading or Error - don't show anything */ }
    }
}
