package me.rerere.rikkahub.data.repository

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import me.rerere.rikkahub.service.MemorySummaryWorker
import java.util.concurrent.TimeUnit

class MemorySummaryScheduler(
    private val context: Context,
) {
    fun enqueueAutomatic(assistantId: String, delayMillis: Long = 0L) {
        val request = request(
            assistantId,
            forceManual = false,
            updateOptions = null,
            delayMillis = delayMillis,
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

    fun enqueueManual(assistantId: String, options: MemorySummaryUpdateOptions) {
        val request = request(
            assistantId,
            forceManual = true,
            updateOptions = options,
            delayMillis = 0L,
        )
        WorkManager.getInstance(context).enqueueUniqueWork(
            automaticWorkName(assistantId),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private fun request(
        assistantId: String,
        forceManual: Boolean,
        updateOptions: MemorySummaryUpdateOptions?,
        delayMillis: Long,
    ) = OneTimeWorkRequestBuilder<MemorySummaryWorker>()
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
        .build()

    private fun automaticWorkName(assistantId: String) = "memory_summary_$assistantId"
}
