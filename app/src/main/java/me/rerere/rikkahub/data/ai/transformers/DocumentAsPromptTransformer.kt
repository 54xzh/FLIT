package me.rerere.rikkahub.data.ai.transformers

import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.document.DocxParser
import me.rerere.document.PdfParser
import me.rerere.rikkahub.workspace.SANDBOX_UPLOAD_MOUNT_TARGET
import java.io.File

/**
 * 内联进 prompt 的文件内容上限（约 10KB 文本 ≈ 数千 token）。
 * 超过上限或无法解析的文件只发送元信息，助手可通过沙盒工具按需读取 /upload 原文件。
 */
private const val INLINE_CONTENT_LIMIT = 10 * 1024

/**
 * 允许解析/读取的文件大小上限。超过的文件连解析都不做（避免超大 PDF/DOCX 整体进内存），
 * 只发元信息交给沙盒工具按需处理。
 */
private const val PARSE_FILE_LIMIT = 20 * 1024 * 1024

object DocumentAsPromptTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return withContext(Dispatchers.IO) {
            messages.map { message ->
                message.copy(
                    parts = message.parts.toMutableList().apply {
                        val documents = filterIsInstance<UIMessagePart.Document>()
                        if (documents.isNotEmpty()) {
                            documents.forEach { document ->
                                add(0, UIMessagePart.Text(buildDocumentPrompt(document, ctx.chatUploadsAccessible)))
                            }
                        }
                    }
                )
            }
        }
    }

    private fun buildDocumentPrompt(
        document: UIMessagePart.Document,
        chatUploadsAccessible: Boolean,
    ): String {
        val file = runCatching { document.url.toUri().toFile() }.getOrNull()
        if (file == null || !file.isFile) {
            return """<UploadFile name="${escapeXmlAttr(document.fileName)}" unavailable="true">The uploaded file is no longer available on this device.</UploadFile>"""
        }
        val sandboxPath = sessionUploadRelativePath(file, chatUploadsAccessible)

        // 非会话上传目录，或本次请求没有可读取 /upload 的沙盒工具：
        // 沿用升级前的原有行为——内容全量内联进 prompt，不设大小上限、也不承诺沙盒路径。
        if (sandboxPath == null) {
            return buildLegacyDocumentPrompt(document, file)
        }

        // 会话上传目录里的文件会挂载进沙盒 /upload，可告知助手按路径读取；
        // 文件名可包含引号等字符，路径同样需要属性转义
        val pathAttr = """ path="${escapeXmlAttr("$SANDBOX_UPLOAD_MOUNT_TARGET/$sandboxPath")}""""
        val size = formatUploadSize(file.length())

        val parsedContent = parseDocumentContent(document, file)
        if (parsedContent != null && parsedContent.length <= INLINE_CONTENT_LIMIT) {
            return """
                |<UploadFile name="${escapeXmlAttr(document.fileName)}"$pathAttr size="$size">
                |```
                |$parsedContent
                |```
                |</UploadFile>
            """.trimMargin()
        }

        // 过大或无法解析：本次请求已确认可以通过沙盒工具读取，因此只发送路径与元信息。
        val hint = "The file content is not inlined because it is too large or not directly readable."
        return """<UploadFile name="${escapeXmlAttr(document.fileName)}"$pathAttr size="$size" inline="false">$hint</UploadFile>"""
    }

    /**
     * 旧版共享 `upload` 目录里的文档（含无沙盒助手发送的附件）：与升级前行为一致，
     * 内容全量内联进 prompt。超大二进制文件（PDF/DOCX 超限）返回 null 时退化为元信息，
     * 避免整个请求因单个附件失败。
     */
    private fun buildLegacyDocumentPrompt(document: UIMessagePart.Document, file: File): String {
        val content = runCatching {
            when (document.mime) {
                "application/pdf" -> PdfParser.parserPdf(file)
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> DocxParser.parse(file)
                else -> file.readText()
            }
        }.getOrNull()
        if (content == null) {
            // 原有通道没有 /upload 路径可读：明确告知内容不可得，避免模型幻觉调用读取工具
            return """<UploadFile name="${escapeXmlAttr(document.fileName)}" size="${formatUploadSize(file.length())}" inline="false">The file content could not be read (it may be corrupted, in an unsupported format, or too large).</UploadFile>"""
        }
        return """
            |<UploadFile name="${escapeXmlAttr(document.fileName)}" size="${formatUploadSize(file.length())}">
            |```
            |$content
            |```
            |</UploadFile>
        """.trimMargin()
    }

    /** 文件名来自用户选择，拼进标签属性前转义，防止破坏标签结构或注入属性。 */
    private fun escapeXmlAttr(value: String): String = value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    /**
     * 尝试把文件解析为文本。返回 null 表示无法解析（如二进制文件），交给沙盒工具读取。
     * 解析异常同样视为不可解析，避免整个请求因单个附件失败。
     */
    private fun parseDocumentContent(document: UIMessagePart.Document, file: File): String? {
        // 任何类型都先按大小预检：超大文件（尤其 PDF/DOCX）不整体解析进内存，
        // 直接走元信息分支交给沙盒工具按需读取
        if (file.length() > PARSE_FILE_LIMIT) return null
        return runCatching {
            when (document.mime) {
                "application/pdf" -> PdfParser.parserPdf(file)
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> DocxParser.parse(file)
                // 纯文本类再按内联上限预检，避免大文件整体读入内存
                else -> if (file.length() <= INLINE_CONTENT_LIMIT) file.readText() else null
            }
        }.getOrNull()
    }

    /** 本次请求可访问时，返回会话上传文件对应的 /upload 内相对路径。 */
    internal fun sessionUploadRelativePath(file: File, chatUploadsAccessible: Boolean): String? {
        if (!chatUploadsAccessible) return null
        val rootName = "chat_uploads"
        val segments = file.absolutePath.split(File.separatorChar)
        val rootIndex = segments.lastIndexOf(rootName)
        if (rootIndex < 0 || segments.size <= rootIndex + 2) return null
        return segments.subList(rootIndex + 2, segments.size).joinToString("/")
    }

    private fun formatUploadSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        }
    }
}
