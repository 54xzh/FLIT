package me.rerere.rikkahub.data.localai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import me.rerere.rikkahub.R
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import kotlin.uuid.Uuid

class LocalModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {
    private val client: OkHttpClient by inject()
    private val repository: LocalModelRepository by inject()

    override suspend fun doWork(): Result = runCatching {
        setForeground(createForegroundInfo())
        val modelId = Uuid.parse(requireNotNull(inputData.getString(INPUT_MODEL_ID)))
        val url = requireNotNull(inputData.getString(INPUT_URL))
        val record = requireNotNull(repository.get(modelId)) { "Model download record is missing" }
        val finalFile = repository.fileFor(record)
        val directory = requireNotNull(finalFile.parentFile)
        check(directory.mkdirs() || directory.isDirectory) { "Unable to prepare model storage" }
        val partial = File(directory, "${finalFile.name}.part")
        val existingBytes = partial.takeIf(File::isFile)?.length() ?: 0L
        val request = Request.Builder().url(url).apply {
            if (existingBytes > 0L) header("Range", "bytes=$existingBytes-")
        }.build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Model download failed (${response.code})" }
            val append = existingBytes > 0L && response.code == 206
            val startBytes = if (append) existingBytes else 0L
            val contentLength = response.body.contentLength().takeIf { it >= 0L }
            val totalBytes = contentLength?.plus(startBytes)
            response.body.byteStream().use { input ->
                FileOutputStream(partial, append).buffered().use { output ->
                    var downloaded = startBytes
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        setProgress(workDataOf(PROGRESS_DOWNLOADED to downloaded, PROGRESS_TOTAL to (totalBytes ?: -1L)))
                    }
                }
            }
        }
        repository.finishCatalogDownload(modelId).getOrThrow()
        Result.success()
    }.getOrElse { error ->
        if (error is kotlinx.coroutines.CancellationException) throw error
        Log.e(TAG, "Model download failed (attempt ${runAttemptCount + 1})", error)
        if (runAttemptCount < MAX_RETRIES) Result.retry()
        else Result.failure(workDataOf(OUTPUT_ERROR to (error.message ?: "Model download failed")))
    }

    private fun createForegroundInfo(): ForegroundInfo {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.local_models_download_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
            applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.local_models_downloading_notification))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()
        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    companion object {
        const val INPUT_CATALOG_ID = "catalog_id"
        const val INPUT_MODEL_ID = "model_id"
        const val INPUT_NAME = "name"
        const val INPUT_FORMAT = "format"
        const val INPUT_URL = "url"
        const val INPUT_SHA256 = "sha256"
        const val INPUT_SIZE = "size"
        const val INPUT_SUPPORTS_TOOLS = "supports_tools"
        const val PROGRESS_DOWNLOADED = "downloaded"
        const val PROGRESS_TOTAL = "total"
        const val OUTPUT_ERROR = "error"
        private const val CHANNEL_ID = "local_model_download"
        private const val NOTIFICATION_ID = 4342
        private const val MAX_RETRIES = 3
        private const val TAG = "LocalModelDownload"
    }
}
