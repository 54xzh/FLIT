package me.rerere.rikkahub.ui.pages.assistant.groupchat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.flowOf
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.supportsBuiltInSearch
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.AssistantSearchMode
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.withBuiltInSearchDisabled
import me.rerere.rikkahub.data.model.GroupChatSeat
import me.rerere.rikkahub.data.model.GroupChatSeatOverrides
import me.rerere.rikkahub.data.model.buildSeatDisplayNames
import me.rerere.rikkahub.data.repository.MemoryConsolidationScheduler
import me.rerere.rikkahub.ui.components.ai.McpPickerButton
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.ai.ReasoningButton
import me.rerere.rikkahub.ui.components.ai.SearchPickerButton
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.DebouncedTextField
import me.rerere.rikkahub.ui.components.ui.HapticSwitch
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.pages.setting.components.SettingGroupItem
import me.rerere.rikkahub.ui.pages.setting.components.SettingsGroup
import me.rerere.rikkahub.ui.theme.AppShapes
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.search.SearchServiceOptions
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

@Composable
fun GroupChatTemplateDetailPage(
    id: String,
) {
    val vm: GroupChatTemplateDetailVM = koinViewModel(
        parameters = { parametersOf(id) }
    )
    val settings by vm.settings.collectAsStateWithLifecycle()
    val template by vm.template.collectAsStateWithLifecycle()
    val currentTemplate = template
    val navController = LocalNavController.current
    val context = LocalContext.current

    val haptics = rememberPremiumHaptics()
    val memoryConsolidationScheduler = remember(context) { MemoryConsolidationScheduler(context) }
    val templateId = currentTemplate?.id?.toString()
    val isManualMemoryConsolidationRunning by remember(templateId) {
        templateId?.let(memoryConsolidationScheduler::observeGroupFullScan) ?: flowOf(false)
    }.collectAsStateWithLifecycle(initialValue = false)
    val defaultName = stringResource(R.string.group_chat_default_name)
    val defaultAssistantName = stringResource(R.string.assistant_page_default_assistant)

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showAddMemberSheet by remember { mutableStateOf(false) }
    var expandedSeatId by remember(template?.id) { mutableStateOf<Uuid?>(null) }
    var showIntroDialog by remember(template?.id) { mutableStateOf(false) }
    var showHostPromptDialog by remember(template?.id) { mutableStateOf(false) }
    var showSeatPromptDialog by remember(template?.id) { mutableStateOf(false) }
    var seatPromptDialogSeatId by remember(template?.id) { mutableStateOf<Uuid?>(null) }
    var showConsolidationConfirmation by remember(template?.id) { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = template?.name?.ifBlank { defaultName } ?: defaultName,
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
                            contentDescription = stringResource(R.string.group_chat_template_delete),
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
            if (currentTemplate == null) {
                Text(
                    text = "Template not found ($id)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                return@Column
            }

            SettingsGroup(title = stringResource(R.string.assistant_page_group_identity)) {
                SettingGroupItem(
                    title = stringResource(R.string.group_chat_template_name),
                    subtitle = stringResource(R.string.assistant_page_name_desc),
                    trailing = {
                        DebouncedTextField(
                            value = currentTemplate.name,
                            onValueChange = vm::updateName,
                            stateKey = currentTemplate.id,
                            modifier = Modifier.fillMaxWidth(0.5f),
                            singleLine = true,
                        )
                    }
                )
                SettingGroupItem(
                    title = stringResource(R.string.group_chat_template_intro),
                    subtitle = stringResource(R.string.group_chat_template_system_prompt_suffix_optional),
                    onClick = { showIntroDialog = true },
                )
            }

            SettingsGroup(title = stringResource(R.string.group_chat_template_members)) {
                val assistantsById = remember(settings.assistants) { settings.assistants.associateBy { it.id } }
                val seatDisplayNames = remember(currentTemplate.seats, assistantsById, defaultAssistantName) {
                    currentTemplate.buildSeatDisplayNames(
                        assistantsById = assistantsById,
                        defaultName = defaultAssistantName,
                    )
                }

                currentTemplate.seats.forEach { seat ->
                    val assistant = settings.assistants.find { it.id == seat.assistantId }
                    val seatTitle = seatDisplayNames[seat.id]
                        ?: assistant?.name?.ifBlank { defaultAssistantName }
                        ?: defaultAssistantName

                    val effectiveModelId = seat.overrides.chatModelId
                        ?: assistant?.chatModelId
                        ?: settings.chatModelId
                    val seatSubtitle = settings.findModelById(effectiveModelId)?.displayName
                        ?: stringResource(R.string.model_list_select_model)

                    val expanded = expandedSeatId == seat.id
                    SettingGroupItem(
                        title = seatTitle,
                        subtitle = seatSubtitle,
                        icon = {
                            UIAvatar(
                                name = seatTitle,
                                value = assistant?.avatar ?: me.rerere.rikkahub.data.model.Avatar.Dummy,
                                modifier = Modifier.size(24.dp),
                            )
                        },
                        trailing = {
                            HapticSwitch(
                                checked = seat.defaultEnabled,
                                onCheckedChange = { enabled ->
                                    vm.setSeatEnabled(seat.id, enabled)
                                }
                            )
                        },
                        onClick = {
                            expandedSeatId = if (expanded) null else seat.id
                        }
                    )
                    AnimatedVisibility(visible = expanded) {
                        SeatOverridesEditor(
                            settings = settings,
                            providers = settings.providers,
                            seat = seat,
                            assistant = assistant,
                            onUpdateOverrides = { transform ->
                                vm.updateSeatOverrides(seat.id, transform)
                            },
                            onEditPrompt = {
                                seatPromptDialogSeatId = seat.id
                                showSeatPromptDialog = true
                            },
                            onRemove = {
                                vm.removeSeat(seat.id)
                                expandedSeatId = null
                            },
                        )
                    }
                }

                SettingGroupItem(
                    title = stringResource(R.string.group_chat_template_add_member),
                    icon = { Icon(imageVector = Icons.Rounded.Add, contentDescription = null) },
                    onClick = {
                        showAddMemberSheet = true
                    }
                )
            }

            SettingsGroup(title = stringResource(R.string.group_chat_template_host_model)) {
                SettingGroupItem(
                    title = stringResource(R.string.group_chat_template_host_model),
                    subtitle = stringResource(R.string.group_chat_template_host_model_desc),
                    trailing = {
                        ModelSelector(
                            modelId = currentTemplate.hostModelId,
                            providers = settings.providers,
                            type = ModelType.CHAT,
                            onSelect = { model ->
                                vm.updateHostModel(model.id)
                            },
                        )
                    }
                )
                SettingGroupItem(
                    title = stringResource(R.string.group_chat_template_host_system_prompt),
                    subtitle = stringResource(R.string.group_chat_template_system_prompt_suffix_optional),
                    onClick = { showHostPromptDialog = true },
                )
            }

            SettingsGroup(title = stringResource(R.string.assistant_page_memory)) {
                val eligibleMemoryAssistantCount = currentTemplate.seats
                    .asSequence()
                    .filter { seat -> seat.overrides.memoryEnabled }
                    .map { seat -> seat.assistantId }
                    .distinct()
                    .mapNotNull { assistantId ->
                        val assistant = settings.assistants.firstOrNull { it.id == assistantId } ?: return@mapNotNull null
                        assistant.takeIf { it.enableMemory && it.enableMemoryConsolidation }
                    }
                    .count()
                val canConsolidateAll = currentTemplate.integrationModelId != null && eligibleMemoryAssistantCount > 0

                SettingGroupItem(
                    title = stringResource(R.string.group_chat_template_integration_model),
                    subtitle = stringResource(R.string.group_chat_template_integration_model_desc),
                    trailing = {
                        ModelSelector(
                            modelId = currentTemplate.integrationModelId,
                            providers = settings.providers,
                            type = ModelType.CHAT,
                            allowClear = true,
                            onSelect = { model ->
                                val shouldClear = model.displayName.isBlank() && model.modelId.isBlank()
                                vm.updateIntegrationModel(if (shouldClear) null else model.id)
                            },
                        )
                    }
                )

                Surface(
                    color = if (LocalDarkMode.current) {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    shape = AppShapes.ListItem,
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = stringResource(R.string.assistant_page_consolidation_delay),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = stringResource(
                                    R.string.assistant_page_minutes_format,
                                    currentTemplate.consolidationDelayMinutes,
                                ),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(
                            text = stringResource(R.string.assistant_page_consolidation_delay_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Slider(
                            value = currentTemplate.consolidationDelayMinutes.toFloat(),
                            onValueChange = { minutes ->
                                vm.updateConsolidationDelayMinutes(minutes.toInt())
                            },
                            valueRange = 0f..240f,
                            steps = 23,
                        )
                    }
                }

                Surface(
                    color = if (LocalDarkMode.current) {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    shape = AppShapes.ListItem,
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                if (isManualMemoryConsolidationRunning) {
                                    haptics.perform(HapticPattern.Thud)
                                    memoryConsolidationScheduler.cancelGroupFullScan(currentTemplate.id.toString())
                                } else {
                                    haptics.perform(HapticPattern.Pop)
                                    showConsolidationConfirmation = true
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = canConsolidateAll || isManualMemoryConsolidationRunning,
                        ) {
                            Icon(
                                imageVector = if (isManualMemoryConsolidationRunning) Icons.Rounded.Close else Icons.Rounded.Psychology,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(
                                    if (isManualMemoryConsolidationRunning) {
                                        R.string.assistant_page_memory_consolidation_cancel
                                    } else {
                                        R.string.assistant_page_memory_consolidate_now
                                    }
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    if (showConsolidationConfirmation && currentTemplate != null) {
        AlertDialog(
            onDismissRequest = { showConsolidationConfirmation = false },
            title = { Text(stringResource(R.string.assistant_page_memory_consolidation_confirm_title)) },
            text = { Text(stringResource(R.string.assistant_page_memory_consolidation_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConsolidationConfirmation = false
                        memoryConsolidationScheduler.enqueueGroupFullScan(currentTemplate.id.toString())
                    },
                ) {
                    Text(stringResource(R.string.assistant_page_memory_consolidation_start))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConsolidationConfirmation = false }) {
                    Text(stringResource(R.string.assistant_page_cancel))
                }
            },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.group_chat_template_delete)) },
            text = {
                Text(
                    text = stringResource(R.string.group_chat_default_name),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptics.perform(HapticPattern.Error)
                        vm.deleteTemplate()
                        showDeleteDialog = false
                        navController.popBackStack()
                    }
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showAddMemberSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAddMemberSheet = false },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.group_chat_template_add_member),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    items(
                        items = settings.assistants,
                        key = { assistant -> assistant.id.toString() },
                    ) { assistant ->
                        val assistantName = assistant.name.ifBlank { defaultAssistantName }
                        Surface(
                            onClick = {
                                haptics.perform(HapticPattern.Pop)
                                vm.addSeat(assistant.id)
                                showAddMemberSheet = false
                            },
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = AppShapes.ListItem,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                UIAvatar(
                                    name = assistantName,
                                    value = assistant.avatar,
                                    modifier = Modifier.size(36.dp),
                                )
                                Text(
                                    text = assistantName,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showIntroDialog && currentTemplate != null) {
        var localIntro by remember(currentTemplate.id) { mutableStateOf(currentTemplate.intro) }
        AlertDialog(
            onDismissRequest = { showIntroDialog = false },
            title = { Text(stringResource(R.string.group_chat_template_intro)) },
            text = {
                TextField(
                    value = localIntro,
                    onValueChange = { localIntro = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 6,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.updateIntro(localIntro)
                        showIntroDialog = false
                    }
                ) {
                    Text(stringResource(R.string.assistant_page_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showIntroDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showHostPromptDialog && currentTemplate != null) {
        var localPrompt by remember(currentTemplate.id) { mutableStateOf(currentTemplate.hostSystemPrompt) }
        AlertDialog(
            onDismissRequest = { showHostPromptDialog = false },
            title = { Text(stringResource(R.string.group_chat_template_host_system_prompt)) },
            text = {
                TextField(
                    value = localPrompt,
                    onValueChange = { localPrompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 6,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.updateHostSystemPrompt(localPrompt)
                        showHostPromptDialog = false
                    }
                ) {
                    Text(stringResource(R.string.assistant_page_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showHostPromptDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showSeatPromptDialog && currentTemplate != null) {
        val seatId = seatPromptDialogSeatId
        val seat = seatId?.let { id -> currentTemplate.seats.firstOrNull { it.id == id } }
        val assistant = seat?.assistantId?.let { assistantId -> settings.assistants.firstOrNull { it.id == assistantId } }
        val basePrompt = assistant?.systemPrompt.orEmpty()
        val currentOverride = seat?.overrides?.systemPrompt
        var localPrompt by remember(currentTemplate.id, seatId) { mutableStateOf(currentOverride ?: basePrompt) }

        AlertDialog(
            onDismissRequest = {
                showSeatPromptDialog = false
                seatPromptDialogSeatId = null
            },
            title = { Text(stringResource(R.string.group_chat_template_seat_system_prompt_edit)) },
            text = {
                TextField(
                    value = localPrompt,
                    onValueChange = { localPrompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 8,
                )
            },
            confirmButton = {},
            dismissButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(
                        onClick = {
                            localPrompt = basePrompt
                        },
                        enabled = localPrompt != basePrompt,
                    ) {
                        Text(stringResource(R.string.group_chat_template_seat_system_prompt_restore))
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(
                            onClick = {
                                showSeatPromptDialog = false
                                seatPromptDialogSeatId = null
                            }
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                        TextButton(
                            onClick = {
                                val resolvedSeatId = seatId ?: return@TextButton
                                val normalized = localPrompt.takeIf { it != basePrompt }
                                vm.updateSeatOverrides(resolvedSeatId) { overrides ->
                                    overrides.copy(systemPrompt = normalized)
                                }
                                showSeatPromptDialog = false
                                seatPromptDialogSeatId = null
                            }
                        ) {
                            Text(stringResource(R.string.assistant_page_save))
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun SeatOverridesEditor(
    settings: Settings,
    providers: List<ProviderSetting>,
    seat: GroupChatSeat,
    assistant: Assistant?,
    onUpdateOverrides: ((GroupChatSeatOverrides) -> GroupChatSeatOverrides) -> Unit,
    onEditPrompt: () -> Unit,
    onRemove: () -> Unit,
) {
    val mcpManager = koinInject<me.rerere.rikkahub.data.ai.mcp.McpManager>()

    val defaultReasoningLevel = assistant?.reasoningLevel ?: ReasoningLevel.AUTO
    val effectiveReasoningLevel = seat.overrides.reasoningLevel ?: defaultReasoningLevel
    val effectiveModelId = seat.overrides.chatModelId ?: assistant?.chatModelId ?: settings.chatModelId
    val model = settings.findModelById(effectiveModelId)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = AppShapes.CardMedium,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.assistant_page_chat_model),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                ModelSelector(
                    modelId = effectiveModelId,
                    providers = providers,
                    type = ModelType.CHAT,
                    onSelect = { model ->
                        onUpdateOverrides { it.copy(chatModelId = model.id) }
                    },
                )
                IconButton(
                    enabled = seat.overrides.chatModelId != null,
                    onClick = { onUpdateOverrides { it.copy(chatModelId = null) } },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.a11y_clear),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.assistant_page_thinking_budget),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                ReasoningButton(
                    reasoningLevel = effectiveReasoningLevel,
                    onUpdateReasoningLevel = { level ->
                        onUpdateOverrides { it.copy(reasoningLevel = level) }
                    }
                )
                IconButton(
                    enabled = seat.overrides.reasoningLevel != null,
                    onClick = { onUpdateOverrides { it.copy(reasoningLevel = null) } },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.a11y_clear),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.assistant_page_max_tokens),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = seat.overrides.maxTokens?.toString() ?: "",
                    onValueChange = { raw ->
                        val tokens = raw.toIntOrNull()?.takeIf { it > 0 }
                        onUpdateOverrides { it.copy(maxTokens = tokens) }
                    },
                    modifier = Modifier.width(120.dp),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.auto)) },
                )
            }

            val offText = stringResource(R.string.off)
            val builtInText = stringResource(R.string.built_in_search_title)
            val providerIndex = (seat.overrides.searchMode as? AssistantSearchMode.Provider)?.index
            val providerName = providerIndex?.let { index ->
                val service = settings.searchServices.getOrNull(index)
                val resolved = service?.let { options -> SearchServiceOptions.TYPES[options::class] }
                resolved?.takeIf { it.isNotBlank() } ?: "Provider ${index + 1}"
            }
            val modelProvider = model?.findProvider(settings.providers)
            val supportsBuiltInSearch = model?.supportsBuiltInSearch(modelProvider) == true
            val searchSubtitle = when {
                !seat.overrides.searchEnabled -> offText
                seat.overrides.searchMode is AssistantSearchMode.BuiltIn -> builtInText
                supportsBuiltInSearch && seat.overrides.preferBuiltInSearch -> builtInText
                providerName != null -> providerName
                else -> offText
            }

            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = stringResource(R.string.use_web_search),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                SearchPickerButton(
                    enableSearch = seat.overrides.searchEnabled,
                    settings = settings,
                    shape = CircleShape,
                    onToggleSearch = { enabled ->
                        onUpdateOverrides { overrides ->
                            if (!enabled) return@onUpdateOverrides overrides.copy(searchEnabled = false)
                            val defaultProviderIndex = settings.searchServiceSelected
                                .takeIf { it in settings.searchServices.indices }
                                ?: 0
                            val nextMode = when {
                                overrides.searchMode is AssistantSearchMode.Off && settings.searchServices.isNotEmpty() ->
                                    AssistantSearchMode.Provider(defaultProviderIndex)
                                else -> overrides.searchMode
                            }
                            overrides.copy(searchEnabled = true, searchMode = nextMode)
                        }
                    },
                    onUpdateSearchService = { index ->
                        onUpdateOverrides { overrides ->
                            overrides.copy(
                                searchEnabled = true,
                                searchMode = AssistantSearchMode.Provider(index),
                            )
                        }
                    },
                    model = model,
                    selectedProviderIndex = providerIndex ?: -1,
                    isBuiltInMode = seat.overrides.searchMode is AssistantSearchMode.BuiltIn,
                    preferBuiltInSearch = seat.overrides.preferBuiltInSearch,
                    onTogglePreferBuiltInSearch = { enabled ->
                        onUpdateOverrides { overrides ->
                            overrides.copy(
                                searchEnabled = overrides.searchEnabled || enabled,
                                preferBuiltInSearch = enabled,
                                searchMode = if (enabled) overrides.searchMode else {
                                    overrides.searchMode.withBuiltInSearchDisabled(
                                        searchServiceCount = settings.searchServices.size,
                                        selectedSearchServiceIndex = settings.searchServiceSelected,
                                    )
                                },
                            )
                        }
                    },
                    contentColor = if (seat.overrides.searchEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    onlyIcon = true,
                )
            }

            if (settings.mcpServers.isNotEmpty()) {
                val mcpAssistant = (assistant ?: Assistant(id = seat.assistantId)).copy(
                    id = assistant?.id ?: seat.assistantId,
                    name = assistant?.name.orEmpty(),
                    avatar = assistant?.avatar ?: me.rerere.rikkahub.data.model.Avatar.Dummy,
                    mcpServers = seat.overrides.mcpServerIds,
                )

                Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = stringResource(R.string.mcp_picker_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    McpPickerButton(
                        selectedServerIds = mcpAssistant.mcpServers,
                        visibleWorkspaceId = mcpAssistant.workspaceId,
                        servers = settings.mcpServers,
                        mcpManager = mcpManager,
                        onSelectionChange = { selected ->
                            onUpdateOverrides { it.copy(mcpServerIds = selected) }
                        },
                    )
                }
            }

            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.group_chat_template_member_use_memory),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                HapticSwitch(
                    checked = seat.overrides.memoryEnabled,
                    onCheckedChange = { enabled ->
                        onUpdateOverrides { it.copy(memoryEnabled = enabled) }
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = onEditPrompt) {
                    Text(text = stringResource(R.string.group_chat_template_seat_system_prompt_edit))
                }

                TextButton(onClick = onRemove) {
                    Text(
                        text = stringResource(R.string.group_chat_template_remove_member),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
