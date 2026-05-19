package me.rerere.rikkahub.data.ai.rag

import me.rerere.ai.provider.ProviderManager
import me.rerere.rikkahub.data.ai.AIRequestLogManager
import me.rerere.rikkahub.data.ai.AIRequestSource
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getEmbeddingRetrievalTimeoutSeconds

data class EmbeddingResult(
    val embeddings: List<List<Float>>,
    val modelId: String
)

class EmbeddingService(
    private val providerManager: ProviderManager,
    private val settingsStore: SettingsStore,
    private val requestLogManager: AIRequestLogManager,
) {
    /**
     * Get the current embedding model ID for an assistant (or global if not set)
     */
    fun getEmbeddingModelId(assistantId: String? = null): String {
        val settings = settingsStore.settingsFlow.value
        val modelId = if (assistantId != null) {
            val assistant = settings.assistants.find { it.id.toString() == assistantId }
            assistant?.embeddingModelId ?: settings.embeddingModelId
        } else {
            settings.embeddingModelId
        }
        return modelId.toString()
    }

    suspend fun embed(
        text: String,
        assistantId: String? = null,
        source: AIRequestSource = AIRequestSource.OTHER,
    ): List<Float> {
        return embedBatch(listOf(text), assistantId, source).embeddings.first()
    }

    suspend fun embedWithModelId(
        text: String,
        assistantId: String? = null,
        source: AIRequestSource = AIRequestSource.OTHER,
    ): EmbeddingResult {
        val result = embedBatch(listOf(text), assistantId, source)
        return EmbeddingResult(result.embeddings, result.modelId)
    }

    suspend fun embedBatch(
        texts: List<String>,
        assistantId: String? = null,
        source: AIRequestSource = AIRequestSource.OTHER,
    ): EmbeddingResult {
        val settings = settingsStore.settingsFlow.value
        
        // Use assistant embedding model if available, otherwise use global
        val modelId = if (assistantId != null) {
            val assistant = settings.assistants.find { it.id.toString() == assistantId }
            assistant?.embeddingModelId ?: settings.embeddingModelId
        } else {
            settings.embeddingModelId
        }
        
        val model = settings.findModelById(modelId) ?: error("Embedding model not found: $modelId")
        
        // Check if provider supports embeddings
        val providerSetting = model.findProvider(settings.providers) ?: error("Provider not found for embedding model")
        val provider = providerManager.getProviderByType(providerSetting)
        val callTimeoutSeconds = when (source) {
            AIRequestSource.MEMORY_RETRIEVAL,
            AIRequestSource.TOOL_RESULT_RAG -> settings.getEmbeddingRetrievalTimeoutSeconds().toLong()
            else -> null
        }
        val requestBodyJson = provider.buildEmbeddingRequestBodyForLog(
            providerSetting = providerSetting,
            input = texts,
            model = model,
        )
        
        val startAt = System.currentTimeMillis()
        var failure: Throwable? = null
        var embeddingResult: List<List<Float>> = emptyList()
        try {
            // Check if provider supports embeddings (OpenAI does, others may not)
            embeddingResult = provider.createEmbedding(providerSetting, texts, model, callTimeoutSeconds)
            if (embeddingResult.isEmpty() && texts.isNotEmpty()) {
                error("Provider ${providerSetting::class.simpleName} does not support embeddings or returned empty result")
            }
            return EmbeddingResult(embeddingResult, modelId.toString())
        } catch (t: Throwable) {
            failure = t
            throw t
        } finally {
            requestLogManager.logEmbedding(
                source = source,
                providerSetting = providerSetting,
                model = model,
                inputs = texts,
                requestBodyJson = requestBodyJson,
                embeddingCount = embeddingResult.size.takeIf { it > 0 },
                dimensions = embeddingResult.firstOrNull()?.size,
                durationMs = System.currentTimeMillis() - startAt,
                error = failure,
            )
        }
    }
}
