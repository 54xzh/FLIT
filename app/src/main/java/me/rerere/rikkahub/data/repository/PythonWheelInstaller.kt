/*
 * 负责从 SAF Uri 导入多个 Python Wheel（.whl），安全解压并写入本地清单。
 */

package me.rerere.rikkahub.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.model.PythonWheel
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

class PythonWheelInstaller(
    private val context: Context,
    private val repository: PythonWheelRepository = PythonWheelRepository(context),
) {
    data class BatchResult(
        val success: List<PythonWheel> = emptyList(),
        val duplicated: List<PythonWheel> = emptyList(),
        val failed: List<Failure> = emptyList(),
    )

    data class Failure(
        val displayName: String?,
        val reason: String,
    )

    suspend fun importFromUris(uris: List<Uri>): BatchResult = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext BatchResult()

        val existing = repository.readManifest().wheels
        val existingBySha = existing.associateBy { it.sha256 }
        val importedSha = HashSet<String>()

        val success = mutableListOf<PythonWheel>()
        val duplicated = mutableListOf<PythonWheel>()
        val failed = mutableListOf<Failure>()

        for (uri in uris) {
            val displayName = queryDisplayName(uri)
            val fileSizeBytes = querySize(uri)
            val nameForCheck = (displayName ?: uri.lastPathSegment).orEmpty()
            if (!nameForCheck.lowercase().endsWith(".whl")) {
                failed += Failure(displayName = displayName, reason = "请选择 .whl 文件")
                continue
            }

            val result = runCatching {
                importSingleWheel(uri = uri, displayName = displayName, fileSizeBytes = fileSizeBytes)
            }.getOrElse { e ->
                failed += Failure(displayName = displayName, reason = e.message ?: "未知错误")
                null
            } ?: continue

            val duplicate = existingBySha[result.sha256] ?: success.firstOrNull { it.sha256 == result.sha256 }
            if (duplicate != null || importedSha.contains(result.sha256)) {
                runCatching { repository.wheelRootDir(result.id).deleteRecursively() }
                duplicated += (duplicate ?: result)
                continue
            }

            importedSha += result.sha256
            repository.updateManifest { manifest ->
                manifest.copy(wheels = manifest.wheels + result)
            }
            success += result
        }

        BatchResult(success = success, duplicated = duplicated, failed = failed)
    }

    private fun importSingleWheel(
        uri: Uri,
        displayName: String?,
        fileSizeBytes: Long?,
    ): PythonWheel {
        val id = java.util.UUID.randomUUID().toString()

        val rootDir = repository.wheelRootDir(id)
        val wheelFile = repository.wheelFile(id)
        val unpackedDir = repository.wheelUnpackedDir(id)
        rootDir.mkdirs()
        unpackedDir.mkdirs()

        try {
            val sha256 = copyToPrivateFileAndSha256(uri = uri, outFile = wheelFile)
            val unzipResult = safeUnpackWheelZip(wheelFile = wheelFile, destDir = unpackedDir)
            val sysPaths = computeSysPaths(unpackedDir)
            val (pkgName, pkgVersion) = parseMetadataNameVersion(unpackedDir, sysPaths)

            return PythonWheel(
                id = id,
                displayName = displayName ?: wheelFile.name,
                packageName = pkgName,
                packageVersion = pkgVersion,
                sha256 = sha256,
                fileSizeBytes = fileSizeBytes ?: wheelFile.length(),
                installedAt = System.currentTimeMillis(),
                enabled = true,
                sysPaths = sysPaths,
                hasNativeCode = unzipResult.hasNativeCode,
            )
        } catch (e: Exception) {
            runCatching { rootDir.deleteRecursively() }
            throw e
        }
    }

    private fun copyToPrivateFileAndSha256(uri: Uri, outFile: File): String {
        outFile.parentFile?.mkdirs()

        val digest = MessageDigest.getInstance("SHA-256")
        val input = context.contentResolver.openInputStream(uri) ?: error("无法读取文件")
        input.use { stream ->
            FileOutputStream(outFile).use { out ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    total += read
                    if (total > MAX_WHEEL_BYTES) error("文件过大")
                }
            }
        }

        return digest.digest().toHexLower()
    }

    private data class UnpackResult(val hasNativeCode: Boolean)

    private fun safeUnpackWheelZip(wheelFile: File, destDir: File): UnpackResult {
        var entryCount = 0
        var totalBytes = 0L
        var hasNativeCode = false

        ZipInputStream(BufferedInputStream(FileInputStream(wheelFile))).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                entryCount++
                if (entryCount > MAX_ZIP_ENTRIES) error("压缩包条目过多")

                val entryName = entry.name
                if (entryName.endsWith(".so", ignoreCase = true) || entryName.endsWith(".pyd", ignoreCase = true)) {
                    hasNativeCode = true
                }

                val outFile = safeResolve(destDir, entryName) ?: error("非法压缩包路径")
                if (entry.isDirectory) {
                    outFile.mkdirs()
                    zis.closeEntry()
                    continue
                }
                outFile.parentFile?.mkdirs()

                FileOutputStream(outFile).use { out ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var entryBytes = 0L
                    while (true) {
                        val read = zis.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        entryBytes += read
                        totalBytes += read
                        if (entryBytes > MAX_ENTRY_BYTES) error("压缩包条目过大")
                        if (totalBytes > MAX_TOTAL_UNPACK_BYTES) error("解压后体积过大")
                    }
                }

                zis.closeEntry()
            }
        }

        return UnpackResult(hasNativeCode = hasNativeCode)
    }

    private fun computeSysPaths(unpackedDir: File): List<String> {
        val out = LinkedHashSet<String>()

        val dataDirs = unpackedDir.listFiles()
            ?.filter { it.isDirectory && it.name.endsWith(".data") }
            .orEmpty()

        for (dataDir in dataDirs) {
            val purelib = File(dataDir, "purelib")
            if (purelib.isDirectory) out += purelib.absolutePath
            val platlib = File(dataDir, "platlib")
            if (platlib.isDirectory) out += platlib.absolutePath
        }

        out += unpackedDir.absolutePath
        return out.toList()
    }

    private fun parseMetadataNameVersion(
        unpackedDir: File,
        sysPaths: List<String>,
    ): Pair<String?, String?> {
        val candidates = buildList<File> {
            add(unpackedDir)
            for (p in sysPaths) add(File(p))
        }.distinctBy { it.absolutePath }

        val distInfoDir = candidates
            .asSequence()
            .filter { it.isDirectory }
            .flatMap { dir -> dir.listFiles().orEmpty().asSequence() }
            .firstOrNull { it.isDirectory && it.name.endsWith(".dist-info") }

        val metadata = distInfoDir?.let { File(it, "METADATA") }?.takeIf { it.isFile } ?: return null to null

        var name: String? = null
        var version: String? = null
        runCatching {
            metadata.useLines { lines ->
                for (line in lines) {
                    if (name == null && line.startsWith("Name:")) {
                        name = line.substringAfter("Name:").trim().takeIf { it.isNotBlank() }
                    }
                    if (version == null && line.startsWith("Version:")) {
                        version = line.substringAfter("Version:").trim().takeIf { it.isNotBlank() }
                    }
                    if (name != null && version != null) break
                }
            }
        }

        return name to version
    }

    private fun safeResolve(rootDir: File, entryName: String): File? {
        val normalized = File(rootDir, entryName).toPath().normalize().toFile()
        val rootPath = rootDir.canonicalFile.toPath()
        val filePath = normalized.canonicalFile.toPath()
        if (!filePath.startsWith(rootPath)) return null
        return filePath.toFile()
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index == -1) return@use null
            if (!cursor.moveToFirst()) return@use null
            cursor.getString(index)
        }
    }.getOrNull()

    private fun querySize(uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index == -1) return@use null
            if (!cursor.moveToFirst()) return@use null
            cursor.getLong(index)
        }
    }.getOrNull()

    private fun ByteArray.toHexLower(): String = buildString(size * 2) {
        for (b in this@toHexLower) {
            append(((b.toInt() shr 4) and 0xF).toString(16))
            append((b.toInt() and 0xF).toString(16))
        }
    }

    companion object {
        private const val MAX_WHEEL_BYTES: Long = 100L * 1024L * 1024L
        private const val MAX_ZIP_ENTRIES: Int = 20_000
        private const val MAX_ENTRY_BYTES: Long = 200L * 1024L * 1024L
        private const val MAX_TOTAL_UNPACK_BYTES: Long = 300L * 1024L * 1024L
    }
}
