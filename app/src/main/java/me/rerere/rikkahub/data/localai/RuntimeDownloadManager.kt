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

    fun observe(runtimePackage: LocalRuntimePackage = LocalRuntimePackage.GGUF): Flow<RuntimeDownloadState> =
        workManager.getWorkInfosForUniqueWorkFlow(workName(runtimePackage)).map { infos ->
        val latest = infos.maxWithOrNull(compareBy<WorkInfo> { !it.state.isFinished }
            .thenBy { info -> info.tags.mapNotNull { it.removePrefix(CREATED_TAG).toLongOrNull() }.maxOrNull() ?: 0L }) ?: return@map RuntimeDownloadState.Idle
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

    fun download(runtimePackage: LocalRuntimePackage = LocalRuntimePackage.GGUF): UUID {
        val request = OneTimeWorkRequestBuilder<RuntimeDownloadWorker>()
            .setInputData(workDataOf(RuntimeDownloadWorker.INPUT_ENGINE to runtimePackage.engine))
            .addTag("$CREATED_TAG${System.currentTimeMillis()}")
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueueUniqueWork(workName(runtimePackage), ExistingWorkPolicy.KEEP, request)
        return request.id
    }

    suspend fun pause(runtimePackage: LocalRuntimePackage = LocalRuntimePackage.GGUF) = withContext(Dispatchers.IO) {
        workManager.cancelUniqueWork(workName(runtimePackage)).result.get()
    }

    private fun workName(runtimePackage: LocalRuntimePackage) = when (runtimePackage) {
        LocalRuntimePackage.GGUF -> WORK_NAME
        LocalRuntimePackage.LITERT_LM -> "${WORK_NAME}_litert"
    }

    companion object {
        private const val CREATED_TAG = "runtime_created:"
        const val WORK_NAME = "local_runtime_download"
    }
}
