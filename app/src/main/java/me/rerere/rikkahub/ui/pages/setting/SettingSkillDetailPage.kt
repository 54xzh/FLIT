package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.ToggleOn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.SkillDirectoryNode
import me.rerere.rikkahub.data.files.SkillDirectoryTree
import me.rerere.rikkahub.data.files.SkillPaths
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.pages.extensions.workspace.ViewerTarget
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceFileViewerSheet
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceFileViewerState
import me.rerere.rikkahub.ui.pages.setting.components.AssistantToggleSheet
import me.rerere.rikkahub.ui.theme.AppShapes
import org.koin.androidx.compose.koinViewModel
import java.io.File

@Composable
fun SettingSkillDetailPage(
    skillName: String,
    vm: SettingVM = koinViewModel(),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val haptics = rememberPremiumHaptics()
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val skillsRoot = remember(context) { File(context.filesDir, "skills") }
    val fileViewerState = remember { WorkspaceFileViewerState() }

    var treeState by remember { mutableStateOf<SkillTreeState>(SkillTreeState.Loading) }
    var showAssistantToggleSheet by remember { mutableStateOf(false) }

    LaunchedEffect(skillName) {
        treeState = SkillTreeState.Loading
        treeState = withContext(Dispatchers.IO) {
            val skillDir = SkillPaths.resolveSkillDir(skillsRoot, skillName)
            when {
                skillDir?.isDirectory != true -> SkillTreeState.Missing
                else -> SkillTreeState.Ready(SkillDirectoryTree.load(skillsRoot, skillName))
            }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            OneUITopAppBar(
                title = skillName,
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    haptics.perform(HapticPattern.Tick)
                    showAssistantToggleSheet = true
                },
                shape = AppShapes.CardLarge,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Icon(
                    imageVector = Icons.Rounded.ToggleOn,
                    contentDescription = stringResource(R.string.skills_toggle_assistants),
                )
            }
        },
    ) { paddingValues ->
        when (val state = treeState) {
            SkillTreeState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            SkillTreeState.Missing -> SkillTreeEmptyState(paddingValues)

            is SkillTreeState.Ready -> {
                if (state.nodes.isEmpty()) {
                    SkillTreeEmptyState(paddingValues)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = paddingValues.calculateTopPadding() + 16.dp,
                            bottom = paddingValues.calculateBottomPadding() + 96.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        item(key = "skill_tree") {
                            SkillDirectoryNodes(
                                nodes = state.nodes,
                                depth = 0,
                                onOpenFile = { relativePath ->
                                    scope.launch {
                                        val file = withContext(Dispatchers.IO) {
                                            val skillDir = SkillPaths.resolveSkillDir(skillsRoot, skillName)
                                                ?: return@withContext null
                                            SkillPaths.resolveSkillFile(skillDir, relativePath)
                                                ?.takeIf { it.isFile }
                                        }
                                        if (file == null) {
                                            haptics.perform(HapticPattern.Error)
                                            toaster.show(context.getString(R.string.skills_file_unavailable))
                                        } else {
                                            haptics.perform(HapticPattern.Pop)
                                            fileViewerState.showLocalFile(file)
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    WorkspaceFileViewerSheet(
        state = fileViewerState,
        resolveFileUri = { target ->
            if (target is ViewerTarget.LocalFile) {
                runCatching {
                    val skillDir = SkillPaths.resolveSkillDir(skillsRoot, skillName) ?: return@runCatching null
                    val canonicalDir = skillDir.canonicalFile
                    val canonicalTarget = target.file.canonicalFile
                    val relativePath = canonicalTarget.relativeTo(canonicalDir).invariantSeparatorsPath
                    val validated = SkillPaths.resolveSkillFile(skillDir, relativePath)?.canonicalFile
                    if (validated != canonicalTarget || !validated.isFile) return@runCatching null
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        validated,
                    )
                }.getOrNull()
            } else {
                null
            }
        },
    )

    if (showAssistantToggleSheet) {
        AssistantToggleSheet(
            title = skillName,
            assistants = settings.assistants,
            isEnabled = { assistant -> skillName in assistant.enabledSkills },
            onToggle = { assistant, enabled ->
                vm.updateSettings { latest ->
                    latest.copy(
                        assistants = latest.assistants.map { current ->
                            if (current.id != assistant.id) current else {
                                val enabledSkills = if (enabled) {
                                    current.enabledSkills + skillName
                                } else {
                                    current.enabledSkills - skillName
                                }
                                current.copy(enabledSkills = enabledSkills)
                            }
                        }
                    )
                }
            },
            onDismiss = { showAssistantToggleSheet = false },
        )
    }
}

private sealed interface SkillTreeState {
    data object Loading : SkillTreeState
    data object Missing : SkillTreeState
    data class Ready(val nodes: List<SkillDirectoryNode>) : SkillTreeState
}

@Composable
private fun SkillTreeEmptyState(paddingValues: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            shape = AppShapes.CardLarge,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        ) {
            Text(
                text = stringResource(R.string.skills_detail_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}

@Composable
private fun SkillDirectoryNodes(
    nodes: List<SkillDirectoryNode>,
    depth: Int,
    onOpenFile: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        nodes.forEach { node ->
            when (node) {
                is SkillDirectoryNode.FileNode -> SkillFileRow(
                    node = node,
                    depth = depth,
                    onOpen = { onOpenFile(node.relativePath) },
                )

                is SkillDirectoryNode.DirectoryNode -> SkillDirectoryRow(
                    node = node,
                    depth = depth,
                    onOpenFile = onOpenFile,
                )
            }
        }
    }
}

@Composable
private fun SkillDirectoryRow(
    node: SkillDirectoryNode.DirectoryNode,
    depth: Int,
    onOpenFile: (String) -> Unit,
) {
    val haptics = rememberPremiumHaptics()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    var expanded by rememberSaveable(node.relativePath) { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 400f),
        label = "skill_directory_scale",
    )
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "skill_directory_arrow",
    )

    Column {
        Surface(
            onClick = {
                haptics.perform(HapticPattern.Pop)
                expanded = !expanded
            },
            shape = AppShapes.ListItem,
            color = Color.Transparent,
            interactionSource = interactionSource,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
        ) {
            Row(
                modifier = Modifier.padding(
                    start = 12.dp + (depth * 20).dp,
                    top = 12.dp,
                    end = 12.dp,
                    bottom = 12.dp,
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Rounded.FolderOpen else Icons.Rounded.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = node.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(20.dp)
                        .graphicsLayer { rotationZ = arrowRotation },
                )
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(spring(dampingRatio = 0.7f, stiffness = 300f)) +
                fadeIn(spring(dampingRatio = 0.8f, stiffness = 400f)),
            exit = shrinkVertically(spring(dampingRatio = 0.8f, stiffness = 400f)) + fadeOut(),
        ) {
            Column(modifier = Modifier.padding(top = 4.dp)) {
                SkillDirectoryNodes(
                    nodes = node.children,
                    depth = depth + 1,
                    onOpenFile = onOpenFile,
                )
            }
        }
    }
}

@Composable
private fun SkillFileRow(
    node: SkillDirectoryNode.FileNode,
    depth: Int,
    onOpen: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 400f),
        label = "skill_file_scale",
    )

    Surface(
        onClick = {
            onOpen()
        },
        shape = AppShapes.ListItem,
        color = Color.Transparent,
        interactionSource = interactionSource,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
    ) {
        Row(
            modifier = Modifier.padding(
                start = 12.dp + (depth * 20).dp,
                top = 12.dp,
                end = 12.dp,
                bottom = 12.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = node.name,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
