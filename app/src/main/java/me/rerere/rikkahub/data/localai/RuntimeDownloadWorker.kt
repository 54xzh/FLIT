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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.FileOutputStream

class RuntimeDownloadWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params), KoinComponent {
    private val client: OkHttpClient by inject()
    private val installer: RuntimePackageInstaller by inject()
    private val runtime: LocalRuntimeManager by inject()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val runtimePackage = LocalRuntimePackage.fromEngine(inputData.getString(INPUT_ENGINE))
            ?: return@withContext Result.failure(workDataOf(OUTPUT_ERROR to "Unknown runtime engine"))
        transferLocks.getValue(runtimePackage).withLock {
            val directory = File(applicationContext.cacheDir, "local-ai-runtime-download")
            val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return@withLock Result.failure()
            val fileName = if (runtimePackage == LocalRuntimePackage.GGUF) "$abi.zip.part"
                else "litert-${LocalRuntimePackage.LITERT_VERSION}.aar.part"
            val partial = File(directory, fileName)
            try {
                setForeground(createForegroundInfo(runtimePackage))
                check(directory.mkdirs() || directory.isDirectory) { "Unable to prepare runtime download" }
                val url = when (runtimePackage) {
                    LocalRuntimePackage.GGUF -> "$RUNTIME_BASE_URL/$abi.zip"
                    LocalRuntimePackage.LITERT_LM -> LocalRuntimePackage.LITERT_AAR_URL
                }
                download(url, partial)
                setProgress(workDataOf(PROGRESS_PHASE to PHASE_INSTALLING))
                currentCoroutineContext().ensureActive()
                try {
                    installer.installDownloadedArchive(partial, runtimePackage)
                } catch (error: Exception) {
                    // A complete but invalid file must not be resumed forever on every retry.
                    if (error !is CancellationException) partial.delete()
                    throw error
                }
                partial.delete()
                Result.success()
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (error is CancellationException) throw error
                // Transient network errors may retry; malformed/incompatible packages need a
                // visible error so users can act rather than waiting through repeated retries.
                if (error is java.io.IOException && runAttemptCount < MAX_RETRIES) Result.retry()
                else Result.failure(workDataOf(OUTPUT_ERROR to (error.message ?: "Runtime download failed")))
            } finally {
                // Installation may finish atomically just as the worker is paused.
                runtime.refresh()
            }
        }
    }

    private suspend fun download(url: String, partial: File) = coroutineScope {
        val existing = partial.takeIf(File::isFile)?.length() ?: 0L
        val request = Request.Builder().url(url).header("Accept-Encoding", "identity").apply {
            if (existing > 0) header("Range", "bytes=$existing-")
        }.build()
        val call = client.newCall(request)
        // Blocking reads run on IO; cancellation also closes their socket rather than waiting
        // for a read timeout before a paused download can safely be resumed.
        val cancelWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            call.execute().use { response ->
                if (response.code == 416 && existing > 0) {
                    // A completed download interrupted just before installation can skip re-fetch.
                    val total = response.header("Content-Range")?.substringAfter("bytes */", "")?.toLongOrNull()
                    if (total == existing) return@use
                    partial.delete()
                    error("Downloaded runtime changed; retry the download")
                }
                check(response.isSuccessful) { "Runtime download failed (${response.code})" }
                val append = existing > 0 && response.code == 206
                if (response.code == 206) {
                    val rangeStart = response.header("Content-Range")?.substringAfter("bytes ", "")
                        ?.substringBefore('-')?.toLongOrNull()
                    check(rangeStart == if (append) existing else 0L) { "Invalid runtime download range" }
                }
                val start = if (append) existing else 0L
                val total = response.body.contentLength().takeIf { it >= 0 }?.plus(start)
                var downloaded = start
                suspend fun report() = setProgress(workDataOf(
                    PROGRESS_PHASE to PHASE_DOWNLOADING,
                    PROGRESS_DOWNLOADED to downloaded,
                    PROGRESS_TOTAL to (total ?: -1L),
                ))
                report()
                var lastReport = android.os.SystemClock.elapsedRealtime()
                response.body.byteStream().use { input ->
                    FileOutputStream(partial, append).buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            downloaded += count
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - lastReport >= 250) { report(); lastReport = now }
                        }
                    }
                }
                check(total == null || total == downloaded) { "Runtime download is incomplete" }
                report()
            }
        } finally {
            cancelWatcher.cancel()
        }
    }

    private fun createForegroundInfo(runtimePackage: LocalRuntimePackage): ForegroundInfo {
        val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID,
            applicationContext.getString(R.string.local_models_runtime_download_channel), NotificationManager.IMPORTANCE_LOW)
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.local_models_runtime_downloading_notification))
            .setContentText(runtimePackage.displayName)
            .setOngoing(true).setOnlyAlertOnce(true).setProgress(0, 0, true).build()
        return ForegroundInfo(NOTIFICATION_ID + runtimePackage.ordinal, notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
    }

    companion object {
        private const val RUNTIME_BASE_URL = "https://flit-runtime.54xzh.com"
        const val INPUT_ENGINE = "engine"
        const val PROGRESS_PHASE = "phase"
        const val PROGRESS_DOWNLOADED = "downloaded"
        const val PROGRESS_TOTAL = "total"
        const val PHASE_DOWNLOADING = "downloading"
        const val PHASE_INSTALLING = "installing"
        const val OUTPUT_ERROR = "error"
        private const val MAX_RETRIES = 2
        private const val NOTIFICATION_CHANNEL_ID = "local_runtime_downloads"
        private const val NOTIFICATION_ID = 3102
        private val transferLocks = LocalRuntimePackage.entries.associateWith { Mutex() }
    }
}
