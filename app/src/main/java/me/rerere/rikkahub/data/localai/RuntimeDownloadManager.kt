package me.rerere.rikkahub.data.localai

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

sealed interface RuntimeDownloadState {
    data object Idle : RuntimeDownloadState
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long?) : RuntimeDownloadState
    data object Installing : RuntimeDownloadState
    data object Paused : RuntimeDownloadState
    data class Failed(val message: String) : RuntimeDownloadState
}

/** Schedules one resumable runtime package transfer for the ABI of this device. */
class RuntimeDownloadManager(private val context: Context) {
    private val workManager get() = WorkManager.getInstance(context)

    fun observe(): Flow<RuntimeDownloadState> = workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME).map { infos ->
        val latest = infos.maxByOrNull { it.runAttemptCount } ?: return@map RuntimeDownloadState.Idle
        when (latest.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED, WorkInfo.State.RUNNING -> {
                val phase = latest.progress.getString(RuntimeDownloadWorker.PROGRESS_PHASE)
                if (phase == RuntimeDownloadWorker.PHASE_INSTALLING) RuntimeDownloadState.Installing
                else RuntimeDownloadState.Downloading(
                    downloadedBytes = latest.progress.getLong(RuntimeDownloadWorker.PROGRESS_DOWNLOADED, 0L),
                    totalBytes = latest.progress.getLong(RuntimeDownloadWorker.PROGRESS_TOTAL, -1L).takeIf { it > 0L },
                )
            }
            WorkInfo.State.CANCELLED -> RuntimeDownloadState.Paused
            WorkInfo.State.FAILED -> RuntimeDownloadState.Failed(
                latest.outputData.getString(RuntimeDownloadWorker.OUTPUT_ERROR) ?: "Runtime download failed",
            )
            WorkInfo.State.SUCCEEDED -> RuntimeDownloadState.Idle
        }
    }

    fun download(): UUID {
        val request = OneTimeWorkRequestBuilder<RuntimeDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        return request.id
    }

    suspend fun pause() = withContext(Dispatchers.IO) {
        workManager.cancelUniqueWork(WORK_NAME).result.get()
    }

    companion object {
        const val WORK_NAME = "local_runtime_download"
    }
}
