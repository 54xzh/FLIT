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
import me.rerere.rikkahub.data.backup.BackupRemoteResult
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.datastore.sanitize
import me.rerere.rikkahub.data.db.AppDatabase
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
private val FILES_DIR_BACKUP_PATHS = listOf(
    "upload",
    "avatars",
    "images",
    "skills",
    "python/wheels",
    "custom_fonts",
    "chat_files",
    "custom_icons",
    // 沙盒工作区：仅备份每个工作区下的 files/ 用户文件，
    // 排除 linux/（rootfs 体积大、可重装）与 tmp/（临时文件），见 addDirectoryToZip 的 skipSandboxHeavyDirs。
    "sandbox_workspaces",
)

class WebdavSync(
    private val settingsStore: SettingsStore,
    private val json: Json,
    private val context: Context,
    private val database: AppDatabase,
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
                        // 沙盒工作区：跳过每个工作区下的 linux/（rootfs）与 tmp/，只保留 files/ 用户文件，
                        // 防止备份包膨胀；恢复后 rootfs 会落到 DISABLED（未安装），由用户重装。
                        val skipSubdirs = if (relativePath == "sandbox_workspaces") setOf("linux", "tmp") else emptySet()
                        addDirectoryToZip(zipOut, folder, relativePath, skipSubdirs)
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
                                    // 恢复设置
                                    val settingsJson = zipIn.readBytes().toString(Charsets.UTF_8)
                                    Log.i(TAG, "restoreFromBackupFile: Restoring settings")
                                    try {
                                        val settings = json.decodeFromString<Settings>(settingsJson)
                                        // Sanitize settings to clean up deprecated/invalid data and fix avatar paths
                                        val (cleanedSettings, cleanupResult) = settings.sanitize(context)
                                        settingsCleanupResult = cleanupResult
                                        settingsStore.update(cleanedSettings)
                                        Log.i(
                                            TAG,
                                            "restoreFromBackupFile: Settings restored and sanitized (issues fixed: ${cleanupResult.totalIssuesFixed})"
                                        )
                                    } catch (e: Exception) {
                                        Log.e(
                                            TAG,
                                            "restoreFromBackupFile: Failed to restore settings",
                                            e
                                        )
                                        throw Exception("Failed to restore settings: ${e.message}")
                                    }
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

                                    val baseDir = File(context.filesDir, subfolder)
                                    if (!baseDir.exists()) {
                                        baseDir.mkdirs()
                                        Log.i(TAG, "restoreFromBackupFile: Created $subfolder directory")
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

                // Sanitize and Restore Database
                val tempDbFile = File(restoreTempDir, "rikka_hub")
                
                if (tempDbFile.exists()) {
                    Log.i(TAG, "Starting database sanitization...")
                    try {
                         val (cleanDb, result) = DatabaseSanitizer.sanitize(context, tempDbFile)
                         sanitizationResult = result
                         
                         // Move clean DB to final location
                         val finalDbFile = context.getDatabasePath("rikka_hub")
                         if(finalDbFile.exists()) finalDbFile.delete()
                         
                         cleanDb.copyTo(finalDbFile, overwrite = true)
                         
                         val cleanWal = File(cleanDb.path + "-wal")
                         val cleanShm = File(cleanDb.path + "-shm")
                         
                         if(cleanWal.exists()) {
                             cleanWal.copyTo(File(finalDbFile.path + "-wal"), overwrite = true)
                         } else {
                             File(finalDbFile.path + "-wal").delete()
                         }
                         
                         if(cleanShm.exists()) {
                             cleanShm.copyTo(File(finalDbFile.path + "-shm"), overwrite = true)
                         } else {
                             File(finalDbFile.path + "-shm").delete()
                         }
                         
                         Log.i(TAG, "Database restored and sanitized: $sanitizationResult")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to sanitize database", e)
                        throw Exception("Database sanitization failed: ${e.message}")
                    }
                }

                Log.i(TAG, "restoreFromBackupFile: Restore completed successfully")
                
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

private fun addDirectoryToZip(
    zipOut: ZipOutputStream,
    dir: File,
    entryPrefix: String,
    skipSubdirs: Set<String> = emptySet(),
) {
    if (!dir.exists() || !dir.isDirectory) return
    val prefix = entryPrefix.trim('/')
    if (prefix.isBlank()) return

    dir.walkTopDown()
        .onEnter { current ->
            // 跳过指定名称的直接子目录（相对 dir 的第一级目录名），整棵子树不打包。
            // 用 canonicalFile 比较避免符号链接误判。
            val relToDir = runCatching { current.relativeTo(dir) }.getOrNull()
            val firstSegment = relToDir?.path?.substringBefore(File.separatorChar)?.trimStart('/')
            current == dir || firstSegment !in skipSubdirs
        }
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
