package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_BACKGROUND_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_CONTEXT_SUMMARY_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_MEMORY_CONSOLIDATION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_MEMORY_SUMMARY_PROMPT
import me.rerere.ai.core.ReasoningLevel
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.ai.ReasoningButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.HapticSwitch
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.pages.setting.components.SettingsGroup
import me.rerere.rikkahub.ui.pages.setting.components.SettingGroupItem
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.utils.toFixed

/**
 * Model tab - All model and generation-related settings.
 * Designed with cohesive SettingsGroup pattern.
 */
@Composable
fun AssistantModelSubPage(
    assistant: Assistant,
    providers: List<ProviderSetting>,
    onUpdate: (Assistant) -> Unit,
    onApplyBackgroundPromptToAll: (String) -> Unit,
    onApplyConsolidationPromptToAll: (String) -> Unit,
    onApplyMemorySummaryPromptToAll: (String) -> Unit,
    onApplyContextSummaryPromptToAll: (String) -> Unit,
) {
    var showBackgroundPromptSheet by remember { mutableStateOf(false) }
    var showConsolidationPromptSheet by remember { mutableStateOf(false) }
    var showContextSummaryPromptSheet by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        // ═══════════════════════════════════════════════════════════════════
        // MODELS GROUP
        // ═══════════════════════════════════════════════════════════════════
        SettingsGroup(title = stringResource(R.string.assistant_page_group_models)) {
            // Chat Model (Primary)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = if (LocalDarkMode.current) 
                    MaterialTheme.colorScheme.surfaceContainerLow 
                else 
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.assistant_page_chat_model),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.assistant_page_chat_model_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ModelSelector(
                        modelId = assistant.chatModelId,
                        providers = providers,
                        type = ModelType.CHAT,
                        onSelect = { onUpdate(assistant.copy(chatModelId = it.id)) },
                    )
                }
            }
            
            // Background Model
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = if (LocalDarkMode.current) 
                    MaterialTheme.colorScheme.surfaceContainerLow 
                else 
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.assistant_page_background_model),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.assistant_page_background_model_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            ModelSelector(
                                modelId = assistant.backgroundModelId,
                                providers = providers,
                                type = ModelType.CHAT,
                                onSelect = { onUpdate(assistant.copy(backgroundModelId = it.id)) },
                                modifier = Modifier.wrapContentWidth(),
                            )
                        }
                        IconButton(onClick = { showBackgroundPromptSheet = true }) {
                            Icon(Icons.Rounded.Settings, contentDescription = null)
                        }
                    }
                }
            }
            
            // Summarizer Model
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = if (LocalDarkMode.current) 
                    MaterialTheme.colorScheme.surfaceContainerLow 
                else 
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.assistant_page_consolidation_summarizer_model),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.assistant_page_consolidation_summarizer_model_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            ModelSelector(
                                modelId = assistant.summarizerModelId,
                                providers = providers,
                                type = ModelType.CHAT,
                                onSelect = { onUpdate(assistant.copy(summarizerModelId = it.id)) },
                                modifier = Modifier.wrapContentWidth(),
                            )
                        }
                        IconButton(onClick = { showConsolidationPromptSheet = true }) {
                            Icon(Icons.Rounded.Settings, contentDescription = null)
                        }
                    }
                }
            }

            // Context Summarizer Model
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = if (LocalDarkMode.current)
                    MaterialTheme.colorScheme.surfaceContainerLow
                else
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.assistant_page_context_summarizer_model),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.assistant_page_context_summarizer_model_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            ModelSelector(
                                modelId = assistant.contextSummarizerModelId,
                                providers = providers,
                                type = ModelType.CHAT,
                                onSelect = { onUpdate(assistant.copy(contextSummarizerModelId = it.id)) },
                                modifier = Modifier.wrapContentWidth(),
                            )
                        }
                        IconButton(onClick = { showContextSummaryPromptSheet = true }) {
                            Icon(Icons.Rounded.Settings, contentDescription = null)
                        }
                    }
                }
            }
        }

        if (showBackgroundPromptSheet) {
            AssistantPromptEditorSheet(
                prompt = assistant.backgroundPrompt,
                defaultPrompt = DEFAULT_BACKGROUND_PROMPT,
                promptVariablesText = stringResource(R.string.assistant_page_background_prompt_vars),
                onDismiss = { showBackgroundPromptSheet = false },
                onPromptChange = { onUpdate(assistant.copy(backgroundPrompt = it)) },
                onApplyToGlobal = onApplyBackgroundPromptToAll,
            )
        }

        if (showConsolidationPromptSheet) {
            AssistantMemoryPromptEditorSheet(
                consolidationPrompt = assistant.consolidationPrompt,
                memorySummaryPrompt = assistant.memorySummaryPrompt,
                onDismiss = { showConsolidationPromptSheet = false },
                onConsolidationPromptChange = { onUpdate(assistant.copy(consolidationPrompt = it)) },
                onMemorySummaryPromptChange = { onUpdate(assistant.copy(memorySummaryPrompt = it)) },
                onApplyConsolidationPromptToAll = onApplyConsolidationPromptToAll,
                onApplyMemorySummaryPromptToAll = onApplyMemorySummaryPromptToAll,
            )
        }

        if (showContextSummaryPromptSheet) {
            AssistantPromptEditorSheet(
                prompt = assistant.contextSummaryPrompt,
                defaultPrompt = DEFAULT_CONTEXT_SUMMARY_PROMPT,
                promptVariablesText = stringResource(R.string.assistant_page_context_summary_prompt_vars),
                onDismiss = { showContextSummaryPromptSheet = false },
                onPromptChange = { onUpdate(assistant.copy(contextSummaryPrompt = it)) },
                onApplyToGlobal = onApplyContextSummaryPromptToAll,
            )
        }

        // ═══════════════════════════════════════════════════════════════════
        // GENERATION GROUP
        // ═══════════════════════════════════════════════════════════════════
        SettingsGroup(title = stringResource(R.string.assistant_page_group_generation)) {
            // Temperature
            val tempLabel = if (assistant.temperature != null) {
                val temp = assistant.temperature
                val levelRes = when (temp) {
                    in 0.0f..0.3f -> R.string.assistant_page_strict
                    in 0.3f..1.0f -> R.string.assistant_page_balanced
                    in 1.0f..1.5f -> R.string.assistant_page_creative
                    else -> R.string.assistant_page_chaotic
                }
                stringResource(
                    R.string.assistant_page_temperature_level_value_format,
                    stringResource(levelRes),
                    temp.toFixed(2)
                )
            } else {
                stringResource(R.string.assistant_page_thinking_budget_default)
            }
            
            SettingGroupItem(
                title = stringResource(R.string.assistant_page_temperature),
                subtitle = tempLabel,
                trailing = {
                    HapticSwitch(
                        checked = assistant.temperature != null,
                        onCheckedChange = { enabled ->
                            onUpdate(assistant.copy(temperature = if (enabled) 1.0f else null))
                        }
                    )
                }
            )
            
            // Temperature Slider
            AnimatedVisibility(
                visible = assistant.temperature != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Surface(
                    color = if (LocalDarkMode.current) 
                        MaterialTheme.colorScheme.surfaceContainerLow 
                    else 
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Slider(
                            value = assistant.temperature ?: 1.0f,
                            onValueChange = { onUpdate(assistant.copy(temperature = it.toFixed(2).toFloatOrNull() ?: 0.6f)) },
                            valueRange = 0f..2f,
                            steps = 39,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val currentTemp = assistant.temperature ?: 1.0f
                            val tagType = when (currentTemp) {
                                in 0.0f..0.3f -> TagType.INFO
                                in 0.3f..1.0f -> TagType.SUCCESS
                                in 1.0f..1.5f -> TagType.WARNING
                                else -> TagType.ERROR
                            }
                            Tag(type = tagType) {
                                Text(when (currentTemp) {
                                    in 0.0f..0.3f -> stringResource(R.string.assistant_page_strict)
                                    in 0.3f..1.0f -> stringResource(R.string.assistant_page_balanced)
                                    in 1.0f..1.5f -> stringResource(R.string.assistant_page_creative)
                                    else -> stringResource(R.string.assistant_page_chaotic)
                                })
                            }
                        }
                    }
                }
            }

            // Top-P
            SettingGroupItem(
                title = stringResource(R.string.assistant_page_top_p),
                subtitle = if (assistant.topP != null) "Enabled (${assistant.topP})" else "Default",
                trailing = {
                    HapticSwitch(
                        checked = assistant.topP != null,
                        onCheckedChange = { enabled ->
                            onUpdate(assistant.copy(topP = if (enabled) 0.9f else null))
                        }
                    )
                }
            )

            // Top-P Slider
            AnimatedVisibility(
                visible = assistant.topP != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Surface(
                    color = if (LocalDarkMode.current) 
                        MaterialTheme.colorScheme.surfaceContainerLow 
                    else 
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Slider(
                            value = assistant.topP ?: 0.9f,
                            onValueChange = { onUpdate(assistant.copy(topP = it.toFixed(2).toFloatOrNull() ?: 0.9f)) },
                            valueRange = 0f..1f,
                            steps = 19,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

        }

        // ═══════════════════════════════════════════════════════════════════
        // OUTPUT GROUP
        // ═══════════════════════════════════════════════════════════════════
        SettingsGroup(title = stringResource(R.string.assistant_page_group_output)) {
            // Stream Output
            SettingGroupItem(
                title = stringResource(R.string.assistant_page_stream_output),
                subtitle = stringResource(R.string.assistant_page_stream_output_desc),
                trailing = {
                    HapticSwitch(
                        checked = assistant.streamOutput,
                        onCheckedChange = { onUpdate(assistant.copy(streamOutput = it)) }
                    )
                }
            )

            // Thinking Budget
            SettingGroupItem(
                title = stringResource(R.string.assistant_page_thinking_budget),
                subtitle = reasoningLevelLabel(assistant.reasoningLevel),
                trailing = {
                    ReasoningButton(
                        reasoningLevel = assistant.reasoningLevel,
                        onUpdateReasoningLevel = { level ->
                            onUpdate(assistant.copy(reasoningLevel = level))
                        }
                    )
                }
            )

            // Max Tokens
            SettingGroupItem(
                title = stringResource(R.string.assistant_page_max_tokens),
                subtitle = if (assistant.maxTokens != null) 
                    stringResource(R.string.assistant_page_max_tokens_limit, assistant.maxTokens) 
                else 
                    stringResource(R.string.assistant_page_max_tokens_no_token_limit),
                trailing = {
                    OutlinedTextField(
                        value = assistant.maxTokens?.toString() ?: "",
                        onValueChange = { text ->
                            val tokens = if (text.isBlank()) null else text.toIntOrNull()?.takeIf { it > 0 }
                            onUpdate(assistant.copy(maxTokens = tokens))
                        },
                        modifier = Modifier.width(100.dp),
                        placeholder = { Text(stringResource(R.string.auto)) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                }
            )
        }
    }
}

@Composable
private fun AssistantPromptEditorSheet(
    prompt: String,
    defaultPrompt: String,
    promptVariablesText: String,
    onDismiss: () -> Unit,
    onPromptChange: (String) -> Unit,
    onApplyToGlobal: (String) -> Unit,
) {
    var showApplyConfirm by remember { mutableStateOf(false) }
    var editorPrompt by remember { mutableStateOf(prompt.ifBlank { defaultPrompt }) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AssistantPromptEditorForm(
                editorPrompt = editorPrompt,
                defaultPrompt = defaultPrompt,
                promptVariablesText = promptVariablesText,
                onPromptChange = {
                    editorPrompt = it
                    onPromptChange(it)
                },
                onApplyToGlobal = { showApplyConfirm = true },
            )
        }
    }

    if (showApplyConfirm) {
        AlertDialog(
            onDismissRequest = { showApplyConfirm = false },
            title = { Text(stringResource(R.string.assistant_page_apply_prompt_global_confirm_title)) },
            text = { Text(stringResource(R.string.assistant_page_apply_prompt_global_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onApplyToGlobal(editorPrompt)
                        showApplyConfirm = false
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showApplyConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun AssistantMemoryPromptEditorSheet(
    consolidationPrompt: String,
    memorySummaryPrompt: String,
    onDismiss: () -> Unit,
    onConsolidationPromptChange: (String) -> Unit,
    onMemorySummaryPromptChange: (String) -> Unit,
    onApplyConsolidationPromptToAll: (String) -> Unit,
    onApplyMemorySummaryPromptToAll: (String) -> Unit,
) {
    var selectedTab by remember { mutableStateOf(0) }
    var applyTargetTab by remember { mutableStateOf<Int?>(null) }
    var editorConsolidationPrompt by remember {
        mutableStateOf(consolidationPrompt.ifBlank { DEFAULT_MEMORY_CONSOLIDATION_PROMPT })
    }
    var editorMemorySummaryPrompt by remember {
        mutableStateOf(memorySummaryPrompt.ifBlank { DEFAULT_MEMORY_SUMMARY_PROMPT })
    }
    val haptics = rememberPremiumHaptics()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = 0.5f,
                        stiffness = 400f,
                    )
                )
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SecondaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = {
                        haptics.perform(HapticPattern.Pop)
                        selectedTab = 0
                    },
                    text = { Text(stringResource(R.string.assistant_page_consolidation_prompt_tab)) },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = {
                        haptics.perform(HapticPattern.Pop)
                        selectedTab = 1
                    },
                    text = { Text(stringResource(R.string.assistant_page_memory_summary_prompt_tab)) },
                )
            }

            when (selectedTab) {
                0 -> AssistantPromptEditorForm(
                    editorPrompt = editorConsolidationPrompt,
                    defaultPrompt = DEFAULT_MEMORY_CONSOLIDATION_PROMPT,
                    promptVariablesText = stringResource(R.string.assistant_page_consolidation_prompt_vars),
                    onPromptChange = {
                        editorConsolidationPrompt = it
                        onConsolidationPromptChange(it)
                    },
                    onApplyToGlobal = { applyTargetTab = 0 },
                )

                else -> AssistantPromptEditorForm(
                    editorPrompt = editorMemorySummaryPrompt,
                    defaultPrompt = DEFAULT_MEMORY_SUMMARY_PROMPT,
                    promptVariablesText = stringResource(R.string.assistant_page_memory_summary_prompt_vars),
                    onPromptChange = {
                        editorMemorySummaryPrompt = it
                        onMemorySummaryPromptChange(it)
                    },
                    onApplyToGlobal = { applyTargetTab = 1 },
                )
            }
        }
    }

    if (applyTargetTab != null) {
        AlertDialog(
            onDismissRequest = { applyTargetTab = null },
            title = { Text(stringResource(R.string.assistant_page_apply_prompt_global_confirm_title)) },
            text = { Text(stringResource(R.string.assistant_page_apply_prompt_global_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (applyTargetTab == 0) {
                            onApplyConsolidationPromptToAll(editorConsolidationPrompt)
                        } else {
                            onApplyMemorySummaryPromptToAll(editorMemorySummaryPrompt)
                        }
                        applyTargetTab = null
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { applyTargetTab = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun AssistantPromptEditorForm(
    editorPrompt: String,
    defaultPrompt: String,
    promptVariablesText: String,
    onPromptChange: (String) -> Unit,
    onApplyToGlobal: () -> Unit,
) {
    FormItem(
        label = {
            Text(stringResource(R.string.setting_model_page_prompt))
        },
        description = {
            Text(promptVariablesText)
        }
    ) {
        OutlinedTextField(
            value = editorPrompt,
            onValueChange = onPromptChange,
            modifier = Modifier.fillMaxWidth(),
            maxLines = 10,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start
        ) {
            TextButton(onClick = { onPromptChange(defaultPrompt) }) {
                Text(stringResource(R.string.setting_model_page_reset_to_default))
            }
            TextButton(onClick = onApplyToGlobal) {
                Text(stringResource(R.string.assistant_page_apply_to_global))
            }
        }
    }
}

@Composable
private fun reasoningLevelLabel(level: ReasoningLevel): String = when (level) {
    ReasoningLevel.OFF -> stringResource(R.string.reasoning_off)
    ReasoningLevel.AUTO -> stringResource(R.string.reasoning_auto)
    ReasoningLevel.LOW -> stringResource(R.string.reasoning_light)
    ReasoningLevel.MEDIUM -> stringResource(R.string.reasoning_medium)
    ReasoningLevel.HIGH -> stringResource(R.string.reasoning_heavy)
    ReasoningLevel.XHIGH -> stringResource(R.string.reasoning_xhigh)
    ReasoningLevel.MAX -> stringResource(R.string.reasoning_max)
}
