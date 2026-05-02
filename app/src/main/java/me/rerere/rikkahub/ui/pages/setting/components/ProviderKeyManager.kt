package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderApiKey
import me.rerere.ai.provider.ProviderKeyStrategy
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.activeApiKeyValuesForRequest
import me.rerere.ai.provider.copyWithApiKeyConfig
import me.rerere.ai.provider.getApiKeyValue
import me.rerere.ai.provider.getProviderApiKeys
import me.rerere.ai.provider.getProviderKeyStrategy
import me.rerere.ai.provider.isMultiKeyEnabled
import me.rerere.ai.provider.normalizedProviderApiKeys
import me.rerere.ai.provider.splitProviderApiKeys
import me.rerere.ai.provider.syncEnabledApiKeysToLegacyField
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.HapticSwitch
import me.rerere.rikkahub.ui.components.ui.ToastType
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.theme.AppShapes
import me.rerere.rikkahub.ui.theme.extendColors
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.readClipboardText
import org.koin.compose.koinInject

@Composable
fun ColumnScope.ProviderMultiKeySection(
    provider: ProviderSetting,
    onEdit: (ProviderSetting) -> Unit,
) {
    var showManager by remember { mutableStateOf(false) }
    val activeCount = provider.activeApiKeyValuesForRequest().size

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.setting_provider_page_multi_key_mode))
            Text(
                text = stringResource(R.string.setting_provider_page_multi_key_mode_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HapticSwitch(
            checked = provider.isMultiKeyEnabled(),
            onCheckedChange = { enabled ->
                val updated = if (enabled) {
                    provider.enableMultiKeyFromCurrentValue()
                } else {
                    provider.copyWithApiKeyConfig(multiKeyEnabled = false)
                }
                onEdit(updated.syncEnabledApiKeysToLegacyField())
            },
        )
    }

    AnimatedVisibility(visible = provider.isMultiKeyEnabled()) {
        FilledTonalButton(
            onClick = { showManager = true },
            modifier = Modifier.fillMaxWidth(),
            shape = AppShapes.ButtonPill,
        ) {
            Icon(Icons.Rounded.Settings, contentDescription = null)
            Text(
                text = stringResource(
                    R.string.setting_provider_page_manage_multi_keys_with_count,
                    activeCount,
                    provider.getProviderApiKeys().normalizedProviderApiKeys().size,
                ),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }

    if (showManager) {
        ProviderKeyManagerSheet(
            provider = provider,
            onDismissRequest = { showManager = false },
            onProviderChange = onEdit,
        )
    }
}

private fun ProviderSetting.enableMultiKeyFromCurrentValue(): ProviderSetting {
    val existingKeys = getProviderApiKeys().normalizedProviderApiKeys()
    val importedKeys = splitProviderApiKeys(getApiKeyValue()).map { value ->
        ProviderApiKey(value = value)
    }
    return copyWithApiKeyConfig(
        multiKeyEnabled = true,
        apiKeys = (existingKeys.ifEmpty { importedKeys }).normalizedProviderApiKeys(),
    ).syncEnabledApiKeysToLegacyField()
}

@Composable
private fun ProviderKeyManagerSheet(
    provider: ProviderSetting,
    onDismissRequest: () -> Unit,
    onProviderChange: (ProviderSetting) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val haptics = rememberPremiumHaptics()
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val keys = provider.getProviderApiKeys().normalizedProviderApiKeys()
    val activeCount = keys.count { it.enabled }
    val testModel = remember(provider) {
        provider.models.firstOrNull { it.type == ModelType.CHAT }
    }

    var editingKey by remember { mutableStateOf<ProviderApiKey?>(null) }
    var importText by remember { mutableStateOf<String?>(null) }

    fun updateKeys(updatedKeys: List<ProviderApiKey>) {
        onProviderChange(
            provider.copyWithApiKeyConfig(
                multiKeyEnabled = true,
                apiKeys = updatedKeys.normalizedProviderApiKeys(),
            ).syncEnabledApiKeysToLegacyField()
        )
    }

    fun hide() {
        scope.launch {
            sheetState.hide()
            onDismissRequest()
        }
    }

    ModalBottomSheet(
        onDismissRequest = ::hide,
        sheetState = sheetState,
        shape = AppShapes.BottomSheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = {
            IconButton(
                onClick = {
                    haptics.perform(HapticPattern.Pop)
                    hide()
                },
            ) {
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null)
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.setting_provider_page_multi_key_manager),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = stringResource(
                        R.string.setting_provider_page_multi_key_summary,
                        activeCount,
                        keys.size,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ProviderKeyStrategySelector(
                strategy = provider.getProviderKeyStrategy(),
                onStrategyChange = { strategy ->
                    haptics.perform(HapticPattern.Pop)
                    onProviderChange(provider.copyWithApiKeyConfig(keyStrategy = strategy))
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = {
                        haptics.perform(HapticPattern.Pop)
                        editingKey = ProviderApiKey()
                    },
                    modifier = Modifier.weight(1f),
                    shape = AppShapes.ButtonPill,
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Text(
                        text = stringResource(R.string.setting_provider_page_multi_key_add),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                OutlinedButton(
                    onClick = {
                        haptics.perform(HapticPattern.Pop)
                        importText = context.readClipboardText()
                    },
                    modifier = Modifier.weight(1f),
                    shape = AppShapes.ButtonPill,
                ) {
                    Icon(Icons.Rounded.ContentPaste, contentDescription = null)
                    Text(
                        text = stringResource(R.string.setting_provider_page_multi_key_import),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }

            if (testModel == null) {
                Text(
                    text = stringResource(R.string.setting_provider_page_multi_key_test_needs_model),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (keys.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = AppShapes.CardMedium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.setting_provider_page_multi_key_empty),
                        modifier = Modifier.padding(18.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(
                        items = keys,
                        key = { _, key -> key.id.toString() },
                    ) { index, key ->
                        ProviderApiKeyCard(
                            index = index,
                            apiKey = key,
                            testModel = testModel,
                            provider = provider,
                            onToggle = { enabled ->
                                updateKeys(keys.map {
                                    if (it.id == key.id) it.copy(enabled = enabled) else it
                                })
                            },
                            onEdit = {
                                haptics.perform(HapticPattern.Pop)
                                editingKey = key
                            },
                            onDelete = {
                                haptics.perform(HapticPattern.Thud)
                                updateKeys(keys.filterNot { it.id == key.id })
                            },
                        )
                    }
                }
            }
        }
    }

    if (editingKey != null) {
        ProviderApiKeyEditDialog(
            initial = editingKey ?: ProviderApiKey(),
            onDismissRequest = { editingKey = null },
            onConfirm = { editedKey ->
                val updated = if (keys.any { it.id == editedKey.id }) {
                    keys.map { if (it.id == editedKey.id) editedKey else it }
                } else {
                    keys + editedKey
                }
                updateKeys(updated)
                haptics.perform(HapticPattern.Success)
                editingKey = null
            },
        )
    }

    if (importText != null) {
        ProviderApiKeyImportDialog(
            initialText = importText.orEmpty(),
            onDismissRequest = { importText = null },
            onImport = { raw ->
                val existingValues = keys.map { it.value }.toSet()
                val importedKeys = splitProviderApiKeys(raw)
                    .filterNot { it in existingValues }
                    .map { value -> ProviderApiKey(value = value) }
                if (importedKeys.isEmpty()) {
                    haptics.perform(HapticPattern.Error)
                    toaster.show(
                        message = context.getString(R.string.setting_provider_page_multi_key_import_empty),
                        type = ToastType.Warning,
                    )
                } else {
                    updateKeys(keys + importedKeys)
                    haptics.perform(HapticPattern.Success)
                    toaster.show(
                        message = context.getString(
                            R.string.setting_provider_page_multi_key_imported,
                            importedKeys.size,
                        ),
                        type = ToastType.Success,
                    )
                    importText = null
                }
            },
        )
    }
}

@Composable
private fun ProviderKeyStrategySelector(
    strategy: ProviderKeyStrategy,
    onStrategyChange: (ProviderKeyStrategy) -> Unit,
) {
    val options = listOf(
        ProviderKeyStrategy.RANDOM to stringResource(R.string.setting_provider_page_multi_key_strategy_random),
        ProviderKeyStrategy.ROUND_ROBIN to stringResource(R.string.setting_provider_page_multi_key_strategy_round_robin),
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = strategy == option.first,
                onClick = { onStrategyChange(option.first) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                label = { Text(option.second) },
            )
        }
    }
}

@Composable
private fun ProviderApiKeyCard(
    index: Int,
    apiKey: ProviderApiKey,
    testModel: Model?,
    provider: ProviderSetting,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val providerManager = koinInject<ProviderManager>()
    val scope = rememberCoroutineScope()
    val haptics = rememberPremiumHaptics()
    var testState: UiState<String> by remember(apiKey.id) { mutableStateOf(UiState.Idle) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.CardMedium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = apiKey.alias.ifBlank {
                            stringResource(R.string.setting_provider_page_multi_key_default_alias, index + 1)
                        },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = maskProviderApiKey(apiKey.value),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                HapticSwitch(
                    checked = apiKey.enabled,
                    onCheckedChange = onToggle,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TextButton(
                    onClick = {
                        if (testModel == null) {
                            testState = UiState.Error(IllegalStateException("No chat model"))
                            haptics.perform(HapticPattern.Error)
                            return@TextButton
                        }
                        scope.launch {
                            testState = UiState.Loading
                            runCatching {
                                testProviderApiKey(
                                    providerManager = providerManager,
                                    provider = provider,
                                    apiKey = apiKey.value,
                                    model = testModel,
                                )
                            }.onSuccess {
                                haptics.perform(HapticPattern.Success)
                                testState = UiState.Success("Success")
                            }.onFailure {
                                haptics.perform(HapticPattern.Error)
                                testState = UiState.Error(it)
                            }
                        }
                    },
                    enabled = testState !is UiState.Loading,
                ) {
                    Icon(Icons.Rounded.NetworkCheck, contentDescription = null)
                    Text(
                        text = stringResource(R.string.setting_provider_page_test),
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                TextButton(onClick = onEdit) {
                    Icon(Icons.Rounded.Edit, contentDescription = null)
                    Text(
                        text = stringResource(R.string.edit),
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.delete),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            when (val state = testState) {
                UiState.Idle -> Unit
                UiState.Loading -> LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                is UiState.Success -> Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.extendColors.green6,
                    )
                    Text(
                        text = stringResource(R.string.setting_provider_page_test_success),
                        color = MaterialTheme.extendColors.green6,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                is UiState.Error -> Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.extendColors.red6,
                    )
                    Text(
                        text = state.error.message ?: "Error",
                        color = MaterialTheme.extendColors.red6,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderApiKeyEditDialog(
    initial: ProviderApiKey,
    onDismissRequest: () -> Unit,
    onConfirm: (ProviderApiKey) -> Unit,
) {
    var alias by remember(initial.id) { mutableStateOf(initial.alias) }
    var value by remember(initial.id) { mutableStateOf(initial.value) }
    var visible by remember(initial.id) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(stringResource(R.string.setting_provider_page_multi_key_edit_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = { Text(stringResource(R.string.setting_provider_page_multi_key_alias)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.trim() },
                    label = { Text(stringResource(R.string.setting_provider_page_api_key)) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = if (visible) 4 else 1,
                    visualTransformation = if (visible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                imageVector = if (visible) {
                                    Icons.Rounded.VisibilityOff
                                } else {
                                    Icons.Rounded.Visibility
                                },
                                contentDescription = null,
                            )
                        }
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = value.isNotBlank(),
                onClick = {
                    onConfirm(
                        initial.copy(
                            alias = alias.trim(),
                            value = value.trim(),
                        )
                    )
                },
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun ProviderApiKeyImportDialog(
    initialText: String,
    onDismissRequest: () -> Unit,
    onImport: (String) -> Unit,
) {
    var text by remember(initialText) { mutableStateOf(initialText) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(stringResource(R.string.setting_provider_page_multi_key_import_title))
        },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 5,
                maxLines = 8,
                label = {
                    Text(stringResource(R.string.setting_provider_page_multi_key_import_input))
                },
                supportingText = {
                    Text(stringResource(R.string.setting_provider_page_multi_key_import_hint))
                },
            )
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onImport(text) },
            ) {
                Text(stringResource(R.string.setting_provider_page_multi_key_import_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

private suspend fun testProviderApiKey(
    providerManager: ProviderManager,
    provider: ProviderSetting,
    apiKey: String,
    model: Model,
) {
    val messages = listOf(UIMessage.user("hello"))
    val params = TextGenerationParams(model = model)
    when (provider) {
        is ProviderSetting.OpenAI -> {
            val testProvider = provider.copy(
                apiKey = apiKey,
                multiKeyEnabled = false,
                apiKeys = emptyList(),
            )
            providerManager.getProviderByType(testProvider).generateText(testProvider, messages, params)
        }

        is ProviderSetting.Google -> {
            val testProvider = provider.copy(
                apiKey = apiKey,
                multiKeyEnabled = false,
                apiKeys = emptyList(),
            )
            providerManager.getProviderByType(testProvider).generateText(testProvider, messages, params)
        }

        is ProviderSetting.Claude -> {
            val testProvider = provider.copy(
                apiKey = apiKey,
                multiKeyEnabled = false,
                apiKeys = emptyList(),
            )
            providerManager.getProviderByType(testProvider).generateText(testProvider, messages, params)
        }
    }
}

private fun maskProviderApiKey(value: String): String {
    val trimmed = value.trim()
    if (trimmed.length <= 8) return "****"
    return "${trimmed.take(4)}...${trimmed.takeLast(4)}"
}
