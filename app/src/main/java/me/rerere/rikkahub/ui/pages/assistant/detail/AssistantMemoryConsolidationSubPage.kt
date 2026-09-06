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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import me.rerere.rikkahub.ui.components.ui.HapticSwitch
import androidx.compose.material3.Text
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import me.rerere.rikkahub.data.db.dao.ChatEpisodeUiEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.utils.toLocalString

@Composable
fun AssistantMemoryConsolidationSubPage(
    vm: AssistantDetailVM,
    assistant: Assistant,
    onUpdate: (Assistant) -> Unit,
    onConsolidate: (Boolean) -> Unit
) {
    val haptics = rememberPremiumHaptics()
    val episodes: List<ChatEpisodeUiEntity> by vm.episodes.collectAsStateWithLifecycle(initialValue = emptyList())
    val stats by vm.episodeStats.collectAsStateWithLifecycle()
    val consolidationProgress by vm.memoryConsolidationProgress.collectAsStateWithLifecycle()
    val pendingCount by vm.pendingConsolidationCount.collectAsStateWithLifecycle()
    val snackbarMessage: String? by vm.snackbarMessage.collectAsStateWithLifecycle(initialValue = null)

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
                                if (!enabled && consolidationProgress.isRunning) {
                                    vm.cancelMemoryConsolidation()
                                }
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
                        }

                        val isRunning = consolidationProgress.isRunning
                        val canStart = pendingCount > 0

                        Button(
                            onClick = {
                                haptics.perform(HapticPattern.Pop)
                                if (isRunning) {
                                    vm.cancelMemoryConsolidation()
                                } else {
                                    onConsolidate(true)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = if (isRunning) true else (canStart && assistant.enableMemoryConsolidation),
                        ) {
                            Icon(
                                imageVector = if (isRunning) Icons.Rounded.Close else Icons.Rounded.Psychology,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(
                                    if (isRunning) {
                                        R.string.assistant_page_memory_consolidation_cancel
                                    } else {
                                        R.string.assistant_page_memory_consolidate_now
                                    }
                                )
                            )
                        }

                        AnimatedVisibility(
                            visible = isRunning,
                            enter = expandVertically(spring(dampingRatio = 0.6f, stiffness = 300f)) + fadeIn(),
                            exit = shrinkVertically(spring(dampingRatio = 0.6f, stiffness = 300f)) + fadeOut(),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                val progressText = if (consolidationProgress.total > 0) {
                                    stringResource(
                                        R.string.assistant_page_memory_consolidation_progress,
                                        consolidationProgress.current,
                                        consolidationProgress.total,
                                    )
                                } else {
                                    stringResource(R.string.assistant_page_memory_consolidation_preparing)
                                }
                                Text(
                                    text = progressText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                if (consolidationProgress.total > 0) {
                                    LinearWavyProgressIndicator(
                                        progress = {
                                            consolidationProgress.current.toFloat() / consolidationProgress.total.coerceAtLeast(1)
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                } else {
                                    LinearWavyProgressIndicator(
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }

                        if (!isRunning) {
                            val statusText = if (pendingCount > 0) {
                                stringResource(R.string.assistant_page_memory_pending_consolidation_count, pendingCount)
                            } else {
                                stringResource(R.string.assistant_page_memory_all_consolidated)
                            }
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (pendingCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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
