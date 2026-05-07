package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.ToolSystemPromptDefinition
import me.rerere.rikkahub.data.ai.tools.ToolSystemPromptGroup
import me.rerere.rikkahub.data.ai.tools.ToolSystemPromptRegistry
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.pages.setting.components.SettingGroupItem
import me.rerere.rikkahub.ui.pages.setting.components.SettingsGroup
import me.rerere.rikkahub.ui.theme.AppShapes
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingCustomToolPromptsPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var editingDefinition by remember { mutableStateOf<ToolSystemPromptDefinition?>(null) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val lazyListState = rememberLazyListState()
    val groupedDefinitions = remember {
        ToolSystemPromptRegistry.definitions.groupBy { it.group }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            OneUITopAppBar(
                title = stringResource(R.string.setting_tool_prompts_title),
                scrollBehavior = scrollBehavior,
                navigationIcon = { BackButton() },
            )
        }
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = lazyListState,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            groupedDefinitions.forEach { (group, definitions) ->
                item(key = group.name) {
                    SettingsGroup(title = group.titleText()) {
                        definitions.forEach { definition ->
                            val customized = settings.customToolSystemPrompts.containsKey(definition.toolName)
                            SettingGroupItem(
                                title = definition.toolName,
                                subtitle = if (customized) {
                                    stringResource(R.string.setting_tool_prompts_status_custom)
                                } else {
                                    stringResource(R.string.setting_tool_prompts_status_default)
                                },
                                icon = {
                                    Icon(
                                        imageVector = definition.group.iconVector(),
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                    )
                                },
                                onClick = { editingDefinition = definition },
                            )
                        }
                    }
                }
            }
        }
    }

    editingDefinition?.let { definition ->
        ToolPromptEditorSheet(
            definition = definition,
            savedTemplate = settings.customToolSystemPrompts[definition.toolName],
            onDismiss = { editingDefinition = null },
            onSave = { text ->
                vm.updateSettings { current ->
                    current.copy(
                        customToolSystemPrompts = current.customToolSystemPrompts
                            .updatedToolPrompt(definition, text)
                    )
                }
                editingDefinition = null
            },
            onReset = {
                vm.updateSettings { current ->
                    current.copy(
                        customToolSystemPrompts = current.customToolSystemPrompts
                            .minus(definition.toolName)
                    )
                }
            },
        )
    }
}

private fun ToolSystemPromptGroup.iconVector(): ImageVector {
    return when (this) {
        ToolSystemPromptGroup.Search -> Icons.Rounded.Search
        ToolSystemPromptGroup.Memory -> Icons.Rounded.Memory
        ToolSystemPromptGroup.Local -> Icons.Rounded.PhoneAndroid
        ToolSystemPromptGroup.Skills -> Icons.Rounded.Extension
        ToolSystemPromptGroup.Workspace -> Icons.Rounded.Folder
        ToolSystemPromptGroup.ScheduledTasks -> Icons.Rounded.History
        ToolSystemPromptGroup.Lorebooks -> Icons.Rounded.Bookmark
        ToolSystemPromptGroup.UserInteraction -> Icons.AutoMirrored.Rounded.Chat
    }
}

@Composable
private fun ToolSystemPromptGroup.titleText(): String {
    return when (this) {
        ToolSystemPromptGroup.Search -> stringResource(R.string.setting_tool_prompts_group_search)
        ToolSystemPromptGroup.Memory -> stringResource(R.string.setting_tool_prompts_group_memory)
        ToolSystemPromptGroup.Local -> stringResource(R.string.setting_tool_prompts_group_local)
        ToolSystemPromptGroup.Skills -> stringResource(R.string.setting_tool_prompts_group_skills)
        ToolSystemPromptGroup.Workspace -> stringResource(R.string.setting_tool_prompts_group_workspace)
        ToolSystemPromptGroup.ScheduledTasks -> stringResource(R.string.setting_tool_prompts_group_scheduled_tasks)
        ToolSystemPromptGroup.Lorebooks -> stringResource(R.string.setting_tool_prompts_group_lorebooks)
        ToolSystemPromptGroup.UserInteraction -> stringResource(R.string.setting_tool_prompts_group_user_interaction)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolPromptEditorSheet(
    definition: ToolSystemPromptDefinition,
    savedTemplate: String?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onReset: () -> Unit,
) {
    val haptics = rememberPremiumHaptics()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val initialTemplate = savedTemplate ?: definition.defaultTemplate
    var draft by remember(definition.toolName, initialTemplate) {
        mutableStateOf(TextFieldValue(initialTemplate))
    }
    var showResetConfirm by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = AppShapes.BottomSheet,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = definition.toolName,
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = if (savedTemplate != null) {
                        stringResource(R.string.setting_tool_prompts_status_custom)
                    } else {
                        stringResource(R.string.setting_tool_prompts_status_default)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (definition.affectedToolNames.size > 1) {
                Text(
                    text = stringResource(
                        R.string.setting_tool_prompts_affected_tools,
                        definition.affectedToolNames.joinToString(", "),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (definition.variables.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.setting_tool_prompts_variables),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        definition.variables.forEach { variable ->
                            val token = "{{${variable.key}}}"
                            AssistChip(
                                onClick = {
                                    haptics.perform(HapticPattern.Pop)
                                    draft = draft.insertAtCursor(token)
                                },
                                label = { Text(token) },
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 260.dp),
                label = { Text(stringResource(R.string.setting_tool_prompts_prompt_label)) },
                minLines = 10,
                maxLines = 24,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = { showResetConfirm = true },
                    enabled = savedTemplate != null || draft.text.normalizePrompt() != definition.defaultTemplate.normalizePrompt(),
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.setting_tool_prompts_reset_default))
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.cancel))
                }
                Button(onClick = { onSave(draft.text) }) {
                    Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.save))
                }
            }
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(R.string.setting_tool_prompts_reset_confirm_title)) },
            text = { Text(stringResource(R.string.setting_tool_prompts_reset_confirm_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptics.perform(HapticPattern.Pop)
                        onReset()
                        draft = TextFieldValue(definition.defaultTemplate)
                        showResetConfirm = false
                    }
                ) {
                    Text(stringResource(R.string.setting_tool_prompts_reset_default))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

private fun Map<String, String>.updatedToolPrompt(
    definition: ToolSystemPromptDefinition,
    text: String,
): Map<String, String> {
    val normalizedText = text.normalizePrompt()
    val normalizedDefault = definition.defaultTemplate.normalizePrompt()
    return toMutableMap().apply {
        if (normalizedText == normalizedDefault) {
            remove(definition.toolName)
        } else {
            put(definition.toolName, normalizedText)
        }
    }
}

private fun String.normalizePrompt(): String {
    return replace("\r\n", "\n").trimEnd()
}

private fun TextFieldValue.insertAtCursor(textToInsert: String): TextFieldValue {
    val start = selection.start.coerceIn(0, text.length)
    val end = selection.end.coerceIn(0, text.length)
    val rangeStart = minOf(start, end)
    val rangeEnd = maxOf(start, end)
    val newText = text.replaceRange(rangeStart, rangeEnd, textToInsert)
    val cursor = rangeStart + textToInsert.length
    return copy(
        text = newText,
        selection = TextRange(cursor),
    )
}
