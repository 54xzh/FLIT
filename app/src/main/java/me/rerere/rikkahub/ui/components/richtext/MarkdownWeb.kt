package me.rerere.rikkahub.ui.components.richtext

import android.content.Context
import androidx.compose.material3.ColorScheme
import me.rerere.rikkahub.utils.base64Encode
import me.rerere.rikkahub.utils.toCssHex

/**
 * Build HTML page for markdown preview with support for:
 * - Markdown rendering via marked.js
 * - LaTeX math via KaTeX
 * - Mermaid diagrams
 * - Syntax highlighting via highlight.js
 */
fun buildMarkdownPreviewHtml(context: Context, markdown: String, colorScheme: ColorScheme): String {
    val htmlTemplate = context.assets.open("html/mark.html").bufferedReader().use { it.readText() }

    return htmlTemplate
        .replace("{{MARKDOWN_BASE64}}", markdown.base64Encode())
        .replace("{{BACKGROUND_COLOR}}", colorScheme.background.toCssHex())
        .replace("{{ON_BACKGROUND_COLOR}}", colorScheme.onBackground.toCssHex())
        .replace("{{SURFACE_COLOR}}", colorScheme.surface.toCssHex())
        .replace("{{ON_SURFACE_COLOR}}", colorScheme.onSurface.toCssHex())
        .replace("{{SURFACE_VARIANT_COLOR}}", colorScheme.surfaceVariant.toCssHex())
        .replace("{{ON_SURFACE_VARIANT_COLOR}}", colorScheme.onSurfaceVariant.toCssHex())
        .replace("{{PRIMARY_COLOR}}", colorScheme.primary.toCssHex())
        .replace("{{OUTLINE_COLOR}}", colorScheme.outline.toCssHex())
        .replace("{{OUTLINE_VARIANT_COLOR}}", colorScheme.outlineVariant.toCssHex())
}

/**
 * 构建 docx 预览 HTML：把 docx 文件的 base64 字节嵌入模板，由 docx-preview 在 WebView 内渲染。
 * [docxBase64] 应为原始字节的 base64（用 `kotlin.io.encoding.Base64.encode(ByteArray)` 得到）。
 */
fun buildDocxPreviewHtml(context: Context, docxBase64: String, colorScheme: ColorScheme): String {
    val htmlTemplate = context.assets.open("html/docx.html").bufferedReader().use { it.readText() }

    return htmlTemplate
        .replace("{{DOCX_BASE64}}", docxBase64)
        .replace("{{BACKGROUND_COLOR}}", colorScheme.background.toCssHex())
        .replace("{{ON_BACKGROUND_COLOR}}", colorScheme.onBackground.toCssHex())
        .replace("{{ERROR_COLOR}}", colorScheme.error.toCssHex())
}
