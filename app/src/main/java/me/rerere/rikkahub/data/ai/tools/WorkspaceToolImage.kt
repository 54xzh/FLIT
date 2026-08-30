package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.ToolResultImage
import me.rerere.rikkahub.utils.JsonInstant

/** 工具执行结果中专门承载图片附件的保留字段；进入会话前由 GenerationHandler 提取。 */
const val TOOL_RESULT_IMAGES_KEY = "_tool_result_images"

const val WORKSPACE_TOOL_IMAGE_MAX_BYTES = 8L * 1024L * 1024L

data class WorkspaceImageType(
    val mimeType: String,
    val extension: String,
)

/** 仅根据文件头判断，避免把任意 `.png` 文本文件当成图片发送给模型。 */
fun detectWorkspaceImageType(bytes: ByteArray): WorkspaceImageType? = when {
    bytes.size >= 3 &&
        bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() ->
        WorkspaceImageType("image/jpeg", "jpg")

    bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
    ) -> WorkspaceImageType("image/png", "png")

    bytes.size >= 6 && (
        bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII) == "GIF87a" ||
            bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII) == "GIF89a"
        ) -> WorkspaceImageType("image/gif", "gif")

    bytes.size >= 12 &&
        bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
        bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP" ->
        WorkspaceImageType("image/webp", "webp")

    else -> null
}

fun isWorkspaceImageFileName(name: String): Boolean = name
    .substringAfterLast('.', missingDelimiterValue = "")
    .lowercase()
    .let { it in setOf("jpg", "jpeg", "png", "gif", "webp") }

/** 把附件从给模型的 JSON 内容中分离，避免 base64 落库。 */
fun JsonObject.withToolResultImages(images: List<ToolResultImage>): JsonObject = buildJsonObject {
    this@withToolResultImages.forEach { (key, value) -> put(key, value) }
    put(TOOL_RESULT_IMAGES_KEY, JsonArray(images.map { JsonInstant.encodeToJsonElement(ToolResultImage.serializer(), it) }))
}
