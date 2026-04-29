package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Book
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ui.HapticSwitch
import me.rerere.rikkahub.ui.pages.setting.components.SettingsGroup
import me.rerere.rikkahub.ui.pages.setting.components.SettingGroupItem
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import kotlin.math.roundToInt

@Composable
fun AssistantContextManagementSubPage(
    assistant: Assistant,
    onUpdate: (Assistant) -> Unit,
    onNavigateToLorebooks: () -> Unit,
    onNavigateToModels: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        // ═══════════════════════════════════════════════════════════════════
        // LOREBOOKS
        // ═══════════════════════════════════════════════════════════════════
        SettingsGroup(title = stringResource(R.string.context_lorebooks_title)) {
            SettingGroupItem(
                title = stringResource(R.string.assistant_lorebooks_title),
                subtitle = stringResource(R.string.context_lorebooks_desc),
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.Book,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                onClick = onNavigateToLorebooks
            )
        }

        // ═══════════════════════════════════════════════════════════════════
        // MESSAGE HISTORY
        // ═══════════════════════════════════════════════════════════════════
        SettingsGroup(title = stringResource(R.string.context_message_history_title)) {
            val needsSummarizerWarning =
                assistant.enableContextRefresh &&
                    assistant.contextSummarizerModelId == null &&
                    assistant.summarizerModelId == null
            AnimatedVisibility(
                visible = needsSummarizerWarning,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                SummarizerWarningBanner(onClick = onNavigateToModels)
            }

            SettingGroupItem(
                title = stringResource(R.string.context_refresh_enable),
                subtitle = stringResource(R.string.context_refresh_enable_desc),
                trailing = {
                    HapticSwitch(
                        checked = assistant.enableContextRefresh,
                        onCheckedChange = { enabled ->
                            onUpdate(
                                assistant.copy(
                                    enableContextRefresh = enabled,
                                    autoRegenerateSummary = if (!enabled) false else assistant.autoRegenerateSummary
                                )
                            )
                        }
                    )
                },
                onClick = {
                    val enabled = !assistant.enableContextRefresh
                    onUpdate(
                        assistant.copy(
                            enableContextRefresh = enabled,
                            autoRegenerateSummary = if (!enabled) false else assistant.autoRegenerateSummary
                        )
                    )
                }
            )

            AnimatedVisibility(
                visible = assistant.enableContextRefresh,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                val historyLimit = assistant.maxHistoryMessages ?: 10
                SettingGroupItem(
                    title = stringResource(R.string.context_refresh_auto_summarize_title),
                    subtitle = stringResource(R.string.context_refresh_auto_summarize_desc, historyLimit),
                    trailing = {
                        HapticSwitch(
                            checked = assistant.autoRegenerateSummary,
                            onCheckedChange = { enabled ->
                                onUpdate(
                                    assistant.copy(
                                        autoRegenerateSummary = enabled,
                                        enableHistorySummarization = if (enabled) false else assistant.enableHistorySummarization,
                                        maxHistoryMessages = if (enabled) (assistant.maxHistoryMessages ?: 10) else assistant.maxHistoryMessages
                                    )
                                )
                            }
                        )
                    },
                    onClick = {
                        val enabled = !assistant.autoRegenerateSummary
                        onUpdate(
                            assistant.copy(
                                autoRegenerateSummary = enabled,
                                enableHistorySummarization = if (enabled) false else assistant.enableHistorySummarization,
                                maxHistoryMessages = if (enabled) (assistant.maxHistoryMessages ?: 10) else assistant.maxHistoryMessages
                            )
                        )
                    }
                )
            }

            SettingGroupItem(
                title = stringResource(R.string.context_dynamic_pruning_title),
                subtitle = stringResource(R.string.context_dynamic_pruning_desc),
                trailing = {
                    HapticSwitch(
                        checked = assistant.enableHistorySummarization,
                        onCheckedChange = { enabled ->
                            onUpdate(
                                assistant.copy(
                                    enableHistorySummarization = enabled,
                                    autoRegenerateSummary = if (enabled) false else assistant.autoRegenerateSummary,
                                    maxHistoryMessages = if (enabled) (assistant.maxHistoryMessages ?: 10) else assistant.maxHistoryMessages
                                )
                            )
                        }
                    )
                },
                onClick = {
                    val enabled = !assistant.enableHistorySummarization
                    onUpdate(
                        assistant.copy(
                            enableHistorySummarization = enabled,
                            autoRegenerateSummary = if (enabled) false else assistant.autoRegenerateSummary,
                            maxHistoryMessages = if (enabled) (assistant.maxHistoryMessages ?: 10) else assistant.maxHistoryMessages
                        )
                    )
                }
            )

            AnimatedVisibility(
                visible = assistant.enableHistorySummarization || (assistant.enableContextRefresh && assistant.autoRegenerateSummary),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                val historyLimit = (assistant.maxHistoryMessages ?: 10).coerceAtLeast(1)
                var sliderValue by remember(historyLimit) { mutableFloatStateOf(historyLimit.coerceIn(5, 50).toFloat()) }
                var showManualInputDialog by remember { mutableStateOf(false) }
                var manualInputText by remember(historyLimit) { mutableStateOf(historyLimit.toString()) }
                val sliderValueInt = sliderValue.roundToInt()

                SliderSettingCard(
                    title = if (assistant.enableHistorySummarization) {
                        stringResource(R.string.context_dynamic_pruning_limit_title)
                    } else {
                        stringResource(R.string.context_max_messages)
                    },
                    value = sliderValue,
                    valueText = stringResource(
                        R.string.context_max_messages_value,
                        historyLimit
                    ),
                    onValueTextClick = {
                        manualInputText = historyLimit.toString()
                        showManualInputDialog = true
                    },
                    description = stringResource(
                        if (assistant.enableHistorySummarization) {
                            R.string.context_dynamic_pruning_limit_desc
                        } else {
                            R.string.context_refresh_auto_summarize_desc
                        },
                        historyLimit
                    ),
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = {
                        val newValue = sliderValue.roundToInt().coerceAtLeast(5)
                        onUpdate(
                            assistant.copy(
                                maxHistoryMessages = newValue
                            )
                        )
                    },
                    valueRange = 5f..50f,
                    steps = 44
                )

                if (showManualInputDialog) {
                    val parsedValue = manualInputText.toLongOrNull()
                    val canConfirm = parsedValue != null && parsedValue in 1L..Int.MAX_VALUE.toLong()

                    AlertDialog(
                        onDismissRequest = { showManualInputDialog = false },
                        title = {
                            Text(
                                text = if (assistant.enableHistorySummarization) {
                                    stringResource(R.string.context_dynamic_pruning_limit_title)
                                } else {
                                    stringResource(R.string.context_max_messages)
                                }
                            )
                        },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = manualInputText,
                                    onValueChange = { input ->
                                        manualInputText = input.filter { it.isDigit() }
                                    },
                                    label = { Text(stringResource(R.string.context_manual_limit_input_label)) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    isError = manualInputText.isNotEmpty() && !canConfirm
                                )
                                Text(
                                    text = if (manualInputText.isNotEmpty() && !canConfirm) {
                                        stringResource(R.string.context_manual_limit_input_error)
                                    } else {
                                        stringResource(R.string.context_manual_limit_input_hint)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (manualInputText.isNotEmpty() && !canConfirm) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    val manualValue = manualInputText.toLongOrNull() ?: return@Button
                                    if (manualValue !in 1L..Int.MAX_VALUE.toLong()) return@Button
                                    onUpdate(assistant.copy(maxHistoryMessages = manualValue.toInt()))
                                    showManualInputDialog = false
                                },
                                enabled = canConfirm
                            ) {
                                Text(stringResource(android.R.string.ok))
                            }
                        },
                        dismissButton = {
                            OutlinedButton(onClick = { showManualInputDialog = false }) {
                                Text(stringResource(android.R.string.cancel))
                            }
                        }
                    )
                }
            }
        }

        // ═══════════════════════════════════════════════════════════════════
        // SEARCH RESULTS
        // ═══════════════════════════════════════════════════════════════════
        SettingsGroup(title = stringResource(R.string.context_search_results_title)) {
            val maxSearchResults = assistant.maxSearchResultsRetained ?: 0
            var searchSliderValue by remember(maxSearchResults) { mutableFloatStateOf(maxSearchResults.toFloat()) }
            
            SliderSettingCard(
                title = if (searchSliderValue.roundToInt() == 0) {
                    stringResource(R.string.context_max_search_results_unlimited)
                } else {
                    stringResource(R.string.context_max_search_results, searchSliderValue.roundToInt())
                },
                value = searchSliderValue,
                valueText = "", // Title already shows the value
                description = stringResource(R.string.context_max_search_results_desc),
                onValueChange = { searchSliderValue = it },
                onValueChangeFinished = {
                    val newValue = searchSliderValue.roundToInt()
                    onUpdate(assistant.copy(
                        maxSearchResultsRetained = if (newValue == 0) null else newValue
                    ))
                },
                valueRange = 0f..50f,
                steps = 49
            )
        }

        // ═══════════════════════════════════════════════════════════════════
        // IMAGE ARCHIVING
        // ═══════════════════════════════════════════════════════════════════
        SettingsGroup(title = stringResource(R.string.context_image_archive_title)) {
            val archiveAge = assistant.archiveImagesAfterMessageAge ?: 0
            var archiveSliderValue by remember(archiveAge) { mutableFloatStateOf(archiveAge.toFloat()) }

            SliderSettingCard(
                title = if (archiveSliderValue.roundToInt() == 0) {
                    stringResource(R.string.context_image_archive_disabled)
                } else {
                    stringResource(R.string.context_image_archive_after, archiveSliderValue.roundToInt())
                },
                value = archiveSliderValue,
                valueText = "",
                description = stringResource(R.string.context_image_archive_desc),
                onValueChange = { archiveSliderValue = it },
                onValueChangeFinished = {
                    val newValue = archiveSliderValue.roundToInt()
                    onUpdate(assistant.copy(
                        archiveImagesAfterMessageAge = if (newValue == 0) null else newValue
                    ))
                },
                valueRange = 0f..30f,
                steps = 29
            )
        }

        // ═══════════════════════════════════════════════════════════════════
        // DOCUMENT ARCHIVING
        // ═══════════════════════════════════════════════════════════════════
        SettingsGroup(title = stringResource(R.string.context_doc_archive_title)) {
            val docArchiveAge = assistant.archiveDocumentsAfterMessageAge ?: 0
            var docArchiveSliderValue by remember(docArchiveAge) { mutableFloatStateOf(docArchiveAge.toFloat()) }

            SliderSettingCard(
                title = if (docArchiveSliderValue.roundToInt() == 0) {
                    stringResource(R.string.context_doc_archive_disabled)
                } else {
                    stringResource(R.string.context_doc_archive_after, docArchiveSliderValue.roundToInt())
                },
                value = docArchiveSliderValue,
                valueText = "",
                description = stringResource(R.string.context_doc_archive_desc),
                onValueChange = { docArchiveSliderValue = it },
                onValueChangeFinished = {
                    val newValue = docArchiveSliderValue.roundToInt()
                    onUpdate(assistant.copy(
                        archiveDocumentsAfterMessageAge = if (newValue == 0) null else newValue
                    ))
                },
                valueRange = 0f..30f,
                steps = 29
            )
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun SummarizerWarningBanner(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = stringResource(R.string.context_refresh_no_summarizer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun SliderSettingCard(
    title: String,
    value: Float,
    valueText: String,
    onValueTextClick: (() -> Unit)? = null,
    description: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int
) {
    Surface(
        color = if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (valueText.isNotEmpty()) {
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = if (onValueTextClick != null) {
                        Modifier.clickable(onClick = onValueTextClick)
                    } else {
                        Modifier
                    }
                )
            }
            Slider(
                value = value,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                valueRange = valueRange,
                steps = steps
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
