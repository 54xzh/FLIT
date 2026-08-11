package me.rerere.rikkahub.service

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.model.effectiveMemoryRetrievalMode
import me.rerere.rikkahub.data.model.requiresEmbedding
import me.rerere.rikkahub.data.repository.MemoryRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.uuid.Uuid

private const val TAG = "MemoryEmbeddingBackfill"
private const val INPUT_ASSISTANT_ID = "assistant_id"
private const val INPUT_INCLUDE_CORE = "include_core"
private const val INPUT_INCLUDE_EPISODES = "include_episodes"
private const val MAX_RETRY_ATTEMPTS = 3

class MemoryEmbeddingBackfillScheduler(
    private val context: Context,
) {
    fun enqueue(
        assistantId: String,
        includeCore: Boolean,
        includeEpisodes: Boolean,
    ) {
        if (assistantId.isBlank()) return
        if (includeCore) enqueueForType(assistantId, includeCore = true, includeEpisodes = false, scope = "core")
        if (includeEpisodes) enqueueForType(assistantId, includeCore = false, includeEpisodes = true, scope = "episodes")
    }

    private fun enqueueForType(
        assistantId: String,
        includeCore: Boolean,
        includeEpisodes: Boolean,
        scope: String,
    ) {
        val request = OneTimeWorkRequestBuilder<MemoryEmbeddingBackfillWorker>()
            .setInputData(
                workDataOf(
                    INPUT_ASSISTANT_ID to assistantId,
                    INPUT_INCLUDE_CORE to includeCore,
                    INPUT_INCLUDE_EPISODES to includeEpisodes,
                )
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "memory_embedding_backfill_${assistantId}_$scope",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }
}

class MemoryEmbeddingBackfillWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {
    private val memoryRepository: MemoryRepository by inject()
    private val settingsStore: SettingsStore by inject()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val assistantId = inputData.getString(INPUT_ASSISTANT_ID)
            ?.takeIf { it.isNotBlank() }
            ?: return@withContext Result.failure()
        val includeCore = inputData.getBoolean(INPUT_INCLUDE_CORE, false)
        val includeEpisodes = inputData.getBoolean(INPUT_INCLUDE_EPISODES, false)
        if (!includeCore && !includeEpisodes) return@withContext Result.failure()

        val settings = settingsStore.settingsFlow.value
        if (settings.init) return@withContext Result.retry()
        val parsedAssistantId = runCatching { Uuid.parse(assistantId) }.getOrNull()
            ?: return@withContext Result.success()
        val assistant = settings.getAssistantById(parsedAssistantId)
            ?: return@withContext Result.success()
        if (!assistant.enableMemory || !assistant.effectiveMemoryRetrievalMode().requiresEmbedding) {
            Log.i(TAG, "Skipping backfill because assistant retrieval mode does not use embeddings")
            return@withContext Result.success()
        }

        runCatching {
            memoryRepository.embedMissingMemories(
                assistantId = assistantId,
                includeCore = includeCore,
                includeEpisodes = includeEpisodes,
            )
        }
            .fold(
                onSuccess = { (completed, failed) ->
                    if (completed > 0 || failed > 0) {
                        Log.i(TAG, "Backfilled $completed memories ($failed failed)")
                    }
                    when {
                        failed == 0 -> Result.success()
                        runAttemptCount < MAX_RETRY_ATTEMPTS -> Result.retry()
                        else -> Result.failure()
                    }
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    Log.w(TAG, "Memory embedding backfill failed: ${error.message}", error)
                    if (runAttemptCount < MAX_RETRY_ATTEMPTS) Result.retry() else Result.failure()
                },
            )
    }
}
