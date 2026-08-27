package me.rerere.rikkahub.utils

import java.io.InputStream

/** 供工作区和应用私有文件共用的受限文本预览读取结果。 */
internal sealed interface TextFilePreviewResult {
    data class Success(
        val content: String,
        val truncated: Boolean,
        val encodingSuspect: Boolean,
    ) : TextFilePreviewResult

    data object Binary : TextFilePreviewResult
    data object Unavailable : TextFilePreviewResult
}

/**
 * 流式读取 UTF-8 文本预览：限制最大字符数，并通过 NUL 字符过滤二进制文件。
 * 调用方负责在线程和生命周期上关闭 [InputStream]。
 */
internal fun InputStream.readTextFilePreview(
    maxChars: Int = 200_000,
    binarySniffBytes: Int = 8_192,
): TextFilePreviewResult = try {
    val limit = maxChars.coerceAtLeast(1)
    val reader = buffered().bufferedReader(Charsets.UTF_8)
    val buffer = CharArray(8_192)
    val sniffSize = minOf(buffer.size, binarySniffBytes.coerceAtLeast(1), limit)
    val sniffRead = reader.read(buffer, 0, sniffSize)
    if (sniffRead <= 0) {
        TextFilePreviewResult.Success(content = "", truncated = false, encodingSuspect = false)
    } else {
        val firstChunk = String(buffer, 0, sniffRead)
        if (firstChunk.indexOf('\u0000') >= 0) {
            TextFilePreviewResult.Binary
        } else {
            val content = StringBuilder(firstChunk)
            var totalChars = sniffRead
            while (totalChars < limit) {
                val read = reader.read(buffer, 0, minOf(buffer.size, limit - totalChars))
                if (read <= 0) break
                content.append(buffer, 0, read)
                totalChars += read
            }
            val truncated = totalChars >= limit && reader.read() >= 0
            val text = content.toString()
            TextFilePreviewResult.Success(
                content = text,
                truncated = truncated,
                encodingSuspect = text.contains('\uFFFD'),
            )
        }
    }
} catch (_: Exception) {
    TextFilePreviewResult.Unavailable
}
