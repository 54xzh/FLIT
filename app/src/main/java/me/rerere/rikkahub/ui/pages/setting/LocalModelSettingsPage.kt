package me.rerere.rikkahub.ui.pages.setting

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.localai.LocalModelRepository
import me.rerere.rikkahub.data.localai.LocalModelState
import me.rerere.rikkahub.data.localai.LocalRuntimeManager
import me.rerere.rikkahub.data.localai.LocalRuntimeState
import me.rerere.rikkahub.data.localai.RuntimePackageInstaller
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalModelSettingsPage() {
    val repository = koinInject<LocalModelRepository>()
    val runtime = koinInject<LocalRuntimeManager>()
    val runtimeInstaller = koinInject<RuntimePackageInstaller>()
    val models by repository.observeModels().collectAsStateWithLifecycle(emptyList())
    val runtimeState by runtime.state.collectAsStateWithLifecycle()
    val runtimeReady = runtimeState is LocalRuntimeState.Ready || runtimeState is LocalRuntimeState.Loaded
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val haptics = rememberPremiumHaptics()
    val runtimeImportedText = stringResource(R.string.local_models_runtime_imported)
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            repository.importModel(uri).onSuccess {
                toaster.show(it.displayName)
            }.onFailure {
                toaster.show(it.message ?: "")
            }
        }
    }
    val runtimeImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { runtimeInstaller.installFromUri(uri) }
                .onSuccess {
                    runtime.refresh()
                    toaster.show(runtimeImportedText)
                }
                .onFailure { toaster.show(it.message.orEmpty()) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.local_models_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { scope.launch { runtime.release() } }) {
                        Icon(
                            imageVector = Icons.Rounded.PowerSettingsNew,
                            contentDescription = stringResource(R.string.local_models_release),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                    shape = me.rerere.rikkahub.ui.theme.AppShapes.CardLarge,
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.local_models_runtime), style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (runtimeReady) stringResource(R.string.local_models_runtime_ready)
                            else stringResource(R.string.local_models_runtime_missing),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (!runtimeReady) {
                            Text(
                                stringResource(R.string.local_models_runtime_release_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                haptics.perform(HapticPattern.Pop)
                                runtimeImporter.launch(arrayOf("application/zip", "application/x-zip-compressed"))
                            },
                        ) { Text(stringResource(R.string.local_models_import_runtime)) }
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = {
                        haptics.perform(HapticPattern.Pop)
                        importer.launch(arrayOf("application/octet-stream", "application/*"))
                    },
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                ) { Text(stringResource(R.string.local_models_import)) }
            }
            item {
                Text(
                    stringResource(R.string.local_models_installed),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            items(models, key = { it.entity.modelId }) { record ->
                Card(
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                    shape = me.rerere.rikkahub.ui.theme.AppShapes.CardMedium,
                    colors = CardDefaults.cardColors(),
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(record.entity.displayName, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${record.format.extension.uppercase()} · ${record.entity.sizeBytes / (1024 * 1024)} MB · " +
                                    if (record.state == LocalModelState.READY) stringResource(R.string.local_models_ready)
                                    else record.state.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = {
                            haptics.perform(HapticPattern.Thud)
                            scope.launch { repository.remove(kotlin.uuid.Uuid.parse(record.entity.modelId)) }
                        }) { Text(stringResource(R.string.delete)) }
                    }
                }
            }
        }
    }
}
