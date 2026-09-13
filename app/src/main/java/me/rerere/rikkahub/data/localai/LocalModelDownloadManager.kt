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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

sealed interface LocalModelDownloadState {
    data object Idle : LocalModelDownloadState
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long?) : LocalModelDownloadState
    data class Failed(val message: String) : LocalModelDownloadState
}

/** Schedules one resumable task per catalog model. */
class LocalModelDownloadManager(
    private val context: Context,
    private val repository: LocalModelRepository,
) {
    private val workManager get() = WorkManager.getInstance(context)

    fun observe(): Flow<Map<String, LocalModelDownloadState>> =
        workManager.getWorkInfosByTagFlow(TAG)
            .map { infos ->
                infos.mapNotNull { info ->
                    val catalogId = info.tags.firstOrNull { it.startsWith(CATALOG_TAG_PREFIX) }
                        ?.removePrefix(CATALOG_TAG_PREFIX)
                        ?: return@mapNotNull null
                    catalogId to info.toState()
                }.toMap()
            }
            .catch { emit(emptyMap()) }

    suspend fun download(entry: LocalModelCatalogEntry): Result<Unit> = runCatching {
        val modelId = repository.prepareCatalogDownload(entry.asDownload())
        val request = OneTimeWorkRequestBuilder<LocalModelDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(
                workDataOf(
                    LocalModelDownloadWorker.INPUT_CATALOG_ID to entry.id,
                    LocalModelDownloadWorker.INPUT_MODEL_ID to modelId.toString(),
                    LocalModelDownloadWorker.INPUT_NAME to entry.displayName,
                    LocalModelDownloadWorker.INPUT_FORMAT to entry.format,
                    LocalModelDownloadWorker.INPUT_URL to entry.sourceUrl,
                    LocalModelDownloadWorker.INPUT_SHA256 to entry.sha256,
                    LocalModelDownloadWorker.INPUT_SIZE to entry.sizeBytes,
                    LocalModelDownloadWorker.INPUT_SUPPORTS_TOOLS to entry.supportsTools,
                ),
            )
            .addTag(TAG)
            .addTag(CATALOG_TAG_PREFIX + entry.id)
            .build()
        workManager.enqueueUniqueWork(workName(entry.id), ExistingWorkPolicy.REPLACE, request)
    }

    suspend fun pause(entry: LocalModelCatalogEntry): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val record = repository.getByCatalogId(entry.id) ?: return@withContext
            workManager.cancelUniqueWork(workName(entry.id)).result.get()
            repository.pauseCatalogDownload(Uuid.parse(record.entity.modelId))
        }
    }

    suspend fun cancel(entry: LocalModelCatalogEntry): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val record = repository.getByCatalogId(entry.id) ?: return@withContext
            workManager.cancelUniqueWork(workName(entry.id)).result.get()
            repository.cancelCatalogDownload(Uuid.parse(record.entity.modelId))
        }
    }

    private fun WorkInfo.toState(): LocalModelDownloadState = when (state) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED, WorkInfo.State.RUNNING -> LocalModelDownloadState.Downloading(
            downloadedBytes = progress.getLong(LocalModelDownloadWorker.PROGRESS_DOWNLOADED, 0L),
            totalBytes = progress.getLong(LocalModelDownloadWorker.PROGRESS_TOTAL, -1L).takeIf { it > 0L },
        )
        WorkInfo.State.FAILED -> LocalModelDownloadState.Failed(
            outputData.getString(LocalModelDownloadWorker.OUTPUT_ERROR) ?: "Model download failed",
        )
        else -> LocalModelDownloadState.Idle
    }

    private fun workName(catalogId: String) = "local_model_download_$catalogId"

    companion object {
        const val TAG = "local_model_download"
        private const val CATALOG_TAG_PREFIX = "local_model_download_catalog_"
    }
}
