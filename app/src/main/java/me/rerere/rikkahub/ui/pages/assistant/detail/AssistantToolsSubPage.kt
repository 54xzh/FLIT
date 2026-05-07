package me.rerere.rikkahub.ui.pages.assistant.detail

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import me.rerere.rikkahub.ui.components.ui.HapticSwitch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ai.McpPickerButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.setting.components.SettingsGroup
import me.rerere.rikkahub.ui.pages.setting.components.SettingGroupItem
import kotlin.uuid.Uuid

/**
 * Map LocalToolOption to the actual tool name used in Tool.description.
 * Used as the key for localToolCustomPrompts.
 */
private fun LocalToolOption.toolName(): String? = when (this) {
    LocalToolOption.JavascriptEngine -> "eval_javascript"
    LocalToolOption.AskUser -> "ask_user"
    LocalToolOption.DeviceControl -> null // multiple tools, not supported
    LocalToolOption.WorkspaceFiles -> null // multiple tools, not supported
    LocalToolOption.LorebooksEditor -> null
    LocalToolOption.ScheduledTaskManager -> null // multiple tools
    LocalToolOption.MemorySearch -> null
    LocalToolOption.ChatSearch -> null
    LocalToolOption.PythonEngine -> null
}

@Composable
private fun CustomPromptDialog(
    toolTitle: String,
    currentValue: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember(currentValue) { mutableStateOf(currentValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom Prompt") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Additional instructions for $toolTitle") },
                placeholder = { Text("e.g. Only use this tool when absolutely necessary") },
                modifier = Modifier.fillMaxSize(),
                maxLines = 6
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Tools tab - Local tools and MCP settings.
 * Designed with cohesive SettingsGroup pattern.
 */
@Composable
fun AssistantToolsSubPage(
    assistant: Assistant,
    onUpdate: (Assistant) -> Unit,
    vm: AssistantDetailVM,
    mcpServerConfigs: List<McpServerConfig>
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navController = LocalNavController.current

    var expandedFolderIds by remember { mutableStateOf<Set<Uuid>>(emptySet()) }
    var ungroupedExpanded by remember { mutableStateOf(false) }
    var editingToolName by remember { mutableStateOf<String?>(null) }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        // ═══════════════════════════════════════════════════════════════════
        // LOCAL TOOLS GROUP
        // ═══════════════════════════════════════════════════════════════════
        val deviceControlPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            val newLocalTools = assistant.localTools + LocalToolOption.DeviceControl
            onUpdate(assistant.copy(localTools = newLocalTools))
        }
        
        SettingsGroup(title = stringResource(R.string.assistant_page_tab_local_tools)) {
            // JavaScript Engine
            SettingGroupItem(
                title = stringResource(R.string.assistant_page_local_tools_javascript_engine_title),
                subtitle = stringResource(R.string.assistant_page_local_tools_javascript_engine_desc),
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (assistant.localTools.contains(LocalToolOption.JavascriptEngine)) {
                            FilledTonalIconButton(onClick = { editingToolName = "eval_javascript" }) {
                                Icon(Icons.Rounded.Edit, contentDescription = "Edit custom prompt", modifier = Modifier.size(18.dp))
                            }
                        }
                        HapticSwitch(
                            checked = assistant.localTools.contains(LocalToolOption.JavascriptEngine),
                            onCheckedChange = { enabled ->
                                val newLocalTools = if (enabled) {
                                    assistant.localTools + LocalToolOption.JavascriptEngine
                                } else {
                                    assistant.localTools - LocalToolOption.JavascriptEngine
                                }
                                onUpdate(assistant.copy(localTools = newLocalTools))
                            }
                        )
                    }
                }
            )

            // Python Engine
            SettingGroupItem(
                title = stringResource(R.string.assistant_page_local_tools_python_engine_title),
                subtitle = stringResource(R.string.assistant_page_local_tools_python_engine_desc),
                trailing = {
                    HapticSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.PythonEngine),
                        onCheckedChange = { enabled ->
                            val newLocalTools = if (enabled) {
                                assistant.localTools + LocalToolOption.PythonEngine
                            } else {
                                assistant.localTools - LocalToolOption.PythonEngine
                            }
                            onUpdate(assistant.copy(localTools = newLocalTools))
                        }
                    )
                }
            )

            // Workspace Files
            SettingGroupItem(
                title = stringResource(R.string.assistant_page_local_tools_workspace_files_title),
                subtitle = stringResource(R.string.assistant_page_local_tools_workspace_files_desc),
                trailing = {
                    HapticSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.WorkspaceFiles),
                        onCheckedChange = { enabled ->
                            val newLocalTools = if (enabled) {
                                assistant.localTools + LocalToolOption.WorkspaceFiles
                            } else {
                                assistant.localTools - LocalToolOption.WorkspaceFiles
                            }
                            onUpdate(assistant.copy(localTools = newLocalTools))
                        }
                    )
                }
            )

            // Memory Search
            SettingGroupItem(
                title = stringResource(R.string.assistant_page_local_tools_memory_search_title),
                subtitle = stringResource(R.string.assistant_page_local_tools_memory_search_desc),
                trailing = {
                    HapticSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.MemorySearch),
                        onCheckedChange = { enabled ->
                            val newLocalTools = if (enabled) {
                                assistant.localTools + LocalToolOption.MemorySearch
                            } else {
                                assistant.localTools - LocalToolOption.MemorySearch
                            }
                            onUpdate(assistant.copy(localTools = newLocalTools))
                        }
                    )
                }
            )

            // Chat Search
            SettingGroupItem(
                title = stringResource(R.string.assistant_page_local_tools_chat_search_title),
                subtitle = stringResource(R.string.assistant_page_local_tools_chat_search_desc),
                trailing = {
                    HapticSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.ChatSearch),
                        onCheckedChange = { enabled ->
                            val newLocalTools = if (enabled) {
                                assistant.localTools + LocalToolOption.ChatSearch
                            } else {
                                assistant.localTools - LocalToolOption.ChatSearch
                            }
                            onUpdate(assistant.copy(localTools = newLocalTools))
                        }
                    )
                }
            )

            // Lorebooks Editor
            SettingGroupItem(
                title = stringResource(R.string.assistant_page_local_tools_lorebooks_editor_title),
                subtitle = stringResource(R.string.assistant_page_local_tools_lorebooks_editor_desc),
                trailing = {
                    HapticSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.LorebooksEditor),
                        onCheckedChange = { enabled ->
                            val newLocalTools = if (enabled) {
                                assistant.localTools + LocalToolOption.LorebooksEditor
                            } else {
                                assistant.localTools - LocalToolOption.LorebooksEditor
                            }
                            onUpdate(assistant.copy(localTools = newLocalTools))
                        }
                    )
                }
            )
             
            // Scheduled Task Manager
            SettingGroupItem(
                title = stringResource(R.string.assistant_page_local_tools_scheduled_task_manager_title),
                subtitle = stringResource(R.string.assistant_page_local_tools_scheduled_task_manager_desc),
                trailing = {
                    HapticSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.ScheduledTaskManager),
                        onCheckedChange = { enabled ->
                            val newLocalTools = if (enabled) {
                                assistant.localTools + LocalToolOption.ScheduledTaskManager
                            } else {
                                assistant.localTools - LocalToolOption.ScheduledTaskManager
                            }
                            onUpdate(assistant.copy(localTools = newLocalTools))
                        }
                    )
                }
            )

            // Ask User
            SettingGroupItem(
                title = stringResource(R.string.assistant_page_local_tools_ask_user_title),
                subtitle = stringResource(R.string.assistant_page_local_tools_ask_user_desc),
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (assistant.localTools.contains(LocalToolOption.AskUser)) {
                            FilledTonalIconButton(onClick = { editingToolName = "ask_user" }) {
                                Icon(Icons.Rounded.Edit, contentDescription = "Edit custom prompt", modifier = Modifier.size(18.dp))
                            }
                        }
                        HapticSwitch(
                            checked = assistant.localTools.contains(LocalToolOption.AskUser),
                            onCheckedChange = { enabled ->
                                val newLocalTools = if (enabled) {
                                    assistant.localTools + LocalToolOption.AskUser
                                } else {
                                    assistant.localTools - LocalToolOption.AskUser
                                }
                                onUpdate(assistant.copy(localTools = newLocalTools))
                            }
                        )
                    }
                }
            )

            // Device Control
            SettingGroupItem(
                title = stringResource(R.string.assistant_page_local_tools_device_control_title),
                subtitle = stringResource(R.string.assistant_page_local_tools_device_control_subtitle),
                trailing = {
                    HapticSwitch(
                        checked = assistant.localTools.contains(LocalToolOption.DeviceControl),
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                val permissions = mutableListOf<String>()
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                permissions.add(Manifest.permission.CAMERA)
                                
                                if (permissions.isNotEmpty()) {
                                    deviceControlPermissionLauncher.launch(permissions.toTypedArray())
                                } else {
                                    val newLocalTools = assistant.localTools + LocalToolOption.DeviceControl
                                    onUpdate(assistant.copy(localTools = newLocalTools))
                                }
                            } else {
                                val newLocalTools = assistant.localTools - LocalToolOption.DeviceControl
                                onUpdate(assistant.copy(localTools = newLocalTools))
                            }
                        }
                    )
                }
            )
        }

        // ═══════════════════════════════════════════════════════════════════
        // SKILLS GROUP
        // ═══════════════════════════════════════════════════════════════════
        SettingsGroup(title = stringResource(R.string.skills_group_title)) {
            SettingGroupItem(
                title = stringResource(R.string.skills_manage_title),
                subtitle = stringResource(R.string.skills_manage_desc),
                onClick = { navController.navigate(Screen.SettingSkills) }
            )

            if (settings.skills.isEmpty()) {
                SettingGroupItem(
                    title = stringResource(R.string.skills_page_empty),
                    subtitle = stringResource(R.string.skills_page_empty_hint),
                    onClick = { navController.navigate(Screen.SettingSkills) }
                )
            } else {
                val foldersById = settings.skillFolders.associateBy { it.id }
                val ungroupedSkills = settings.skills.filter { skill ->
                    skill.folderId == null || skill.folderId !in foldersById
                }

                settings.skillFolders.forEach { folder ->
                    val skillsInFolder = settings.skills.filter { it.folderId == folder.id }
                    if (skillsInFolder.isEmpty()) return@forEach

                    val folderSkillIds = skillsInFolder.map { it.id }.toSet()
                    val enabledCount = skillsInFolder.count { assistant.enabledSkillIds.contains(it.id) }
                    val folderEnabled = enabledCount > 0

                    Column {
                        val expanded = expandedFolderIds.contains(folder.id)
                        val arrowRotation by animateFloatAsState(
                            targetValue = if (expanded) 180f else 0f,
                            animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
                            label = "skills_folder_arrow_rotation"
                        )

                        SettingGroupItem(
                            title = folder.name.ifBlank { stringResource(R.string.skills_folder_unnamed) },
                            subtitle = stringResource(R.string.skills_folder_enabled_count, enabledCount, skillsInFolder.size),
                            icon = { Icon(Icons.Rounded.Folder, contentDescription = null) },
                            onClick = {
                                expandedFolderIds = if (expandedFolderIds.contains(folder.id)) {
                                    expandedFolderIds - folder.id
                                } else {
                                    expandedFolderIds + folder.id
                                }
                            },
                            trailing = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.KeyboardArrowDown,
                                        contentDescription = stringResource(if (expanded) R.string.a11y_collapse else R.string.a11y_expand),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .size(24.dp)
                                            .graphicsLayer {
                                                rotationZ = arrowRotation
                                            },
                                    )
                                    HapticSwitch(
                                        checked = folderEnabled,
                                        onCheckedChange = { enabled ->
                                            val newIds = if (enabled) {
                                                assistant.enabledSkillIds + folderSkillIds
                                            } else {
                                                assistant.enabledSkillIds - folderSkillIds
                                            }
                                            onUpdate(assistant.copy(enabledSkillIds = newIds))
                                        }
                                    )
                                }
                            }
                        )

                        AnimatedVisibility(
                            visible = expanded,
                            enter = expandVertically(
                                animationSpec = spring(dampingRatio = 0.7f, stiffness = 300f)
                            ) + fadeIn(
                                animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f)
                            ),
                            exit = shrinkVertically(
                                animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f)
                            ) + fadeOut(),
                        ) {
                            Column(
                                modifier = Modifier.padding(top = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                skillsInFolder.forEach { skill ->
                                    val isEnabled = assistant.enabledSkillIds.contains(skill.id)
                                    SettingGroupItem(
                                        title = skill.name.ifBlank { stringResource(R.string.skills_unnamed) },
                                        subtitle = skill.description.ifBlank { stringResource(R.string.skills_no_description) },
                                        trailing = {
                                            HapticSwitch(
                                                checked = isEnabled,
                                                onCheckedChange = { enabled ->
                                                    val newIds = if (enabled) {
                                                        assistant.enabledSkillIds + skill.id
                                                    } else {
                                                        assistant.enabledSkillIds - skill.id
                                                    }
                                                    onUpdate(assistant.copy(enabledSkillIds = newIds))
                                                }
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                if (ungroupedSkills.isNotEmpty()) {
                    val folderSkillIds = ungroupedSkills.map { it.id }.toSet()
                    val enabledCount = ungroupedSkills.count { assistant.enabledSkillIds.contains(it.id) }
                    val folderEnabled = enabledCount > 0

                    Column {
                        val expanded = ungroupedExpanded
                        val arrowRotation by animateFloatAsState(
                            targetValue = if (expanded) 180f else 0f,
                            animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
                            label = "skills_folder_ungrouped_arrow_rotation"
                        )

                        SettingGroupItem(
                            title = stringResource(R.string.skills_folder_ungrouped),
                            subtitle = stringResource(R.string.skills_folder_enabled_count, enabledCount, ungroupedSkills.size),
                            icon = { Icon(Icons.Rounded.Folder, contentDescription = null) },
                            onClick = { ungroupedExpanded = !ungroupedExpanded },
                            trailing = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.KeyboardArrowDown,
                                        contentDescription = stringResource(if (expanded) R.string.a11y_collapse else R.string.a11y_expand),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .size(24.dp)
                                            .graphicsLayer {
                                                rotationZ = arrowRotation
                                            },
                                    )
                                    HapticSwitch(
                                        checked = folderEnabled,
                                        onCheckedChange = { enabled ->
                                            val newIds = if (enabled) {
                                                assistant.enabledSkillIds + folderSkillIds
                                            } else {
                                                assistant.enabledSkillIds - folderSkillIds
                                            }
                                            onUpdate(assistant.copy(enabledSkillIds = newIds))
                                        }
                                    )
                                }
                            }
                        )

                        AnimatedVisibility(
                            visible = expanded,
                            enter = expandVertically(
                                animationSpec = spring(dampingRatio = 0.7f, stiffness = 300f)
                            ) + fadeIn(
                                animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f)
                            ),
                            exit = shrinkVertically(
                                animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f)
                            ) + fadeOut(),
                        ) {
                            Column(
                                modifier = Modifier.padding(top = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                ungroupedSkills.forEach { skill ->
                                    val isEnabled = assistant.enabledSkillIds.contains(skill.id)
                                    SettingGroupItem(
                                        title = skill.name.ifBlank { stringResource(R.string.skills_unnamed) },
                                        subtitle = skill.description.ifBlank { stringResource(R.string.skills_no_description) },
                                        trailing = {
                                            HapticSwitch(
                                                checked = isEnabled,
                                                onCheckedChange = { enabled ->
                                                    val newIds = if (enabled) {
                                                        assistant.enabledSkillIds + skill.id
                                                    } else {
                                                        assistant.enabledSkillIds - skill.id
                                                    }
                                                    onUpdate(assistant.copy(enabledSkillIds = newIds))
                                                }
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽
        // MCP GROUP (only show if servers configured)
        // 汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽汽
        if (mcpServerConfigs.isNotEmpty()) {
            SettingsGroup(title = stringResource(R.string.assistant_page_tab_mcp)) {
            SettingGroupItem(
                    title = stringResource(R.string.mcp_picker_title),
                    subtitle = stringResource(R.string.assistant_page_mcp_servers_desc),
                    trailing = {
                        McpPickerButton(
                            assistant = assistant,
                            servers = mcpServerConfigs,
                            mcpManager = org.koin.compose.koinInject(),
                            onUpdateAssistant = onUpdate
                        )
                    }
                )
            }
        }
    }

    // Custom prompt edit dialog
    editingToolName?.let { toolName ->
        val currentPrompt = assistant.localToolCustomPrompts[toolName].orEmpty()
        CustomPromptDialog(
            toolTitle = toolName,
            currentValue = currentPrompt,
            onDismiss = { editingToolName = null },
            onSave = { newPrompt ->
                val updatedPrompts = if (newPrompt.isBlank()) {
                    assistant.localToolCustomPrompts - toolName
                } else {
                    assistant.localToolCustomPrompts + (toolName to newPrompt)
                }
                onUpdate(assistant.copy(localToolCustomPrompts = updatedPrompts))
                editingToolName = null
            }
        )
    }
}
