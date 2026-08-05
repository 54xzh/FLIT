package me.rerere.rikkahub.workspace

import android.content.Context
import java.io.File

fun sandboxBindMounts(context: Context): List<SandboxBindMount> {
    val appContext = context.applicationContext
    return listOf(
        SandboxBindMount(File(appContext.filesDir, "skills").apply { mkdirs() }, "/skills"),
        SandboxBindMount(File(appContext.filesDir, "tool_outputs").apply { mkdirs() }, "/tool_outputs"),
    )
}

/** 沙盒内暴露给用户文件的固定目录名（不含会话 id，避免向助手暴露内部标识）。 */
const val SANDBOX_UPLOAD_MOUNT_TARGET = "/upload"

/** 沙盒读文件（工作区与会话上传）的统一大小上限。 */
const val SANDBOX_MAX_READ_BYTES = 8L * 1024L * 1024L

/** 会话上传文件在主机的根目录：`filesDir/chat_uploads/<conversationId>/`。 */
fun Context.chatUploadsRoot(): File = File(applicationContext.filesDir, "chat_uploads")

/**
 * 某个会话的上传目录，按需创建。
 * 会话 id 必须是合法 UUID，防止恶意/异常输入借 `../` 等做路径穿越。
 */
fun Context.chatUploadDir(conversationId: String): File {
    val safeId = runCatching { kotlin.uuid.Uuid.parse(conversationId) }.getOrNull()
        ?: error("Invalid conversation id for chat uploads: $conversationId")
    return chatUploadsRoot().resolve(safeId.toString()).apply { mkdirs() }
}

/**
 * 聊天会话的上传目录挂载：当前会话的文件只读暴露为沙盒内 [SANDBOX_UPLOAD_MOUNT_TARGET]。
 * `conversationId` 为 null（如工作区终端、未绑定会话的场景）时不挂载。
 */
fun chatSessionBindMounts(context: Context, conversationId: String?): List<SandboxBindMount> {
    if (conversationId.isNullOrBlank()) return emptyList()
    val dir = context.chatUploadDir(conversationId)
    if (!dir.exists()) return emptyList()
    return listOf(SandboxBindMount(dir, SANDBOX_UPLOAD_MOUNT_TARGET))
}
