// WorkDir 目录选择器：基于已授权的 Workspace Root（SAF TreeUri）浏览并选择子目录，输出相对路径（relPath）。
package me.rerere.rikkahub.ui.components.workdir

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.theme.AppShapes
import me.rerere.rikkahub.utils.SkillScriptPathUtils
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkDirPickerBottomSheet(
    conversationId: Uuid,
    workspaceRootTreeUri: String?,
    initialRelPath: String? = null,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val context = LocalContext.current
    val haptics = rememberPremiumHaptics()
    val scope = rememberCoroutineScope()
    val settingsStore = koinInject<SettingsStore>()

    val initialSegments = remember(initialRelPath) {
        SkillScriptPathUtils.normalizeAndValidateWorkDirRelPath(initialRelPath?.trim().orEmpty())
            ?.split('/')
            ?.filter { it.isNotBlank() }
            .orEmpty()
    }

    var rootDoc by remember(workspaceRootTreeUri) { mutableStateOf<DocumentFile?>(null) }
    var segments by remember { mutableStateOf(initialSegments) }
    var loading by remember(workspaceRootTreeUri) { mutableStateOf(true) }
    var errorMessage by remember(workspaceRootTreeUri) { mutableStateOf<String?>(null) }
    var folderNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var navigationDirection by remember { mutableStateOf(WorkDirNavigationDirection.Forward) }

    var creatingFolder by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    val newFolderFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    val workspaceRootLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        haptics.perform(HapticPattern.Success)
        navigationDirection = WorkDirNavigationDirection.Back
        segments = emptyList()
        folderNames = emptyList()
        creatingFolder = false
        newFolderName = ""
        scope.launch {
            val key = conversationId.toString()
            settingsStore.update { old ->
                old.copy(
                    conversationWorkspaceRoots = old.conversationWorkspaceRoots + (key to uri.toString()),
                    conversationWorkDirs = old.conversationWorkDirs - key,
                )
            }
        }
    }

    LaunchedEffect(creatingFolder) {
        if (creatingFolder) {
            focusManager.clearFocus(force = true)
            newFolderFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(workspaceRootTreeUri) {
        loading = true
        errorMessage = null
        rootDoc = withContext(Dispatchers.IO) {
            val uriString = workspaceRootTreeUri?.trim().orEmpty()
            if (uriString.isBlank()) return@withContext null
            val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return@withContext null
            DocumentFile.fromTreeUri(context, uri)
        }?.takeIf { it.isDirectory }
        if (rootDoc == null) {
            errorMessage = context.getString(R.string.workspace_root_required_hint_v2)
            folderNames = emptyList()
            loading = false
        }
    }

    LaunchedEffect(rootDoc, segments) {
        val root = rootDoc ?: return@LaunchedEffect
        loading = true
        val (resolvedSegments, dirs) = withContext(Dispatchers.IO) {
            val resolved = resolveDir(root = root, segments = segments)
            if (resolved == null) {
                return@withContext Pair(emptyList(), emptyList())
            }
            val names = resolved.listFiles()
                .asSequence()
                .filter { it.isDirectory }
                .mapNotNull { it.name?.trim() }
                .filter { it.isNotBlank() && it != "." && it != ".." }
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
                .toList()
            Pair(segments, names)
        }
        if (resolvedSegments.isEmpty() && segments.isNotEmpty()) {
            segments = emptyList()
        }
        folderNames = dirs
        loading = false
    }

    if (creatingFolder) {
        AlertDialog(
            onDismissRequest = { creatingFolder = false },
            title = { Text(stringResource(R.string.workdir_picker_new_folder_title)) },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.workdir_picker_new_folder_placeholder)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(newFolderFocusRequester),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = newFolderName.trim()
                        if (name.isBlank() || name == "." || name == ".." || name.contains('/') || name.contains('\\')) {
                            haptics.perform(HapticPattern.Error)
                            return@TextButton
                        }
                        haptics.perform(HapticPattern.Thud)
                        creatingFolder = false
                        newFolderName = ""
                        val root = rootDoc ?: return@TextButton
                        val currentSegments = segments
                        scope.launch {
                            val createdName = withContext(Dispatchers.IO) {
                                val current = resolveDir(root = root, segments = currentSegments) ?: return@withContext null
                                val existing = current.findFile(name)
                                when {
                                    existing != null && existing.isDirectory -> existing.name
                                    existing != null -> null
                                    else -> current.createDirectory(name)?.name
                                }
                            }
                            if (!createdName.isNullOrBlank()) {
                                navigationDirection = WorkDirNavigationDirection.Forward
                                segments = currentSegments + createdName
                            } else {
                                errorMessage = context.getString(R.string.workdir_picker_new_folder_failed)
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.workdir_picker_new_folder_create))
                }
            },
            dismissButton = {
                TextButton(onClick = { creatingFolder = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = AppShapes.BottomSheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.FolderOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.workdir_picker_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    enabled = segments.isNotEmpty(),
                    onClick = {
                        haptics.perform(HapticPattern.Pop)
                        navigationDirection = WorkDirNavigationDirection.Back
                        segments = segments.dropLast(1)
                    }
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                }
            }

            WorkDirBreadcrumb(
                rootLabel = rootDoc?.name?.trim().takeIf { !it.isNullOrBlank() }
                    ?: stringResource(R.string.workspace_root_title),
                segments = segments,
                onSelectDepth = { depth ->
                    haptics.perform(HapticPattern.Pop)
                    navigationDirection = WorkDirNavigationDirection.Back
                    segments = segments.take(depth)
                },
            )

            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            AnimatedContent(
                targetState = folderNames,
                transitionSpec = {
                    val offset = { fullWidth: Int -> (fullWidth / 8).coerceAtLeast(48) }
                    val slideSpec = spring<IntOffset>(dampingRatio = 1f, stiffness = 800f)
                    val fadeSpec = spring<Float>(dampingRatio = 1f, stiffness = 900f)

                    val enter = when (navigationDirection) {
                        WorkDirNavigationDirection.Forward -> {
                            slideInHorizontally(animationSpec = slideSpec, initialOffsetX = { offset(it) }) +
                                fadeIn(animationSpec = fadeSpec)
                        }

                        WorkDirNavigationDirection.Back -> {
                            slideInHorizontally(animationSpec = slideSpec, initialOffsetX = { -offset(it) }) +
                                fadeIn(animationSpec = fadeSpec)
                        }
                    }
                    val exit = when (navigationDirection) {
                        WorkDirNavigationDirection.Forward -> {
                            slideOutHorizontally(animationSpec = slideSpec, targetOffsetX = { -offset(it) }) +
                                fadeOut(animationSpec = fadeSpec)
                        }

                        WorkDirNavigationDirection.Back -> {
                            slideOutHorizontally(animationSpec = slideSpec, targetOffsetX = { offset(it) }) +
                                fadeOut(animationSpec = fadeSpec)
                        }
                    }
                    enter togetherWith exit using SizeTransform(clip = true) { _, _ ->
                        spring(dampingRatio = 1f, stiffness = 900f)
                    }
                },
                label = "workdir_folder_list_transition",
            ) { animatedFolders ->
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    item(key = "choose_other_dir") {
                        ListItem(
                            colors = ListItemDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            ),
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Rounded.FolderOpen,
                                    contentDescription = null,
                                )
                            },
                            headlineContent = {
                                Text(text = stringResource(R.string.workdir_picker_choose_other_directory))
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(AppShapes.CardMedium)
                                .clickable {
                                    haptics.perform(HapticPattern.Pop)
                                    workspaceRootLauncher.launch(null)
                                },
                        )
                    }

                    items(animatedFolders, key = { it }) { name ->
                        ListItem(
                            colors = ListItemDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            ),
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Rounded.Folder,
                                    contentDescription = null,
                                )
                            },
                            headlineContent = {
                                Text(text = name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(AppShapes.CardMedium)
                                .clickable {
                                    haptics.perform(HapticPattern.Pop)
                                    navigationDirection = WorkDirNavigationDirection.Forward
                                    segments = segments + name
                                },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    enabled = rootDoc != null,
                    onClick = {
                        haptics.perform(HapticPattern.Pop)
                        creatingFolder = true
                    },
                ) {
                    Icon(Icons.Rounded.CreateNewFolder, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.workdir_picker_new_folder_action))
                }

                Button(
                    enabled = rootDoc != null,
                    onClick = {
                        val relPath = segments.joinToString("/")
                        val validated = SkillScriptPathUtils.normalizeAndValidateWorkDirRelPath(relPath)
                        if (validated == null) {
                            haptics.perform(HapticPattern.Error)
                            return@Button
                        }
                        haptics.perform(HapticPattern.Success)
                        onConfirm(validated)
                        onDismissRequest()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.workdir_picker_choose_current))
                }
            }
        }
    }
}

@Composable
private fun WorkDirBreadcrumb(
    rootLabel: String,
    segments: List<String>,
    onSelectDepth: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = rootLabel,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { onSelectDepth(0) },
        )
        if (segments.isNotEmpty()) {
            Text(
                text = " / ",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        segments.forEachIndexed { index, name ->
            Text(
                text = name,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .clickable { onSelectDepth(index + 1) },
            )
            if (index < segments.lastIndex) {
                Text(
                    text = " / ",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun resolveDir(root: DocumentFile, segments: List<String>): DocumentFile? {
    var current = root
    for (seg in segments) {
        val next = current.findFile(seg) ?: return null
        if (!next.isDirectory) return null
        current = next
    }
    return current
}

private enum class WorkDirNavigationDirection {
    Forward,
    Back,
}
