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
                                add(0, UIMessagePart.Text(buildDocumentPrompt(document)))
                            }
                        }
                    }
                )
            }
        }
    }

    private fun buildDocumentPrompt(document: UIMessagePart.Document): String {
        val file = runCatching { document.url.toUri().toFile() }.getOrNull()
        if (file == null || !file.isFile) {
            return """<UploadFile name="${escapeXmlAttr(document.fileName)}" unavailable="true">The uploaded file is no longer available on this device.</UploadFile>"""
        }
        val sandboxPath = sessionUploadRelativePath(file)
        val size = formatUploadSize(file.length())

        // 会话上传目录里的文件会挂载进沙盒 /upload，可告知助手按路径读取；
        // 文件名可包含引号等字符，路径同样需要属性转义
        val pathAttr = if (sandboxPath != null) {
            """ path="${escapeXmlAttr("$SANDBOX_UPLOAD_MOUNT_TARGET/$sandboxPath")}""""
        } else {
            ""
        }

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

        // 过大或无法解析：不内联内容。是否真有沙盒工具可用此处无法确知（工作区可被会话覆写、
        // 定时任务无会话），因此只描述事实、不承诺工具存在，避免模型调用不存在的工具。
        val hint = "The file content is not inlined because it is too large or not directly readable."
        return """<UploadFile name="${escapeXmlAttr(document.fileName)}"$pathAttr size="$size" inline="false">$hint</UploadFile>"""
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

    /** 会话上传文件（filesDir/chat_uploads/<会话>/原名）对应的 /upload 内相对路径；非会话上传文件返回 null。 */
    private fun sessionUploadRelativePath(file: File): String? {
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
