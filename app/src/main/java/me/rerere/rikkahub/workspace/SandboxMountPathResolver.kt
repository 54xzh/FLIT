package me.rerere.rikkahub.workspace

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File

private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

data class SandboxMountSource(
    val treeUri: String,
    val sourcePath: String,
    val displayName: String,
)

/** 把系统文件夹选择器返回的本地存储目录解析成 PRoot 可绑定的真实路径。 */
class SandboxMountPathResolver(
    private val context: Context,
) {
    fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

    fun resolve(uri: Uri): SandboxMountSource {
        require(hasAllFilesAccess()) { "All files access is required" }
        val source = expectedSourcePath(uri)
        require(source.isDirectory) { "The selected folder is unavailable" }
        require(source.canRead() && source.canWrite()) { "The selected folder is not writable" }
        val displayName = DocumentFile.fromTreeUri(context, uri)?.name
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: source.name.ifBlank { DocumentsContract.getTreeDocumentId(uri) }
        return SandboxMountSource(
            treeUri = uri.toString(),
            sourcePath = source.absolutePath,
            displayName = displayName,
        )
    }

    fun hasPersistedReadWritePermission(treeUri: String): Boolean {
        val target = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return false
        return context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == target && permission.isReadPermission && permission.isWritePermission
        }
    }

    fun sourceMarkerMatches(treeUri: String, sourcePath: String): Boolean = runCatching {
        expectedSourcePath(Uri.parse(treeUri)) == File(sourcePath).canonicalFile
    }.getOrDefault(false)

    private fun expectedSourcePath(uri: Uri): File {
        require(DocumentsContract.isTreeUri(uri)) { "The selected location is not a folder" }
        require(uri.authority == EXTERNAL_STORAGE_AUTHORITY) { "Please select a folder on this device" }
        val documentId = DocumentsContract.getTreeDocumentId(uri)
        val volumeId = documentId.substringBefore(':', "")
        val roots = volumeRoots().toMutableMap()
        if (volumeId.isNotBlank() && roots.keys.none { it.equals(volumeId, ignoreCase = true) }) {
            roots[volumeId] = File("/storage", volumeId)
        }
        return resolveExternalStorageDocumentPath(documentId, roots).canonicalFile
    }

    private fun volumeRoots(): Map<String, File> {
        val storageManager = context.getSystemService(StorageManager::class.java)
        return buildMap {
            storageManager.storageVolumes.forEach { volume ->
                val root = volume.directory ?: return@forEach
                if (volume.isPrimary) put("primary", root)
                volume.uuid?.let { put(it, root) }
            }
            putIfAbsent("primary", Environment.getExternalStorageDirectory())
        }
    }
}

internal fun resolveExternalStorageDocumentPath(
    documentId: String,
    volumeRoots: Map<String, File>,
): File {
    val separator = documentId.indexOf(':')
    require(separator >= 0) { "The selected folder path is invalid" }
    val volumeId = documentId.substring(0, separator)
    require(volumeId.matches(Regex("[A-Za-z0-9._-]+")) && volumeId != "." && volumeId != "..") {
        "The selected storage volume is invalid"
    }
    val relativePath = documentId.substring(separator + 1).replace('\\', '/').trim('/')
    val segments = relativePath.split('/').filter { it.isNotEmpty() }
    require(segments.none { it == "." || it == ".." || it.contains('\u0000') }) {
        "The selected folder path is invalid"
    }
    val volumeRoot = volumeRoots.entries.firstOrNull { it.key.equals(volumeId, ignoreCase = true) }?.value
        ?: error("The selected storage volume is unavailable")
    val canonicalRoot = volumeRoot.canonicalFile
    val target = segments.fold(canonicalRoot) { parent, segment -> File(parent, segment) }.canonicalFile
    require(target.path == canonicalRoot.path || target.path.startsWith(canonicalRoot.path + File.separator)) {
        "The selected folder path is invalid"
    }
    return target
}
