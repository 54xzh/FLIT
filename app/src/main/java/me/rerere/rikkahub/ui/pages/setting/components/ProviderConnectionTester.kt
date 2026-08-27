package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.theme.extendColors
import me.rerere.rikkahub.utils.UiState
import org.koin.compose.koinInject

@Composable
fun ProviderConnectionTester(
    provider: ProviderSetting,
) {
    var showTestDialog by remember { mutableStateOf(false) }
    val providerManager = koinInject<ProviderManager>()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val haptics = rememberPremiumHaptics()

    IconButton(
        onClick = {
            haptics.perform(HapticPattern.Pop)
            showTestDialog = true
        },
    ) {
        Icon(Icons.Rounded.NetworkCheck, contentDescription = null)
    }

    if (!showTestDialog) return

    var model by remember(provider) {
        mutableStateOf(provider.models.firstOrNull { it.type == ModelType.CHAT })
    }
    var nonStreamingState: UiState<String> by remember { mutableStateOf(UiState.Idle) }
    var streamingState: UiState<String> by remember { mutableStateOf(UiState.Idle) }
    var toolsState: UiState<String> by remember { mutableStateOf(UiState.Idle) }
    var streamingText by remember { mutableStateOf("") }

    fun resetStates() {
        nonStreamingState = UiState.Idle
        streamingState = UiState.Idle
        toolsState = UiState.Idle
        streamingText = ""
    }

    val isRunning = nonStreamingState is UiState.Loading ||
        streamingState is UiState.Loading ||
        toolsState is UiState.Loading

    AlertDialog(
        onDismissRequest = {
            haptics.perform(HapticPattern.Cancel)
            showTestDialog = false
        },
        title = { Text(stringResource(R.string.setting_provider_page_test_connection)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ModelSelector(
                    modelId = model?.id,
                    providers = listOf(provider),
                    type = ModelType.CHAT,
                    includeDisabledProviders = true,
                    modifier = Modifier.fillMaxWidth(),
                    onSelect = { model = it },
                )

                TestResultItem(
                    label = stringResource(R.string.setting_provider_page_test_non_streaming),
                    state = nonStreamingState,
                    resultText = (nonStreamingState as? UiState.Success)?.data.orEmpty(),
                )
                TestResultItem(
                    label = stringResource(R.string.setting_provider_page_test_streaming),
                    state = streamingState,
                    resultText = streamingText,
                )
                TestResultItem(
                    label = stringResource(R.string.setting_provider_page_test_tool_call),
                    state = toolsState,
                    resultText = (toolsState as? UiState.Success)?.data.orEmpty(),
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    haptics.perform(HapticPattern.Cancel)
                    showTestDialog = false
                },
            ) {
                Text(stringResource(R.string.cancel))
            }
        },
        confirmButton = {
            TextButton(
                enabled = model != null && !isRunning,
                onClick = {
                    val selectedModel = model ?: return@TextButton
                    val providerInstance = providerManager.getProviderByType(provider)
                    haptics.perform(HapticPattern.Pop)
                    resetStates()
                    scope.launch {
                        launch {
                            runCatching {
                                nonStreamingState = UiState.Loading
                                val response = providerInstance.generateText(
                                    providerSetting = provider,
                                    messages = testMessages(),
                                    params = selectedModel.testParams(),
                                )
                                response.requireMessage().toContentText()
                            }.onSuccess { text ->
                                nonStreamingState = UiState.Success(text)
                                haptics.perform(HapticPattern.Success)
                            }.onFailure { error ->
                                nonStreamingState = UiState.Error(error)
                                haptics.perform(HapticPattern.Error)
                            }
                        }
                        launch {
                            runCatching {
                                streamingState = UiState.Loading
                                var streamedMessage = UIMessage.assistant("")
                                providerInstance.streamText(
                                    providerSetting = provider,
                                    messages = testMessages(),
                                    params = selectedModel.testParams(),
                                ).collect { chunk ->
                                    streamedMessage += chunk
                                    streamingText = streamedMessage.toContentText()
                                }
                            }.onSuccess {
                                streamingState = UiState.Success(streamingText)
                                haptics.perform(HapticPattern.Success)
                            }.onFailure { error ->
                                streamingState = UiState.Error(error)
                                haptics.perform(HapticPattern.Error)
                            }
                        }
                        launch {
                            runCatching {
                                toolsState = UiState.Loading
                                val response = providerInstance.generateText(
                                    providerSetting = provider,
                                    messages = listOf(
                                        UIMessage.system("You are a helpful assistant"),
                                        UIMessage.user("Use the get_current_time tool."),
                                    ),
                                    params = selectedModel.testParams(
                                        tools = listOf(
                                            Tool(
                                                name = "get_current_time",
                                                description = "Get the current date and time.",
                                                execute = { JsonNull },
                                            ),
                                        ),
                                    ),
                                )
                                val message = response.requireMessage()
                                val toolCall = message.getToolCalls().firstOrNull()
                                if (toolCall != null) {
                                    context.getString(
                                        R.string.setting_provider_page_test_tool_called,
                                        toolCall.toolName,
                                        toolCall.arguments,
                                    )
                                } else {
                                    context.getString(
                                        R.string.setting_provider_page_test_tool_not_called,
                                        message.toContentText(),
                                    )
                                }
                            }.onSuccess { result ->
                                toolsState = UiState.Success(result)
                                haptics.perform(HapticPattern.Success)
                            }.onFailure { error ->
                                toolsState = UiState.Error(error)
                                haptics.perform(HapticPattern.Error)
                            }
                        }
                    }
                },
            ) {
                Text(stringResource(R.string.setting_provider_page_test))
            }
        },
    )
}

private fun testMessages(): List<UIMessage> = listOf(
    UIMessage.system("You are a helpful assistant"),
    UIMessage.user("hello"),
)

private fun Model.testParams(tools: List<Tool> = emptyList()): TextGenerationParams =
    TextGenerationParams(
        model = this,
        tools = tools,
        customHeaders = customHeaders,
        customBody = customBodies,
    )

private fun MessageChunk.requireMessage(): UIMessage = choices.firstOrNull()?.message
    ?: choices.firstOrNull()?.delta
    ?: error("The provider returned no message")

@Composable
private fun TestResultItem(
    label: String,
    state: UiState<String>,
    resultText: String,
) {
    var showErrorSheet by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(120.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        when (state) {
            UiState.Idle -> Text(
                text = "—",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            UiState.Loading -> LinearWavyProgressIndicator(modifier = Modifier.weight(1f))

            is UiState.Success -> Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "✓",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.extendColors.green6,
                )
                if (resultText.isNotBlank()) {
                    Text(
                        text = resultText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            is UiState.Error -> Text(
                text = state.error.message ?: "Error",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.extendColors.red6,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable { showErrorSheet = true },
            )
        }
    }

    if (showErrorSheet && state is UiState.Error) {
        ModalBottomSheet(
            onDismissRequest = { showErrorSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.8f)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = label, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = state.error.message ?: "Error",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.extendColors.red6,
                )
                Text(
                    text = state.error.stackTraceToString(),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
