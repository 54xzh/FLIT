package me.rerere.rikkahub.data.backup

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.datastore.ObjectStorageConfig
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.sync.ObjectStorageBackupItem
import me.rerere.rikkahub.data.sync.ObjectStorageSync
import me.rerere.rikkahub.data.sync.WebDavBackupItem
import me.rerere.rikkahub.data.sync.WebdavSync
import java.io.File

private const val AUTO_SUBFOLDER = "auto"

class BackupCoordinator(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val webdavSync: WebdavSync,
    private val objectStorageSync: ObjectStorageSync,
    private val backupLogManager: BackupLogManager,
    private val backupTaskMutex: BackupTaskMutex,
    private val compatExporter: CompatExporter,
) {
    fun isNetworkAvailable(): Boolean {
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrDefault(false)
    }

    fun hasDueAutoBackup(now: Long = System.currentTimeMillis()): Boolean {
        val settingsSnapshot = settingsStore.settingsFlow.value
        if (settingsSnapshot.init) return false

        return AutoBackupPolicy.canRunWebDav(settingsSnapshot.webDavConfig, now) ||
            AutoBackupPolicy.canRunObjectStorage(settingsSnapshot.objectStorageConfig, now)
    }

    suspend fun manualBackupWebDav() = backupTaskMutex.mutex.withLock {
        val settingsSnapshot = settingsStore.settingsFlow.value
        if (settingsSnapshot.init) return@withLock

        val startAt = System.currentTimeMillis()
        try {
            webdavSync.backupToWebDav(settingsSnapshot.webDavConfig)
            backupLogManager.log(
                action = BackupLogAction.BACKUP,
                trigger = BackupLogTrigger.MANUAL,
                backend = BackupLogBackend.WEBDAV,
                status = BackupLogStatus.SUCCESS,
                message = "Backup finished",
                durationMs = System.currentTimeMillis() - startAt,
            )
        } catch (err: Throwable) {
            if (err is CancellationException) throw err
            backupLogManager.log(
                action = BackupLogAction.BACKUP,
                trigger = BackupLogTrigger.MANUAL,
                backend = BackupLogBackend.WEBDAV,
                status = BackupLogStatus.FAILED,
                message = "Backup failed",
                durationMs = System.currentTimeMillis() - startAt,
                error = err,
            )
            throw err
        }
    }

    suspend fun manualBackupObjectStorage() = backupTaskMutex.mutex.withLock {
        val settingsSnapshot = settingsStore.settingsFlow.value
        if (settingsSnapshot.init) return@withLock

        val startAt = System.currentTimeMillis()
        try {
            objectStorageSync.backupNow(settingsSnapshot.objectStorageConfig)
            backupLogManager.log(
                action = BackupLogAction.BACKUP,
                trigger = BackupLogTrigger.MANUAL,
                backend = BackupLogBackend.OBJECT_STORAGE,
                status = BackupLogStatus.SUCCESS,
                message = "Backup finished",
                durationMs = System.currentTimeMillis() - startAt,
            )
        } catch (err: Throwable) {
            if (err is CancellationException) throw err
            backupLogManager.log(
                action = BackupLogAction.BACKUP,
                trigger = BackupLogTrigger.MANUAL,
                backend = BackupLogBackend.OBJECT_STORAGE,
                status = BackupLogStatus.FAILED,
                message = "Backup failed",
                durationMs = System.currentTimeMillis() - startAt,
                error = err,
            )
            throw err
        }
    }

    suspend fun exportToFile() = backupTaskMutex.mutex.withLock {
        val settingsSnapshot = settingsStore.settingsFlow.value
        if (settingsSnapshot.init) throw IllegalStateException("Settings not ready")
        webdavSync.prepareBackupFile(settingsSnapshot.webDavConfig.copy())
    }

    /**
     * 导出原版 RikkaHub 客户端兼容的备份包(v24 schema,只含普通对话+提供商配置)。
     * 返回 cacheDir 中的临时 zip 文件,调用方负责拷贝到目标位置后删除。
     */
    suspend fun exportRikkaHubCompat(): File = backupTaskMutex.mutex.withLock {
        val settingsSnapshot = settingsStore.settingsFlow.value
        if (settingsSnapshot.init) throw IllegalStateException("Settings not ready")
        compatExporter.exportRikkaHubCompat().file
    }

    suspend fun restoreWebDav(item: WebDavBackupItem): WebdavSync.RestoreResult = backupTaskMutex.mutex.withLock {
        val settingsSnapshot = settingsStore.settingsFlow.value
        if (settingsSnapshot.init) throw IllegalStateException("Settings not ready")
        webdavSync.restoreFromWebDav(
            webDavConfig = settingsSnapshot.webDavConfig,
            item = item,
        )
    }

    suspend fun restoreObjectStorage(item: ObjectStorageBackupItem): WebdavSync.RestoreResult = backupTaskMutex.mutex.withLock {
        val settingsSnapshot = settingsStore.settingsFlow.value
        if (settingsSnapshot.init) throw IllegalStateException("Settings not ready")
        objectStorageSync.restoreFromObjectStorage(
            config = settingsSnapshot.objectStorageConfig,
            item = item,
        )
    }

    suspend fun restoreFromLocalFile(file: java.io.File): WebdavSync.RestoreResult = backupTaskMutex.mutex.withLock {
        val settingsSnapshot = settingsStore.settingsFlow.value
        if (settingsSnapshot.init) throw IllegalStateException("Settings not ready")
        webdavSync.restoreFromLocalFile(
            file = file,
            webDavConfig = settingsSnapshot.webDavConfig,
        )
    }

    suspend fun runAutoBackupIfDue() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val settingsSnapshot = settingsStore.settingsFlow.value
        if (settingsSnapshot.init) return@withContext

        val shouldWebDav = AutoBackupPolicy.canRunWebDav(settingsSnapshot.webDavConfig, now)
        val shouldObjectStorage = AutoBackupPolicy.canRunObjectStorage(settingsSnapshot.objectStorageConfig, now)

        if (!shouldWebDav && !shouldObjectStorage) return@withContext

        if (!isNetworkAvailable()) {
            if (shouldWebDav) {
                backupLogManager.log(
                    action = BackupLogAction.BACKUP,
                    trigger = BackupLogTrigger.AUTO,
                    backend = BackupLogBackend.WEBDAV,
                    status = BackupLogStatus.SKIPPED,
                    message = "Skipped: no network",
                )
            }
            if (shouldObjectStorage) {
                backupLogManager.log(
                    action = BackupLogAction.BACKUP,
                    trigger = BackupLogTrigger.AUTO,
                    backend = BackupLogBackend.OBJECT_STORAGE,
                    status = BackupLogStatus.SKIPPED,
                    message = "Skipped: no network",
                )
            }
            return@withContext
        }

        backupTaskMutex.mutex.withLock {
            if (shouldWebDav) {
                runAutoWebDavBackup(settingsSnapshot.webDavConfig)
            }
            if (shouldObjectStorage) {
                runAutoObjectStorageBackup(settingsSnapshot.objectStorageConfig)
            }
        }
    }

    private suspend fun runAutoWebDavBackup(config: WebDavConfig) {
        val startAt = System.currentTimeMillis()
        try {
            val result = webdavSync.backupToWebDavAuto(config, subfolder = AUTO_SUBFOLDER)
            val successAt = System.currentTimeMillis()
            settingsStore.update { current ->
                if (current.webDavConfig.lastAutoSuccessAt == successAt) current
                else current.copy(webDavConfig = current.webDavConfig.copy(lastAutoSuccessAt = successAt))
            }
            backupLogManager.log(
                action = BackupLogAction.BACKUP,
                trigger = BackupLogTrigger.AUTO,
                backend = BackupLogBackend.WEBDAV,
                status = BackupLogStatus.SUCCESS,
                message = "Backup finished",
                fileName = result.fileName,
                fileSizeBytes = result.fileSizeBytes,
                durationMs = System.currentTimeMillis() - startAt,
            )

            cleanupWebDavAutoBackups(config = config, maxCount = config.autoMaxCount)
        } catch (err: Throwable) {
            if (err is CancellationException) throw err
            backupLogManager.log(
                action = BackupLogAction.BACKUP,
                trigger = BackupLogTrigger.AUTO,
                backend = BackupLogBackend.WEBDAV,
                status = BackupLogStatus.FAILED,
                message = "Backup failed",
                durationMs = System.currentTimeMillis() - startAt,
                error = err,
            )
        }
    }

    private suspend fun cleanupWebDavAutoBackups(config: WebDavConfig, maxCount: Int) {
        if (maxCount <= 0) return
        val safeMax = maxCount
        val autoConfig = config.withSubfolder(AUTO_SUBFOLDER)
        try {
            val items = webdavSync.listBackupFiles(autoConfig)
                .asSequence()
                .filter { it.displayName.startsWith("LastChat_backup_") && it.displayName.endsWith(".zip") }
                .sortedByDescending { it.lastModified }
                .toList()

            val toDelete = items.drop(safeMax)
            if (toDelete.isEmpty()) return

            toDelete.forEach { item ->
                webdavSync.deleteWebDavBackupFile(config, item)
            }

            val deletedCount = toDelete.size
            if (deletedCount > 0) {
                backupLogManager.log(
                    action = BackupLogAction.CLEANUP,
                    trigger = BackupLogTrigger.AUTO,
                    backend = BackupLogBackend.WEBDAV,
                    status = BackupLogStatus.SUCCESS,
                    message = "Deleted $deletedCount old auto backups",
                )
            }
        } catch (err: Throwable) {
            if (err is CancellationException) throw err
            backupLogManager.log(
                action = BackupLogAction.CLEANUP,
                trigger = BackupLogTrigger.AUTO,
                backend = BackupLogBackend.WEBDAV,
                status = BackupLogStatus.FAILED,
                message = "Cleanup failed",
                error = err,
            )
        }
    }

    private suspend fun runAutoObjectStorageBackup(config: ObjectStorageConfig) {
        val startAt = System.currentTimeMillis()
        try {
            val result = objectStorageSync.backupNowAuto(config, subfolder = AUTO_SUBFOLDER)
            val successAt = System.currentTimeMillis()
            settingsStore.update { current ->
                if (current.objectStorageConfig.lastAutoSuccessAt == successAt) current
                else current.copy(objectStorageConfig = current.objectStorageConfig.copy(lastAutoSuccessAt = successAt))
            }
            backupLogManager.log(
                action = BackupLogAction.BACKUP,
                trigger = BackupLogTrigger.AUTO,
                backend = BackupLogBackend.OBJECT_STORAGE,
                status = BackupLogStatus.SUCCESS,
                message = "Backup finished",
                fileName = result.fileName,
                fileSizeBytes = result.fileSizeBytes,
                durationMs = System.currentTimeMillis() - startAt,
            )

            cleanupObjectStorageAutoBackups(config = config, maxCount = config.autoMaxCount)
        } catch (err: Throwable) {
            if (err is CancellationException) throw err
            backupLogManager.log(
                action = BackupLogAction.BACKUP,
                trigger = BackupLogTrigger.AUTO,
                backend = BackupLogBackend.OBJECT_STORAGE,
                status = BackupLogStatus.FAILED,
                message = "Backup failed",
                durationMs = System.currentTimeMillis() - startAt,
                error = err,
            )
        }
    }

    private suspend fun cleanupObjectStorageAutoBackups(config: ObjectStorageConfig, maxCount: Int) {
        if (maxCount <= 0) return
        val safeMax = maxCount
        try {
            val items = objectStorageSync.listBackupFilesAuto(config, subfolder = AUTO_SUBFOLDER)
                .asSequence()
                .filter { it.displayName.startsWith("LastChat_backup_") && it.displayName.endsWith(".zip") }
                .sortedByDescending { it.lastModified }
                .toList()

            val toDelete = items.drop(safeMax)
            if (toDelete.isEmpty()) return

            toDelete.forEach { item ->
                objectStorageSync.deleteBackupFile(config, item)
            }

            val deletedCount = toDelete.size
            if (deletedCount > 0) {
                backupLogManager.log(
                    action = BackupLogAction.CLEANUP,
                    trigger = BackupLogTrigger.AUTO,
                    backend = BackupLogBackend.OBJECT_STORAGE,
                    status = BackupLogStatus.SUCCESS,
                    message = "Deleted $deletedCount old auto backups",
                )
            }
        } catch (err: Throwable) {
            if (err is CancellationException) throw err
            backupLogManager.log(
                action = BackupLogAction.CLEANUP,
                trigger = BackupLogTrigger.AUTO,
                backend = BackupLogBackend.OBJECT_STORAGE,
                status = BackupLogStatus.FAILED,
                message = "Cleanup failed",
                error = err,
            )
        }
    }

    private fun WebDavConfig.withSubfolder(subfolder: String): WebDavConfig {
        val normalized = joinPath(this.path, subfolder)
        return copy(path = normalized)
    }

    private fun joinPath(base: String, child: String): String {
        val baseTrimmed = base.trim().trim('/')
        val childTrimmed = child.trim().trim('/')
        return when {
            baseTrimmed.isBlank() -> childTrimmed
            childTrimmed.isBlank() -> baseTrimmed
            else -> "$baseTrimmed/$childTrimmed"
        }
    }
}

data class BackupRemoteResult(
    val fileName: String,
    val fileSizeBytes: Long,
)
