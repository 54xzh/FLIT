package me.rerere.rikkahub.data.repository

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import me.rerere.rikkahub.service.MemorySummaryWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import androidx.work.WorkInfo
import java.util.UUID
import java.util.concurrent.TimeUnit

class MemorySummaryScheduler(
    private val context: Context,
) {
    fun enqueueAutomatic(assistantId: String, delayMillis: Long = 0L) {
        val request = request(
            assistantId = assistantId,
            forceManual = false,
            updateOptions = null,
            delayMillis = delayMillis,
            tags = emptySet(),
        )
        WorkManager.getInstance(context).enqueueUniqueWork(
            automaticWorkName(assistantId),
            // A worker scheduling the future eligibility check is itself still running.
            // Appending keeps that delayed check instead of dropping it because of the
            // currently running worker; ordinary change notifications still coalesce.
            if (delayMillis > 0L) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun enqueueManual(assistantId: String, options: MemorySummaryUpdateOptions): UUID {
        val request = request(
            assistantId = assistantId,
            forceManual = true,
            updateOptions = options,
            delayMillis = 0L,
            tags = setOf(MANUAL_SUMMARY_TAG, manualWorkTag(assistantId)),
        )
        WorkManager.getInstance(context).enqueueUniqueWork(
            manualWorkName(assistantId),
            ExistingWorkPolicy.REPLACE,
            request,
        )
        return request.id
    }

    suspend fun cancelManual(assistantId: String) = withContext(Dispatchers.IO) {
        WorkManager.getInstance(context).cancelUniqueWork(manualWorkName(assistantId)).result.get()
        WorkManager.getInstance(context).cancelAllWorkByTag(manualWorkTag(assistantId)).result.get()
    }

    fun observeManualRunning(assistantId: String): Flow<Boolean> =
        WorkManager.getInstance(context)
            .getWorkInfosByTagFlow(manualWorkTag(assistantId))
            .map { workInfos -> workInfos.any { !it.state.isFinished } }

    fun observeWorkInfo(id: UUID): Flow<WorkInfo?> =
        WorkManager.getInstance(context).getWorkInfoByIdFlow(id)

    suspend fun getUnfinishedManualWork(assistantId: String): WorkInfo? = withContext(Dispatchers.IO) {
        WorkManager.getInstance(context)
            .getWorkInfosByTag(manualWorkTag(assistantId))
            .get()
            .firstOrNull { !it.state.isFinished }
    }

    private fun request(
        assistantId: String,
        forceManual: Boolean,
        updateOptions: MemorySummaryUpdateOptions?,
        delayMillis: Long,
        tags: Set<String> = emptySet(),
    ): androidx.work.OneTimeWorkRequest {
        val builder = OneTimeWorkRequestBuilder<MemorySummaryWorker>()
            .setInputData(
                workDataOf(
                    MemorySummaryWorker.INPUT_ASSISTANT_ID to assistantId,
                    MemorySummaryWorker.INPUT_FORCE_MANUAL to forceManual,
                    MemorySummaryWorker.INPUT_INCLUDE_ACTIVE_SUMMARY to
                        (updateOptions?.includeActiveSummary ?: true),
                    MemorySummaryWorker.INPUT_INCLUDE_RECENT_REQUIREMENTS to
                        (updateOptions?.includeRecentRequirements ?: true),
                    MemorySummaryWorker.INPUT_MEMORY_SCOPE to
                        (updateOptions?.memoryScope ?: MemorySummaryMemoryScope.ADDED).ordinal,
                )
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
        tags.forEach(builder::addTag)
        return builder.build()
    }

    companion object {
        const val MANUAL_SUMMARY_TAG = "manual_memory_summary"
        fun manualWorkTag(assistantId: String) = "manual_memory_summary_$assistantId"
        fun manualWorkName(assistantId: String) = "manual_memory_summary_work_$assistantId"
        fun automaticWorkName(assistantId: String) = "memory_summary_$assistantId"
    }
}
