package me.rerere.rikkahub.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.AIRequestLogManager
import me.rerere.rikkahub.data.ai.AIRequestSource
import me.rerere.rikkahub.data.ai.prompts.MEMORY_SUMMARY_REQUIREMENT_CHANGE_SYSTEM_PROMPT
import me.rerere.rikkahub.data.ai.prompts.buildMemorySummaryRequirementChangePrompt
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.repository.MemorySummaryRepository
import me.rerere.rikkahub.data.repository.MemorySummaryVersionOperationResult
import kotlin.uuid.Uuid

sealed interface MemorySummaryRequirementChangeResult {
    data class SUCCESS(val versionId: Long) : MemorySummaryRequirementChangeResult
    data object NO_ACTIVE_VERSION : MemorySummaryRequirementChangeResult
    data object MODEL_NOT_CONFIGURED : MemorySummaryRequirementChangeResult
    data object SAVED_WITHOUT_SUMMARY_CHANGE : MemorySummaryRequirementChangeResult
    data object STALE_ACTIVE_VERSION : MemorySummaryRequirementChangeResult
}

class MemorySummaryRequirementChangeService(
    private val settingsStore: SettingsStore,
    private val summaryRepository: MemorySummaryRepository,
    private val providerManager: me.rerere.ai.provider.ProviderManager,
    private val requestLogManager: AIRequestLogManager,
) {
    suspend fun revise(
        assistantId: String,
        requirement: String,
    ): MemorySummaryRequirementChangeResult = withContext(Dispatchers.IO) {
        val normalizedRequirement = requirement.trim()
        require(normalizedRequirement.isNotEmpty()) { "Memory summary requirement cannot be blank" }
        val parsedAssistantId = Uuid.parse(assistantId)
        val settings = settingsStore.settingsFlow.value
        val assistant = settings.getAssistantById(parsedAssistantId)
            ?: return@withContext MemorySummaryRequirementChangeResult.NO_ACTIVE_VERSION
        val activeSnapshot = summaryRepository.getActiveSnapshot(assistantId)
        val activeVersion = activeSnapshot.activeVersion
            ?: return@withContext MemorySummaryRequirementChangeResult.NO_ACTIVE_VERSION
        val modelId = assistant.summarizerModelId ?: assistant.backgroundModelId ?: settings.chatModelId
        val model = settings.findModelById(modelId)
            ?: return@withContext MemorySummaryRequirementChangeResult.MODEL_NOT_CONFIGURED
        val provider = model.findProvider(settings.providers)
            ?: return@withContext MemorySummaryRequirementChangeResult.MODEL_NOT_CONFIGURED
        val providerHandler = providerManager.getProviderByType(provider)
        val requestMessages = listOf(
            UIMessage.system(MEMORY_SUMMARY_REQUIREMENT_CHANGE_SYSTEM_PROMPT),
            UIMessage.user(
                buildMemorySummaryRequirementChangePrompt(
                    currentSummary = activeVersion.content,
                    requirement = normalizedRequirement,
                ),
            ),
        )
        var requestBodyJson: String? = null
        val params = TextGenerationParams(model = model, onRequestBody = { requestBodyJson = it })
        val startedAt = System.currentTimeMillis()
        var responseText = ""
        var rawResponse = ""
        var failure: Throwable? = null
        try {
            val response = providerHandler.generateText(provider, requestMessages, params)
            rawResponse = response.rawResponse.orEmpty()
            responseText = response.choices.firstOrNull()?.message?.toContentText().orEmpty().trim()
            check(responseText.isNotBlank()) { "Memory summary response is empty" }
            val publishResult = summaryRepository.publishRequirementChangeVersion(
                assistantId = assistantId,
                expectedActiveVersionId = activeVersion.id,
                expectedRevision = activeSnapshot.revision,
                content = responseText,
                requirement = normalizedRequirement,
            )
            when (publishResult.operation) {
                MemorySummaryVersionOperationResult.SUCCESS -> MemorySummaryRequirementChangeResult.SUCCESS(
                    versionId = checkNotNull(publishResult.versionId),
                )

                MemorySummaryVersionOperationResult.UNCHANGED_CONTENT ->
                    MemorySummaryRequirementChangeResult.SAVED_WITHOUT_SUMMARY_CHANGE
                MemorySummaryVersionOperationResult.STALE_ACTIVE_VERSION,
                MemorySummaryVersionOperationResult.VERSION_NOT_FOUND,
                    -> MemorySummaryRequirementChangeResult.STALE_ACTIVE_VERSION

                MemorySummaryVersionOperationResult.EMPTY_CONTENT,
                MemorySummaryVersionOperationResult.CANNOT_DELETE_ACTIVE,
                    -> error("Unexpected memory summary revision result")
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val duration = System.currentTimeMillis() - startedAt
            requestLogManager.logTextGeneration(
                source = AIRequestSource.MEMORY_SUMMARY_REQUIREMENT_CHANGE,
                providerSetting = provider,
                params = params,
                requestMessages = requestMessages,
                requestBodyJson = requestBodyJson,
                responseText = responseText,
                responseRawText = rawResponse,
                stream = false,
                latencyMs = duration,
                durationMs = duration,
                error = failure,
            )
        }
    }
}
