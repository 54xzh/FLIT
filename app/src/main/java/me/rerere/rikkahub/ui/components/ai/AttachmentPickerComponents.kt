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
import me.rerere.rikkahub.utils.getFileMimeType
import me.rerere.rikkahub.utils.getFileNameFromUri

internal fun isGeminiAttachmentMenuEnabled(model: Model?): Boolean {
    return model != null && ModelRegistry.GEMINI_SERIES.match(model.modelId)
}

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
