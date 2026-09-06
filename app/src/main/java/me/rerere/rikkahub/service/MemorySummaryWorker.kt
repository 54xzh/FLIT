package me.rerere.rikkahub.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.AIRequestLogManager
import me.rerere.rikkahub.data.ai.AIRequestSource
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_MEMORY_SUMMARY_PROMPT
import me.rerere.rikkahub.data.ai.prompts.MemorySummaryPromptMode
import me.rerere.rikkahub.data.ai.prompts.buildMemorySummaryPrompt
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.db.entity.MemorySummaryUpdateMode
import me.rerere.rikkahub.data.repository.MemorySummaryRepository
import me.rerere.rikkahub.data.repository.MemorySummaryScheduler
import me.rerere.rikkahub.data.repository.MemorySummaryMemoryScope
import me.rerere.rikkahub.data.repository.MemorySummaryUpdateOptions
import me.rerere.rikkahub.data.repository.normalizeManualMemorySummaryUpdateOptions
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.LocalDate

class MemorySummaryWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {
    private val settingsStore: SettingsStore by inject()
    private val summaryRepository: MemorySummaryRepository by inject()
    private val summaryScheduler: MemorySummaryScheduler by inject()
    private val providerManager: me.rerere.ai.provider.ProviderManager by inject()
    private val requestLogManager: AIRequestLogManager by inject()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val forceManual = inputData.getBoolean(INPUT_FORCE_MANUAL, false)
        try {
            updateSummary(forceManual)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.e(TAG, "Memory summary update failed", error)
            if (forceManual) {
                Result.failure()
            } else {
                Result.retry()
            }
        }
    }

    private suspend fun updateSummary(forceManual: Boolean): Result {
        val assistantId = inputData.getString(INPUT_ASSISTANT_ID).orEmpty()
        if (assistantId.isBlank()) {
            return if (forceManual) Result.failure(workDataOf(OUTPUT_ERROR_TYPE to ERROR_TYPE_INVALID_INPUT)) else Result.success()
        }
        val parsedId = runCatching { kotlin.uuid.Uuid.parse(assistantId) }.getOrNull()
        if (parsedId == null) {
            return if (forceManual) Result.failure(workDataOf(OUTPUT_ERROR_TYPE to ERROR_TYPE_INVALID_INPUT)) else Result.success()
        }
        val settings = settingsStore.settingsFlow.value
        val assistant = settings.getAssistantById(parsedId)
        if (assistant == null) {
            return if (forceManual) Result.failure(workDataOf(OUTPUT_ERROR_TYPE to ERROR_TYPE_INVALID_INPUT)) else Result.success()
        }
        if (!assistant.enableMemory || !assistant.enableMemorySummary) {
            return if (forceManual) Result.failure(workDataOf(OUTPUT_ERROR_TYPE to ERROR_TYPE_DISABLED)) else Result.success()
        }
        if (!forceManual && !assistant.enableAutoMemorySummary) return Result.success()

        val activeSnapshot = summaryRepository.getActiveSnapshot(assistantId)
        val activeVersion = activeSnapshot.activeVersion
        val changes = summaryRepository.getPendingChanges(assistantId)
        val currentMemoryCount = summaryRepository.getCurrentMemoryCount(assistantId)
        if (!forceManual && !summaryRepository.hasEnoughChanges(
                activeVersion = activeVersion,
                pendingChanges = changes.size,
                currentMemoryCount = currentMemoryCount,
                threshold = assistant.memorySummaryChangeThreshold,
            )
        ) return Result.success()

        if (!forceManual && activeVersion != null) {
            val remainingDelay = summaryRepository.requiredDelayMillis(
                lastSuccessAt = activeVersion.generatedAt,
                intervalDays = assistant.memorySummaryIntervalDays,
            )
            if (remainingDelay > 0L) {
                summaryScheduler.enqueueAutomatic(assistantId, remainingDelay)
                return Result.success()
            }
        }

        val manualOptions = normalizeManualMemorySummaryUpdateOptions(
            options = MemorySummaryUpdateOptions(
                includeActiveSummary = inputData.getBoolean(INPUT_INCLUDE_ACTIVE_SUMMARY, true),
                includeRecentRequirements = inputData.getBoolean(INPUT_INCLUDE_RECENT_REQUIREMENTS, true),
                memoryScope = inputData.memoryScope(),
            ),
            hasActiveSummary = activeVersion != null,
        )
        val isFullUpdate = if (forceManual) {
            manualOptions.memoryScope == MemorySummaryMemoryScope.ALL
        } else {
            summaryRepository.shouldUseFullUpdate(
                activeVersion = activeVersion,
                changes = changes,
                forceFull = false,
                requiresFullUpdate = activeSnapshot.requiresFullUpdate,
            )
        }
        val includeActiveSummary = if (forceManual) {
            manualOptions.includeActiveSummary
        } else {
            activeVersion != null
        }
        val includeRecentRequirements = if (forceManual) {
            manualOptions.includeRecentRequirements
        } else {
            true
        }
        val updateMode = when {
            !includeActiveSummary -> MemorySummaryUpdateMode.REBUILD
            isFullUpdate -> MemorySummaryUpdateMode.FULL
            else -> MemorySummaryUpdateMode.INCREMENTAL
        }
        val sources = if (isFullUpdate) {
            summaryRepository.getAllSources(assistantId)
        } else {
            summaryRepository.getAddedSources(assistantId, changes)
        }

        if (isFullUpdate && sources.isEmpty()) {
            val published = summaryRepository.publishVersion(
                assistantId = assistantId,
                content = "",
                updateMode = updateMode,
                snapshotChanges = changes,
                expectedActiveVersionId = activeVersion?.id,
                expectedRevision = activeSnapshot.revision,
            )
            if (!published) summaryRepository.scheduleAutomaticCheck(assistantId)
            return if (published) {
                Result.success()
            } else if (forceManual) {
                Result.failure(workDataOf(OUTPUT_ERROR_TYPE to ERROR_TYPE_PUBLISH_FAILED))
            } else {
                Result.success()
            }
        }
        // An explicit immediate update is allowed to refresh an existing incremental
        // summary even when there are no pending additions. Automatic work never
        // reaches this branch without enough changes.
        if (sources.isEmpty() && !forceManual) return Result.success()

        val modelId = assistant.summarizerModelId ?: assistant.backgroundModelId ?: settings.chatModelId
        val model = settings.findModelById(modelId)
        if (model == null) {
            Log.w(TAG, "Summarizer model not found: $modelId")
            return if (forceManual) {
                Result.failure(workDataOf(OUTPUT_ERROR_TYPE to ERROR_TYPE_MODEL_MISSING))
            } else {
                Result.success()
            }
        }
        val provider = model.findProvider(settings.providers)
        if (provider == null) {
            Log.w(TAG, "Provider not found for model: ${model.id}")
            return if (forceManual) {
                Result.failure(workDataOf(OUTPUT_ERROR_TYPE to ERROR_TYPE_MODEL_MISSING))
            } else {
                Result.success()
            }
        }
        val providerHandler = providerManager.getProviderByType(provider)
        val promptMode = when {
            !includeActiveSummary -> MemorySummaryPromptMode.REBUILD
            isFullUpdate -> MemorySummaryPromptMode.FULL
            else -> MemorySummaryPromptMode.INCREMENTAL
        }
        val prompt = buildMemorySummaryPrompt(
            promptTemplate = assistant.memorySummaryPrompt.ifBlank { DEFAULT_MEMORY_SUMMARY_PROMPT },
            mode = promptMode,
            currentDate = LocalDate.now().toString(),
            previousSummary = activeVersion?.content.orEmpty(),
            memories = summaryRepository.formatSources(sources),
            recentRequirements = if (includeRecentRequirements) {
                summaryRepository.getRecentRequirements(assistantId)
            } else {
                emptyList()
            },
        )
        val requestMessages = listOf(UIMessage.user(prompt))
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
            val published = summaryRepository.publishVersion(
                assistantId = assistantId,
                content = responseText,
                updateMode = updateMode,
                snapshotChanges = changes,
                expectedActiveVersionId = activeVersion?.id,
                expectedRevision = activeSnapshot.revision,
            )
            if (!published) summaryRepository.scheduleAutomaticCheck(assistantId)
            return if (published) {
                Result.success()
            } else if (forceManual) {
                Result.failure(workDataOf(OUTPUT_ERROR_TYPE to ERROR_TYPE_PUBLISH_FAILED))
            } else {
                Result.success()
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val duration = System.currentTimeMillis() - startedAt
            requestLogManager.logTextGeneration(
                source = AIRequestSource.MEMORY_SUMMARY,
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

    companion object {
        const val INPUT_ASSISTANT_ID = "ASSISTANT_ID"
        const val INPUT_FORCE_MANUAL = "FORCE_MANUAL"
        const val INPUT_INCLUDE_ACTIVE_SUMMARY = "INCLUDE_ACTIVE_SUMMARY"
        const val INPUT_INCLUDE_RECENT_REQUIREMENTS = "INCLUDE_RECENT_REQUIREMENTS"
        const val INPUT_MEMORY_SCOPE = "MEMORY_SCOPE"

        const val OUTPUT_ERROR_TYPE = "ERROR_TYPE"
        const val ERROR_TYPE_MODEL_MISSING = "MODEL_MISSING"
        const val ERROR_TYPE_DISABLED = "DISABLED"
        const val ERROR_TYPE_PUBLISH_FAILED = "PUBLISH_FAILED"
        const val ERROR_TYPE_INVALID_INPUT = "INVALID_INPUT"

        private const val TAG = "MemorySummaryWorker"
    }
}

private fun androidx.work.Data.memoryScope(): MemorySummaryMemoryScope =
    MemorySummaryMemoryScope.entries.getOrElse(
        getInt(MemorySummaryWorker.INPUT_MEMORY_SCOPE, MemorySummaryMemoryScope.ADDED.ordinal),
    ) { MemorySummaryMemoryScope.ADDED }
