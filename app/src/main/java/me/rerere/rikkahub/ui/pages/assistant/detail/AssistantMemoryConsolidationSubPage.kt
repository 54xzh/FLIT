package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import me.rerere.rikkahub.ui.components.ui.HapticSwitch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Psychology
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.ChatEpisodeEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.MemoryReflectionAction
import me.rerere.rikkahub.data.model.MemoryReflectionMode
import me.rerere.rikkahub.utils.toLocalString
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.IconButton
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

@Composable
fun AssistantMemoryConsolidationSubPage(
    vm: AssistantDetailVM,
    assistant: Assistant,
    onUpdate: (Assistant) -> Unit,
    onConsolidate: (Boolean) -> Unit
) {
    val episodes: List<ChatEpisodeEntity> by vm.episodes.collectAsStateWithLifecycle(initialValue = emptyList())
    val stats by vm.episodeStats.collectAsStateWithLifecycle()
    val snackbarMessage: String? by vm.snackbarMessage.collectAsStateWithLifecycle(initialValue = null)
    val reflectionSuggestions: List<MemoryReflectionAction> by vm.reflectionSuggestions.collectAsStateWithLifecycle()
    val isReflectionLoading: Boolean by vm.isReflectionLoading.collectAsStateWithLifecycle()
    val isReflectionSheetVisible: Boolean by vm.isReflectionSheetVisible.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Settings Card
        item {
            Card(
                shape = me.rerere.rikkahub.ui.theme.AppShapes.CardMedium,
                colors = CardDefaults.cardColors(
                    containerColor = if (me.rerere.rikkahub.ui.theme.LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Rounded.Psychology, null, tint = MaterialTheme.colorScheme.primary)
                        Text(
                            text = stringResource(R.string.assistant_page_consolidation_settings_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }


                    // Enable Memory Consolidation
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.assistant_page_consolidation_enable),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = stringResource(R.string.assistant_page_consolidation_enable_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        HapticSwitch(
                            checked = assistant.enableMemoryConsolidation,
                            onCheckedChange = { enabled ->
                                onUpdate(
                                    if (enabled) {
                                        assistant.copy(
                                            enableMemoryConsolidation = true,
                                            enableRecentChatsReference = true,
                                        )
                                    } else {
                                        assistant.copy(enableMemoryConsolidation = false)
                                    }
                                )
                            }
                        )
                    }

                    if (assistant.enableMemory && assistant.enableMemoryConsolidation) {
                        // Consolidation Delay
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(R.string.assistant_page_consolidation_delay_value, assistant.consolidationDelayMinutes),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = stringResource(R.string.assistant_page_consolidation_delay_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            androidx.compose.material3.Slider(
                                value = assistant.consolidationDelayMinutes.toFloat(),
                                onValueChange = { 
                                    onUpdate(assistant.copy(consolidationDelayMinutes = it.toInt())) 
                                },
                                valueRange = 0f..240f, // 0 to 4 hours
                                steps = 23 // 10 min steps approx
                            )
                        }
                    }

                    // Reflection (Track B)
                    if (assistant.enableMemory) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.assistant_page_reflection_enable),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = stringResource(R.string.assistant_page_reflection_enable_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            HapticSwitch(
                                checked = assistant.enableMemoryReflection,
                                onCheckedChange = { enabled ->
                                    onUpdate(
                                        if (enabled) {
                                            assistant.copy(
                                                enableMemoryReflection = true,
                                                enableMemoryConsolidation = true,
                                                enableRecentChatsReference = true,
                                                memoryReflectionMode = if (assistant.memoryReflectionMode == MemoryReflectionMode.OFF) {
                                                    MemoryReflectionMode.CONFIRM
                                                } else {
                                                    assistant.memoryReflectionMode
                                                }
                                            )
                                        } else {
                                            assistant.copy(
                                                enableMemoryReflection = false,
                                                memoryReflectionMode = MemoryReflectionMode.OFF
                                            )
                                        }
                                    )
                                }
                            )
                        }

                        if (assistant.enableMemoryReflection) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = stringResource(R.string.assistant_page_reflection_mode),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Medium
                                )

                                val modes = listOf(
                                    MemoryReflectionMode.CONFIRM,
                                    MemoryReflectionMode.AUTO_CONSERVATIVE,
                                )
                                val selectedIndex = modes.indexOf(assistant.memoryReflectionMode).coerceAtLeast(0)

                                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                    modes.forEachIndexed { index, mode ->
                                        SegmentedButton(
                                            selected = index == selectedIndex,
                                            onClick = { onUpdate(assistant.copy(memoryReflectionMode = mode)) },
                                            shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                                        ) {
                                            Text(
                                                text = when (mode) {
                                                    MemoryReflectionMode.CONFIRM -> stringResource(R.string.assistant_page_reflection_mode_confirm)
                                                    MemoryReflectionMode.AUTO_CONSERVATIVE -> stringResource(R.string.assistant_page_reflection_mode_auto)
                                                    MemoryReflectionMode.OFF -> stringResource(R.string.assistant_page_reflection_mode_confirm)
                                                }
                                            )
                                        }
                                    }
                                }

                                Text(
                                    text = stringResource(R.string.assistant_page_reflection_interval_value, assistant.memoryReflectionIntervalHours),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = stringResource(R.string.assistant_page_reflection_interval_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                androidx.compose.material3.Slider(
                                    value = assistant.memoryReflectionIntervalHours.toFloat(),
                                    onValueChange = {
                                        onUpdate(assistant.copy(memoryReflectionIntervalHours = it.toInt()))
                                    },
                                    valueRange = 1f..72f,
                                    steps = 70
                                )
                            }
                        }
                    }
                }
            }
        }

        if (assistant.enableMemory) {
            // Status Card
            item {
                Card(
                    shape = me.rerere.rikkahub.ui.theme.AppShapes.CardMedium,
                    colors = CardDefaults.cardColors(
                        containerColor = if (me.rerere.rikkahub.ui.theme.LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.tertiary)
                            Text(
                                text = stringResource(R.string.assistant_page_memory_statistics_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
    

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            StatItem(
                                label = stringResource(R.string.assistant_page_memory_stats_core),
                                value = stats.coreMemoryCount.toString()
                            )
                            StatItem(
                                label = stringResource(R.string.assistant_page_memory_stats_episodic),
                                value = stats.totalEpisodes.toString()
                            )
                        }
                        
                        // Detailed Run Stats
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = stringResource(R.string.assistant_page_memory_recent_activity),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            
                            // Track A Stats
                            Column {
                                Text(
                                    text = stringResource(R.string.assistant_page_memory_track_a),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold
                                )
                                if (assistant.lastConsolidationTime > 0) {
                                    val time = java.time.Instant.ofEpochMilli(assistant.lastConsolidationTime)
                                        .atZone(java.time.ZoneId.systemDefault())
                                        .toLocalDateTime()
                                        .toLocalString()
                                    Text(
                                        text = stringResource(R.string.assistant_page_memory_last_run, time),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = stringResource(R.string.assistant_page_activity_result, assistant.lastConsolidationResult),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    Text(
                                        text = stringResource(R.string.assistant_page_memory_no_run),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }

                            // Track B Stats
                            if (assistant.enableMemoryReflection) {
                                Spacer(Modifier.size(8.dp))
                                Column {
                                    Text(
                                        text = stringResource(R.string.assistant_page_memory_track_b),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (assistant.lastReflectionTime > 0) {
                                        val time = java.time.Instant.ofEpochMilli(assistant.lastReflectionTime)
                                            .atZone(java.time.ZoneId.systemDefault())
                                            .toLocalDateTime()
                                            .toLocalString()
                                        Text(
                                            text = stringResource(R.string.assistant_page_memory_last_run, time),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Text(
                                            text = stringResource(R.string.assistant_page_activity_result, assistant.lastReflectionResult),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    } else {
                                        Text(
                                            text = stringResource(R.string.assistant_page_memory_no_run),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                        }

                        Button(
                            onClick = { onConsolidate(true) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = assistant.enableMemoryConsolidation,
                        ) {
                            Icon(Icons.Rounded.Psychology, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.assistant_page_memory_consolidate_now))
                        }

                        Button(
                            onClick = { vm.previewReflection() },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = assistant.enableMemoryReflection && !isReflectionLoading,
                        ) {
                            Icon(Icons.Rounded.AutoAwesome, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.assistant_page_reflection_run_now))
                        }
                        
                        val consolidationMessage = snackbarMessage?.takeIf { it.contains("consolidation") }
                        if (consolidationMessage != null) {
                            Text(
                                text = consolidationMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // Episodes List Section
            item {
                Text(
                    text = stringResource(R.string.assistant_page_memory_episodes_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            
            item {
                if (episodes.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.assistant_page_memory_episodes_count, episodes.count()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    }

    if (isReflectionSheetVisible) {
        ReflectionSuggestionsSheet(
            suggestions = reflectionSuggestions,
            onApply = { selected -> vm.applyReflectionSuggestions(selected) },
            onDismiss = { vm.hideReflectionSheet() },
        )
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ReflectionSuggestionsSheet(
    suggestions: List<MemoryReflectionAction>,
    onApply: (List<MemoryReflectionAction>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val selectable = suggestions.filter { !it.content.isNullOrBlank() }
    var selectedIndexes by remember(selectable) {
        mutableStateOf(
            selectable.indices
                .filter { idx -> selectable[idx].sensitivity != me.rerere.rikkahub.data.model.MemoryReflectionSensitivity.HIGH }
                .toSet()
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        dragHandle = {
            IconButton(
                onClick = {
                    scope.launch {
                        sheetState.hide()
                        onDismiss()
                    }
                }
            ) {
                Icon(Icons.Rounded.KeyboardArrowDown, null)
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.assistant_page_reflection_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 4.dp)
            )

            if (selectable.isEmpty()) {
                Text(
                    text = stringResource(R.string.assistant_page_reflection_no_suggestions),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(selectable) { index, item ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            val checked = selectedIndexes.contains(index)
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { isChecked ->
                                    selectedIndexes = if (isChecked) {
                                        selectedIndexes + index
                                    } else {
                                        selectedIndexes - index
                                    }
                                }
                            )
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = item.content.orEmpty(),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                val meta = buildList {
                                    add(item.stability.name)
                                    item.confidence?.let { add("conf=${"%.2f".format(it)}") }
                                    if (item.evidence_episode_ids.isNotEmpty()) add("evidence=${item.evidence_episode_ids.joinToString(",")}")
                                    item.review_in_days?.let { add("review=${it}d") }
                                }.joinToString(" · ")
                                if (meta.isNotBlank()) {
                                    Text(
                                        text = meta,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (item.reason.isNotBlank()) {
                                    Text(
                                        text = item.reason,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                Button(
                    onClick = {
                        val selected = selectable.filterIndexed { index, _ -> selectedIndexes.contains(index) }
                        onApply(selected)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = selectedIndexes.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.assistant_page_reflection_apply_selected))
                }
            }
        }
    }
}
