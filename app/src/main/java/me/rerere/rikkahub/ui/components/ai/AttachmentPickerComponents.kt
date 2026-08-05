package me.rerere.rikkahub.ui.components.ai

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.Model
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.utils.createChatFilesByContents
import me.rerere.rikkahub.utils.createChatUploadFiles
import me.rerere.rikkahub.utils.getFileMimeType
import me.rerere.rikkahub.utils.getFileNameFromUri

internal fun isGeminiAttachmentMenuEnabled(model: Model?): Boolean {
    return model != null && ModelRegistry.GEMINI_SERIES.match(model.modelId)
}

/**
 * 当前会话生效的工作区能否按需读取 /upload 附件：需助手开启工作区文件工具 + 沙盒类型 +
 * rootfs 就绪且已安装（与 WorkspaceToolFactory.createForAssistant 的门控保持一致）。
 * 不满足时附件选择只接受可直接解析进 prompt 的文档类型。
 */
internal fun isSandboxUploadReady(
    assistant: me.rerere.rikkahub.data.model.Assistant,
    workspace: me.rerere.rikkahub.data.repository.Workspace?,
    hasRootfs: (workspaceId: String) -> Boolean,
): Boolean =
    assistant.localTools.contains(me.rerere.rikkahub.data.ai.tools.LocalToolOption.WorkspaceFiles) &&
        workspace != null &&
        workspace.type == me.rerere.rikkahub.data.db.entity.WorkspaceType.SANDBOX &&
        workspace.sandboxStatus == me.rerere.rikkahub.data.db.entity.SandboxRootfsStatus.READY &&
        hasRootfs(workspace.id)

/**
 * 附件发送的原有类型白名单（MIME + 常见文本扩展名）。
 * 无沙盒的助手沿用该白名单：文件内容发送时全量内联进 prompt，不受大小限制，
 * 与升级前行为一致。
 */
internal fun isSupportedChatDocument(
    fileName: String,
    mime: String,
): Boolean {
    return mime.startsWith("text/") ||
        mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
        mime == "application/pdf" ||
        fileName.endsWith(".txt", ignoreCase = true) ||
        fileName.endsWith(".md", ignoreCase = true) ||
        fileName.endsWith(".csv", ignoreCase = true) ||
        fileName.endsWith(".json", ignoreCase = true) ||
        fileName.endsWith(".js", ignoreCase = true) ||
        fileName.endsWith(".html", ignoreCase = true) ||
        fileName.endsWith(".css", ignoreCase = true) ||
        fileName.endsWith(".xml", ignoreCase = true) ||
        fileName.endsWith(".py", ignoreCase = true) ||
        fileName.endsWith(".java", ignoreCase = true) ||
        fileName.endsWith(".kt", ignoreCase = true) ||
        fileName.endsWith(".ts", ignoreCase = true) ||
        fileName.endsWith(".tsx", ignoreCase = true) ||
        fileName.endsWith(".markdown", ignoreCase = true) ||
        fileName.endsWith(".mdx", ignoreCase = true) ||
        fileName.endsWith(".yml", ignoreCase = true) ||
        fileName.endsWith(".yaml", ignoreCase = true)
}

/**
 * 有沙盒工作区的助手：任意类型文件落盘到会话上传目录（挂载进沙盒 `/upload`）。
 * 可解析的小文件仍会被 [me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer]
 * 内联进 prompt，其余文件由助手通过工作区工具按需读取。
 */
internal fun Context.toChatUploadDocuments(
    conversationId: String,
    uris: List<Uri>,
): List<UIMessagePart.Document> {
    if (conversationId.isBlank()) return emptyList()
    return uris.mapNotNull { uri ->
        val mime = getFileMimeType(uri) ?: "text/plain"
        val upload = createChatUploadFiles(conversationId, listOf(uri)).firstOrNull()
            ?: return@mapNotNull null
        UIMessagePart.Document(
            url = upload.uri.toString(),
            fileName = upload.fileName,
            mime = mime,
        )
    }
}

/**
 * 无沙盒工作区的助手：恢复原有行为——按 [isSupportedChatDocument] 白名单过滤，
 * 文件存进共享 `upload` 目录（UUID 文件名），发送时内容全量内联进 prompt。
 */
internal fun Context.toSupportedChatDocuments(
    uris: List<Uri>,
): List<UIMessagePart.Document> {
    return uris.mapNotNull { uri ->
        val fileName = getFileNameFromUri(uri) ?: "file"
        val mime = getFileMimeType(uri) ?: "text/plain"
        if (!isSupportedChatDocument(fileName = fileName, mime = mime)) {
            return@mapNotNull null
        }

        val localUri = createChatFilesByContents(listOf(uri)).firstOrNull()
            ?: return@mapNotNull null

        UIMessagePart.Document(
            url = localUri.toString(),
            fileName = fileName,
            mime = mime,
        )
    }
}

@Composable
internal fun GeminiAttachmentMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onPickVideo: () -> Unit,
    onPickAudio: () -> Unit,
    onPickFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.widthIn(min = 132.dp),
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 8.dp,
        shadowElevation = 4.dp,
    ) {
        DropdownMenuItem(
            text = { Text(text = stringResource(R.string.video)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.VideoLibrary,
                    contentDescription = null,
                )
            },
            contentPadding = PaddingValues(start = 12.dp, end = 10.dp),
            onClick = {
                onDismissRequest()
                onPickVideo()
            },
        )
        DropdownMenuItem(
            text = { Text(text = stringResource(R.string.audio)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.AudioFile,
                    contentDescription = null,
                )
            },
            contentPadding = PaddingValues(start = 12.dp, end = 10.dp),
            onClick = {
                onDismissRequest()
                onPickAudio()
            },
        )
        DropdownMenuItem(
            text = { Text(text = stringResource(R.string.modes_page_add_file)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.FolderOpen,
                    contentDescription = null,
                )
            },
            contentPadding = PaddingValues(start = 12.dp, end = 10.dp),
            onClick = {
                onDismissRequest()
                onPickFile()
            },
        )
    }
}

@Composable
internal fun GeminiAttachmentMenuIcon(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.FolderOpen,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .background(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = CircleShape,
                )
                .size(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}
