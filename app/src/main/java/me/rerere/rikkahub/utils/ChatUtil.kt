package me.rerere.rikkahub.utils

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.net.toFile
import androidx.core.net.toUri
import androidx.navigation.NavHostController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.Screen
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.Uuid

private const val TAG = "ChatUtil"
private val MANAGED_CHAT_DIR_NAMES = setOf("upload", "avatars", "images", "custom_icons", "chat_uploads")

internal fun isManagedChatFile(filesDir: File, file: File): Boolean {
    val canonicalFilesDir = runCatching { filesDir.canonicalFile }.getOrNull() ?: return false
    val canonicalTarget = runCatching { file.canonicalFile }.getOrNull() ?: return false
    return MANAGED_CHAT_DIR_NAMES.any { dirName ->
        val managedDir = runCatching { canonicalFilesDir.resolve(dirName).canonicalFile }.getOrNull() ?: return@any false
        canonicalTarget.isStrictChildOf(managedDir)
    }
}

private fun File.isStrictChildOf(parent: File): Boolean {
    val parentPath = parent.path.trimEnd(File.separatorChar)
    val selfPath = path
    return selfPath.startsWith("$parentPath${File.separator}")
}

private fun Uri.toManagedChatFileOrNull(filesDir: File): File? {
    if (scheme != "file") return null
    val localFile = runCatching { toFile() }.getOrNull() ?: return null
    return localFile.takeIf { isManagedChatFile(filesDir, it) }
}

fun navigateToChatPage(
    navController: NavHostController,
    chatId: Uuid = Uuid.random(),
    initText: String? = null,
    initFiles: List<Uri> = emptyList(),
    searchQuery: String? = null,
    autoSend: Boolean = false,
) {
    Log.i(TAG, "navigateToChatPage: navigate to $chatId")
    navController.navigate(
        route = Screen.Chat(
            id = chatId.toString(),
            text = initText,
            files = initFiles.map { it.toString() },
            searchQuery = searchQuery,
            autoSend = autoSend,
        ),
    ) {
        popUpTo(0) {
            inclusive = true
        }
        launchSingleTop = true
    }
}

fun Context.copyMessageToClipboard(message: UIMessage) {
    this.writeClipboardText(message.toText())
}

@OptIn(ExperimentalEncodingApi::class)
suspend fun Context.saveMessageImage(image: String) = withContext(Dispatchers.IO) {
    when {
        image.startsWith("data:image") -> {
            val byteArray = Base64.decode(image.substringAfter("base64,").normalizeBase64Payload().toByteArray())
            val bitmap = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
            exportImage(this@saveMessageImage.getActivity()!!, bitmap)
        }

        image.startsWith("file:") -> {
            val file = image.toUri().toFile()
            exportImageFile(this@saveMessageImage.getActivity()!!, file)
        }

        image.startsWith("http") -> {
            kotlin.runCatching { // Use runCatching to handle potential network exceptions
                val url = java.net.URL(image)
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.connect()

                if (connection.responseCode == java.net.HttpURLConnection.HTTP_OK) {
                    val bitmap = BitmapFactory.decodeStream(connection.inputStream)
                    exportImage(this@saveMessageImage.getActivity()!!, bitmap)
                } else {
                    Log.e(
                        TAG,
                        "saveMessageImage: Failed to download image from $image, response code: ${connection.responseCode}"
                    )
                    null // Return null on failure
                }
            }.getOrNull() // Return null if any exception occurs during download
        }

        else -> error("Invalid image format")
    }
}

fun Context.createChatFilesByContents(uris: List<Uri>, desiredNames: List<String?>? = null): List<Uri> {
    val newUris = mutableListOf<Uri>()
    val dir = this.filesDir.resolve("upload")
    if (!dir.exists()) {
        dir.mkdirs()
    }
    uris.forEachIndexed { index, uri ->
        // desiredNames 提供合法文件名时按原名落盘（净化非法字符并去重），否则用随机 UUID
        val requestedName = desiredNames?.getOrNull(index)
            ?.takeIf { it.isNotBlank() }
            ?.let { sanitizeChatUploadFileName(it) }
        val fileName = requestedName?.let { dedupeUploadFileName(dir, it) } ?: Uuid.random().toString()
        val file = dir.resolve(fileName)
        if (!file.exists()) {
            file.createNewFile()
        }
        val newUri = file.toUri()
        runCatching {
            this.contentResolver.openInputStream(uri)?.use { inputStream ->
                file.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            newUris.add(newUri)
        }.onFailure {
            it.printStackTrace()
            Log.e(TAG, "createChatFilesByContents: Failed to save image from $uri", it)
        }
    }
    return newUris
}

fun Context.createChatFilesByByteArrays(byteArrays: List<ByteArray>): List<Uri> {
    val newUris = mutableListOf<Uri>()
    val dir = this.filesDir.resolve("upload")
    if (!dir.exists()) {
        dir.mkdirs()
    }
    byteArrays.forEach { byteArray ->
        val fileName = Uuid.random()
        val file = dir.resolve("$fileName")
        if (!file.exists()) {
            file.createNewFile()
        }
        val newUri = file.toUri()
        file.outputStream().use { outputStream ->
            outputStream.write(byteArray)
        }
        newUris.add(newUri)
    }
    return newUris
}

/**
 * 单个会话的附件上传结果：本地 URI 与最终落盘文件名。
 *
 * `fileName` 是会话目录内去重后的真实文件名（同名自动追加 ` (1)`、` (2)`…序号），
 * 与沙盒内 `/upload/<fileName>` 一一对应，UI 芯片直接展示该名称。
 */
data class ChatFileUploadResult(val uri: Uri, val fileName: String)

/** 原始文件名中不适合作为落盘文件名的字符（路径分隔符、控制字符等）替换为下划线。 */
fun sanitizeChatUploadFileName(rawName: String): String {
    val cleaned = rawName
        .replace('/', '_')
        .replace('\\', '_')
        .replace(':', '_')
        .replace(Regex("[\\u0000-\\u001f]"), "")
        .trim()
        .take(200)
    return cleaned.ifBlank { "file" }
}


/**
 * 会话目录内按原名去重：已存在同名文件时在扩展名前追加序号（`report.pdf` → `report (1).pdf`），
 * 无扩展名文件直接追加（`data` → `data (1)`）；隐藏文件（`.hidden`）整体当作主名处理。
 */
internal fun dedupeUploadFileName(dir: File, desiredName: String): String {
    if (!File(dir, desiredName).exists()) return desiredName
    val dotIndex = desiredName.lastIndexOf('.')
    val baseName = if (dotIndex <= 0) desiredName else desiredName.substring(0, dotIndex)
    val extension = if (dotIndex <= 0) "" else desiredName.substring(dotIndex + 1)
    var index = 1
    while (true) {
        val candidate = if (extension.isEmpty()) "$baseName ($index)" else "$baseName ($index).$extension"
        if (!File(dir, candidate).exists()) return candidate
        index++
    }
}

/**
 * 把选中的文件复制进会话专属上传目录 `filesDir/chat_uploads/<conversationId>/`，
 * 保留原始文件名并去重。会话目录后续会挂载进沙盒的 `/upload`。
 */
fun Context.createChatUploadFiles(conversationId: String, uris: List<Uri>): List<ChatFileUploadResult> =
    uris.mapNotNull { uri -> createChatUploadFile(conversationId, uri) }

/**
 * 复制单个文件进会话上传目录。[desiredName] 优先作为落盘文件名
 * （file:// 来源查不到 SAF 元数据时用它保住原名，如消息分叉场景）。
 * 复制失败返回 null 并清掉半截文件。
 */
fun Context.createChatUploadFile(
    conversationId: String,
    sourceUri: Uri,
    desiredName: String? = null,
): ChatFileUploadResult? {
    // 会话 id 必须是合法 UUID，防止借 `../` 等做路径穿越
    val safeId = runCatching { Uuid.parse(conversationId) }.getOrNull()?.toString() ?: return null
    val dir = File(filesDir.resolve("chat_uploads"), safeId)
    if (!dir.exists()) {
        dir.mkdirs()
    }
    val fileName = dedupeUploadFileName(
        dir,
        sanitizeChatUploadFileName(desiredName?.takeIf { it.isNotBlank() } ?: getFileNameFromUri(sourceUri) ?: "file"),
    )
    val file = dir.resolve(fileName)
    return runCatching {
        contentResolver.openInputStream(sourceUri)?.use { inputStream ->
            file.outputStream().use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        } ?: error("Unable to open input stream for $sourceUri")
        ChatFileUploadResult(file.toUri(), fileName)
    }.onFailure {
        it.printStackTrace()
        Log.e(TAG, "createChatUploadFile: Failed to save file from $sourceUri", it)
        runCatching { if (file.exists()) file.delete() }
    }.getOrNull()
}

/** 删除某个会话的全部上传文件（删除会话时调用）。 */
fun Context.deleteChatUploadDir(conversationId: String) {
    val safeId = runCatching { Uuid.parse(conversationId) }.getOrNull()?.toString() ?: return
    val dir = File(filesDir.resolve("chat_uploads"), safeId)
    if (dir.exists()) {
        dir.deleteRecursively()
    }
}

/** 统计所有会话上传文件的数量与总大小（存储管理展示用）。 */
fun Context.countChatUploadFiles(): Pair<Int, Long> {
    val root = filesDir.resolve("chat_uploads")
    if (!root.exists()) return Pair(0, 0)
    var count = 0
    var size = 0L
    root.listFiles()?.forEach { conversationDir ->
        if (conversationDir.isDirectory) {
            conversationDir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    count++
                    size += file.length()
                }
            }
        }
    }
    return Pair(count, size)
}

fun Context.getFileNameFromUri(uri: Uri): String? {
    var fileName: String? = null
    val projection = arrayOf(
        OpenableColumns.DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME // 优先尝试 DocumentProvider 标准列
    )
    contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        // 移动到第一行结果
        if (cursor.moveToFirst()) {
            // 尝试获取 DocumentsContract.Document.COLUMN_DISPLAY_NAME 的索引
            val documentDisplayNameIndex =
                cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (documentDisplayNameIndex != -1) {
                fileName = cursor.getString(documentDisplayNameIndex)
            } else {
                // 如果 DocumentProvider 标准列不存在，尝试 OpenableColumns.DISPLAY_NAME
                val openableDisplayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (openableDisplayNameIndex != -1) {
                    fileName = cursor.getString(openableDisplayNameIndex)
                }
            }
        }
    }
    // 如果查询失败或没有获取到名称，fileName 会保持 null
    return fileName
}

fun Context.getFileMimeType(uri: Uri): String? {
    return when (uri.scheme) {
        "content" -> contentResolver.getType(uri)
        else -> null
    }
}

@OptIn(ExperimentalEncodingApi::class)
suspend fun Context.convertBase64ImagePartToLocalFile(message: UIMessage): UIMessage =
    withContext(Dispatchers.IO) {
        message.copy(
            parts = message.parts.map { part ->
                when (part) {
                    is UIMessagePart.Image -> {
                        if (part.url.startsWith("data:image")) {
                            runCatching {
                                val sourceByteArray = Base64.decode(
                                    part.url.substringAfter("base64,").normalizeBase64Payload().toByteArray()
                                )
                                val urls = createChatFilesByByteArrays(listOf(sourceByteArray))
                                Log.i(
                                    TAG,
                                    "convertBase64ImagePartToLocalFile: convert base64 img to ${urls.joinToString(", ")}"
                                )
                                part.copy(
                                    url = urls.first().toString(),
                                )
                            }.getOrElse { throwable ->
                                Log.w(
                                    TAG,
                                    "convertBase64ImagePartToLocalFile: failed to convert base64 image, keep original",
                                    throwable
                                )
                                part
                            }
                        } else {
                            part
                        }
                    }

                    else -> part
                }
            }
        )
    }

fun Context.deleteChatFiles(uris: List<Uri>) {
    val appFilesDir = filesDir
    uris.forEach { uri ->
        val file = uri.toManagedChatFileOrNull(appFilesDir) ?: run {
            Log.w(TAG, "deleteChatFiles: skip unmanaged file uri=$uri")
            return@forEach
        }
        if (!file.exists() || !file.isFile) {
            return@forEach
        }
        if (!file.delete()) {
            Log.w(TAG, "deleteChatFiles: failed to delete ${file.absolutePath}")
        }
    }
}

fun Context.deleteAllChatFiles() {
    val dir = this.filesDir.resolve("upload")
    if (dir.exists()) {
        dir.deleteRecursively()
    }
    // 会话上传目录（chat_uploads）一并清理
    val uploadsRoot = this.filesDir.resolve("chat_uploads")
    if (uploadsRoot.exists()) {
        uploadsRoot.deleteRecursively()
    }
}

suspend fun Context.countChatFiles(): Pair<Int, Long> = withContext(Dispatchers.IO) {
    val dir = filesDir.resolve("upload")
    if (!dir.exists()) {
        return@withContext Pair(0, 0)
    }
    val files = dir.listFiles() ?: return@withContext Pair(0, 0)
    val count = files.size
    val size = files.sumOf { it.length() }
    Pair(count, size)
}

fun Context.getImagesDir(): File {
    val dir = this.filesDir.resolve("images")
    if (!dir.exists()) {
        dir.mkdirs()
    }
    return dir
}

fun Context.createImageFileFromBase64(base64Data: String, filePath: String): File {
    val data = if (base64Data.startsWith("data:image")) {
        base64Data.substringAfter("base64,")
    } else {
        base64Data
    }

    val byteArray = Base64.decode(data.normalizeBase64Payload().toByteArray())
    val file = File(filePath)
    file.parentFile?.mkdirs()
    file.writeBytes(byteArray)
    return file
}

private fun String.normalizeBase64Payload(): String {
    val compact = this.filterNot { it.isWhitespace() }
    val firstPadIndex = compact.indexOf('=')
    if (firstPadIndex < 0) {
        return compact
    }
    var endIndex = firstPadIndex
    while (endIndex < compact.length && compact[endIndex] == '=') {
        endIndex++
    }
    return compact.substring(0, endIndex)
}

fun Context.listImageFiles(): List<File> {
    val imagesDir = getImagesDir()
    return imagesDir.listFiles()
        ?.filter { it.isFile && it.extension.lowercase() in listOf("png", "jpg", "jpeg", "webp") }?.toList()
        ?: emptyList()
}
