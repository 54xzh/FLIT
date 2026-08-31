package me.rerere.rikkahub.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
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
        try {
            updateSummary()
            Result.success()
        } catch (error: Throwable) {
            Log.e(TAG, "Memory summary update failed", error)
            Result.retry()
        }
    }

    private suspend fun updateSummary() {
        val assistantId = inputData.getString(INPUT_ASSISTANT_ID).orEmpty()
        if (assistantId.isBlank()) return
        val forceManual = inputData.getBoolean(INPUT_FORCE_MANUAL, false)
        val forceFull = inputData.getBoolean(INPUT_FORCE_FULL, false)
        val forceRebuild = inputData.getBoolean(INPUT_FORCE_REBUILD, false)
        val parsedId = runCatching { kotlin.uuid.Uuid.parse(assistantId) }.getOrNull() ?: return
        val settings = settingsStore.settingsFlow.value
        val assistant = settings.getAssistantById(parsedId) ?: return
        if (!assistant.enableMemory || !assistant.enableMemorySummary) return
        if (!forceManual && !assistant.enableAutoMemorySummary) return

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
        ) return

        if (!forceManual && activeVersion != null) {
            val remainingDelay = summaryRepository.requiredDelayMillis(
                lastSuccessAt = activeVersion.generatedAt,
                intervalDays = assistant.memorySummaryIntervalDays,
            )
            if (remainingDelay > 0L) {
                summaryScheduler.enqueueAutomatic(assistantId, remainingDelay)
                return
            }
        }

        val isFullUpdate = summaryRepository.shouldUseFullUpdate(
            activeVersion = activeVersion,
            changes = changes,
            forceFull = forceFull || forceRebuild,
            requiresFullUpdate = activeSnapshot.requiresFullUpdate,
        )
        val updateMode = when {
            forceRebuild -> MemorySummaryUpdateMode.REBUILD
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
            return
        }
        // An explicit immediate update is allowed to refresh an existing incremental
        // summary even when there are no pending additions. Automatic work never
        // reaches this branch without enough changes.
        if (sources.isEmpty() && !forceManual) return

        val modelId = assistant.summarizerModelId ?: assistant.backgroundModelId ?: settings.chatModelId
        val model = settings.findModelById(modelId) ?: return
        val provider = model.findProvider(settings.providers) ?: return
        val providerHandler = providerManager.getProviderByType(provider)
        val promptMode = when {
            forceRebuild -> MemorySummaryPromptMode.REBUILD
            isFullUpdate -> MemorySummaryPromptMode.FULL
            else -> MemorySummaryPromptMode.INCREMENTAL
        }
        val prompt = buildMemorySummaryPrompt(
            promptTemplate = assistant.memorySummaryPrompt.ifBlank { DEFAULT_MEMORY_SUMMARY_PROMPT },
            mode = promptMode,
            currentDate = LocalDate.now().toString(),
            previousSummary = activeVersion?.content.orEmpty(),
            memories = summaryRepository.formatSources(sources),
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
        const val INPUT_FORCE_FULL = "FORCE_FULL"
        const val INPUT_FORCE_REBUILD = "FORCE_REBUILD"
        private const val TAG = "MemorySummaryWorker"
    }
}
