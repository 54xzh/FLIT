package me.rerere.rikkahub.data.sync

import android.content.Context
import android.util.Log
import at.bitfire.dav4jvm.okhttp.BasicDigestAuthHandler
import at.bitfire.dav4jvm.okhttp.DavCollection
import at.bitfire.dav4jvm.okhttp.Response
import at.bitfire.dav4jvm.okhttp.exception.NotFoundException
import at.bitfire.dav4jvm.property.webdav.DisplayName
import at.bitfire.dav4jvm.property.webdav.GetContentLength
import at.bitfire.dav4jvm.property.webdav.GetLastModified
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.backup.BackupRemoteResult
import me.rerere.rikkahub.data.datastore.ChatReadPositionStore
import me.rerere.rikkahub.data.datastore.ConversationReadPosition
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.datastore.migration.migrateLegacyReasoningSettingsJson
import me.rerere.rikkahub.data.datastore.sanitize
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.migration.RestoreTargets
import me.rerere.rikkahub.data.migration.SettingsJsonHolder
import me.rerere.rikkahub.data.migration.SkillUuidMigration
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val TAG = "DataSync"
private const val BACKUP_FILE_PREFIX = "FLIT_backup_"
private const val BACKUP_FILE_SUFFIX = ".zip"
// 旧版本导出的备份以 LastChat_backup_ 开头，识别时一并兼容
private val BACKUP_FILE_PREFIXES = setOf(BACKUP_FILE_PREFIX, "LastChat_backup_")
private const val DATABASE_SNAPSHOT_PREFIX = "rikka_hub_snapshot_"
private val STALE_BACKUP_TEMP_MAX_AGE_MS = TimeUnit.HOURS.toMillis(24)

internal data class RestoreDirectorySnapshot(
    val relativePath: String,
    val existed: Boolean,
    val backupDir: File,
)

private fun resolveRestoreDirectory(root: File, relativePath: String): File {
    val canonicalRoot = root.canonicalFile
    val target = canonicalRoot.resolve(relativePath).canonicalFile
    check(target.path.startsWith(canonicalRoot.path + File.separator)) {
        "Restore path escapes files root: $relativePath"
    }
    return target
}

internal fun sandboxRestoreRollbackPath(relativePath: String): String? {
    val parts = relativePath.replace('\\', '/').split('/')
    if (parts.any { it.isBlank() || it == "." || it == ".." }) return null
    if (parts.size < 3 || parts[1] != "files") return null
    return "sandbox_workspaces/${parts[0]}/files"
}

internal fun prepareRestoreDirectorySnapshots(
    filesDir: File,
    rollbackRoot: File,
    relativePaths: Collection<String>,
): List<RestoreDirectorySnapshot> {
    check(rollbackRoot.mkdirs() || rollbackRoot.isDirectory) {
        "Failed to create restore rollback directory"
    }
    return relativePaths.distinct().sorted().mapIndexed { index, relativePath ->
        val liveDir = resolveRestoreDirectory(filesDir, relativePath)
        val backupDir = File(rollbackRoot, index.toString())
        val existed = liveDir.exists()
        if (existed) {
            check(liveDir.isDirectory) { "Restore target is not a directory: $relativePath" }
            check(liveDir.copyRecursively(backupDir, overwrite = false)) {
                "Failed to snapshot restore directory: $relativePath"
            }
        }
        RestoreDirectorySnapshot(relativePath, existed, backupDir)
    }
}

internal fun landStagedRestoreDirectories(
    filesDir: File,
    stagedFilesRoot: File,
    relativePaths: Collection<String>,
    replacePaths: Set<String> = setOf("skills"),
) {
    relativePaths.distinct().sorted().forEach { relativePath ->
        val stagedDir = resolveRestoreDirectory(stagedFilesRoot, relativePath)
        check(stagedDir.isDirectory) { "Staged restore directory is missing: $relativePath" }
        val liveDir = resolveRestoreDirectory(filesDir, relativePath)
        if (relativePath in replacePaths && liveDir.exists()) {
            check(liveDir.deleteRecursively()) { "Failed to clear restore directory: $relativePath" }
        }
        liveDir.parentFile?.let { parent ->
            check(parent.mkdirs() || parent.isDirectory) {
                "Failed to create restore parent directory: $relativePath"
            }
        }
        check(stagedDir.copyRecursively(liveDir, overwrite = true)) {
            "Failed to land restore directory: $relativePath"
        }
    }
}

internal fun restoreDirectorySnapshots(
    filesDir: File,
    snapshots: List<RestoreDirectorySnapshot>,
) {
    val failures = mutableListOf<Throwable>()
    snapshots.asReversed().forEach { snapshot ->
        runCatching {
            val liveDir = resolveRestoreDirectory(filesDir, snapshot.relativePath)
            if (liveDir.exists()) {
                check(liveDir.deleteRecursively()) {
                    "Failed to remove partially restored directory: ${snapshot.relativePath}"
                }
            }
            if (snapshot.existed) {
                check(snapshot.backupDir.copyRecursively(liveDir, overwrite = false)) {
                    "Failed to restore directory snapshot: ${snapshot.relativePath}"
                }
            }
        }.onFailure(failures::add)
    }
    if (failures.isNotEmpty()) {
        val error = IllegalStateException("Failed to roll back restored file directories")
        failures.forEach(error::addSuppressed)
        throw error
    }
}

/**
 * 沙盒恢复后待清理的 sentinel 文件名（写在 filesDir 根下，不在 sandbox_workspaces 目录里，
 * 避免被当成工作区遍历）。恢复成功后写入，启动时 checkIntegrity 检测到它就逐个工作区清掉
 * 旧 rootfs（linux/ 与 tmp/）并把状态降级为未安装，完成后删除 sentinel。
 */
const val SANDBOX_RESTORE_SENTINEL = ".sandbox_restore_pending"
private val FILES_DIR_BACKUP_PATHS = listOf(
    "upload",
    "avatars",
    "images",
    "skills",
    "python/wheels",
    "custom_fonts",
    "chat_files",
    "custom_icons",
    // 沙盒工作区：仅备份每个工作区下的 files/ 用户文件（白名单），其余根级目录
    // （linux/ rootfs、tmp/ 等）不进包，见 addSandboxWorkspacesToZip。
    "sandbox_workspaces",
)

class WebdavSync(
    private val settingsStore: SettingsStore,
    private val readPositionStore: ChatReadPositionStore,
    private val json: Json,
    private val context: Context,
    private val database: AppDatabase,
    private val skillUuidMigration: SkillUuidMigration,
) {
    suspend fun testWebdav(webDavConfig: WebDavConfig) {
        val davCollection = DavCollection(
            httpClient = webDavConfig.requireClient(),
            location = webDavConfig.url.toHttpUrl(),
        )

        withContext(Dispatchers.IO) {
            davCollection.propfind(
                depth = 1,
            ) { response, relation ->
                Log.i(TAG, "testWebdav: $response | $relation")
            }
        }
    }

    suspend fun backupToWebDav(webDavConfig: WebDavConfig) = withContext(Dispatchers.IO) {
        val file = prepareBackupFile(webDavConfig)
        try {
            val collection = webDavConfig.requireCollection()
            collection.ensureCollectionExists() // ensure collection exists
            val target = webDavConfig.requireCollection(file.name)
            target.put(
                body = file.asRequestBody(),
            ) { response ->
                Log.i(TAG, "backupToWebDav: $response")
            }
        } finally {
            runCatching { file.delete() }
        }
    }

    suspend fun backupToWebDavAuto(
        webDavConfig: WebDavConfig,
        subfolder: String,
    ): BackupRemoteResult = withContext(Dispatchers.IO) {
        cleanupStaleBackupTempFilesNow()

        // Check the remote destination before creating a potentially large local backup.
        webDavConfig.requireCollection().ensureCollectionExists()
        val autoConfig = webDavConfig.copy(path = joinPath(webDavConfig.path, subfolder))
        autoConfig.requireCollection().ensureCollectionExists()

        val file = prepareBackupFile(webDavConfig)
        try {
            val target = autoConfig.requireCollection(file.name)
            target.put(
                body = file.asRequestBody(),
            ) { response ->
                Log.i(TAG, "backupToWebDavAuto: $response")
            }

            BackupRemoteResult(
                fileName = file.name,
                fileSizeBytes = file.length(),
            )
        } finally {
            runCatching { file.delete() }
        }
    }

    suspend fun listBackupFiles(webDavConfig: WebDavConfig): List<WebDavBackupItem> =
        withContext(Dispatchers.IO) {
            val collection = webDavConfig.requireCollection()
            val files = mutableListOf<WebDavBackupItem>()
            collection.propfind(
                depth = 1,
            ) { response, relation ->
                Log.i(TAG, "listBackupFiles: ${response.properties} ${response.href}")
                if (relation == Response.HrefRelation.MEMBER) {
                    val displayName = response.properties.filterIsInstance<DisplayName>()
                        .firstOrNull()?.displayName ?: "Unknown"
                    if (!displayName.endsWith(".zip")) return@propfind
                    val size = response.properties.filterIsInstance<GetContentLength>()
                        .firstOrNull()?.contentLength ?: 0L
                    val lastModified = response.properties.filterIsInstance<GetLastModified>()
                        .firstOrNull()?.lastModified ?: Instant.EPOCH
                    files.add(
                        WebDavBackupItem(
                            href = response.href.toString(),
                            displayName = displayName,
                            size = size,
                            lastModified = lastModified
                        )
                    )
                }
            }
            files
        }

    suspend fun restoreFromWebDav(webDavConfig: WebDavConfig, item: WebDavBackupItem): RestoreResult =
        withContext(Dispatchers.IO) {
            val collection = DavCollection(
                httpClient = webDavConfig.requireClient(),
                location = item.href.toHttpUrl(),
            )
            val backupFile = File(context.cacheDir, item.displayName)
            if (backupFile.exists()) {
                backupFile.delete()
            }

            // 下载备份文件
            collection.get(
                accept = "",
                headers = null
            ) { response ->
                if (response.isSuccessful) {
                    Log.i(
                        TAG,
                        "restoreFromWebDav: Downloading ${item.displayName} to ${backupFile.absolutePath}"
                    )
                    response.body?.byteStream()?.use { inputStream ->
                        FileOutputStream(backupFile).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                } else {
                    Log.e(
                        TAG,
                        "restoreFromWebDav: Failed to download ${item.displayName}, response: $response"
                    )
                    throw Exception("Failed to download backup file: ${response.message}")
                }
            }

            Log.i(TAG, "restoreFromWebDav: Downloaded ${backupFile.length()} bytes")

            try {
                // 解压并恢复备份文件
                // Force include both DATABASE and FILES during restore to ensure all data is restored
                val restoreConfig = webDavConfig.copy(
                    items = listOf(WebDavConfig.BackupItem.DATABASE, WebDavConfig.BackupItem.FILES)
                )
                restoreFromBackupFile(backupFile, restoreConfig)
            } finally {
                // 清理临时文件
                if (backupFile.exists()) {
                    backupFile.delete()
                    Log.i(TAG, "restoreFromWebDav: Cleaned up temporary backup file")
                }
            }
        }

    suspend fun deleteWebDavBackupFile(webDavConfig: WebDavConfig, item: WebDavBackupItem) =
        withContext(Dispatchers.IO) {
            val collection = DavCollection(
                httpClient = webDavConfig.requireClient(),
                location = item.href.toHttpUrl()
            )
            collection.delete { response ->
                Log.i(TAG, "deleteWebDavBackupFile: $response")
            }
        }

    suspend fun restoreFromLocalFile(file: File, webDavConfig: WebDavConfig): RestoreResult =
        withContext(Dispatchers.IO) {
            Log.i(TAG, "restoreFromLocalFile: Starting restore from ${file.absolutePath}")

            if (!file.exists()) {
                throw Exception("Backup file does not exist")
            }

            if (!file.canRead()) {
                throw Exception("Cannot read backup file")
            }

            try {
                // Force include both DATABASE and FILES during restore to ensure all data is restored
                val restoreConfig = webDavConfig.copy(
                    items = listOf(WebDavConfig.BackupItem.DATABASE, WebDavConfig.BackupItem.FILES)
                )
                restoreFromBackupFile(file, restoreConfig)
            } catch (e: Exception) {
                Log.e(TAG, "restoreFromLocalFile: Failed to restore from local file", e)
                throw Exception("Restore failed: ${e.message}")
            }
        }

    suspend fun prepareBackupFile(webDavConfig: WebDavConfig): File = withContext(Dispatchers.IO) {
        cleanupStaleBackupTempFilesNow()

        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))
        val backupFile = File(
            context.cacheDir,
            "$BACKUP_FILE_PREFIX$timestamp$BACKUP_FILE_SUFFIX"
        )
        if (backupFile.exists()) {
            backupFile.delete()
        }

        // 创建zip文件并备份数据库
        ZipOutputStream(FileOutputStream(backupFile)).use { zipOut ->
            addVirtualFileToZip(
                zipOut = zipOut,
                name = "settings.json",
                content = json.encodeToString(settingsStore.settingsFlow.value)
            )

            // 阅读位置已从 Settings 挪到独立存储，单独入包，恢复时才能继续带回各会话的阅读进度
            readPositionStore.awaitReady()
            addVirtualFileToZip(
                zipOut = zipOut,
                name = "read_positions.json",
                content = json.encodeToString(readPositionStore.positionsFlow.value)
            )

            // 备份数据库
            if (webDavConfig.items.contains(WebDavConfig.BackupItem.DATABASE)) {
                val snapshotFile = File(context.cacheDir, "rikka_hub_snapshot_$timestamp")
                if (snapshotFile.exists()) snapshotFile.delete()

                val snapshotOk = runCatching {
                    exportDatabaseSnapshot(snapshotFile)
                }.isSuccess && snapshotFile.exists()

                if (snapshotOk) {
                    addFileToZip(zipOut, snapshotFile, "rikka_hub.db")
                    snapshotFile.delete()
                } else {
                    if (snapshotFile.exists()) snapshotFile.delete()
                    // Fallback: copy db file + wal/shm (may be inconsistent if the DB is being written).
                    val dbFile = context.getDatabasePath("rikka_hub")
                    if (dbFile.exists()) {
                        addFileToZip(zipOut, dbFile, "rikka_hub.db")
                    }

                    val walFile = File(dbFile.parentFile, "rikka_hub-wal")
                    if (walFile.exists()) {
                        addFileToZip(zipOut, walFile, "rikka_hub-wal")
                    }

                    val shmFile = File(dbFile.parentFile, "rikka_hub-shm")
                    if (shmFile.exists()) {
                        addFileToZip(zipOut, shmFile, "rikka_hub-shm")
                    }
                }
            }

            // 备份聊天文件
            if (webDavConfig.items.contains(WebDavConfig.BackupItem.FILES)) {
                FILES_DIR_BACKUP_PATHS.forEach { relativePath ->
                    val folder = File(context.filesDir, relativePath)
                    if (folder.exists() && folder.isDirectory) {
                        Log.i(
                            TAG,
                            "prepareBackupFile: Backing up $relativePath from ${folder.absolutePath}"
                        )
                        if (relativePath == "sandbox_workspaces") {
                            // 白名单：只打包每个工作区下的 files/ 用户文件，其余根级目录
                            // （linux/ rootfs、tmp/ 及未来新增的大目录）天然不进包，
                            // 避免黑名单「猜该跳谁」带来的漏排/误排。files/ 不存在的工作区直接跳过。
                            addSandboxWorkspacesToZip(zipOut, folder)
                        } else {
                            addDirectoryToZip(zipOut, folder, relativePath)
                        }
                    } else {
                        Log.i(
                            TAG,
                            "prepareBackupFile: $relativePath folder does not exist or is not a directory"
                        )
                    }
                }
            }
        }

        backupFile
    }

    suspend fun cleanupStaleBackupTempFiles(
        maxAgeMs: Long = STALE_BACKUP_TEMP_MAX_AGE_MS,
    ) = withContext(Dispatchers.IO) {
        cleanupStaleBackupTempFilesNow(maxAgeMs = maxAgeMs)
    }

    private fun cleanupStaleBackupTempFilesNow(
        maxAgeMs: Long = STALE_BACKUP_TEMP_MAX_AGE_MS,
    ) {
        val cutoff = System.currentTimeMillis() - maxAgeMs.coerceAtLeast(0L)
        context.cacheDir
            .listFiles()
            .orEmpty()
            .asSequence()
            .filter { entry ->
                entry.exists() &&
                    entry.lastModified() < cutoff &&
                    (entry.isBackupZipTempFile() || entry.name.startsWith(DATABASE_SNAPSHOT_PREFIX))
            }
            .forEach { entry ->
                runCatching {
                    if (entry.isDirectory) entry.deleteRecursively() else entry.delete()
                }.onSuccess { deleted ->
                    if (deleted) Log.i(TAG, "cleanupStaleBackupTempFiles: deleted ${entry.name}")
                }.onFailure { err ->
                    Log.w(TAG, "cleanupStaleBackupTempFiles: failed to delete ${entry.name}", err)
                }
            }
    }

    private fun exportDatabaseSnapshot(targetFile: File) {
        val path = targetFile.absolutePath.replace("'", "''")
        database.openHelper.writableDatabase.execSQL("VACUUM INTO '$path'")
    }



    data class RestoreResult(
        val sanitization: DatabaseSanitizer.SanitizationResult,
        val settingsCleanup: BackupCleanupResult
    )

    private suspend fun restoreFromBackupFile(backupFile: File, webDavConfig: WebDavConfig): RestoreResult =
        withContext(Dispatchers.IO) {
            Log.i(TAG, "restoreFromBackupFile: Starting restore from ${backupFile.absolutePath}")
            Log.i(TAG, "restoreFromBackupFile: webDavConfig.items = ${webDavConfig.items}")
            Log.i(TAG, "restoreFromBackupFile: context.filesDir = ${context.filesDir.absolutePath}")

            var unsupportedZipEntriesBytes: Long = 0
            var settingsCleanupResult = BackupCleanupResult()
            // Temp directory for extraction
            val restoreTempDir = File(context.cacheDir, "restore_temp_${System.currentTimeMillis()}")
            if (!restoreTempDir.exists()) restoreTempDir.mkdirs()
            // 所有文件先恢复到临时区，完成迁移和净化后再统一落盘，失败时正式数据保持不变。
            val tempFilesRoot = File(restoreTempDir, "files")
            check(tempFilesRoot.mkdirs()) { "Failed to create temporary restore files directory" }
            val tempSkillsRoot = File(tempFilesRoot, "skills")
            // settings.json 暂存到 holder，先迁移、最后才 sanitize 落盘，确保旧 UUID 备份被改写。
            val settingsJsonHolder = SettingsJsonHolder(json = null)
            // 新版备份的阅读位置独立条目；老备份没有该条目，恢复时从 settings.json 的旧字段提取
            var readPositionsJson: String? = null
            val restoredFilePaths = linkedSetOf<String>()
            val rollbackFilePaths = linkedSetOf<String>()

            var sanitizationResult = DatabaseSanitizer.SanitizationResult()

            try {
                ZipInputStream(FileInputStream(backupFile)).use { zipIn ->
                    var entry: ZipEntry?
                    while (zipIn.nextEntry.also { entry = it } != null) {
                        entry?.let { zipEntry ->
                            Log.i(TAG, "restoreFromBackupFile: Processing entry ${zipEntry.name}")

                            if (zipEntry.isDirectory) {
                                Log.i(
                                    TAG,
                                    "restoreFromBackupFile: Skipping directory entry ${zipEntry.name}"
                                )
                                zipIn.closeEntry()
                                return@let
                            }

                            when (zipEntry.name) {
                                "settings.json" -> {
                                    // 恢复设置：先暂存原始字符串，技能迁移后再 sanitize 落盘。
                                    val settingsJson = zipIn.readBytes().toString(Charsets.UTF_8)
                                    Log.i(TAG, "restoreFromBackupFile: Captured settings (deferred)")
                                    settingsJsonHolder.json = settingsJson
                                }

                                "read_positions.json" -> {
                                    readPositionsJson = zipIn.readBytes().toString(Charsets.UTF_8)
                                    Log.i(TAG, "restoreFromBackupFile: Captured read positions (deferred)")
                                }

                                "rikka_hub.db", "rikka_hub-wal", "rikka_hub-shm" -> {
                                    if (webDavConfig.items.contains(WebDavConfig.BackupItem.DATABASE)) {
                                        // Extract to temp dir first
                                        val tempFile = when (zipEntry.name) {
                                            // Use the actual db base name so SQLite can see -wal/-shm correctly
                                            "rikka_hub.db" -> File(restoreTempDir, "rikka_hub")
                                            else -> File(restoreTempDir, zipEntry.name)
                                        }
                                        FileOutputStream(tempFile).use { outputStream ->
                                            zipIn.copyTo(outputStream)
                                        }
                                        Log.i(TAG, "Extracted ${zipEntry.name} to temp")
                                    }
                                }

                                else -> {
                                    fun skipEntry(reason: String) {
                                        val size = zipEntry.size.coerceAtLeast(0)
                                        Log.i(
                                            TAG,
                                            "restoreFromBackupFile: Skipping $reason entry ${zipEntry.name} (${size} bytes)"
                                        )
                                        unsupportedZipEntriesBytes += size
                                    }

                                fun safeResolveTargetFile(baseDir: File, relativePath: String): File? {
                                    val normalized = relativePath.replace('\\', '/').trimStart('/')
                                    if (normalized.isBlank()) return null
                                    val targetFile = File(baseDir, normalized)
                                    val canonicalBase =
                                        runCatching { baseDir.canonicalFile }.getOrNull() ?: return null
                                    val canonicalTarget =
                                        runCatching { targetFile.canonicalFile }.getOrNull() ?: return null

                                    val basePath = canonicalBase.path.let { path ->
                                        if (path.endsWith(File.separator)) path else path + File.separator
                                    }
                                    return canonicalTarget.takeIf { it.path.startsWith(basePath) }
                                }

                                    fun restoreToFilesDirSubfolder(subfolder: String, prefix: String) {
                                    val relativePath = zipEntry.name.removePrefix(prefix)
                                    if (relativePath.isBlank()) return

                                    val rollbackPath = if (subfolder == "sandbox_workspaces") {
                                        sandboxRestoreRollbackPath(relativePath) ?: run {
                                            Log.w(TAG, "restoreFromBackupFile: Skipping unsupported sandbox entry ${zipEntry.name}")
                                            skipEntry(reason = "unsupported sandbox")
                                            return
                                        }
                                    } else {
                                        subfolder
                                    }

                                    val baseDir = resolveRestoreDirectory(tempFilesRoot, subfolder)
                                    if (!baseDir.exists()) {
                                        check(baseDir.mkdirs()) {
                                            "Failed to create staged restore directory: $subfolder"
                                        }
                                    }

                                    val targetFile = safeResolveTargetFile(baseDir, relativePath)
                                    if (targetFile == null) {
                                        Log.w(
                                            TAG,
                                            "restoreFromBackupFile: Skipping unsafe entry ${zipEntry.name}"
                                        )
                                        skipEntry(reason = "unsafe")
                                        return
                                    }

                                    Log.i(
                                        TAG,
                                        "restoreFromBackupFile: Restoring file ${zipEntry.name} to ${targetFile.absolutePath}"
                                    )

                                    try {
                                        targetFile.parentFile?.mkdirs()
                                        FileOutputStream(targetFile).use { outputStream ->
                                            zipIn.copyTo(outputStream)
                                        }
                                        restoredFilePaths.add(subfolder)
                                        rollbackFilePaths.add(rollbackPath)
                                        Log.i(
                                            TAG,
                                            "restoreFromBackupFile: Restored ${zipEntry.name} (${targetFile.length()} bytes)"
                                        )
                                    } catch (e: Exception) {
                                        Log.e(
                                            TAG,
                                            "restoreFromBackupFile: Failed to restore file ${zipEntry.name}",
                                            e
                                        )
                                        throw Exception("Failed to restore file ${zipEntry.name}: ${e.message}")
                                    }
                                }

                                if (webDavConfig.items.contains(WebDavConfig.BackupItem.FILES)) {
                                    val supportedPath = FILES_DIR_BACKUP_PATHS.firstOrNull { relativePath ->
                                        zipEntry.name.startsWith(filesDirBackupZipPrefix(relativePath))
                                    }
                                    if (supportedPath != null) {
                                        restoreToFilesDirSubfolder(
                                            subfolder = supportedPath,
                                            prefix = filesDirBackupZipPrefix(supportedPath)
                                        )
                                    } else {
                                        skipEntry(reason = "unsupported")
                                    }
                                } else {
                                    skipEntry(reason = "unsupported")
                                }
                            }
                            }

                            zipIn.closeEntry()
                        }
                    }
                }

                // 临时数据就位后：对临时技能目录 + 临时 settings JSON + 临时 DB 跑技能 UUID→名迁移。
                // 迁移作用于临时数据，不写 KEY_DONE、不持久化进度、不碰正式存储。
                // 只要恢复了 settings 就先迁移其中的技能引用；实际恢复到技能文件或数据库时，再同步迁移对应存储。
                val restoreSettings = settingsJsonHolder.json != null
                val restoreFiles = "skills" in restoredFilePaths
                val restoreDb = webDavConfig.items.contains(WebDavConfig.BackupItem.DATABASE)
                val tempDbFile = File(restoreTempDir, "rikka_hub")
                val tempDbRestored = tempDbFile.exists()

                if (restoreSettings) {
                    Log.i(TAG, "restoreFromBackupFile: Running skill UUID→name migration on restored temp data")
                    RestoreTargets(
                        context = context,
                        settingsJsonHolder = settingsJsonHolder,
                        tempDbFile = tempDbFile.takeIf { tempDbRestored },
                    ).use { targets ->
                        skillUuidMigration.migrateRestoreData(
                            tempSkillsRoot = tempSkillsRoot,
                            targets = targets,
                            migrateFiles = restoreFiles,
                            migrateDatabase = restoreDb && tempDbRestored,
                        )
                    }
                    Log.i(TAG, "restoreFromBackupFile: Temp skill migration completed")
                } else if (restoreFiles || tempDbRestored) {
                    throw Exception("Backup is missing settings.json required for skill migration")
                }

                // ---- 正式数据不变的前提下，先完成设置解析和数据库净化 ----

                val cleanedSettings = if (restoreSettings) {
                    val migratedJson = settingsJsonHolder.json
                        ?: throw Exception("Failed to restore settings: no settings captured")
                    try {
                        val reasoningMigratedJson = migrateLegacyReasoningSettingsJson(migratedJson)
                        settingsJsonHolder.json = reasoningMigratedJson
                        val settings = json.decodeFromString<Settings>(reasoningMigratedJson)
                        val (cleaned, cleanupResult) = settings.sanitize(context)
                        settingsCleanupResult = cleanupResult
                        cleaned
                    } catch (e: Exception) {
                        Log.e(TAG, "restoreFromBackupFile: Failed to prepare settings", e)
                        throw Exception("Failed to restore settings: ${e.message}")
                    }
                } else {
                    null
                }

                val cleanDb = if (tempDbRestored) {
                    Log.i(TAG, "Starting database sanitization...")
                    try {
                        val (cleaned, result) = DatabaseSanitizer.sanitize(context, tempDbFile)
                        sanitizationResult = result
                        cleaned
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to sanitize database", e)
                        throw Exception("Database sanitization failed: ${e.message}")
                    }
                } else {
                    null
                }

                // ---- 所有预检查成功后，准备回滚快照并统一落盘 ----

                val settingsSnapshot = settingsStore.createRestoreSnapshot()
                val fileSnapshots = prepareRestoreDirectorySnapshots(
                    filesDir = context.filesDir,
                    rollbackRoot = File(restoreTempDir, "live_files_rollback"),
                    relativePaths = rollbackFilePaths,
                )

                val finalDbFile = context.getDatabasePath("rikka_hub")
                val liveDbFiles = listOf(
                    finalDbFile,
                    File(finalDbFile.path + "-wal"),
                    File(finalDbFile.path + "-shm"),
                )
                val dbRollbackDir = File(restoreTempDir, "live_db_rollback")
                if (tempDbRestored) {
                    database.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(FULL)")
                    dbRollbackDir.mkdirs()
                    liveDbFiles.filter { it.isFile }.forEach { file ->
                        file.copyTo(File(dbRollbackDir, file.name), overwrite = true)
                    }
                }

                var filesAttempted = false
                var databaseAttempted = false
                var settingsAttempted = false
                try {
                    if (restoredFilePaths.isNotEmpty()) {
                        filesAttempted = true
                        landStagedRestoreDirectories(
                            filesDir = context.filesDir,
                            stagedFilesRoot = tempFilesRoot,
                            relativePaths = restoredFilePaths,
                        )
                        Log.i(TAG, "restoreFromBackupFile: Restored file directories landed")
                    }

                    if (cleanDb != null) {
                        databaseAttempted = true
                        if (finalDbFile.exists()) check(finalDbFile.delete()) {
                            "Failed to replace current database"
                        }

                        cleanDb.copyTo(finalDbFile, overwrite = true)

                        val cleanWal = File(cleanDb.path + "-wal")
                        val cleanShm = File(cleanDb.path + "-shm")

                        if (cleanWal.exists()) {
                            cleanWal.copyTo(File(finalDbFile.path + "-wal"), overwrite = true)
                        } else {
                            File(finalDbFile.path + "-wal").delete()
                        }

                        if (cleanShm.exists()) {
                            cleanShm.copyTo(File(finalDbFile.path + "-shm"), overwrite = true)
                        } else {
                            File(finalDbFile.path + "-shm").delete()
                        }

                        Log.i(TAG, "Database restored and sanitized: $sanitizationResult")
                    }

                    if (cleanedSettings != null) {
                        settingsAttempted = true
                        settingsStore.update(cleanedSettings)
                        Log.i(
                            TAG,
                            "restoreFromBackupFile: Settings restored and sanitized (issues fixed: ${settingsCleanupResult.totalIssuesFixed})"
                        )

                        // 阅读位置属锦上添花的数据：失败只记日志，不让整个恢复失败、也不参与回滚
                        runCatching {
                            val capturedReadPositionsJson = readPositionsJson
                            val positions: Map<String, ConversationReadPosition> = when {
                                capturedReadPositionsJson != null ->
                                    json.decodeFromString(capturedReadPositionsJson)

                                else -> {
                                    // 老备份没有独立条目：从 settings.json 的旧字段里提取
                                    val legacyElement = settingsJsonHolder.json
                                        ?.let { json.parseToJsonElement(it) }
                                        ?.jsonObject
                                        ?.get("conversationReadPositions")
                                    legacyElement?.let { json.decodeFromJsonElement(it) } ?: emptyMap()
                                }
                            }
                            // 与旧行为一致：只要 settings 恢复成功就整体替换（备份为空则清空本地）
                            readPositionStore.replaceAll(positions)
                            Log.i(TAG, "restoreFromBackupFile: Restored ${positions.size} read positions")
                        }.onFailure {
                            Log.w(TAG, "restoreFromBackupFile: Failed to restore read positions", it)
                        }
                    }
                } catch (restoreError: Exception) {
                    val rollbackErrors = mutableListOf<Throwable>()

                    if (filesAttempted) {
                        runCatching { restoreDirectorySnapshots(context.filesDir, fileSnapshots) }
                            .onFailure(rollbackErrors::add)
                    }

                    if (databaseAttempted) {
                        runCatching {
                            liveDbFiles.forEach { current ->
                                if (current.exists()) check(current.delete()) {
                                    "Failed to remove partially restored database file ${current.name}"
                                }
                            }
                            dbRollbackDir.listFiles().orEmpty().forEach { backup ->
                                backup.copyTo(File(finalDbFile.parentFile, backup.name), overwrite = true)
                            }
                        }.onFailure(rollbackErrors::add)
                    }

                    if (settingsAttempted) {
                        runCatching { settingsStore.restoreSnapshot(settingsSnapshot) }
                            .onFailure(rollbackErrors::add)
                    }

                    rollbackErrors.forEach(restoreError::addSuppressed)
                    throw restoreError
                }

                Log.i(TAG, "restoreFromBackupFile: Restore completed successfully")

                // 写入「待沙盒清理」sentinel：备份不含 rootfs，恢复到本机后旧 linux/ 会残留，
                // 导致 hasRootfs 误判已就绪。这里只留个标记，真正的清理放到重启后 checkIntegrity
                // 里做——那时 DB 已是恢复的新库，能完整遍历所有工作区（含 files/ 为空、zip 里
                // 没有条目的工作区），避免按 zip 条目触发清理漏掉空工作区。
                runCatching {
                    val sentinel = File(context.filesDir, SANDBOX_RESTORE_SENTINEL)
                    sentinel.writeText(System.currentTimeMillis().toString())
                }.onFailure {
                    Log.w(TAG, "restoreFromBackupFile: 写入沙盒恢复 sentinel 失败: ${it.message}")
                }

                // Combine cleanup results
                val totalCleanupResult = settingsCleanupResult.copy(
                    unsupportedZipEntriesBytes = unsupportedZipEntriesBytes
                )
                
                Log.i(TAG, "restoreFromBackupFile: Cleanup summary - skipped ${unsupportedZipEntriesBytes} bytes, fixed ${totalCleanupResult.totalIssuesFixed} issues")
                
                RestoreResult(
                    sanitization = sanitizationResult,
                    settingsCleanup = totalCleanupResult
                )
            } finally {
                // Cleanup temp dir
                restoreTempDir.deleteRecursively()
            }
        }

}

private fun addFileToZip(zipOut: ZipOutputStream, file: File, entryName: String) {
    FileInputStream(file).use { fis ->
        val zipEntry = ZipEntry(entryName)
        zipOut.putNextEntry(zipEntry)
        fis.copyTo(zipOut)
        zipOut.closeEntry()
        Log.d(TAG, "addFileToZip: Added $entryName (${file.length()} bytes) to zip")
    }
}

internal fun addDirectoryToZip(
    zipOut: ZipOutputStream,
    dir: File,
    entryPrefix: String,
) {
    if (!dir.exists() || !dir.isDirectory) return
    val prefix = entryPrefix.trim('/')
    if (prefix.isBlank()) return

    dir.walkTopDown()
        .filter { it.isFile }
        .forEach { file ->
            val relPath = runCatching { file.relativeTo(dir).path.replace('\\', '/') }.getOrNull()
                ?: return@forEach
            if (relPath.isBlank()) return@forEach
            runCatching {
                addFileToZip(zipOut, file, "$prefix/$relPath")
            }.onFailure { err ->
                Log.w(TAG, "addDirectoryToZip: Skip $prefix/$relPath: ${err.message}")
            }
        }
}

private fun filesDirBackupZipPrefix(relativePath: String): String {
    return "${relativePath.trim('/')}/"
}

/**
 * 白名单方式备份沙盒工作区：遍历 [sandboxRoot] 下每个工作区子目录（按 id 命名），
 * 只打包其 `files/` 目录，条目前缀为 `sandbox_workspaces/<id>/files`。其余根级目录
 * （如 linux/ rootfs、tmp/）不进包。不校验 id 格式；`files/` 不存在的工作区跳过、不打条目。
 */
internal fun addSandboxWorkspacesToZip(
    zipOut: ZipOutputStream,
    sandboxRoot: File,
) {
    if (!sandboxRoot.exists() || !sandboxRoot.isDirectory) return
    val workspaceDirs = sandboxRoot.listFiles { file -> file.isDirectory && !file.isHidden }
    if (workspaceDirs == null) {
        Log.w(TAG, "addSandboxWorkspacesToZip: failed to list $sandboxRoot, sandbox backup skipped")
        return
    }
    workspaceDirs.sortedBy { it.name }.forEach { workspaceDir ->
        val filesDir = File(workspaceDir, "files")
        if (!filesDir.exists() || !filesDir.isDirectory) return@forEach
        val prefix = "sandbox_workspaces/${workspaceDir.name}/files"
        addDirectoryToZip(zipOut, filesDir, prefix)
    }
}

private fun addVirtualFileToZip(zipOut: ZipOutputStream, name: String, content: String) {
    val zipEntry = ZipEntry(name)
    zipOut.putNextEntry(zipEntry)
    zipOut.write(content.toByteArray())
    zipOut.closeEntry()
    Log.i(TAG, "addVirtualFileToZip: $name （${content.length} bytes）")
}

private fun WebDavConfig.requireClient(): OkHttpClient {
    val authHandler = BasicDigestAuthHandler(
        domain = null,
        username = this.username,
        password = this.password.toCharArray()
    )
    val okHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .authenticator(authHandler)
        .addNetworkInterceptor(authHandler)
        .writeTimeout(5, TimeUnit.MINUTES)
        .build()
    return okHttpClient
}

private fun WebDavConfig.requireCollection(path: String? = null): DavCollection {
    val location = buildString {
        append(this@requireCollection.url.trimEnd('/'))
        append("/")
        if (this@requireCollection.path.isNotBlank()) {
            append(this@requireCollection.path.trim('/'))
            append("/")
        }
        if (path != null) {
            append(path.trim('/'))
        }
    }.toHttpUrl()
    val davCollection = DavCollection(
        httpClient = this.requireClient(),
        location = location,
    )
    return davCollection
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

private fun File.isBackupZipTempFile(): Boolean {
    return name.endsWith(BACKUP_FILE_SUFFIX) && BACKUP_FILE_PREFIXES.any { name.startsWith(it) }
}

private suspend fun DavCollection.ensureCollectionExists() = withContext(Dispatchers.IO) {
    try {
        propfind(depth = 0) { response, relation ->
            Log.i(TAG, "ensureCollectionExists: $response $relation")
        }
    } catch (e: NotFoundException) {
        e.printStackTrace()
        Log.i(TAG, "ensureCollectionExists: ${this@ensureCollectionExists.location}")
        mkCol(null) { res ->
            Log.i(TAG, "ensureCollectionExists: $res")
        }
    }
}

data class WebDavBackupItem(
    val href: String,
    val displayName: String,
    val size: Long,
    val lastModified: Instant,
)
