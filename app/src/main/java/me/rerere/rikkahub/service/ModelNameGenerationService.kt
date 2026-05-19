package me.rerere.rikkahub.service

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.AIRequestLogManager
import me.rerere.rikkahub.data.ai.AIRequestSource
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.utils.applyPlaceholders

private const val TAG = "ModelNameGenerationService"

class ModelNameGenerationService(
    private val providerManager: ProviderManager,
    private val requestLogManager: AIRequestLogManager,
) {
    suspend fun generateModelName(settings: Settings, modelId: String): String? = withContext(Dispatchers.IO) {
        val modelIdTrimmed = modelId.trim()
        if (modelIdTrimmed.isBlank()) {
            return@withContext null
        }

        val model = settings.findModelById(settings.modelNameGenerationModelId) ?: return@withContext null
        val provider = model.findProvider(settings.providers) ?: return@withContext null
        val providerHandler = providerManager.getProviderByType(provider)
        val prompt = settings.modelNameGenerationPrompt
            .applyPlaceholders("model_id" to modelIdTrimmed)
        val requestMessages = listOf(UIMessage.user(prompt))
        var requestBodyJson: String? = null
        val params = TextGenerationParams(
            model = model,
            thinkingBudget = -1,
            onRequestBody = { requestBodyJson = it },
        )
        val startAt = System.currentTimeMillis()
        var failure: Throwable? = null
        var responseText = ""
        var rawResponseText = ""
        val result = runCatching {
            providerHandler.generateText(
                providerSetting = provider,
                messages = requestMessages,
                params = params
            )
        }.onFailure {
            failure = it
            Log.w(TAG, "generateModelName failed: ${it.message}", it)
        }.getOrNull()

        responseText = result?.choices?.firstOrNull()?.message?.toContentText().orEmpty()
        rawResponseText = result?.rawResponse.orEmpty()

        runCatching {
            requestLogManager.logTextGeneration(
                source = AIRequestSource.MODEL_NAME_GENERATION,
                providerSetting = provider,
                params = params,
                requestMessages = requestMessages,
                requestBodyJson = requestBodyJson,
                responseText = responseText,
                responseRawText = rawResponseText,
                stream = false,
                latencyMs = System.currentTimeMillis() - startAt,
                durationMs = System.currentTimeMillis() - startAt,
                error = failure,
            )
        }

        responseText.toGeneratedName()
    }
}

private fun String.toGeneratedName(): String? {
    val firstLine = lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotBlank() }
        ?: return null

    val normalized = firstLine
        .removePrefix("model_name:")
        .removePrefix("Model Name:")
        .removePrefix("model name:")
        .trim()
        .trim('"', '\'', '`')

    return normalized.takeIf { it.isNotBlank() }
}
