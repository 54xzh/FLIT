package me.rerere.rikkahub.utils

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class WorkspaceSyncLimits(
    val maxFiles: Int = 500,
    val maxTotalBytes: Long = 20L * 1024L * 1024L,
    val maxSingleFileBytes: Long = 10L * 1024L * 1024L,
)

data class WorkspaceSyncStats(
    val filesCopied: Int,
    val bytesCopied: Long,
    val skippedFiles: Int,
)

object WorkspaceSync {
    suspend fun syncExternalToInternal(
        context: Context,
        externalDir: DocumentFile,
        internalDir: File,
        limits: WorkspaceSyncLimits = WorkspaceSyncLimits(),
    ): WorkspaceSyncStats = withContext(Dispatchers.IO) {
        if (!externalDir.isDirectory) {
            return@withContext WorkspaceSyncStats(filesCopied = 0, bytesCopied = 0, skippedFiles = 0)
        }

        if (internalDir.exists()) {
            runCatching { internalDir.deleteRecursively() }
        }
        internalDir.mkdirs()

        var filesCopied = 0
        var bytesCopied = 0L
        var skipped = 0

        fun copyDir(src: DocumentFile, dest: File) {
            if (filesCopied >= limits.maxFiles || bytesCopied >= limits.maxTotalBytes) return
            if (!dest.exists()) dest.mkdirs()

            src.listFiles().forEach { child ->
                if (filesCopied >= limits.maxFiles || bytesCopied >= limits.maxTotalBytes) return
                val name = child.name ?: return@forEach
                if (child.isDirectory) {
                    copyDir(child, File(dest, name))
                } else if (child.isFile) {
                    val targetFile = File(dest, name)
                    val size = child.length()
                    if (size > 0 && size > limits.maxSingleFileBytes) {
                        skipped++
                        return@forEach
                    }
                    if (bytesCopied + size > limits.maxTotalBytes) {
                        skipped++
                        return@forEach
                    }
                    val copiedBytes = runCatching {
                        context.contentResolver.openInputStream(child.uri)?.use { input ->
                            targetFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        } ?: 0L
                    }.getOrElse {
                        skipped++
                        return@forEach
                    }
                    filesCopied++
                    bytesCopied += if (size > 0) size else copiedBytes
                }
            }
        }

        copyDir(externalDir, internalDir)
        WorkspaceSyncStats(filesCopied = filesCopied, bytesCopied = bytesCopied, skippedFiles = skipped)
    }

    suspend fun syncInternalToExternal(
        context: Context,
        internalDir: File,
        externalDir: DocumentFile,
        limits: WorkspaceSyncLimits = WorkspaceSyncLimits(),
    ): WorkspaceSyncStats = withContext(Dispatchers.IO) {
        if (!internalDir.exists() || !internalDir.isDirectory) {
            return@withContext WorkspaceSyncStats(filesCopied = 0, bytesCopied = 0, skippedFiles = 0)
        }
        if (!externalDir.isDirectory) {
            return@withContext WorkspaceSyncStats(filesCopied = 0, bytesCopied = 0, skippedFiles = 0)
        }

        var filesCopied = 0
        var bytesCopied = 0L
        var skipped = 0

        fun ensureDir(parent: DocumentFile, name: String): DocumentFile? {
            val existing = parent.findFile(name)
            if (existing != null) return if (existing.isDirectory) existing else null
            return parent.createDirectory(name)
        }

        fun ensureFile(parent: DocumentFile, name: String, mimeType: String): DocumentFile? {
            val existing = parent.findFile(name)
            if (existing != null) return if (existing.isFile) existing else null
            return parent.createFile(mimeType, name)
        }

        fun guessMimeType(name: String): String {
            val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
            if (ext.isBlank()) return "application/octet-stream"
            val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            return mime ?: "application/octet-stream"
        }

        fun copyDir(src: File, dest: DocumentFile) {
            if (filesCopied >= limits.maxFiles || bytesCopied >= limits.maxTotalBytes) return
            src.listFiles()?.forEach { child ->
                if (filesCopied >= limits.maxFiles || bytesCopied >= limits.maxTotalBytes) return
                val name = child.name
                if (child.isDirectory) {
                    val nextDest = ensureDir(dest, name) ?: run {
                        skipped++
                        return@forEach
                    }
                    copyDir(child, nextDest)
                } else if (child.isFile) {
                    val size = child.length()
                    if (size > limits.maxSingleFileBytes || bytesCopied + size > limits.maxTotalBytes) {
                        skipped++
                        return@forEach
                    }
                    val target = ensureFile(dest, name, guessMimeType(name)) ?: run {
                        skipped++
                        return@forEach
                    }
                    val ok = runCatching {
                        context.contentResolver.openOutputStream(target.uri, "wt")?.use { output ->
                            child.inputStream().use { input ->
                                input.copyTo(output)
                            }
                        } != null
                    }.getOrDefault(false)
                    if (ok) {
                        filesCopied++
                        bytesCopied += size
                    } else {
                        skipped++
                    }
                }
            }
        }

        copyDir(internalDir, externalDir)
        WorkspaceSyncStats(filesCopied = filesCopied, bytesCopied = bytesCopied, skippedFiles = skipped)
    }
}
