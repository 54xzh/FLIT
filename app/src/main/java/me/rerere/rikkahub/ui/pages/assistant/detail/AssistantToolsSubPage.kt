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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import me.rerere.rikkahub.data.model.AssistantSearchMode
import me.rerere.rikkahub.ui.components.ai.McpPickerButton
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.setting.components.SettingsGroup
import me.rerere.rikkahub.ui.pages.setting.components.SettingGroupItem
import me.rerere.search.SearchServiceOptions
import kotlin.uuid.Uuid

/**
 * Tools & Search tab - Combined search, local tools, and MCP settings.
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
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        // ═══════════════════════════════════════════════════════════════════
        // SEARCH GROUP
        // ═══════════════════════════════════════════════════════════════════
        SettingsGroup(title = stringResource(R.string.search_ability_search)) {
            // Build options list for Select
            val currentSearchMode = assistant.searchMode
            
            // Create sealed class options for the selector
            data class SearchOption(val mode: AssistantSearchMode, val displayName: String)
            
            val offText = stringResource(R.string.off)
            val searchOptions = buildList {
                add(SearchOption(AssistantSearchMode.Off, offText))
                settings.searchServices.forEachIndexed { index, service ->
                    val name = SearchServiceOptions.TYPES[service::class] ?: "Provider ${index + 1}"
                    add(SearchOption(AssistantSearchMode.Provider(index), name))
                }
            }
            
            val selectedOption = searchOptions.find { option ->
                when (val mode = option.mode) {
                    is AssistantSearchMode.Off -> currentSearchMode is AssistantSearchMode.Off || currentSearchMode is AssistantSearchMode.BuiltIn
                    is AssistantSearchMode.BuiltIn -> currentSearchMode is AssistantSearchMode.BuiltIn
                    is AssistantSearchMode.Provider -> currentSearchMode is AssistantSearchMode.Provider && currentSearchMode.index == mode.index
                }
            } ?: searchOptions.first()
            
            SettingGroupItem(
                title = stringResource(R.string.assistant_page_search_provider_title),
                subtitle = selectedOption.displayName,
                trailing = {
                    Select(
                        options = searchOptions,
                        selectedOption = selectedOption,
                        onOptionSelected = { option ->
                            onUpdate(assistant.copy(searchMode = option.mode))
                        },
                        optionToString = { it.displayName },
                        modifier = Modifier.width(150.dp)
                    )
                }
            )
            
            // Prefer Built-in Search
            SettingGroupItem(
                title = stringResource(R.string.assistant_page_prefer_built_in_search),
                subtitle = stringResource(R.string.assistant_page_prefer_built_in_search_desc),
                trailing = {
                    HapticSwitch(
                        checked = assistant.preferBuiltInSearch,
                        onCheckedChange = { onUpdate(assistant.copy(preferBuiltInSearch = it)) }
                    )
                }
            )
        }

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
}
