package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Summarize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.pages.setting.components.SettingGroupItem
import me.rerere.rikkahub.ui.pages.setting.components.SettingsGroup
import me.rerere.rikkahub.ui.theme.AppShapes
import me.rerere.rikkahub.ui.theme.LocalDarkMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContextManagementSheet(
    activeInjectionCount: Int,
    injectionSummary: String,
    showSummarize: Boolean,
    showClearThoughtSignatures: Boolean,
    onOpenInjection: () -> Unit,
    onOpenSummarize: () -> Unit,
    onOpenClearThoughtSignatures: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val isDarkMode = LocalDarkMode.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = if (isDarkMode) Color.Black else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.minimal_input_context_management),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )

            SettingsGroup {
                SettingGroupItem(
                    title = stringResource(R.string.injection_picker_title),
                    subtitle = injectionSummary,
                    icon = {
                        Icon(
                            imageVector = Icons.Rounded.AutoFixHigh,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = if (activeInjectionCount > 0) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                    trailing = {
                        Icon(
                            imageVector = Icons.Rounded.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    onClick = onOpenInjection,
                )

                if (showSummarize) {
                    SettingGroupItem(
                        title = stringResource(R.string.context_management_summarize_title),
                        subtitle = stringResource(R.string.context_management_summarize_desc),
                        icon = {
                            Icon(
                                imageVector = Icons.Rounded.Summarize,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        trailing = {
                            Icon(
                                imageVector = Icons.Rounded.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        onClick = onOpenSummarize,
                    )
                }

                if (showClearThoughtSignatures) {
                    SettingGroupItem(
                        title = stringResource(R.string.clear_thought_signatures_title),
                        subtitle = stringResource(R.string.clear_thought_signatures_desc),
                        icon = {
                            Icon(
                                imageVector = Icons.Rounded.CleaningServices,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailing = {
                            Icon(
                                imageVector = Icons.Rounded.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        onClick = onOpenClearThoughtSignatures,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ClearThoughtSignaturesConfirmDialog(
    onConfirm: suspend () -> ChatService.ClearThoughtSignaturesResult,
    onResult: (ChatService.ClearThoughtSignaturesResult) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptics = rememberPremiumHaptics()
    var isClearing by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isClearing) onDismiss() },
        modifier = Modifier.padding(horizontal = 24.dp),
        shape = AppShapes.CardLarge,
        containerColor = if (LocalDarkMode.current) {
            MaterialTheme.colorScheme.surfaceContainerLow
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        title = {
            Text(
                text = stringResource(R.string.clear_thought_signatures_confirm_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Text(
                text = stringResource(R.string.clear_thought_signatures_confirm_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            Button(
                enabled = !isClearing,
                shape = AppShapes.ButtonPill,
                onClick = {
                    if (isClearing) return@Button
                    haptics.perform(HapticPattern.Pop)
                    isClearing = true
                    scope.launch {
                        val result = onConfirm()
                        isClearing = false
                        onResult(result)
                    }
                },
            ) {
                if (isClearing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.CleaningServices,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.clear_thought_signatures_confirm_action))
            }
        },
        dismissButton = {
            OutlinedButton(
                enabled = !isClearing,
                shape = AppShapes.ButtonPill,
                onClick = {
                    haptics.perform(HapticPattern.Pop)
                    onDismiss()
                },
            ) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
