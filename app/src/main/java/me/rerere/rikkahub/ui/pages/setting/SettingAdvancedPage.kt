package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.getEmbeddingRetrievalTimeoutSeconds
import me.rerere.rikkahub.data.datastore.getHttp429MaxRetries
import me.rerere.rikkahub.data.datastore.getMcpToolCallTimeoutSeconds
import me.rerere.rikkahub.data.model.ToolResultHistoryMode
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.components.ui.HapticSwitch
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.pages.setting.components.SettingGroupItem
import me.rerere.rikkahub.ui.pages.setting.components.SettingsGroup
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingAdvancedPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var displaySetting by remember(settings) { mutableStateOf(settings.displaySetting) }
    val haptics = rememberPremiumHaptics()
    val navController = LocalNavController.current

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

    Scaffold(
        topBar = {
            OneUITopAppBar(
                title = stringResource(R.string.setting_page_advanced_settings),
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
            item {
                SettingsGroup(
                    title = stringResource(R.string.setting_page_timeout_settings)
                ) {
                    SettingGroupItem(
                        title = stringResource(R.string.setting_display_page_embedding_retrieval_timeout_title),
                        subtitle = stringResource(R.string.setting_display_page_embedding_retrieval_timeout_desc),
                        trailing = {
                            var timeoutText by remember(settings.getEmbeddingRetrievalTimeoutSeconds()) {
                                mutableStateOf(settings.getEmbeddingRetrievalTimeoutSeconds().toString())
                            }

                            OutlinedTextField(
                                value = timeoutText,
                                onValueChange = { value ->
                                    val filtered = value.filter { it.isDigit() }
                                    val parsed = filtered.toIntOrNull()
                                    val safe = parsed?.coerceAtLeast(1)

                                    timeoutText = (safe ?: filtered).toString()

                                    if (safe != null) {
                                        updateDisplaySetting(displaySetting.copy(embeddingRetrievalTimeoutSeconds = safe))
                                    }
                                },
                                modifier = Modifier.widthIn(min = 80.dp, max = 120.dp),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                        }
                    )
                    SettingGroupItem(
                        title = stringResource(R.string.setting_display_page_use_last_turn_memory_title),
                        subtitle = stringResource(R.string.setting_display_page_use_last_turn_memory_desc),
                        trailing = {
                            HapticSwitch(
                                checked = displaySetting.useLastTurnMemoryOnSkip,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(useLastTurnMemoryOnSkip = it))
                                }
                            )
                        }
                    )
                    SettingGroupItem(
                        title = stringResource(R.string.setting_display_page_mcp_tool_call_timeout_title),
                        subtitle = stringResource(R.string.setting_display_page_mcp_tool_call_timeout_desc),
                        trailing = {
                            var timeoutText by remember(settings.getMcpToolCallTimeoutSeconds()) {
                                mutableStateOf(settings.getMcpToolCallTimeoutSeconds().toString())
                            }

                            OutlinedTextField(
                                value = timeoutText,
                                onValueChange = { value ->
                                    val filtered = value.filter { it.isDigit() }
                                    val parsed = filtered.toIntOrNull()
                                    val safe = parsed?.coerceAtLeast(1)

                                    timeoutText = (safe ?: filtered).toString()

                                    if (safe != null) {
                                        vm.updateSettings { current ->
                                            if (current.mcpToolCallTimeoutSeconds == safe) current
                                            else current.copy(mcpToolCallTimeoutSeconds = safe)
                                        }
                                    }
                                },
                                modifier = Modifier.widthIn(min = 80.dp, max = 120.dp),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                        }
                    )
                    SettingGroupItem(
                        title = stringResource(R.string.setting_display_page_http_429_retry_max_title),
                        subtitle = stringResource(R.string.setting_display_page_http_429_retry_max_desc),
                        trailing = {
                            var retryText by remember(settings.getHttp429MaxRetries()) {
                                mutableStateOf(settings.getHttp429MaxRetries().toString())
                            }

                            OutlinedTextField(
                                value = retryText,
                                onValueChange = { value ->
                                    val filtered = value.filter { it.isDigit() }
                                    val parsed = filtered.toIntOrNull()
                                    val safe = parsed?.coerceIn(0, 10)

                                    retryText = (safe ?: filtered).toString()

                                    if (safe != null) {
                                        vm.updateSettings { current ->
                                            if (current.http429MaxRetries == safe) current
                                            else current.copy(http429MaxRetries = safe)
                                        }
                                    }
                                },
                                modifier = Modifier.widthIn(min = 80.dp, max = 120.dp),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                        }
                    )
                }
            }

            item {
                SettingsGroup(
                    title = stringResource(R.string.setting_advanced_page_custom_request_title)
                ) {
                    SettingGroupItem(
                        title = stringResource(R.string.setting_advanced_page_custom_request_json_mode_title),
                        subtitle = stringResource(R.string.setting_advanced_page_custom_request_json_mode_desc),
                        trailing = {
                            HapticSwitch(
                                checked = displaySetting.useJsonEditorForCustomRequest,
                                onCheckedChange = {
                                    updateDisplaySetting(
                                        displaySetting.copy(useJsonEditorForCustomRequest = it)
                                    )
                                }
                            )
                        }
                    )
                    SettingGroupItem(
                        title = stringResource(R.string.setting_tool_prompts_title),
                        subtitle = stringResource(R.string.setting_tool_prompts_desc),
                        icon = { Icon(Icons.Rounded.Build, null, modifier = Modifier.size(20.dp)) },
                        onClick = { navController.navigate(Screen.SettingCustomToolPrompts) },
                    )
                }
            }

            item {
                SettingsGroup(
                    title = stringResource(R.string.setting_advanced_page_conversation_actions_title)
                ) {
                    SettingGroupItem(
                        title = stringResource(R.string.setting_advanced_page_show_export_conversation_json_title),
                        subtitle = stringResource(R.string.setting_advanced_page_show_export_conversation_json_desc),
                        trailing = {
                            HapticSwitch(
                                checked = displaySetting.showExportConversationJsonButton,
                                onCheckedChange = {
                                    updateDisplaySetting(
                                        displaySetting.copy(showExportConversationJsonButton = it)
                                    )
                                }
                            )
                        }
                    )
                }
            }

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
                            color = if (LocalDarkMode.current) {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
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
                                    steps = 19,
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
        }
    }
}
