package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import me.rerere.rikkahub.data.datastore.TOOL_RESULT_KEEP_USER_MESSAGES_MAX
import me.rerere.rikkahub.data.datastore.TOOL_RESULT_KEEP_USER_MESSAGES_MIN
import me.rerere.rikkahub.data.datastore.getEmbeddingRetrievalTimeoutSeconds
import me.rerere.rikkahub.data.datastore.getHttpRetryDelaySeconds
import me.rerere.rikkahub.data.datastore.getHttpRetryMaxRetries
import me.rerere.rikkahub.data.datastore.getMcpToolCallTimeoutSeconds
import me.rerere.rikkahub.data.datastore.getToolResultKeepUserMessages
import me.rerere.rikkahub.data.model.ToolResultHistoryMode
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.components.ui.HapticSwitch
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.pages.setting.components.SettingGroupItem
import me.rerere.rikkahub.ui.pages.setting.components.SettingsGroup
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import org.koin.androidx.compose.koinViewModel
import kotlin.math.roundToInt

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
                    val retryMaxRetries = settings.getHttpRetryMaxRetries()
                    IntegerSliderSettingItem(
                        title = stringResource(R.string.setting_display_page_http_retry_max_title),
                        subtitle = stringResource(R.string.setting_display_page_http_retry_max_desc),
                        value = retryMaxRetries,
                        valueText = if (retryMaxRetries == 0) {
                            stringResource(R.string.setting_display_page_http_retry_off)
                        } else {
                            stringResource(
                                R.string.setting_display_page_http_retry_max_value,
                                retryMaxRetries,
                            )
                        },
                        valueRange = 0..10,
                        onValueChange = { retries ->
                            haptics.perform(HapticPattern.Pop)
                            vm.updateSettings { current ->
                                if (current.httpRetryMaxRetries == retries) current
                                else current.copy(httpRetryMaxRetries = retries)
                            }
                        }
                    )
                    val retryDelaySeconds = settings.getHttpRetryDelaySeconds()
                    IntegerSliderSettingItem(
                        title = stringResource(R.string.setting_display_page_http_retry_delay_title),
                        subtitle = stringResource(R.string.setting_display_page_http_retry_delay_desc),
                        value = retryDelaySeconds,
                        valueText = stringResource(
                            R.string.setting_display_page_http_retry_delay_value,
                            retryDelaySeconds,
                        ),
                        valueRange = 1..30,
                        onValueChange = { delaySeconds ->
                            haptics.perform(HapticPattern.Pop)
                            vm.updateSettings { current ->
                                if (current.httpRetryDelaySeconds == delaySeconds) current
                                else current.copy(httpRetryDelaySeconds = delaySeconds)
                            }
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
                    val keepUserMessages = displaySetting.getToolResultKeepUserMessages()
                    val discardOldToolResults = displaySetting.toolResultHistoryMode != ToolResultHistoryMode.KEEP_ALL

                    SettingGroupItem(
                        title = stringResource(R.string.assistant_page_tool_results_mode_discard),
                        subtitle = stringResource(
                            R.string.assistant_page_tool_results_mode_discard_desc,
                            keepUserMessages,
                        ),
                        trailing = {
                            HapticSwitch(
                                checked = discardOldToolResults,
                                onCheckedChange = { enabled ->
                                    updateDisplaySetting(
                                        displaySetting.copy(
                                            toolResultHistoryMode = if (enabled) {
                                                ToolResultHistoryMode.DISCARD
                                            } else {
                                                ToolResultHistoryMode.KEEP_ALL
                                            }
                                        )
                                    )
                                }
                            )
                        }
                    )

                    if (discardOldToolResults) {
                        IntegerSliderSettingItem(
                            title = stringResource(R.string.assistant_page_tool_results_keep_title),
                            subtitle = stringResource(
                                R.string.assistant_page_tool_results_keep_desc,
                                keepUserMessages,
                            ),
                            value = keepUserMessages,
                            valueText = keepUserMessages.toString(),
                            valueRange = TOOL_RESULT_KEEP_USER_MESSAGES_MIN..TOOL_RESULT_KEEP_USER_MESSAGES_MAX,
                            onValueChange = { value ->
                                haptics.perform(HapticPattern.Pop)
                                updateDisplaySetting(displaySetting.copy(toolResultKeepUserMessages = value))
                            }
                        )
                    }

                }
            }
        }
    }
}

@Composable
private fun IntegerSliderSettingItem(
    title: String,
    subtitle: String,
    value: Int,
    valueText: String,
    valueRange: IntRange,
    onValueChange: (Int) -> Unit,
) {
    val safeValue = value.coerceIn(valueRange.first, valueRange.last)
    var sliderValue by remember(safeValue, valueRange.first, valueRange.last) {
        mutableFloatStateOf(safeValue.toFloat())
    }
    Surface(
        color = if (LocalDarkMode.current) {
            MaterialTheme.colorScheme.surfaceContainerLow
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Slider(
                value = sliderValue,
                onValueChange = { newValue ->
                    val roundedValue = newValue
                        .roundToInt()
                        .coerceIn(valueRange.first, valueRange.last)
                    if (roundedValue != sliderValue.roundToInt()) {
                        onValueChange(roundedValue)
                    }
                    sliderValue = roundedValue.toFloat()
                },
                valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
                steps = (valueRange.last - valueRange.first - 1).coerceAtLeast(0),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
