package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.localai.LocalModelDownloadManager
import me.rerere.rikkahub.data.localai.LocalModelRepository
import me.rerere.rikkahub.data.localai.LocalRuntimeManager
import me.rerere.rikkahub.data.localai.LocalRuntimePackage
import me.rerere.rikkahub.data.localai.RuntimeDownloadManager
import me.rerere.rikkahub.data.localai.RuntimePackageInstaller
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import org.koin.compose.koinInject

@Composable
internal fun LocalProviderRemovalDialog(onDismiss: () -> Unit, onRemove: suspend () -> Unit) {
    val models = koinInject<LocalModelRepository>()
    val modelDownloads = koinInject<LocalModelDownloadManager>()
    val runtime = koinInject<LocalRuntimeManager>()
    val runtimeDownloads = koinInject<RuntimeDownloadManager>()
    val installer = koinInject<RuntimePackageInstaller>()
    val scope = rememberCoroutineScope()
    val haptics = rememberPremiumHaptics()
    var removeFiles by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.local_provider_remove_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.selectableGroup()) {
                    listOf(false, true).forEach { deleteFiles ->
                        Row(
                            modifier = Modifier.fillMaxWidth().selectable(
                                selected = removeFiles == deleteFiles,
                                enabled = !busy,
                                role = Role.RadioButton,
                                onClick = {
                                    haptics.perform(HapticPattern.Pop)
                                    removeFiles = deleteFiles
                                },
                            ).padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            RadioButton(selected = removeFiles == deleteFiles, onClick = null, enabled = !busy)
                            Text(stringResource(if (deleteFiles) R.string.local_provider_remove_all else R.string.local_provider_remove_entry))
                        }
                    }
                }
                Text(stringResource(if (removeFiles) R.string.local_provider_remove_all_description else R.string.local_provider_remove_entry_description))
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (failed) Text(stringResource(R.string.local_provider_remove_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            RemovalButton(stringResource(R.string.cancel), enabled = !busy, onClick = onDismiss)
        },
        confirmButton = {
            RemovalButton(stringResource(R.string.delete), enabled = !busy) {
                busy = true
                failed = false
                scope.launch {
                    try {
                        if (removeFiles) {
                            modelDownloads.cancelAll()
                            LocalRuntimePackage.entries.forEach { runtimeDownloads.pause(it) }
                            runtime.withReleasedModel {
                                models.removeAll()
                                installer.removeAll()
                            }
                        }
                        onRemove()
                        onDismiss()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        failed = true
                    } finally {
                        busy = false
                    }
                }
            }
        },
    )
}

@Composable
private fun RemovalButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.85f else 1f, spring(dampingRatio = 0.5f, stiffness = 400f))
    val haptics = rememberPremiumHaptics()
    TextButton(
        onClick = { haptics.perform(HapticPattern.Pop); onClick() },
        enabled = enabled,
        interactionSource = interaction,
        modifier = Modifier.scale(scale),
    ) { Text(text) }
}
