package me.rerere.rikkahub.ui.pages.assistant.scheduled

import android.app.AlarmManager
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.ai.provider.supportsBuiltInSearch
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.AssistantSearchMode
import me.rerere.rikkahub.service.scheduledtask.ScheduledTaskAccuracyMode
import me.rerere.rikkahub.service.scheduledtask.ScheduledTaskIntervalUnit
import me.rerere.rikkahub.service.scheduledtask.ScheduledTaskOverrideType
import me.rerere.rikkahub.service.scheduledtask.ScheduledTaskRepeatType
import me.rerere.rikkahub.service.scheduledtask.ScheduledTaskSearchOverrideType
import me.rerere.rikkahub.ui.components.ai.McpPickerButton
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.ai.SearchPickerButton
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.HapticSwitch
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.pages.setting.components.SettingGroupItem
import me.rerere.rikkahub.ui.pages.setting.components.SettingsGroup
import me.rerere.search.displayName
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.uuid.Uuid

@Composable
fun AssistantScheduledTaskEditPage(
    assistantId: String,
    taskId: String?,
) {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val settings = LocalSettings.current
    val toaster = LocalToaster.current
    val haptics = rememberPremiumHaptics()
    val scope = rememberCoroutineScope()

    val vm: AssistantScheduledTaskEditVM = koinViewModel(
        parameters = { parametersOf(assistantId, taskId) }
    )
    val draft by vm.draft.collectAsStateWithLifecycle()
    val existingTask by vm.existingTask.collectAsStateWithLifecycle()

    val locale = Locale.getDefault()
    val zoneId = ZoneId.systemDefault()

    var promptValue by remember { mutableStateOf(TextFieldValue(draft.promptTemplate)) }
    LaunchedEffect(existingTask?.id) {
        promptValue = TextFieldValue(draft.promptTemplate)
    }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.scheduled_tasks_delete_title)) },
            text = { Text(stringResource(R.string.scheduled_tasks_delete_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptics.perform(HapticPattern.Thud)
                        showDeleteConfirm = false
                        scope.launch {
                            val ok = vm.delete()
                            if (ok) {
                                navController.popBackStack()
                            } else {
                                toaster.show(context.getString(R.string.scheduled_tasks_delete_failed))
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.assistant_page_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.assistant_page_cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (taskId == null) {
                            stringResource(R.string.scheduled_tasks_new)
                        } else {
                            stringResource(R.string.scheduled_tasks_edit)
                        },
                        maxLines = 1,
                    )
                },
                navigationIcon = { BackButton() },
                actions = {
                    TextButton(
                        onClick = {
                            haptics.perform(HapticPattern.Pop)
                            scope.launch {
                                val ok = vm.save()
                                if (ok) {
                                    navController.popBackStack()
                                } else {
                                    toaster.show(context.getString(R.string.scheduled_tasks_save_invalid))
                                }
                            }
                        }
                    ) {
                        Text(stringResource(R.string.save))
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            GeneralGroup(draft = draft, onUpdate = vm::update)

            PromptGroup(
                promptValue = promptValue,
                onPromptChange = { value ->
                    promptValue = value
                    vm.update { it.copy(promptTemplate = value.text) }
                },
                onInsertVar = { token ->
                    val next = promptValue.insertText(token)
                    promptValue = next
                    vm.update { it.copy(promptTemplate = next.text) }
                }
            )

            ScheduleGroup(
                context = context,
                draft = draft,
                locale = locale,
                onUpdate = vm::update,
            )

            ExecutionGroup(
                context = context,
                settings = settings,
                draft = draft,
                locale = locale,
                zoneId = zoneId,
                onUpdate = vm::update,
            )

            if (existingTask != null) {
                DangerGroup(onDelete = { showDeleteConfirm = true })
            }
        }
    }
}

@Composable
private fun GeneralGroup(draft: ScheduledTaskDraft, onUpdate: ((ScheduledTaskDraft) -> ScheduledTaskDraft) -> Unit) {
    SettingsGroup(title = stringResource(R.string.scheduled_tasks_group_general)) {
        SettingGroupItem(
            title = stringResource(R.string.scheduled_tasks_enabled),
            subtitle = stringResource(R.string.scheduled_tasks_enabled_desc),
            trailing = {
                HapticSwitch(
                    checked = draft.enabled,
                    onCheckedChange = { enabled -> onUpdate { it.copy(enabled = enabled) } }
                )
            }
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.scheduled_tasks_name),
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { text -> onUpdate { it.copy(name = text) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        }
    }
}

@Composable
private fun PromptGroup(
    promptValue: TextFieldValue,
    onPromptChange: (TextFieldValue) -> Unit,
    onInsertVar: (String) -> Unit,
) {
    SettingsGroup(title = stringResource(R.string.scheduled_tasks_group_prompt)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.scheduled_tasks_prompt),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.scheduled_tasks_prompt_vars_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PromptVarTag("{date}") { onInsertVar("{date}") }
                    PromptVarTag("{weekday}") { onInsertVar("{weekday}") }
                    PromptVarTag("{time}") { onInsertVar("{time}") }
                }

                OutlinedTextField(
                    value = promptValue,
                    onValueChange = onPromptChange,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 10,
                )
            }
        }
    }
}

@Composable
private fun ScheduleGroup(
    context: Context,
    draft: ScheduledTaskDraft,
    locale: Locale,
    onUpdate: ((ScheduledTaskDraft) -> ScheduledTaskDraft) -> Unit,
) {
    val timeText = remember(draft.timeOfDayMinutes) {
        draft.timeOfDayMinutes.toLocalTimeOrNull()
            ?.format(DateTimeFormatter.ofPattern("HH:mm", locale))
            ?: "--:--"
    }

    SettingsGroup(title = stringResource(R.string.scheduled_tasks_group_schedule)) {
        SettingGroupItem(
            title = stringResource(R.string.scheduled_tasks_time),
            subtitle = stringResource(R.string.scheduled_tasks_time_desc, timeText),
            onClick = {
                val current = draft.timeOfDayMinutes.toLocalTimeOrNull() ?: LocalTime.of(9, 0)
                TimePickerDialog(
                    context,
                    { _, hour, minute ->
                        onUpdate { it.copy(timeOfDayMinutes = hour * 60 + minute) }
                    },
                    current.hour,
                    current.minute,
                    true
                ).show()
            }
        )

        data class RepeatOption(val type: Int, val label: String)
        val repeatOptions = listOf(
            RepeatOption(ScheduledTaskRepeatType.ONCE, stringResource(R.string.scheduled_tasks_repeat_once)),
            RepeatOption(ScheduledTaskRepeatType.DAILY, stringResource(R.string.scheduled_tasks_repeat_daily)),
            RepeatOption(ScheduledTaskRepeatType.WEEKLY, stringResource(R.string.scheduled_tasks_repeat_weekly)),
            RepeatOption(ScheduledTaskRepeatType.MONTHLY, stringResource(R.string.scheduled_tasks_repeat_monthly)),
            RepeatOption(ScheduledTaskRepeatType.INTERVAL, stringResource(R.string.scheduled_tasks_repeat_interval)),
        )
        val selectedRepeat = repeatOptions.firstOrNull { it.type == draft.repeatType } ?: repeatOptions[1]

        SettingGroupItem(
            title = stringResource(R.string.scheduled_tasks_repeat),
            subtitle = selectedRepeat.label,
            trailing = {
                Select(
                    options = repeatOptions,
                    selectedOption = selectedRepeat,
                    onOptionSelected = { opt ->
                        onUpdate { d ->
                            val weeklyMask = if (opt.type == ScheduledTaskRepeatType.WEEKLY && d.weeklyMask == 0) {
                                dayOfWeekMask(LocalDate.now().dayOfWeek)
                            } else {
                                d.weeklyMask
                            }
                            d.copy(repeatType = opt.type, weeklyMask = weeklyMask)
                        }
                    },
                    optionToString = { it.label },
                    modifier = Modifier.width(170.dp)
                )
            }
        )

        when (draft.repeatType) {
            ScheduledTaskRepeatType.WEEKLY -> WeeklyPicker(draft.weeklyMask, onUpdateMask = { mask ->
                onUpdate { it.copy(weeklyMask = mask) }
            })

            ScheduledTaskRepeatType.MONTHLY -> MonthlyPicker(
                monthlyDay = draft.monthlyDay,
                onUpdateDay = { day -> onUpdate { it.copy(monthlyDay = day) } }
            )

            ScheduledTaskRepeatType.INTERVAL -> IntervalPicker(
                intervalValue = draft.intervalValue,
                intervalUnit = draft.intervalUnit,
                onUpdateValue = { v -> onUpdate { it.copy(intervalValue = v) } },
                onUpdateUnit = { u -> onUpdate { it.copy(intervalUnit = u) } },
            )
        }
    }
}

@Composable
private fun ExecutionGroup(
    context: Context,
    settings: me.rerere.rikkahub.data.datastore.Settings,
    draft: ScheduledTaskDraft,
    locale: Locale,
    zoneId: ZoneId,
    onUpdate: ((ScheduledTaskDraft) -> ScheduledTaskDraft) -> Unit,
) {
    SettingsGroup(title = stringResource(R.string.scheduled_tasks_group_execution)) {
        SettingGroupItem(
            title = stringResource(R.string.scheduled_tasks_notify),
            subtitle = stringResource(R.string.scheduled_tasks_notify_desc),
            trailing = {
                HapticSwitch(
                    checked = draft.notifyOnDone,
                    onCheckedChange = { enabled -> onUpdate { it.copy(notifyOnDone = enabled) } }
                )
            }
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.scheduled_tasks_model_override),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.scheduled_tasks_model_override_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                val overrideModelUuid = draft.overrideModelId
                    ?.takeIf { it.isNotBlank() }
                    ?.let { id -> runCatching { Uuid.parse(id) }.getOrNull() }

                ModelSelector(
                    modelId = overrideModelUuid,
                    providers = settings.providers,
                    type = ModelType.CHAT,
                    allowClear = true,
                    onSelect = { model ->
                        val shouldClear = model.displayName.isBlank() && model.modelId.isBlank()
                        onUpdate { it.copy(overrideModelId = if (shouldClear) null else model.id.toString()) }
                    }
                )
            }
        }

        SearchProviderPicker(settings = settings, draft = draft, onUpdate = onUpdate)
        McpServerPicker(settings = settings, draft = draft, onUpdate = onUpdate)

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val canExact = remember {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) true else alarmManager.canScheduleExactAlarms()
        }

        SettingGroupItem(
            title = stringResource(R.string.scheduled_tasks_exact_timing),
            subtitle = if (draft.accuracyMode == ScheduledTaskAccuracyMode.EXACT && !canExact) {
                stringResource(R.string.scheduled_tasks_exact_timing_unavailable)
            } else {
                stringResource(R.string.scheduled_tasks_exact_timing_desc)
            },
            trailing = {
                HapticSwitch(
                    checked = draft.accuracyMode == ScheduledTaskAccuracyMode.EXACT,
                    onCheckedChange = { enabled ->
                        onUpdate {
                            it.copy(
                                accuracyMode = if (enabled) {
                                    ScheduledTaskAccuracyMode.EXACT
                                } else {
                                    ScheduledTaskAccuracyMode.ECO
                                }
                            )
                        }
                    }
                )
            }
        )

        if (draft.accuracyMode == ScheduledTaskAccuracyMode.EXACT && !canExact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SettingGroupItem(
                title = stringResource(R.string.scheduled_tasks_exact_timing_request),
                subtitle = stringResource(R.string.scheduled_tasks_exact_timing_request_desc),
                onClick = {
                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            )
        }
    }
}

@Composable
private fun SearchProviderPicker(
    settings: me.rerere.rikkahub.data.datastore.Settings,
    draft: ScheduledTaskDraft,
    onUpdate: ((ScheduledTaskDraft) -> ScheduledTaskDraft) -> Unit,
) {
    val assistant = runCatching { Uuid.parse(draft.assistantId) }
        .getOrNull()
        ?.let(settings::getAssistantById)
        ?: settings.getCurrentAssistant()

    val overrideModelUuid = draft.overrideModelId
        ?.takeIf { it.isNotBlank() }
        ?.let { id -> runCatching { Uuid.parse(id) }.getOrNull() }
    val resolvedModelId = overrideModelUuid ?: assistant.backgroundModelId ?: assistant.chatModelId ?: settings.chatModelId
    val model = settings.findModelById(resolvedModelId)
    val modelProvider = model?.findProvider(settings.providers)
    val modelSupportsBuiltIn = model?.supportsBuiltInSearch(modelProvider) == true

    val inheritText = stringResource(R.string.scheduled_tasks_inherit)
    val offText = stringResource(R.string.off)
    val customText = stringResource(R.string.scheduled_tasks_custom)

    data class ModeOption(val mode: Int, val title: String)
    val modeOptions = listOf(
        ModeOption(ScheduledTaskSearchOverrideType.INHERIT, inheritText),
        ModeOption(ScheduledTaskSearchOverrideType.OFF, offText),
        ModeOption(ScheduledTaskSearchOverrideType.OVERRIDE, customText),
    )
    val selectedModeValue = when (draft.searchOverrideType) {
        ScheduledTaskSearchOverrideType.INHERIT -> ScheduledTaskSearchOverrideType.INHERIT
        ScheduledTaskSearchOverrideType.OFF -> ScheduledTaskSearchOverrideType.OFF
        else -> ScheduledTaskSearchOverrideType.OVERRIDE
    }
    val selectedMode = modeOptions.firstOrNull { it.mode == selectedModeValue } ?: modeOptions.first()

    val preferBuiltInSearch = when (draft.searchOverrideType) {
        ScheduledTaskSearchOverrideType.INHERIT -> assistant.preferBuiltInSearch
        ScheduledTaskSearchOverrideType.OVERRIDE_PREFER_BUILTIN -> true
        else -> false
    }

    val providerIndexFromAssistant = (assistant.searchMode as? AssistantSearchMode.Provider)?.index
    val effectiveProviderIndex = when (draft.searchOverrideType) {
        ScheduledTaskSearchOverrideType.INHERIT -> providerIndexFromAssistant ?: -1
        else -> draft.searchProviderIndex
    }
    val enableSearch = when (draft.searchOverrideType) {
        ScheduledTaskSearchOverrideType.OFF -> false
        ScheduledTaskSearchOverrideType.INHERIT -> assistant.searchMode !is AssistantSearchMode.Off
        ScheduledTaskSearchOverrideType.OVERRIDE -> draft.searchProviderIndex >= 0
        ScheduledTaskSearchOverrideType.OVERRIDE_PREFER_BUILTIN -> modelSupportsBuiltIn || draft.searchProviderIndex >= 0
        else -> false
    }
    val effectiveBuiltInSearch = enableSearch && modelSupportsBuiltIn && when (draft.searchOverrideType) {
        ScheduledTaskSearchOverrideType.INHERIT -> {
            assistant.searchMode is AssistantSearchMode.BuiltIn || assistant.preferBuiltInSearch
        }

        ScheduledTaskSearchOverrideType.OVERRIDE_PREFER_BUILTIN -> true
        else -> false
    }

    val providerName = effectiveProviderIndex
        .takeIf { it >= 0 }
        ?.let { index -> settings.searchServices.getOrNull(index) }
        ?.displayName
        ?.ifBlank { null }
        ?: if (effectiveProviderIndex >= 0) {
            "Provider ${effectiveProviderIndex + 1}"
        } else {
            offText
        }

    val detailText = when {
        draft.searchOverrideType == ScheduledTaskSearchOverrideType.OFF -> offText
        effectiveBuiltInSearch -> stringResource(R.string.built_in_search_title)
        effectiveProviderIndex >= 0 -> providerName
        else -> offText
    }
    val subtitle = when (selectedModeValue) {
        ScheduledTaskSearchOverrideType.INHERIT -> "$inheritText · $detailText"
        ScheduledTaskSearchOverrideType.OFF -> offText
        else -> "$customText · $detailText"
    }

    val providerIndexForDialog = when {
        effectiveProviderIndex >= 0 -> effectiveProviderIndex
        settings.searchServices.isNotEmpty() -> 0
        else -> -1
    }

    fun defaultProviderIndex(): Int {
        return draft.searchProviderIndex.takeIf { it >= 0 }
            ?: providerIndexFromAssistant
            ?: if (settings.searchServices.isNotEmpty()) 0 else -1
    }

    SettingGroupItem(
        title = stringResource(R.string.scheduled_tasks_search_provider),
        subtitle = subtitle,
        trailing = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Select(
                    options = modeOptions,
                    selectedOption = selectedMode,
                    onOptionSelected = { opt ->
                        onUpdate { d ->
                            when (opt.mode) {
                                ScheduledTaskSearchOverrideType.INHERIT -> {
                                    d.copy(searchOverrideType = ScheduledTaskSearchOverrideType.INHERIT)
                                }

                                ScheduledTaskSearchOverrideType.OFF -> {
                                    d.copy(searchOverrideType = ScheduledTaskSearchOverrideType.OFF)
                                }

                                else -> {
                                    val shouldPreferBuiltIn = when (d.searchOverrideType) {
                                        ScheduledTaskSearchOverrideType.OVERRIDE_PREFER_BUILTIN -> true
                                        ScheduledTaskSearchOverrideType.INHERIT -> assistant.preferBuiltInSearch
                                        else -> false
                                    }
                                    d.copy(
                                        searchOverrideType = if (shouldPreferBuiltIn) {
                                            ScheduledTaskSearchOverrideType.OVERRIDE_PREFER_BUILTIN
                                        } else {
                                            ScheduledTaskSearchOverrideType.OVERRIDE
                                        },
                                        searchProviderIndex = defaultProviderIndex(),
                                    )
                                }
                            }
                        }
                    },
                    optionToString = { it.title },
                    modifier = Modifier.width(110.dp),
                )

                SearchPickerButton(
                    enableSearch = enableSearch,
                    settings = settings,
                    shape = CircleShape,
                    onToggleSearch = { enabled ->
                        onUpdate { d ->
                            if (!enabled) {
                                d.copy(searchOverrideType = ScheduledTaskSearchOverrideType.OFF)
                            } else {
                                val shouldPreferBuiltIn = when (d.searchOverrideType) {
                                    ScheduledTaskSearchOverrideType.OVERRIDE_PREFER_BUILTIN -> true
                                    ScheduledTaskSearchOverrideType.INHERIT -> assistant.preferBuiltInSearch
                                    else -> false
                                }
                                d.copy(
                                    searchOverrideType = if (shouldPreferBuiltIn) {
                                        ScheduledTaskSearchOverrideType.OVERRIDE_PREFER_BUILTIN
                                    } else {
                                        ScheduledTaskSearchOverrideType.OVERRIDE
                                    },
                                    searchProviderIndex = defaultProviderIndex(),
                                )
                            }
                        }
                    },
                    onUpdateSearchService = { index ->
                        onUpdate { d ->
                            val shouldPreferBuiltIn = when (d.searchOverrideType) {
                                ScheduledTaskSearchOverrideType.OVERRIDE_PREFER_BUILTIN -> true
                                ScheduledTaskSearchOverrideType.INHERIT -> assistant.preferBuiltInSearch
                                else -> false
                            }
                            d.copy(
                                searchOverrideType = if (shouldPreferBuiltIn) {
                                    ScheduledTaskSearchOverrideType.OVERRIDE_PREFER_BUILTIN
                                } else {
                                    ScheduledTaskSearchOverrideType.OVERRIDE
                                },
                                searchProviderIndex = index,
                            )
                        }
                    },
                    model = model,
                    selectedProviderIndex = providerIndexForDialog,
                    preferBuiltInSearch = preferBuiltInSearch,
                    onTogglePreferBuiltInSearch = { enabled ->
                        onUpdate { d ->
                            d.copy(
                                searchOverrideType = if (enabled) {
                                    ScheduledTaskSearchOverrideType.OVERRIDE_PREFER_BUILTIN
                                } else {
                                    ScheduledTaskSearchOverrideType.OVERRIDE
                                },
                                searchProviderIndex = defaultProviderIndex(),
                            )
                        }
                    },
                    contentColor = if (enableSearch) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    onlyIcon = true,
                )
            }
        },
    )
}

@Composable
private fun McpServerPicker(
    settings: me.rerere.rikkahub.data.datastore.Settings,
    draft: ScheduledTaskDraft,
    onUpdate: ((ScheduledTaskDraft) -> ScheduledTaskDraft) -> Unit,
) {
    if (settings.mcpServers.isEmpty()) return

    val assistant = runCatching { Uuid.parse(draft.assistantId) }
        .getOrNull()
        ?.let(settings::getAssistantById)
        ?: settings.getCurrentAssistant()

    val inheritText = stringResource(R.string.scheduled_tasks_inherit)
    val offText = stringResource(R.string.off)
    val customText = stringResource(R.string.scheduled_tasks_custom)

    data class ModeOption(val mode: Int, val title: String)
    val modeOptions = listOf(
        ModeOption(ScheduledTaskOverrideType.INHERIT, inheritText),
        ModeOption(ScheduledTaskOverrideType.OFF, offText),
        ModeOption(ScheduledTaskOverrideType.OVERRIDE, customText),
    )
    val selectedModeValue = when (draft.mcpOverrideType) {
        ScheduledTaskOverrideType.INHERIT -> ScheduledTaskOverrideType.INHERIT
        ScheduledTaskOverrideType.OFF -> ScheduledTaskOverrideType.OFF
        else -> ScheduledTaskOverrideType.OVERRIDE
    }
    val selectedMode = modeOptions.firstOrNull { it.mode == selectedModeValue } ?: modeOptions.first()

    val selectedServerIds = when (draft.mcpOverrideType) {
        ScheduledTaskOverrideType.INHERIT -> assistant.mcpServers
        ScheduledTaskOverrideType.OFF -> emptySet()
        ScheduledTaskOverrideType.OVERRIDE -> draft.mcpServerId.toUuidSet()
        else -> assistant.mcpServers
    }

    val subtitle = when (selectedModeValue) {
        ScheduledTaskOverrideType.INHERIT -> "$inheritText · ${assistant.mcpServers.size}"
        ScheduledTaskOverrideType.OFF -> offText
        else -> "$customText · ${selectedServerIds.size}"
    }

    fun defaultServerIdsCsvOrNull(): String? {
        val current = draft.mcpServerId?.takeIf { it.isNotBlank() }
        if (current != null) return current
        return assistant.mcpServers.toUuidCsvOrNull()
    }

    SettingGroupItem(
        title = stringResource(R.string.scheduled_tasks_mcp_server),
        subtitle = subtitle,
        trailing = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Select(
                    options = modeOptions,
                    selectedOption = selectedMode,
                    onOptionSelected = { opt ->
                        onUpdate { d ->
                            when (opt.mode) {
                                ScheduledTaskOverrideType.INHERIT -> d.copy(mcpOverrideType = ScheduledTaskOverrideType.INHERIT)
                                ScheduledTaskOverrideType.OFF -> d.copy(mcpOverrideType = ScheduledTaskOverrideType.OFF)
                                else -> d.copy(
                                    mcpOverrideType = ScheduledTaskOverrideType.OVERRIDE,
                                    mcpServerId = defaultServerIdsCsvOrNull(),
                                )
                            }
                        }
                    },
                    optionToString = { it.title },
                    modifier = Modifier.width(110.dp),
                )

                val assistantForPicker = assistant.copy(mcpServers = selectedServerIds)
                McpPickerButton(
                    assistant = assistantForPicker,
                    servers = settings.mcpServers,
                    mcpManager = koinInject(),
                    onUpdateAssistant = { updatedAssistant ->
                        onUpdate { d ->
                            d.copy(
                                mcpOverrideType = ScheduledTaskOverrideType.OVERRIDE,
                                mcpServerId = updatedAssistant.mcpServers.toUuidCsvOrNull(),
                            )
                        }
                    }
                )
            }
        }
    )
}

@Composable
private fun DangerGroup(onDelete: () -> Unit) {
    SettingsGroup(title = stringResource(R.string.scheduled_tasks_group_danger)) {
        SettingGroupItem(
            title = stringResource(R.string.assistant_page_delete),
            subtitle = stringResource(R.string.scheduled_tasks_delete_desc),
            onClick = onDelete,
        )
    }
}

@Composable
private fun PromptVarTag(text: String, onClick: () -> Unit) {
    Tag(type = TagType.DEFAULT, onClick = onClick) {
        Text(text)
    }
}

private fun TextFieldValue.insertText(insert: String): TextFieldValue {
    val start = selection.start.coerceIn(0, text.length)
    val end = selection.end.coerceIn(0, text.length)
    val newText = buildString {
        append(text.substring(0, start))
        append(insert)
        append(text.substring(end))
    }
    val newCursor = start + insert.length
    return copy(text = newText, selection = TextRange(newCursor))
}

private fun String?.toUuidSet(): Set<Uuid> {
    val raw = this?.trim().orEmpty()
    if (raw.isBlank()) return emptySet()
    return raw
        .split(',')
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .mapNotNull { id -> runCatching { Uuid.parse(id) }.getOrNull() }
        .toSet()
}

private fun Set<Uuid>.toUuidCsvOrNull(): String? {
    if (this.isEmpty()) return null
    return this
        .map { it.toString() }
        .sorted()
        .joinToString(",")
}

private fun Int.toLocalTimeOrNull(): LocalTime? {
    if (this !in 0..(24 * 60 - 1)) return null
    return LocalTime.of(this / 60, this % 60)
}

private fun dayOfWeekMask(day: DayOfWeek): Int = 1 shl (day.value - 1)

@Composable
private fun WeeklyPicker(weeklyMask: Int, onUpdateMask: (Int) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.scheduled_tasks_weekdays),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DayChip(DayOfWeek.MONDAY, weeklyMask, onUpdateMask)
                DayChip(DayOfWeek.TUESDAY, weeklyMask, onUpdateMask)
                DayChip(DayOfWeek.WEDNESDAY, weeklyMask, onUpdateMask)
                DayChip(DayOfWeek.THURSDAY, weeklyMask, onUpdateMask)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DayChip(DayOfWeek.FRIDAY, weeklyMask, onUpdateMask)
                DayChip(DayOfWeek.SATURDAY, weeklyMask, onUpdateMask)
                DayChip(DayOfWeek.SUNDAY, weeklyMask, onUpdateMask)
            }
        }
    }
}

@Composable
private fun DayChip(day: DayOfWeek, weeklyMask: Int, onUpdateMask: (Int) -> Unit) {
    val selected = weeklyMask and dayOfWeekMask(day) != 0
    Tag(
        type = if (selected) TagType.INFO else TagType.DEFAULT,
        onClick = {
            val bit = dayOfWeekMask(day)
            val next = if (selected) weeklyMask and bit.inv() else weeklyMask or bit
            onUpdateMask(next)
        }
    ) {
        Text(day.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()))
    }
}

@Composable
private fun MonthlyPicker(monthlyDay: Int, onUpdateDay: (Int) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.scheduled_tasks_monthly_day),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.scheduled_tasks_monthly_day_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = monthlyDay.toString(),
                onValueChange = { raw ->
                    val value = raw.toIntOrNull() ?: 1
                    onUpdateDay(value)
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
    }
}

@Composable
private fun IntervalPicker(
    intervalValue: Int,
    intervalUnit: Int,
    onUpdateValue: (Int) -> Unit,
    onUpdateUnit: (Int) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.scheduled_tasks_interval),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = intervalValue.toString(),
                    onValueChange = { raw ->
                        val v = raw.toIntOrNull()?.coerceAtLeast(1) ?: 1
                        onUpdateValue(v)
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                data class UnitOption(val unit: Int, val label: String)
                val options = listOf(
                    UnitOption(ScheduledTaskIntervalUnit.HOURS, stringResource(R.string.scheduled_tasks_interval_hours)),
                    UnitOption(ScheduledTaskIntervalUnit.DAYS, stringResource(R.string.scheduled_tasks_interval_days)),
                )
                val selected = options.firstOrNull { it.unit == intervalUnit } ?: options[1]
                Select(
                    options = options,
                    selectedOption = selected,
                    onOptionSelected = { opt -> onUpdateUnit(opt.unit) },
                    optionToString = { it.label },
                    modifier = Modifier.width(140.dp)
                )
            }
        }
    }
}
