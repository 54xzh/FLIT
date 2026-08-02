package me.rerere.rikkahub.ui.components.message

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.WorkspaceFileReferenceContext
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.repository.WorkspaceFileEntry
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.theme.AppShapes
import org.koin.compose.koinInject
import java.io.File
import java.security.MessageDigest

internal data class WorkspaceFileReferenceCandidate(
    val workspaceId: String,
    val path: String,
)

private data class WorkspaceFileReference(
    val entryKey: String,
    val workspaceId: String,
    val path: String,
    val name: String,
    val mime: String,
    val sizeBytes: Long,
)

internal class WorkspaceFileReferenceEntryTracker(
    initiallyEnteredKeys: Set<String> = emptySet(),
) {
    private val enteredKeys = mutableStateMapOf<String, Unit>().apply {
        initiallyEnteredKeys.forEach { key -> this[key] = Unit }
    }

    fun hasEntered(key: String): Boolean = key in enteredKeys

    fun markEntered(key: String) {
        enteredKeys[key] = Unit
    }
}

internal val LocalWorkspaceFileReferenceEntryTracker =
    staticCompositionLocalOf<WorkspaceFileReferenceEntryTracker?> { null }

internal fun WorkspaceFileReferenceCandidate.workspaceFileReferenceEntryKey(
    entryScope: String? = null,
): String {
    val scope = entryScope?.takeIf { it.isNotBlank() }
    return if (scope == null) {
        "$workspaceId\u0000$path"
    } else {
        "$scope\u0000$workspaceId\u0000$path"
    }
}

private fun WorkspaceFileReferenceCandidate.toWorkspaceFileReference(
    entry: WorkspaceFileEntry?,
    entryScope: String? = null,
): WorkspaceFileReference? {
    if (entry?.isDirectory == true) return null
    val name = entry?.name?.trim()?.takeIf { it.isNotEmpty() }
        ?: path.substringAfterLast('/').ifBlank { "workspace_file" }
    val mime = MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
        ?: "application/octet-stream"
    return WorkspaceFileReference(
        entryKey = workspaceFileReferenceEntryKey(entryScope),
        workspaceId = workspaceId.trim(),
        path = path.trim(),
        name = name,
        mime = mime,
        sizeBytes = entry?.sizeBytes?.coerceAtLeast(0L) ?: 0L,
    )
}

@Composable
internal fun WorkspaceFileReferenceCards(
    items: List<WorkspaceFileReferenceCandidate>,
    entryScope: String? = null,
) {
    val repository = koinInject<WorkspaceRepository>()
    var references by remember(items, entryScope) { mutableStateOf(emptyList<WorkspaceFileReference>()) }
    LaunchedEffect(items, entryScope, repository) {
        references = withContext(Dispatchers.IO) {
            items.distinct().mapNotNull { candidate ->
                val entry = runCatching {
                    repository.resolveWorkspaceEntry(candidate.workspaceId, candidate.path)
                }.getOrNull()
                candidate.toWorkspaceFileReference(entry, entryScope)
            }
        }
    }
    if (references.isEmpty()) return

    LazyRow(
        modifier = Modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(
            items = references,
            key = { _, reference -> reference.entryKey },
        ) { _, reference ->
            WorkspaceFileReferenceCard(
                reference = reference,
            )
        }
    }
}

@Composable
internal fun rememberWorkspaceFileReferenceClickHandler(
    workspaceContext: WorkspaceFileReferenceContext?,
): (String) -> Unit {
    val context = LocalContext.current
    val repository = koinInject<WorkspaceRepository>()
    val scope = rememberCoroutineScope()
    val settings = LocalSettings.current
    val haptics = rememberPremiumHaptics(enabled = settings.displaySetting.enableUIHaptics)
    val currentWorkspaceContext = rememberUpdatedState(workspaceContext)
    val currentHaptics = rememberUpdatedState(haptics)

    return remember(context, repository, scope, haptics) {
        { path ->
            val workspaceId = currentWorkspaceContext.value?.workspaceId
            if (!workspaceId.isNullOrBlank()) {
                currentHaptics.value.perform(HapticPattern.Pop)
                scope.launch {
                    try {
                        openWorkspaceFileReference(
                            context = context,
                            repository = repository,
                            candidate = WorkspaceFileReferenceCandidate(
                                workspaceId = workspaceId,
                                path = path,
                            ),
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: FileUnavailableException) {
                        currentHaptics.value.perform(HapticPattern.Error)
                        showWorkspaceFileToast(context, R.string.workspace_file_reference_unavailable)
                    } catch (_: ActivityNotFoundException) {
                        currentHaptics.value.perform(HapticPattern.Error)
                        showWorkspaceFileToast(context, R.string.workspace_file_reference_open_failed)
                    } catch (_: Exception) {
                        currentHaptics.value.perform(HapticPattern.Error)
                        showWorkspaceFileToast(context, R.string.workspace_file_reference_open_failed)
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceFileReferenceCard(
    reference: WorkspaceFileReference,
) {
    val context = LocalContext.current
    val repository = koinInject<WorkspaceRepository>()
    val scope = rememberCoroutineScope()
    val settings = LocalSettings.current
    val haptics = rememberPremiumHaptics(enabled = settings.displaySetting.enableUIHaptics)
    var opening by remember(reference.workspaceId, reference.path) { mutableStateOf(false) }
    val entryTracker = LocalWorkspaceFileReferenceEntryTracker.current
    val entryKey = remember(reference) { reference.entryKey }
    var entered by remember(entryTracker, entryKey) {
        mutableStateOf(entryTracker?.hasEntered(entryKey) == true)
    }
    val entryScale by animateFloatAsState(
        targetValue = if (entered) 1f else 0.84f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "workspace_file_entry_scale",
    )
    val entryAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f),
        label = "workspace_file_entry_alpha",
        finishedListener = { value ->
            if (value == 1f) {
                entryTracker?.markEntered(entryKey)
            }
        },
    )
    val entryOffset by animateDpAsState(
        targetValue = if (entered) 0.dp else 16.dp,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "workspace_file_entry_offset",
    )

    LaunchedEffect(entryTracker, entryKey) {
        if (!entered) {
            withFrameNanos { }
            entered = true
        }
    }

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

    val openInteractionSource = remember { MutableInteractionSource() }
    val openPressed by openInteractionSource.collectIsPressedAsState()
    val openScale by animateFloatAsState(
        targetValue = if (openPressed) 0.85f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "workspace_file_open_scale",
    )
    val fileNameTooltipState = rememberTooltipState()

    TooltipBox(
        positionProvider = WorkspaceFileTooltipPositionProvider,
        tooltip = {
            PlainTooltip {
                Text(
                    text = reference.name,
                    modifier = Modifier.widthIn(max = 280.dp),
                )
            }
        },
        state = fileNameTooltipState,
    ) {
        Surface(
            modifier = Modifier
                .width(160.dp)
                .height(64.dp)
                .offset(y = entryOffset)
                .graphicsLayer {
                    alpha = entryAlpha
                    scaleX = entryScale
                    scaleY = entryScale
                    transformOrigin = TransformOrigin(0f, 0.5f)
                },
            shape = AppShapes.CardMedium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 1.dp,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .graphicsLayer {
                            scaleX = openScale
                            scaleY = openScale
                            alpha = if (opening) 0.65f else 1f
                        }
                        .clip(AppShapes.CardMedium)
                        .combinedClickable(
                            enabled = !opening,
                            role = Role.Button,
                            onClickLabel = stringResource(R.string.workspace_file_reference_open),
                            onLongClickLabel = reference.name,
                            interactionSource = openInteractionSource,
                            indication = null,
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
                            onLongClick = {
                                haptics.perform(HapticPattern.Pop)
                                scope.launch { fileNameTooltipState.show() }
                            },
                        )
                        .padding(start = 10.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (opening) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.InsertDriveFile,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = reference.name,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                        )
                        Text(
                            text = Formatter.formatShortFileSize(context, reference.sizeBytes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                WorkspaceFileSaveAction(
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
private fun WorkspaceFileSaveAction(
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.85f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "workspace_file_save_scale",
    )
    Icon(
        imageVector = Icons.Rounded.Save,
        contentDescription = stringResource(R.string.workspace_file_reference_save),
        modifier = Modifier
            .size(40.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(AppShapes.ButtonPill)
            .clickable(
                role = Role.Button,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(10.dp),
        tint = MaterialTheme.colorScheme.primary,
    )
}

private object WorkspaceFileTooltipPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = calculateWorkspaceFileTooltipPosition(
        anchorBounds = anchorBounds,
        windowSize = windowSize,
        popupContentSize = popupContentSize,
    )
}

internal fun calculateWorkspaceFileTooltipPosition(
    anchorBounds: IntRect,
    windowSize: IntSize,
    popupContentSize: IntSize,
): IntOffset {
    val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
    val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
    val centeredX = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
    val belowY = anchorBounds.bottom
    val aboveY = anchorBounds.top - popupContentSize.height
    val preferredY = when {
        aboveY >= 0 -> aboveY
        belowY <= maxY -> belowY
        else -> anchorBounds.top
    }

    return IntOffset(
        x = centeredX.coerceIn(0, maxX),
        y = preferredY.coerceIn(0, maxY),
    )
}

private class FileUnavailableException : Exception()

internal suspend fun openWorkspaceFileReference(
    context: Context,
    repository: WorkspaceRepository,
    candidate: WorkspaceFileReferenceCandidate,
) {
    val (reference, uri) = withContext(Dispatchers.IO) {
        val entry = repository.resolveWorkspaceFile(candidate.workspaceId, candidate.path)
            ?: throw FileUnavailableException()
        val reference = candidate.toWorkspaceFileReference(entry)
            ?: throw FileUnavailableException()
        reference to resolveWorkspaceFileUri(context, repository, reference)
    }
    openWorkspaceFile(context, uri, reference.mime)
}

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

private fun showWorkspaceFileToast(context: Context, messageRes: Int) {
    Toast.makeText(context, context.getString(messageRes), Toast.LENGTH_SHORT).show()
}
