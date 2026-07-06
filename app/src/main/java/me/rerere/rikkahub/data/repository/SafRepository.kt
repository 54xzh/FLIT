package me.rerere.rikkahub.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SAF（Android 存储访问框架）文件操作仓库。
 *
 * 把原本散落在 [me.rerere.rikkahub.service.ChatService] 里的 DocumentFile
 * 操作抽出来复用，供 AI 工作区工具和工作区文件管理器共用。
 *
 * 所有方法都跑在 [Dispatchers.IO]，调用方无需再切换线程。
 * relPath 均为相对 [root] 的路径，"" 表示根目录本身。
 */
class SafRepository(
    private val context: Context,
) {

    /**
     * 列出 [relPath] 目录下的子条目。
     *
     * 排序：目录在前，名字按大小写不敏感升序；上限 [MAX_LIST_ENTRIES] 条。
     * 解析失败或不可访问时返回空列表（不抛异常，调用方可据此显示空态）。
     */
    suspend fun listChildren(root: DocumentFile, relPath: String): List<WorkspaceFileEntry> =
        withContext(Dispatchers.IO) {
            val dir = resolve(root, relPath) ?: return@withContext emptyList()
            if (!dir.isDirectory) return@withContext emptyList()
            dir.listFiles()
                .filterNotNull()
                .map { it.toEntry(relPath) }
                .sortedWith(
                    compareBy<WorkspaceFileEntry> { !it.isDirectory }
                        .thenBy { it.name.lowercase() },
                )
                .take(MAX_LIST_ENTRIES)
        }

    /**
     * 沿 [relPath] 逐段 findFile 解析。
     * - "" 返回 [root]
     * - 中间某段是文件（而非目录）则返回 null，避免误穿
     * - 任一段不存在返回 null
     */
    suspend fun resolve(root: DocumentFile, relPath: String): DocumentFile? =
        withContext(Dispatchers.IO) {
            val segments = splitRelPath(relPath)
            if (segments.isEmpty()) return@withContext root
            var current = root
            segments.forEachIndexed { index, seg ->
                val next = current.findFile(seg) ?: return@withContext null
                if (index < segments.lastIndex && !next.isDirectory) return@withContext null
                current = next
            }
            current
        }

    /**
     * 沿 [relPath] 逐段解析目录，缺失则创建。
     * - 已存在但不是目录返回 null（路径冲突）
     * - createDirectory 失败返回 null
     */
    suspend fun resolveOrCreateDir(root: DocumentFile, relPath: String): DocumentFile? =
        withContext(Dispatchers.IO) {
            val segments = splitRelPath(relPath)
            var current = root
            for (seg in segments) {
                val existing = current.findFile(seg)
                current = when {
                    existing != null && existing.isDirectory -> existing
                    existing != null -> return@withContext null
                    else -> current.createDirectory(seg) ?: return@withContext null
                }
            }
            current
        }

    /**
     * 把 [inputStream] 写入 [destDirRelPath] 目录，文件名 [displayName]。
     *
     * 同名冲突时在扩展名前插入 " (1)"、" (2)"... 直到空闲，绝不覆盖已有文件。
     * 失败返回 null。
     */
    suspend fun importFromUri(
        root: DocumentFile,
        destDirRelPath: String,
        inputStream: InputStream,
        displayName: String,
    ): WorkspaceFileEntry? = withContext(Dispatchers.IO) {
        val destDir = resolveOrCreateDir(root, destDirRelPath) ?: return@withContext null
        val targetName = resolveConflictName(destDir, displayName)
        val target = destDir.createFile(guessMimeFromName(targetName), targetName) ?: return@withContext null
        runCatching {
            context.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
                inputStream.copyTo(out)
            } ?: return@withContext null
        }.onFailure {
            runCatching { target.delete() }
            return@withContext null
        }
        target.toEntry(destDirRelPath)
    }

    /**
     * 删除 [relPath] 指向的条目；目录递归删除。失败返回 false。
     */
    suspend fun delete(root: DocumentFile, relPath: String): Boolean =
        withContext(Dispatchers.IO) {
            val target = resolve(root, relPath) ?: return@withContext false
            deleteDocument(target)
        }

    /**
     * 递归删除 [target]。供已有解析好 DocumentFile 的调用方直接使用（避免重新解析）。
     */
    suspend fun deleteDocument(target: DocumentFile): Boolean = withContext(Dispatchers.IO) {
        if (target.isDirectory) {
            target.listFiles().forEach { child ->
                deleteDocument(child)
            }
        }
        runCatching { target.delete() }.getOrDefault(false)
    }

    /**
     * 纯字符串工具：把相对路径拆成非空段。"" / "/" 都返回空列表。
     */
    fun splitRelPath(relPath: String): List<String> =
        relPath.split('/').filter { it.isNotBlank() }

    private fun DocumentFile.toEntry(parentRelPath: String): WorkspaceFileEntry {
        val name = name.orEmpty().ifBlank { "/" }
        val path = if (parentRelPath.isBlank()) name else "$parentRelPath/$name"
        return WorkspaceFileEntry(
            path = path,
            name = name,
            isDirectory = isDirectory,
            sizeBytes = if (isDirectory) 0L else length(),
            updatedAt = lastModified(),
        )
    }

    private fun resolveConflictName(destDir: DocumentFile, displayName: String): String {
        if (destDir.findFile(displayName) == null) return displayName
        val dot = displayName.lastIndexOf('.')
        val (base, ext) = if (dot > 0 && dot < displayName.length - 1) {
            displayName.substring(0, dot) to displayName.substring(dot)
        } else {
            displayName to ""
        }
        var n = 1
        while (true) {
            val candidate = "$base ($n)$ext"
            if (destDir.findFile(candidate) == null) return candidate
            n++
        }
    }

    private fun guessMimeFromName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isBlank()) return "*/*"
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    companion object {
        /** 单次列目录的最大条目数，避免超大目录卡死 UI。 */
        private const val MAX_LIST_ENTRIES = 500
    }
}

/**
 * 工作区文件条目。path 相对工作区根，"" 表示根本身。
 */
data class WorkspaceFileEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val updatedAt: Long,
)