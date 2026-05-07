package me.rerere.rikkahub.ui.pages.storage

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.repository.CacheTopLevelUsage
import me.rerere.rikkahub.data.repository.StorageCategoryUsage
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.theme.AppShapes
import me.rerere.rikkahub.utils.UiState

@Composable
fun StorageCacheCard(
    usageState: UiState<StorageCategoryUsage>,
    topLevelUsageState: UiState<List<CacheTopLevelUsage>>,
    onClearCache: () -> Unit,
) {
    val context = LocalContext.current
    val haptics = rememberPremiumHaptics()
    var showConfirm by rememberSaveable { mutableStateOf(false) }

    Card(
        shape = AppShapes.CardLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.storage_cache_title),
                style = MaterialTheme.typography.titleMedium,
            )

            when (usageState) {
                UiState.Idle,
                UiState.Loading,
                -> Text(
                    text = stringResource(R.string.storage_manager_loading_placeholder),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                is UiState.Error -> Text(
                    text = usageState.error.message ?: "Error",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )

                is UiState.Success -> {
                    val bytes = usageState.data.bytes
                    val count = usageState.data.fileCount
                    val sizeText = runCatching { Formatter.formatShortFileSize(context, bytes) }.getOrNull()
                        ?: "${bytes} B"

                    Text(
                        text = stringResource(R.string.storage_category_usage_value, sizeText, count),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            CacheTopLevelUsageList(state = topLevelUsageState)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FilledTonalButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        haptics.perform(HapticPattern.Pop)
                        showConfirm = true
                    },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Icon(Icons.Rounded.DeleteForever, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.storage_action_clear_cache))
                }
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.storage_confirm_clear_cache_title)) },
            text = { Text(stringResource(R.string.storage_confirm_clear_cache_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptics.perform(HapticPattern.Thud)
                        showConfirm = false
                        onClearCache()
                    }
                ) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun CacheTopLevelUsageList(
    state: UiState<List<CacheTopLevelUsage>>,
) {
    val context = LocalContext.current

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.storage_cache_top_level_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )

        when (state) {
            UiState.Idle,
            UiState.Loading,
            -> Text(
                text = stringResource(R.string.storage_manager_loading_placeholder),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            is UiState.Error -> Text(
                text = state.error.message ?: "Error",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )

            is UiState.Success -> {
                if (state.data.isEmpty()) {
                    Text(
                        text = stringResource(R.string.storage_cache_top_level_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    state.data.forEach { entry ->
                        CacheTopLevelUsageRow(
                            entry = entry,
                            sizeText = runCatching {
                                Formatter.formatShortFileSize(context, entry.bytes)
                            }.getOrNull() ?: "${entry.bytes} B",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CacheTopLevelUsageRow(
    entry: CacheTopLevelUsage,
    sizeText: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (entry.isDirectory) {
                    stringResource(R.string.storage_cache_top_level_directory_subtitle, entry.fileCount)
                } else {
                    stringResource(R.string.storage_cache_top_level_file_subtitle)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Text(
            text = sizeText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
fun StorageLogsCard(
    onOpenLogs: () -> Unit,
) {
    Card(
        shape = AppShapes.CardLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.storage_logs_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.storage_logs_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = onOpenLogs) {
                Text(stringResource(R.string.storage_action_open_logs))
            }
        }
    }
}
