package me.rerere.rikkahub.ui.components.message

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.WORKSPACE_FILE_REFERENCE_TOOL_NAME
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.theme.AppShapes
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import org.koin.compose.koinInject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

private data class WorkspaceFileReference(
    val workspaceId: String,
    val path: String,
    val name: String,
    val mime: String,
    val sizeBytes: Long,
)

internal fun UIMessagePart.isHiddenWorkspaceFileReferencePart(): Boolean = when (this) {
    is UIMessagePart.ToolCall -> toolName == WORKSPACE_FILE_REFERENCE_TOOL_NAME
    is UIMessagePart.ToolApproval -> toolName == WORKSPACE_FILE_REFERENCE_TOOL_NAME
    is UIMessagePart.ToolResult -> toolName == WORKSPACE_FILE_REFERENCE_TOOL_NAME
    else -> false
}

private fun UIMessagePart.workspaceFileReferenceContent(): JsonObject? {
    val result = this as? UIMessagePart.ToolResult ?: return null
    if (result.toolName != WORKSPACE_FILE_REFERENCE_TOOL_NAME) return null
    return result.content as? JsonObject
}

internal fun List<UIMessagePart>.workspaceFileReferenceContentsFromParts(): List<JsonObject> {
    return mapNotNull(UIMessagePart::workspaceFileReferenceContent)
        .filter { it.toWorkspaceFileReference() != null }
        .distinctBy { content ->
            val reference = content.toWorkspaceFileReference()
            "${reference?.workspaceId}:${reference?.path}"
        }
}

internal fun List<MessageRenderBlock>.workspaceFileReferenceContentsFromBlocks(): List<JsonObject> {
    return flatMap { block ->
        (block as? MessageRenderBlock.ProcessGroup)
            ?.parts
            .orEmpty()
    }.workspaceFileReferenceContentsFromParts()
}

@Composable
internal fun WorkspaceFileReferenceCards(parts: List<UIMessagePart>) {
    parts.workspaceFileReferenceContentsFromParts().forEach { content ->
        WorkspaceFileReferenceCard(content = content)
    }
}

private fun JsonObject.toWorkspaceFileReference(): WorkspaceFileReference? {
    if (this["ok"]?.jsonPrimitiveOrNull?.contentOrNull != "true") return null
    if (this["type"]?.jsonPrimitiveOrNull?.contentOrNull != "workspace_file_reference") return null
    val workspaceId = this["workspace_id"]?.jsonPrimitiveOrNull?.contentOrNull?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    val path = this["path"]?.jsonPrimitiveOrNull?.contentOrNull?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    val name = this["name"]?.jsonPrimitiveOrNull?.contentOrNull?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: path.substringAfterLast('/').ifBlank { "workspace_file" }
    val mime = this["mime"]?.jsonPrimitiveOrNull?.contentOrNull
        ?.takeIf { it.contains('/') }
        ?: "application/octet-stream"
    val sizeBytes = this["size_bytes"]?.jsonPrimitiveOrNull?.contentOrNull?.toLongOrNull()
        ?.coerceAtLeast(0L)
        ?: 0L
    return WorkspaceFileReference(
        workspaceId = workspaceId,
        path = path,
        name = name,
        mime = mime,
        sizeBytes = sizeBytes,
    )
}

@Composable
internal fun WorkspaceFileReferenceCard(content: JsonObject) {
    val reference = remember(content) { content.toWorkspaceFileReference() } ?: return
    val context = LocalContext.current
    val repository = koinInject<WorkspaceRepository>()
    val scope = rememberCoroutineScope()
    val settings = LocalSettings.current
    val haptics = rememberPremiumHaptics(enabled = settings.displaySetting.enableUIHaptics)
    var opening by remember(reference.workspaceId, reference.path) { mutableStateOf(false) }

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(reference.mime),
    ) { targetUri ->
        if (targetUri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val output = withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(targetUri)
                } ?: error("Unable to open save destination")
                withContext(Dispatchers.IO) {
                    output.use {
                        repository.exportWorkspaceFile(reference.workspaceId, reference.path, it)
                    }
                }
                haptics.perform(HapticPattern.Success)
                showWorkspaceFileToast(context, R.string.workspace_file_reference_saved)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                haptics.perform(HapticPattern.Error)
                showWorkspaceFileToast(context, R.string.workspace_file_reference_save_failed)
            }
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        shape = AppShapes.CardMedium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.InsertDriveFile,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = reference.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = reference.path,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(
                            R.string.workspace_file_reference_info,
                            fileMimeLabel(
                                mime = reference.mime,
                                unknownLabel = stringResource(R.string.workspace_file_reference_unknown_type),
                            ),
                            Formatter.formatShortFileSize(context, reference.sizeBytes),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WorkspaceFileAction(
                    icon = {
                        if (opening) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                    label = stringResource(R.string.workspace_file_reference_open),
                    enabled = !opening,
                    onClick = {
                        haptics.perform(HapticPattern.Pop)
                        opening = true
                        scope.launch {
                            try {
                                val uri = withContext(Dispatchers.IO) {
                                    resolveWorkspaceFileUri(context, repository, reference)
                                }
                                openWorkspaceFile(context, uri, reference.mime)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: FileUnavailableException) {
                                haptics.perform(HapticPattern.Error)
                                showWorkspaceFileToast(context, R.string.workspace_file_reference_unavailable)
                            } catch (_: ActivityNotFoundException) {
                                haptics.perform(HapticPattern.Error)
                                showWorkspaceFileToast(context, R.string.workspace_file_reference_open_failed)
                            } catch (_: Exception) {
                                haptics.perform(HapticPattern.Error)
                                showWorkspaceFileToast(context, R.string.workspace_file_reference_open_failed)
                            } finally {
                                opening = false
                            }
                        }
                    },
                )
                Spacer(modifier = Modifier.width(6.dp))
                WorkspaceFileAction(
                    icon = {
                        Icon(
                            imageVector = Icons.Rounded.Save,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    label = stringResource(R.string.workspace_file_reference_save),
                    onClick = {
                        haptics.perform(HapticPattern.Pop)
                        saveLauncher.launch(reference.name.safeWorkspaceFileName())
                    },
                )
            }
        }
    }
}

@Composable
private fun WorkspaceFileAction(
    icon: @Composable () -> Unit,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "workspace_file_action_scale",
    )
    Row(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.65f
            }
            .clip(AppShapes.ButtonPill)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        icon()
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

private class FileUnavailableException : Exception()

private suspend fun resolveWorkspaceFileUri(
    context: Context,
    repository: WorkspaceRepository,
    reference: WorkspaceFileReference,
): android.net.Uri {
    repository.resolveWorkspaceFileUri(reference.workspaceId, reference.path)?.let { return it }

    // 用路径的 SHA-256 前缀做缓存文件名：hashCode 只有 32 位，不同路径可能碰撞，
    // 导致打开 A 文件时看到 B 的内容。
    val shareFile = File(
        context.cacheDir,
        "workspace_share/${reference.workspaceId}/${reference.path.sha256HexPrefix()}_${reference.name.safeWorkspaceFileName()}",
    )
    shareFile.parentFile?.mkdirs()
    try {
        shareFile.outputStream().use { output ->
            repository.exportWorkspaceFile(reference.workspaceId, reference.path, output)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        throw FileUnavailableException().also { it.initCause(error) }
    }
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", shareFile)
}

private fun openWorkspaceFile(context: Context, uri: android.net.Uri, mime: String) {
    val openMime = mime.takeUnless { it == "application/octet-stream" } ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, openMime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.workspace_file_reference_open)))
}

private fun String.safeWorkspaceFileName(): String =
    replace('/', '_').replace('\\', '_').ifBlank { "workspace_file" }

private fun String.sha256HexPrefix(): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(toByteArray())
    return bytes.take(8).joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private fun fileMimeLabel(mime: String, unknownLabel: String): String = when {
    mime == "application/octet-stream" -> unknownLabel
    else -> mime.substringAfterLast('/').uppercase(Locale.ROOT)
}

private fun showWorkspaceFileToast(context: Context, messageRes: Int) {
    Toast.makeText(context, context.getString(messageRes), Toast.LENGTH_SHORT).show()
}
