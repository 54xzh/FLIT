package me.rerere.rikkahub.data.localai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import me.rerere.rikkahub.R
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.FileOutputStream

class RuntimeDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {
    private val client: OkHttpClient by inject()
    private val installer: RuntimePackageInstaller by inject()
    private val runtime: LocalRuntimeManager by inject()

    override suspend fun doWork(): Result = runCatching {
        setForeground(createForegroundInfo())
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: error("No supported ABI")
        val url = "$RUNTIME_BASE_URL/$abi.zip"
        val directory = File(applicationContext.cacheDir, "local-ai-runtime-download")
        check(directory.mkdirs() || directory.isDirectory) { "Unable to prepare runtime download" }
        val partial = File(directory, "$abi.zip.part")
        val existingBytes = partial.takeIf(File::isFile)?.length() ?: 0L
        val request = Request.Builder().url(url).apply {
            if (existingBytes > 0L) header("Range", "bytes=$existingBytes-")
        }.build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Runtime download failed (${response.code})" }
            val append = existingBytes > 0L && response.code == 206
            val startBytes = if (append) existingBytes else 0L
            val responseBytes = response.body.contentLength().takeIf { it >= 0L }
            val totalBytes = responseBytes?.plus(startBytes)
            setProgress(workDataOf(PROGRESS_PHASE to PHASE_DOWNLOADING, PROGRESS_DOWNLOADED to startBytes,
                PROGRESS_TOTAL to (totalBytes ?: -1L)))
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
                        setProgress(workDataOf(PROGRESS_PHASE to PHASE_DOWNLOADING, PROGRESS_DOWNLOADED to downloaded,
                            PROGRESS_TOTAL to (totalBytes ?: -1L)))
                    }
                }
            }
        }
        setProgress(workDataOf(PROGRESS_PHASE to PHASE_INSTALLING))
        installer.installDownloadedArchive(partial)
        partial.delete()
        runtime.refresh()
        Result.success()
    }.getOrElse { error ->
        if (error is kotlinx.coroutines.CancellationException) throw error
        if (runAttemptCount < MAX_RETRIES) Result.retry()
        else Result.failure(workDataOf(OUTPUT_ERROR to (error.message ?: "Runtime download failed")))
    }

    private fun createForegroundInfo(): ForegroundInfo {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                applicationContext.getString(R.string.local_models_runtime_download_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
            applicationContext.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.local_models_runtime_downloading_notification))
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
        // A stable endpoint lets the CDN replace the package without APK changes. The archive
        // manifest remains the source of truth for its installed version and ABI.
        private const val RUNTIME_BASE_URL = "https://flit-runtime.54xzh.com"
        const val PROGRESS_PHASE = "phase"
        const val PROGRESS_DOWNLOADED = "downloaded"
        const val PROGRESS_TOTAL = "total"
        const val PHASE_DOWNLOADING = "downloading"
        const val PHASE_INSTALLING = "installing"
        const val OUTPUT_ERROR = "error"
        private const val MAX_RETRIES = 3
        private const val NOTIFICATION_CHANNEL_ID = "local_runtime_download"
        private const val NOTIFICATION_ID = 4341
    }
}
