package me.rerere.rikkahub.ui.activity

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.model.DEFAULT_TEXT_SELECTION_ACTIONS

private const val TAG = "TextSelectionVM"

/**
 * Quick action types for text selection
 */
enum class QuickAction {
    TRANSLATE,
    EXPLAIN,
    SUMMARIZE,
    CUSTOM
}

/**
 * UI State for text selection feature
 */
sealed interface TextSelectionState {
    data object ActionSelection : TextSelectionState
    data object CustomPrompt : TextSelectionState
    data object Loading : TextSelectionState
    data class Result(
        val responseText: String,
        val isStreaming: Boolean = true,
        val isReasoning: Boolean = false
    ) : TextSelectionState
    data class Error(val message: String) : TextSelectionState
}

class TextSelectionVM(
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
) : ViewModel() {

    var selectedText by mutableStateOf("")
        private set
    
    var state by mutableStateOf<TextSelectionState>(TextSelectionState.ActionSelection)
        private set
    
    // Keep custom prompt separate from state to prevent AnimatedContent from resetting it
    var customPrompt by mutableStateOf("")
        private set
    
    // Track the last action taken for Continue in App routing
    var lastAction by mutableStateOf<QuickAction?>(null)
        private set
    
    private var currentJob: Job? = null
    private var messages = mutableListOf<UIMessage>()

    private fun QuickAction.toActionId(): String {
        return when (this) {
            QuickAction.TRANSLATE -> "translate"
            QuickAction.EXPLAIN -> "explain"
            QuickAction.SUMMARIZE -> "summarize"
            QuickAction.CUSTOM -> "custom"
        }
    }

    private fun normalizeTemplate(text: String): String {
        return text.replace("\r\n", "\n").trim()
    }

    private fun renderPromptTemplate(
        template: String,
        variables: Map<String, String>,
    ): String {
        var rendered = normalizeTemplate(template)
        variables.forEach { (key, value) ->
            rendered = rendered.replace("{{${key}}}", value)
        }
        return rendered.trim()
    }

    fun updateSelectedText(text: String) {
        selectedText = text
    }

    fun onActionSelected(action: QuickAction, customPromptText: String = "") {
        when (action) {
            QuickAction.CUSTOM -> {
                customPrompt = "" // Reset when entering custom mode
                state = TextSelectionState.CustomPrompt
            }
            else -> {
                executeAction(action, customPromptText)
            }
        }
    }

    fun updateCustomPrompt(prompt: String) {
        customPrompt = prompt
    }

    fun submitCustomPrompt() {
        if (customPrompt.isNotBlank()) {
            executeAction(QuickAction.CUSTOM, customPrompt)
        }
    }

    fun backToActionSelection() {
        currentJob?.cancel()
        state = TextSelectionState.ActionSelection
        customPrompt = ""
        messages.clear()
    }

    fun cancelGeneration() {
        currentJob?.cancel()
        val currentState = state
        if (currentState is TextSelectionState.Result) {
            state = currentState.copy(isStreaming = false)
        }
    }

    private fun executeAction(action: QuickAction, customPrompt: String = "") {
        currentJob?.cancel()
        state = TextSelectionState.Loading
        lastAction = action
        messages.clear()

        currentJob = viewModelScope.launch {
            try {
                val settings = settingsStore.settingsFlow.value

                // Get the assistant from text selection config for its system prompt
                val assistantId = settings.textSelectionConfig.assistantId
                val assistant = assistantId?.let { settings.getAssistantById(it) }
                val assistantPrompt = assistant?.systemPrompt ?: ""

                val actionId = action.toActionId()
                val configuredAction = settings.textSelectionConfig.actions
                    .firstOrNull { it.id == actionId }
                val defaultAction = DEFAULT_TEXT_SELECTION_ACTIONS
                    .firstOrNull { it.id == actionId }
                val actionPromptTemplate = configuredAction?.prompt
                    ?: defaultAction?.prompt
                    ?: ""

                // Use per-action model if set, otherwise fall back to current chat model
                val actionModelId = configuredAction?.modelId
                val model = if (actionModelId != null) {
                    settings.findModelById(actionModelId)
                } else {
                    settings.getCurrentChatModel()
                }
                val providerSetting = model?.findProvider(settings.providers)

                if (model == null || providerSetting == null) {
                    state = TextSelectionState.Error("No model configured")
                    return@launch
                }

                val systemPrompt = buildSystemPrompt(
                    action = action,
                    customPrompt = customPrompt,
                    assistantPrompt = assistantPrompt,
                    translationTargetLanguage = settings.textSelectionConfig.translateLanguage,
                    actionPromptTemplate = actionPromptTemplate,
                )
                val userMessage = UIMessage.user(selectedText)
                
                messages.add(UIMessage.system(systemPrompt))
                messages.add(userMessage)

                val params = TextGenerationParams(
                    model = model,
                    temperature = 0.7f,
                )

                // Get the actual provider from the manager
                val provider = providerManager.getProviderByType(providerSetting)
                
                provider.streamText(
                    providerSetting = providerSetting,
                    messages = messages,
                    params = params,
                ).catch { e ->
                    Log.e(TAG, "Stream error", e)
                    state = TextSelectionState.Error(e.message ?: "Unknown error")
                }.collect { chunk ->
                    handleChunk(chunk, model)
                }

                // Mark streaming complete
                val currentState = state
                if (currentState is TextSelectionState.Result) {
                    state = currentState.copy(isStreaming = false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error executing action", e)
                state = TextSelectionState.Error(e.message ?: "Unknown error")
            }
        }
    }

    private fun handleChunk(chunk: MessageChunk, model: Model) {
        messages = messages.handleMessageChunk(chunk, model).toMutableList()
        
        val lastMessage = messages.lastOrNull()
        // Use toContentText() to filter out reasoning from displayed text
        val responseText = lastMessage?.toContentText() ?: ""
        // Check if the model is currently reasoning (has unfinished reasoning parts)
        val isReasoning = lastMessage?.parts?.any { 
            it is UIMessagePart.Reasoning && it.finishedAt == null 
        } ?: false
        
        state = TextSelectionState.Result(
            responseText = responseText,
            isStreaming = true,
            isReasoning = isReasoning
        )
    }

    private fun buildSystemPrompt(
        action: QuickAction,
        customPrompt: String,
        assistantPrompt: String,
        translationTargetLanguage: String,
        actionPromptTemplate: String,
    ): String {
        val language = translationTargetLanguage.trim().ifBlank { "their device language" }
        val actionPrompt = renderPromptTemplate(
            template = actionPromptTemplate,
            variables = mapOf(
                "language" to language,
                "custom_prompt" to customPrompt,
            )
        )

        // For Translate, use only the action prompt (no assistant personality)
        if (action == QuickAction.TRANSLATE) {
            return actionPrompt
        }
        
        // Combine assistant prompt with action prompt
        return if (assistantPrompt.isNotBlank()) {
            """
                $assistantPrompt
                
                $actionPrompt
            """.trimIndent()
        } else {
            actionPrompt
        }
    }

    override fun onCleared() {
        super.onCleared()
        currentJob?.cancel()
    }
}
