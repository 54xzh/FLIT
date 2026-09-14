package me.rerere.rikkahub.ui.pages.project

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.DebouncedTextField
import me.rerere.rikkahub.ui.components.ui.HapticSwitch
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.pages.setting.components.SettingGroupItem
import me.rerere.rikkahub.ui.pages.setting.components.SettingsGroup
import me.rerere.rikkahub.ui.theme.AppShapes
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun ProjectDetailPage(
    id: String,
) {
    val vm: ProjectDetailVM = koinViewModel(
        parameters = { parametersOf(id) }
    )
    val settings by vm.settings.collectAsStateWithLifecycle()
    val project by vm.project.collectAsStateWithLifecycle()
    val currentProject = project
    val navController = LocalNavController.current
    val haptics = rememberPremiumHaptics()

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showPromptDialog by remember { mutableStateOf(false) }
    var showIconPickerSheet by remember { mutableStateOf(false) }

    val defaultProjectName = stringResource(R.string.project_name)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = currentProject?.name?.ifBlank { defaultProjectName } ?: defaultProjectName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(
                        onClick = {
                            haptics.perform(HapticPattern.Thud)
                            showDeleteDialog = true
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = stringResource(R.string.project_delete),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            if (currentProject == null) {
                Text(
                    text = "Project not found ($id)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                return@Column
            }

            // 顶部大图标区域（参考助手资料页）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val iconInteractionSource = remember { MutableInteractionSource() }
                val isIconPressed by iconInteractionSource.collectIsPressedAsState()
                val iconScale by animateFloatAsState(
                    targetValue = if (isIconPressed) 0.85f else 1f,
                    animationSpec = spring(dampingRatio = 0.5f, stiffness = 400f),
                    label = "project_top_icon_scale",
                )

                Surface(
                    shape = AppShapes.CardLarge,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier
                        .size(80.dp)
                        .graphicsLayer {
                            scaleX = iconScale
                            scaleY = iconScale
                        }
                        .clip(AppShapes.CardLarge)
                        .clickable(
                            interactionSource = iconInteractionSource,
                            indication = null,
                            onClick = {
                                haptics.perform(HapticPattern.Pop)
                                showIconPickerSheet = true
                            }
                        )
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            imageVector = ProjectIcons.getIcon(currentProject.icon),
                            contentDescription = stringResource(R.string.project_icon),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.project_tap_to_change_icon),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 基础设置
            SettingsGroup(title = stringResource(R.string.project_basic_settings)) {
                SettingGroupItem(
                    title = stringResource(R.string.project_name),
                    subtitle = stringResource(R.string.project_name_desc),
                    trailing = {
                        DebouncedTextField(
                            value = currentProject.name,
                            onValueChange = vm::updateName,
                            stateKey = currentProject.id,
                            modifier = Modifier.fillMaxWidth(0.5f),
                            singleLine = true,
                        )
                    }
                )

                val promptPreview = currentProject.systemPrompt.ifBlank {
                    stringResource(R.string.project_context_empty)
                }
                SettingGroupItem(
                    title = stringResource(R.string.project_context_title),
                    subtitle = promptPreview,
                    onClick = { showPromptDialog = true },
                )
            }

            // 模型设置
            SettingsGroup(title = stringResource(R.string.project_model_settings)) {
                val assistant = settings.getCurrentAssistant()
                val assistantModel = assistant.chatModelId?.let { settings.findModelById(it) } ?: settings.getCurrentChatModel()
                val effectiveModelSubtitle = if (currentProject.modelId != null) {
                    settings.findModelById(currentProject.modelId)?.displayName
                        ?: stringResource(R.string.project_model_inherit_generic)
                } else {
                    if (assistantModel != null) {
                        stringResource(R.string.project_model_inherit, assistantModel.displayName)
                    } else {
                        stringResource(R.string.project_model_inherit_generic)
                    }
                }

                SettingGroupItem(
                    title = stringResource(R.string.project_model_title),
                    subtitle = effectiveModelSubtitle,
                    trailing = {
                        ModelSelector(
                            modelId = currentProject.modelId,
                            providers = settings.providers,
                            type = ModelType.CHAT,
                            allowClear = true,
                            onClear = { vm.updateModelId(null) },
                            onSelect = { model ->
                                val shouldClear = model.displayName.isBlank() && model.modelId.isBlank()
                                vm.updateModelId(if (shouldClear) null else model.id)
                            },
                        )
                    }
                )
            }

            // 记忆策略
            SettingsGroup(title = stringResource(R.string.project_memory_strategy)) {
                SettingGroupItem(
                    title = stringResource(R.string.project_enable_memory_tools),
                    subtitle = stringResource(R.string.project_enable_memory_tools_desc),
                    trailing = {
                        HapticSwitch(
                            checked = currentProject.enableMemoryTools,
                            onCheckedChange = vm::updateEnableMemoryTools,
                        )
                    }
                )

                SettingGroupItem(
                    title = stringResource(R.string.project_enable_consolidation),
                    subtitle = stringResource(R.string.project_enable_consolidation_desc),
                    trailing = {
                        HapticSwitch(
                            checked = currentProject.enableConsolidation,
                            onCheckedChange = vm::updateEnableConsolidation,
                        )
                    }
                )

                AnimatedVisibility(
                    visible = currentProject.enableMemoryTools || currentProject.enableConsolidation,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    SettingGroupItem(
                        title = stringResource(R.string.project_expose_to_external),
                        subtitle = stringResource(R.string.project_expose_to_external_desc),
                        trailing = {
                            HapticSwitch(
                                checked = currentProject.exposeToExternal,
                                onCheckedChange = vm::updateExposeToExternal,
                            )
                        }
                    )
                }

                SettingGroupItem(
                    title = stringResource(R.string.project_read_external),
                    subtitle = stringResource(R.string.project_read_external_desc),
                    trailing = {
                        HapticSwitch(
                            checked = currentProject.readExternalMemory,
                            onCheckedChange = vm::updateReadExternalMemory,
                        )
                    }
                )
            }
        }
    }

    // 项目提示词编辑弹窗
    if (showPromptDialog && currentProject != null) {
        var localPrompt by remember(currentProject.id) { mutableStateOf(currentProject.systemPrompt) }
        AlertDialog(
            onDismissRequest = { showPromptDialog = false },
            title = { Text(stringResource(R.string.project_context_title)) },
            text = {
                OutlinedTextField(
                    value = localPrompt,
                    onValueChange = { localPrompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 6,
                    placeholder = { Text(stringResource(R.string.project_context_hint)) }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.updateSystemPrompt(localPrompt)
                        showPromptDialog = false
                    }
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPromptDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // 删除确认弹窗
    if (showDeleteDialog && currentProject != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.project_delete)) },
            text = { Text(stringResource(R.string.project_delete_confirm, currentProject.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptics.perform(HapticPattern.Error)
                        vm.deleteProject {
                            showDeleteDialog = false
                            navController.popBackStack()
                        }
                    }
                ) {
                    Text(
                        text = stringResource(R.string.delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showIconPickerSheet && currentProject != null) {
        ProjectIconPickerSheet(
            selectedKey = currentProject.icon,
            onSelect = { newIcon ->
                vm.updateIcon(newIcon)
            },
            onDismiss = { showIconPickerSheet = false }
        )
    }
}

