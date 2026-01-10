package me.rerere.rikkahub.ui.pages.setting

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import me.rerere.rikkahub.ui.components.ui.HapticSwitch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.KeepAliveMode
import me.rerere.rikkahub.data.datastore.MessageInputStyle
import me.rerere.rikkahub.data.model.ToolResultHistoryMode
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionNotification
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.hooks.rememberAmoledDarkMode
import me.rerere.rikkahub.ui.hooks.rememberSharedPreferenceBoolean
import me.rerere.rikkahub.ui.pages.setting.components.PresetThemeButtonGroup
import me.rerere.rikkahub.ui.pages.setting.components.SettingsGroup
import me.rerere.rikkahub.ui.pages.setting.components.SettingGroupItem
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

private enum class LiveUpdateUiOption {
    OFF,
    DEFAULT,
    COMPATIBILITY,
}

private fun DisplaySetting.toLiveUpdateUiOption(): LiveUpdateUiOption {
    return when {
        enableLiveUpdate -> LiveUpdateUiOption.DEFAULT
        enableKeepAliveNotification -> LiveUpdateUiOption.COMPATIBILITY
        else -> LiveUpdateUiOption.OFF
    }
}

@Composable
fun SettingDisplayPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var displaySetting by remember(settings) { mutableStateOf(settings.displaySetting) }
    var amoledDarkMode by rememberAmoledDarkMode()
    val haptics = rememberPremiumHaptics()

    fun updateDisplaySetting(setting: DisplaySetting) {
        displaySetting = setting
        vm.updateSettings(
            settings.copy(
                displaySetting = setting
            )
        )
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val lazyListState = rememberLazyListState()

    val permissionState = rememberPermissionState(
        permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setOf(
            PermissionNotification
        ) else emptySet(),
    )
    PermissionManager(permissionState = permissionState)

    Scaffold(
        topBar = {
            OneUITopAppBar(
                title = stringResource(R.string.setting_display_page_title),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    BackButton()
                }
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = lazyListState,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            // Theme Settings
            item {
                var useExpressiveFont by me.rerere.rikkahub.ui.hooks.rememberExpressiveFont()
                
                SettingsGroup(
                    title = stringResource(R.string.setting_page_theme_setting)
                ) {
                    SettingGroupItem(
                        title = stringResource(R.string.setting_page_dynamic_color),
                        subtitle = stringResource(R.string.setting_page_dynamic_color_desc),
                        trailing = {
                            HapticSwitch(
                                checked = settings.dynamicColor,
                                onCheckedChange = {
                                    vm.updateSettings(settings.copy(dynamicColor = it))
                                },
                            )
                        }
                    )
                    SettingGroupItem(
                        title = stringResource(R.string.setting_display_page_font_style_title),
                        subtitle = if (useExpressiveFont) {
                            stringResource(R.string.setting_display_page_font_style_expressive)
                        } else {
                            stringResource(R.string.setting_display_page_font_style_normal)
                        },
                        trailing = {
                            HapticSwitch(
                                checked = useExpressiveFont,
                                onCheckedChange = { useExpressiveFont = it }
                            )
                        }
                    )
                }
            }

            if (!settings.dynamicColor) {
                item {
                    PresetThemeButtonGroup(
                        themeId = settings.themeId,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        onChangeTheme = {
                            vm.updateSettings(settings.copy(themeId = it))
                        }
                    )
                }
            }


            // Basic Settings
            item {
                var createNewConversationOnStart by rememberSharedPreferenceBoolean(
                    "create_new_conversation_on_start",
                    true
                )
                SettingsGroup(
                    title = stringResource(R.string.setting_page_basic_settings)
                ) {
                    SettingGroupItem(
                        title = stringResource(R.string.setting_display_page_create_new_conversation_on_start_title),
                        subtitle = stringResource(R.string.setting_display_page_create_new_conversation_on_start_desc),
                        trailing = {
                            HapticSwitch(
                                checked = createNewConversationOnStart,
                                onCheckedChange = {
                                    createNewConversationOnStart = it
                                }
                            )
                        }
                    )
                    SettingGroupItem(
                        title = stringResource(R.string.setting_display_page_notification_message_generated),
                        subtitle = stringResource(R.string.setting_display_page_notification_message_generated_desc),
                        trailing = {
                            HapticSwitch(
                                checked = displaySetting.enableNotificationOnMessageGeneration,
                                onCheckedChange = {
                                    if (it && !permissionState.allPermissionsGranted) {
                                        permissionState.requestPermissions()
                                    }
                                    updateDisplaySetting(displaySetting.copy(enableNotificationOnMessageGeneration = it))
                                }
                            )
                        }
                    )
                    if (displaySetting.enableNotificationOnMessageGeneration) {
                        SettingGroupItem(
                            title = stringResource(R.string.setting_display_page_notification_live_update),
                            subtitle = stringResource(R.string.setting_display_page_notification_live_update_desc),
                            trailing = {
                                val options = remember { LiveUpdateUiOption.entries.toList() }
                                val selected = remember(displaySetting) { displaySetting.toLiveUpdateUiOption() }
                                Select(
                                    options = options,
                                    selectedOption = selected,
                                    onOptionSelected = { option ->
                                        when (option) {
                                            LiveUpdateUiOption.OFF -> updateDisplaySetting(
                                                displaySetting.copy(
                                                    enableLiveUpdate = false,
                                                    enableKeepAliveNotification = false,
                                                    keepAliveMode = KeepAliveMode.ALWAYS
                                                )
                                            )

                                            LiveUpdateUiOption.DEFAULT -> {
                                                if (!permissionState.allPermissionsGranted) {
                                                    permissionState.requestPermissions()
                                                }
                                                updateDisplaySetting(
                                                    displaySetting.copy(
                                                        enableLiveUpdate = true,
                                                        enableKeepAliveNotification = false,
                                                        keepAliveMode = KeepAliveMode.ALWAYS
                                                    )
                                                )
                                            }

                                            LiveUpdateUiOption.COMPATIBILITY -> {
                                                if (!permissionState.allPermissionsGranted) {
                                                    permissionState.requestPermissions()
                                                }
                                                updateDisplaySetting(
                                                    displaySetting.copy(
                                                        enableLiveUpdate = false,
                                                        enableKeepAliveNotification = true,
                                                        keepAliveMode = KeepAliveMode.GENERATION
                                                    )
                                                )
                                            }
                                        }
                                    },
                                    optionToString = { option ->
                                        when (option) {
                                            LiveUpdateUiOption.OFF -> stringResource(R.string.setting_live_update_mode_off)
                                            LiveUpdateUiOption.DEFAULT -> stringResource(R.string.setting_live_update_mode_default)
                                            LiveUpdateUiOption.COMPATIBILITY -> stringResource(R.string.setting_live_update_mode_compatibility)
                                        }
                                    },
                                    modifier = Modifier.widthIn(min = 64.dp, max = 120.dp)
                                )
                            }
                        )
                    }
                    SettingGroupItem(
                        title = stringResource(R.string.setting_display_page_check_updates_title),
                        subtitle = stringResource(R.string.setting_display_page_check_updates_desc),
                        trailing = {
                            HapticSwitch(
                                checked = displaySetting.checkForUpdates,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(checkForUpdates = it))
                                }
                            )
                        }
                    )
                }
            }

            // Chat Display Settings
            item {
                SettingsGroup(
                    title = stringResource(R.string.setting_page_chat_settings)
                ) {
                    SettingGroupItem(
                        title = stringResource(R.string.setting_display_page_show_user_avatar_title),
                        subtitle = stringResource(R.string.setting_display_page_show_user_avatar_desc),
                        trailing = {
                            HapticSwitch(
                                checked = displaySetting.showUserAvatar,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(showUserAvatar = it))
                                }
                            )
                        }
                    )
                    SettingGroupItem(
                        title = stringResource(R.string.setting_display_page_show_assistant_avatar_title),
                        subtitle = stringResource(R.string.setting_display_page_show_assistant_avatar_desc),
                        trailing = {
                            HapticSwitch(
                                checked = displaySetting.showModelIcon,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(showModelIcon = it))
                                }
                            )
                        }
                    )
                    SettingGroupItem(
                        title = stringResource(R.string.setting_display_page_show_token_usage_title),
                        subtitle = stringResource(R.string.setting_display_page_show_token_usage_desc),
                        trailing = {
                            HapticSwitch(
                                checked = displaySetting.showTokenUsage,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(showTokenUsage = it))
                                }
                            )
                        }
                    )
                    SettingGroupItem(
                        title = stringResource(R.string.setting_display_page_message_input_style_title),
                        subtitle = stringResource(R.string.setting_display_page_message_input_style_desc),
                        trailing = {
                            val options = remember { MessageInputStyle.entries.toList() }
                            val selected = remember(displaySetting) { displaySetting.messageInputStyle }
                            Select(
                                options = options,
                                selectedOption = selected,
                                onOptionSelected = { option ->
                                    updateDisplaySetting(displaySetting.copy(messageInputStyle = option))
                                },
                                optionToString = { option ->
                                    when (option) {
                                        MessageInputStyle.STANDARD -> stringResource(
                                            R.string.setting_display_page_message_input_style_standard
                                        )

                                        MessageInputStyle.MINIMAL -> stringResource(
                                            R.string.setting_display_page_message_input_style_minimal
                                        )
                                    }
                                },
                                modifier = Modifier.widthIn(min = 64.dp, max = 140.dp)
                            )
                        }
                    )
                    SettingGroupItem(
                        title = stringResource(R.string.setting_display_page_auto_collapse_thinking_title),
                        subtitle = stringResource(R.string.setting_display_page_auto_collapse_thinking_desc),
                        trailing = {
                            HapticSwitch(
                                checked = displaySetting.autoCloseThinking,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(autoCloseThinking = it))
                                }
                            )
                        }
                    )
                    SettingGroupItem(
                        title = stringResource(R.string.setting_display_page_show_context_stacks_title),
                        subtitle = stringResource(R.string.setting_display_page_show_context_stacks_desc),
                        trailing = {
                            HapticSwitch(
                                checked = displaySetting.showContextStacks,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(showContextStacks = it))
                                }
                            )
                        }
                    )
                }
            }

            // Tool Result History (prompt context optimization)
            item {
                SettingsGroup(
                    title = stringResource(R.string.assistant_page_tool_results_group_title)
                ) {
                    data class ModeOption(
                        val mode: ToolResultHistoryMode,
                        val title: String,
                        val subtitle: String,
                    )

                    val modeOptions = listOf(
                        ModeOption(
                            mode = ToolResultHistoryMode.KEEP_ALL,
                            title = stringResource(R.string.assistant_page_tool_results_mode_keep_all),
                            subtitle = stringResource(R.string.assistant_page_tool_results_mode_keep_all_desc),
                        ),
                        ModeOption(
                            mode = ToolResultHistoryMode.DISCARD,
                            title = stringResource(R.string.assistant_page_tool_results_mode_discard),
                            subtitle = stringResource(R.string.assistant_page_tool_results_mode_discard_desc),
                        ),
                        ModeOption(
                            mode = ToolResultHistoryMode.RAG,
                            title = stringResource(R.string.assistant_page_tool_results_mode_rag),
                            subtitle = stringResource(R.string.assistant_page_tool_results_mode_rag_desc),
                        ),
                    )

                    val selectedMode = modeOptions.firstOrNull { it.mode == displaySetting.toolResultHistoryMode }
                        ?: modeOptions.first()

                    SettingGroupItem(
                        title = stringResource(R.string.assistant_page_tool_results_mode_title),
                        subtitle = selectedMode.subtitle,
                        trailing = {
                            Select(
                                options = modeOptions,
                                selectedOption = selectedMode,
                                onOptionSelected = { option ->
                                    haptics.perform(HapticPattern.Pop)
                                    updateDisplaySetting(displaySetting.copy(toolResultHistoryMode = option.mode))
                                },
                                optionToString = { it.title },
                                modifier = Modifier.widthIn(min = 110.dp, max = 130.dp)
                            )
                        }
                    )

                    if (displaySetting.toolResultHistoryMode != ToolResultHistoryMode.KEEP_ALL) {
                        data class KeepOption(val value: Int)
                        val keepOptions = (1..20).map { KeepOption(it) }
                        val keepUserMessages = displaySetting.toolResultKeepUserMessages.coerceIn(1, 20)
                        val selectedKeep = keepOptions.firstOrNull { it.value == keepUserMessages }
                            ?: KeepOption(keepUserMessages)

                        SettingGroupItem(
                            title = stringResource(R.string.assistant_page_tool_results_keep_title),
                            subtitle = stringResource(
                                R.string.assistant_page_tool_results_keep_desc,
                                keepUserMessages,
                            ),
                            trailing = {
                                Select(
                                    options = keepOptions,
                                    selectedOption = selectedKeep,
                                    onOptionSelected = { option ->
                                        haptics.perform(HapticPattern.Pop)
                                        updateDisplaySetting(displaySetting.copy(toolResultKeepUserMessages = option.value))
                                    },
                                    optionToString = { it.value.toString() },
                                    modifier = Modifier.widthIn(min = 80.dp, max = 120.dp)
                                )
                            }
                        )
                    }

                    if (displaySetting.toolResultHistoryMode == ToolResultHistoryMode.RAG) {
                        Surface(
                            color = if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.assistant_page_tool_results_similarity_threshold),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                var threshold by remember(displaySetting.toolResultRagSimilarityThreshold) {
                                    mutableFloatStateOf(displaySetting.toolResultRagSimilarityThreshold.coerceIn(0f, 1f))
                                }
                                val currentThreshold = String.format("%.2f", threshold)
                                Text(
                                    text = stringResource(
                                        R.string.assistant_page_tool_results_similarity_threshold_desc,
                                        currentThreshold,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Slider(
                                    value = threshold,
                                    onValueChange = { newValue ->
                                        threshold = newValue
                                        updateDisplaySetting(displaySetting.copy(toolResultRagSimilarityThreshold = newValue))
                                    },
                                    valueRange = 0f..1f,
                                    steps = 19, // 0.05 increments
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        stringResource(R.string.assistant_page_rag_similarity_all),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                    Text(
                                        stringResource(R.string.assistant_page_rag_similarity_exact),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
             
            // Message Jumper Settings
            item {
                SettingsGroup(
                    title = stringResource(R.string.setting_display_page_section_message_jumper)
                ) {
                    SettingGroupItem(
                        title = stringResource(R.string.setting_display_page_show_message_jumper_title),
                        subtitle = stringResource(R.string.setting_display_page_show_message_jumper_desc),
                        trailing = {
                            HapticSwitch(
                                checked = displaySetting.showMessageJumper,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(showMessageJumper = it))
                                }
                            )
                        }
                    )
                    if (displaySetting.showMessageJumper) {
                        SettingGroupItem(
                            title = stringResource(R.string.setting_display_page_message_jumper_position_title),
                            subtitle = stringResource(R.string.setting_display_page_message_jumper_position_desc),
                            trailing = {
                                HapticSwitch(
                                    checked = displaySetting.messageJumperOnLeft,
                                    onCheckedChange = {
                                        updateDisplaySetting(displaySetting.copy(messageJumperOnLeft = it))
                                    }
                                )
                            }
                        )
                    }
                }
            }

            // Haptics Settings
            item {
                SettingsGroup(
                    title = stringResource(R.string.setting_display_page_section_haptics)
                ) {
                    SettingGroupItem(
                        title = stringResource(R.string.setting_display_page_enable_message_generation_haptic_effect_title),
                        subtitle = stringResource(R.string.setting_display_page_enable_message_generation_haptic_effect_desc),
                        trailing = {
                            HapticSwitch(
                                checked = displaySetting.enableMessageGenerationHapticEffect,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(enableMessageGenerationHapticEffect = it))
                                }
                            )
                        }
                    )
                    SettingGroupItem(
                        title = stringResource(R.string.setting_display_page_ui_haptics_title),
                        subtitle = stringResource(R.string.setting_display_page_ui_haptics_desc),
                        trailing = {
                            HapticSwitch(
                                checked = displaySetting.enableUIHaptics,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(enableUIHaptics = it))
                                }
                            )
                        }
                    )
                }
            }

            // Media Settings
            item {
                SettingsGroup(
                    title = stringResource(R.string.setting_display_page_section_media)
                ) {
                    SettingGroupItem(
                        title = stringResource(R.string.setting_display_page_skip_crop_image_title),
                        subtitle = stringResource(R.string.setting_display_page_skip_crop_image_desc),
                        trailing = {
                            HapticSwitch(
                                checked = displaySetting.skipCropImage,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(skipCropImage = it))
                                }
                            )
                        }
                    )
                }
            }

            // Code Blocks Settings
            item {
                SettingsGroup(
                    title = stringResource(R.string.setting_display_page_section_code_blocks)
                ) {
                    SettingGroupItem(
                        title = stringResource(R.string.setting_display_page_code_block_auto_wrap_title),
                        subtitle = stringResource(R.string.setting_display_page_code_block_auto_wrap_desc),
                        trailing = {
                            HapticSwitch(
                                checked = displaySetting.codeBlockAutoWrap,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(codeBlockAutoWrap = it))
                                }
                            )
                        }
                    )
                    SettingGroupItem(
                        title = stringResource(R.string.setting_display_page_code_block_auto_collapse_title),
                        subtitle = stringResource(R.string.setting_display_page_code_block_auto_collapse_desc),
                        trailing = {
                            HapticSwitch(
                                checked = displaySetting.codeBlockAutoCollapse,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(codeBlockAutoCollapse = it))
                                }
                            )
                        }
                    )
                }
            }

            // Advanced Settings (RP Optimizations & Font Size)
            item {
                val navController = me.rerere.rikkahub.ui.context.LocalNavController.current
                SettingsGroup(
                    title = stringResource(R.string.setting_display_page_section_advanced)
                ) {
                    SettingGroupItem(
                        title = stringResource(R.string.setting_display_page_rp_optimizations_title),
                        subtitle = stringResource(R.string.setting_display_page_rp_optimizations_desc),
                        onClick = { navController.navigate(me.rerere.rikkahub.Screen.SettingRpOptimizations) }
                    )
                }
            }

            // Font Size Slider
            item {
                SettingsGroup(
                    title = stringResource(R.string.setting_display_page_font_size_title)
                ) {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Slider(
                            value = displaySetting.fontSizeRatio,
                            onValueChange = {
                                updateDisplaySetting(displaySetting.copy(fontSizeRatio = it))
                            },
                            valueRange = 0.5f..2f,
                            steps = 11,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${(displaySetting.fontSizeRatio * 100).toInt()}%",
                        )
                    }
                    MarkdownBlock(
                        content = stringResource(R.string.setting_display_page_font_size_preview),
                        modifier = Modifier.padding(8.dp),
                        style = LocalTextStyle.current.copy(
                            fontSize = LocalTextStyle.current.fontSize * displaySetting.fontSizeRatio,
                            lineHeight = LocalTextStyle.current.lineHeight * displaySetting.fontSizeRatio,
                        )
                    )
                }
            }
        }
    }
}
