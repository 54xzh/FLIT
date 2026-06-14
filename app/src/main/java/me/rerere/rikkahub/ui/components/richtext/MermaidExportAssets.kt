package me.rerere.rikkahub.ui.components.richtext

import android.graphics.Bitmap
import java.security.MessageDigest

data class MermaidExportAssets(
    val images: Map<String, Bitmap> = emptyMap()
)

internal fun mermaidExportKey(code: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(code.toByteArray())
    return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private val MermaidFenceRegex = Regex(
    pattern = """(?m)^[ \t]*(`{3,}|~{3,})[ \t]*mermaid[^\r\n]*\r?\n([\s\S]*?)\r?\n[ \t]*\1[ \t]*$"""
)

fun extractMermaidCodeBlocks(markdown: String): List<String> {
    return MermaidFenceRegex.findAll(markdown)
        .map { match -> match.groupValues[2].trimIndent() }
        .toList()
}
