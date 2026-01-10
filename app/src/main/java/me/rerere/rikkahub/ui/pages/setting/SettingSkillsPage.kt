package me.rerere.rikkahub.ui.pages.setting

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Skill
import me.rerere.rikkahub.data.model.SkillFolder
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.components.ui.ItemPosition
import me.rerere.rikkahub.ui.components.ui.ListSelectableItem
import me.rerere.rikkahub.ui.components.ui.PhysicsSwipeToDelete
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.theme.AppShapes
import me.rerere.rikkahub.utils.SkillZipImport
import org.koin.androidx.compose.koinViewModel
import java.io.File
import kotlin.uuid.Uuid

@Composable
fun SettingSkillsPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val toaster = LocalToaster.current
    val haptics = rememberPremiumHaptics()

    var deletingSkill by remember { mutableStateOf<Skill?>(null) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedSkillIds by remember { mutableStateOf<Set<Uuid>>(emptySet()) }
    var selectedFolderIds by remember { mutableStateOf<Set<Uuid>>(emptySet()) }

    var expandedFolderIds by remember { mutableStateOf<Set<Uuid>>(emptySet()) }
    var ungroupedExpanded by remember { mutableStateOf(false) }

    var showMoveSheet by remember { mutableStateOf(false) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }

    var creatingFolder by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var renamingFolder by remember { mutableStateOf<SkillFolder?>(null) }
    var renameFolderName by remember { mutableStateOf("") }

    var showEnableScriptExecutionDialog by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            when (val result = SkillZipImport.importFromUri(context, uri)) {
                is SkillZipImport.ImportResult.Success -> {
                    vm.updateSettings { old ->
                        val installed = result.skills
                        if (installed.size <= 1) {
                            old.copy(skills = old.skills + installed)
                        } else {
                            val folderName = result.archiveName?.trim()?.takeIf { it.isNotBlank() }
                                ?: context.getString(R.string.skills_import_folder_default)

                            val existingFolder = old.skillFolders.firstOrNull { folder ->
                                folder.name.trim().equals(folderName, ignoreCase = true)
                            }

                            val folderId = existingFolder?.id ?: Uuid.random()
                            val updatedFolders = if (existingFolder != null) {
                                old.skillFolders
                            } else {
                                old.skillFolders + SkillFolder(id = folderId, name = folderName)
                            }

                            old.copy(
                                skillFolders = updatedFolders,
                                skills = old.skills + installed.map { it.copy(folderId = folderId) },
                            )
                        }
                    }
                    haptics.perform(HapticPattern.Success)
                    toaster.show(
                        message = context.getString(R.string.skills_import_success, result.skills.size),
                    )
                }

                is SkillZipImport.ImportResult.Error -> {
                    haptics.perform(HapticPattern.Error)
                    toaster.show(message = result.message)
                }
            }
        }
    }

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
        vm.updateSettings { old ->
            old.copy(
                workspaceRootTreeUri = uri.toString(),
                conversationWorkDirs = emptyMap(),
            )
        }
        haptics.perform(HapticPattern.Success)
        toaster.show(message = context.getString(R.string.workspace_root_set_success))
    }

    fun requestImport() {
        haptics.perform(HapticPattern.Tick)
        importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
    }

    fun exitSelectionMode() {
        isSelectionMode = false
        selectedSkillIds = emptySet()
        selectedFolderIds = emptySet()
    }

    fun deleteSelectedItems(skillIds: Set<Uuid>, folderIds: Set<Uuid>) {
        if (skillIds.isEmpty() && folderIds.isEmpty()) return
        scope.launch {
            // 1) Update settings first (so UI/assistant state is consistent immediately).
            vm.updateSettings { old ->
                val deletedSkills = old.skills.filter { it.id in skillIds }
                val affectedFolderIds = deletedSkills.mapNotNull { it.folderId }.toSet()

                var remainingSkills = old.skills.filter { it.id !in skillIds }

                // Safety: in case a folder got deleted while still referenced.
                if (folderIds.isNotEmpty()) {
                    remainingSkills = remainingSkills.map { skill ->
                        if (skill.folderId in folderIds) skill.copy(folderId = null) else skill
                    }
                }

                val cleanedFolders = old.skillFolders.filterNot { folder ->
                    folder.id in folderIds ||
                        (folder.id in affectedFolderIds && remainingSkills.none { it.folderId == folder.id })
                }

                old.copy(
                    skillFolders = cleanedFolders,
                    skills = remainingSkills,
                    assistants = old.assistants.map { assistant ->
                        assistant.copy(enabledSkillIds = assistant.enabledSkillIds - skillIds)
                    },
                )
            }

            // 2) Remove files on IO dispatcher.
            withContext(Dispatchers.IO) {
                skillIds.forEach { id ->
                    runCatching {
                        File(context.filesDir, "skills/$id").deleteRecursively()
                    }
                }
            }
        }
    }

    fun deleteSkill(skill: Skill) {
        deleteSelectedItems(skillIds = setOf(skill.id), folderIds = emptySet())
    }

    fun deleteSkills(skillIds: Set<Uuid>) {
        deleteSelectedItems(skillIds = skillIds, folderIds = emptySet())
    }

    fun moveSkills(skillIds: Set<Uuid>, folderId: Uuid?) {
        if (skillIds.isEmpty()) return
        scope.launch {
            vm.updateSettings { old ->
                old.copy(
                    skills = old.skills.map { skill ->
                        if (skill.id in skillIds) skill.copy(folderId = folderId) else skill
                    }
                )
            }
        }
    }

    fun isFolderNameUsed(name: String, excludeId: Uuid? = null): Boolean {
        val normalized = name.trim()
        if (normalized.isBlank()) return false
        return settings.skillFolders.any { folder ->
            folder.id != excludeId && folder.name.trim().equals(normalized, ignoreCase = true)
        }
    }

    LaunchedEffect(settings.skills, settings.skillFolders) {
        if (selectedSkillIds.isNotEmpty()) {
            val validIds = settings.skills.map { it.id }.toSet()
            val cleaned = selectedSkillIds.intersect(validIds)
            if (cleaned != selectedSkillIds) {
                selectedSkillIds = cleaned
            }
        }

        if (selectedFolderIds.isNotEmpty()) {
            val validIds = settings.skillFolders.map { it.id }.toSet()
            val cleaned = selectedFolderIds.intersect(validIds)
            if (cleaned != selectedFolderIds) {
                selectedFolderIds = cleaned
            }
        }

        if (isSelectionMode && settings.skills.isEmpty() && settings.skillFolders.isEmpty()) {
            exitSelectionMode()
        }
    }

    LaunchedEffect(settings.skillFolders) {
        if (expandedFolderIds.isEmpty()) return@LaunchedEffect
        val validIds = settings.skillFolders.map { it.id }.toSet()
        val cleaned = expandedFolderIds.intersect(validIds)
        if (cleaned != expandedFolderIds) {
            expandedFolderIds = cleaned
        }
    }

    Scaffold(
        topBar = {
            OneUITopAppBar(
                title = if (isSelectionMode) {
                    stringResource(R.string.skills_selected_count, selectedSkillIds.size + selectedFolderIds.size)
                } else {
                    stringResource(R.string.skills_page_title)
                },
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    if (isSelectionMode) {
                        HapticIconButton(onClick = { exitSelectionMode() }) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cancel))
                        }
                    } else {
                        BackButton()
                    }
                },
                actions = {
                    if (isSelectionMode) {
                        val allSkillIds = settings.skills.map { it.id }.toSet()
                        val emptyFolderIds = settings.skillFolders
                            .filter { folder -> settings.skills.none { it.folderId == folder.id } }
                            .map { it.id }
                            .toSet()

                        val allSelected = (allSkillIds.isNotEmpty() || emptyFolderIds.isNotEmpty()) &&
                            selectedSkillIds.containsAll(allSkillIds) &&
                            selectedFolderIds.containsAll(emptyFolderIds)

                        HapticIconButton(
                            onClick = {
                                if (allSelected) {
                                    selectedSkillIds = emptySet()
                                    selectedFolderIds = emptySet()
                                } else {
                                    selectedSkillIds = allSkillIds
                                    selectedFolderIds = emptyFolderIds
                                }
                            }
                        ) {
                            Icon(
                                Icons.Rounded.SelectAll,
                                contentDescription = stringResource(if (allSelected) R.string.deselect_all else R.string.select_all)
                            )
                        }
                    } else {
                        HapticIconButton(onClick = { creatingFolder = true }) {
                            Icon(
                                Icons.Rounded.CreateNewFolder,
                                contentDescription = stringResource(R.string.skills_action_create_folder)
                            )
                        }
                        HapticIconButton(
                            onClick = {
                                if (settings.skills.isNotEmpty() || settings.skillFolders.isNotEmpty()) {
                                    isSelectionMode = true
                                    selectedSkillIds = emptySet()
                                    selectedFolderIds = emptySet()
                                }
                            }
                        ) {
                            Icon(
                                Icons.Rounded.Edit,
                                contentDescription = stringResource(R.string.skills_action_batch_edit)
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (!isSelectionMode) {
                FloatingActionButton(
                    onClick = { requestImport() },
                    shape = AppShapes.CardLarge,
                ) {
                    Icon(Icons.Rounded.Download, contentDescription = stringResource(R.string.import_label))
                }
            }
        },
        bottomBar = {
            if (isSelectionMode) {
                val selectedCount = selectedSkillIds.size + selectedFolderIds.size
                val hasSkillSelection = selectedSkillIds.isNotEmpty()
                val hasAnySelection = selectedCount > 0
                BottomAppBar {
                    Text(
                        text = stringResource(R.string.skills_selected_count, selectedCount),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    FilledTonalButton(
                        enabled = hasSkillSelection,
                        onClick = {
                            haptics.perform(HapticPattern.Pop)
                            showMoveSheet = true
                        },
                        shape = AppShapes.ButtonPill,
                    ) {
                        Icon(
                            Icons.Rounded.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.skills_action_move))
                    }
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(
                        enabled = hasAnySelection,
                        onClick = {
                            haptics.perform(HapticPattern.Thud)
                            showBatchDeleteDialog = true
                        },
                        shape = AppShapes.ButtonPill,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            disabledContainerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                            disabledContentColor = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.5f),
                        ),
                    ) {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.delete))
                    }
                    Spacer(Modifier.width(12.dp))
                }
            }
        }
    ) { paddingValues ->
        if (settings.skills.isEmpty() && settings.skillFolders.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 24.dp)
                ) {
                    Icon(
                        Icons.Rounded.Terminal,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Text(
                        text = stringResource(R.string.skills_page_empty),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.skills_page_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = {
                            haptics.perform(HapticPattern.Pop)
                            workspaceRootLauncher.launch(null)
                        }
                    ) {
                        Text(stringResource(R.string.workspace_root_choose))
                    }
                    Spacer(Modifier.width(1.dp))
                }
            }
        } else {
            val foldersById = settings.skillFolders.associateBy { it.id }
            val ungroupedSkills = settings.skills.filter { skill ->
                skill.folderId == null || skill.folderId !in foldersById
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp)
                    .padding(
                        top = 16.dp,
                        bottom = if (isSelectionMode) 16.dp else 96.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item(key = "skill_scripts_settings") {
                    Card(
                        shape = AppShapes.CardLarge,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.skill_scripts_title),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        text = stringResource(R.string.skill_scripts_description),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = settings.enableSkillScriptExecution,
                                    onCheckedChange = { checked ->
                                        haptics.perform(HapticPattern.Pop)
                                        if (checked && !settings.enableSkillScriptExecution) {
                                            showEnableScriptExecutionDialog = true
                                        } else {
                                            vm.updateSettings { old -> old.copy(enableSkillScriptExecution = checked) }
                                        }
                                    },
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.workspace_root_title),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        text = if (settings.workspaceRootTreeUri.isNullOrBlank()) {
                                            stringResource(R.string.workspace_root_not_set)
                                        } else {
                                            stringResource(R.string.workspace_root_set)
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        haptics.perform(HapticPattern.Pop)
                                        workspaceRootLauncher.launch(null)
                                    }
                                ) {
                                    Text(stringResource(R.string.workspace_root_choose))
                                }
                                if (!settings.workspaceRootTreeUri.isNullOrBlank()) {
                                    TextButton(
                                        onClick = {
                                            haptics.perform(HapticPattern.Thud)
                                            val uri = runCatching { Uri.parse(settings.workspaceRootTreeUri) }.getOrNull()
                                            if (uri != null) {
                                                runCatching {
                                                    context.contentResolver.releasePersistableUriPermission(
                                                        uri,
                                                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                                    )
                                                }
                                            }
                                            vm.updateSettings { old ->
                                                old.copy(
                                                    workspaceRootTreeUri = null,
                                                    conversationWorkDirs = emptyMap(),
                                                )
                                            }
                                            toaster.show(message = context.getString(R.string.workspace_root_cleared))
                                        }
                                    ) {
                                        Text(stringResource(R.string.clear))
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                settings.skillFolders.forEachIndexed { folderIndex, folder ->
                    val skillsInFolder = settings.skills.filter { it.folderId == folder.id }

                    item(key = "folder_group_${folder.id}") {
                        val expanded = expandedFolderIds.contains(folder.id)
                        Column {
                            val toggleExpanded = {
                                expandedFolderIds = if (expandedFolderIds.contains(folder.id)) {
                                    expandedFolderIds - folder.id
                                } else {
                                    expandedFolderIds + folder.id
                                }
                            }

                            if (isSelectionMode) {
                                val isFolderSelected = if (skillsInFolder.isEmpty()) {
                                    selectedFolderIds.contains(folder.id)
                                } else {
                                    skillsInFolder.all { selectedSkillIds.contains(it.id) }
                                }

                                ListSelectableItem(
                                    isSelected = isFolderSelected,
                                    onSelectChange = { selected ->
                                        if (skillsInFolder.isEmpty()) {
                                            selectedFolderIds = if (selected) {
                                                selectedFolderIds + folder.id
                                            } else {
                                                selectedFolderIds - folder.id
                                            }
                                        } else {
                                            val ids = skillsInFolder.map { it.id }.toSet()
                                            selectedSkillIds = if (selected) {
                                                selectedSkillIds + ids
                                            } else {
                                                selectedSkillIds - ids
                                            }
                                        }
                                    },
                                ) {
                                    FolderHeader(
                                        title = folder.name.ifBlank { stringResource(R.string.skills_folder_unnamed) },
                                        count = skillsInFolder.size,
                                        expanded = expanded,
                                        onToggleExpanded = toggleExpanded,
                                        onRename = null,
                                        clickEnabled = false,
                                    )
                                }
                            } else {
                                FolderHeader(
                                    title = folder.name.ifBlank { stringResource(R.string.skills_folder_unnamed) },
                                    count = skillsInFolder.size,
                                    expanded = expanded,
                                    onToggleExpanded = toggleExpanded,
                                    onRename = {
                                        renamingFolder = folder
                                        renameFolderName = folder.name
                                    },
                                    clickEnabled = true,
                                )
                            }

                            AnimatedVisibility(
                                visible = expanded,
                                enter = expandVertically(
                                    animationSpec = spring(dampingRatio = 0.7f, stiffness = 300f)
                                ) + fadeIn(
                                    animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f)
                                ),
                                exit = shrinkVertically(
                                    animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f)
                                ) + fadeOut(),
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (skillsInFolder.isEmpty()) {
                                        EmptyFolderHint()
                                    } else {
                                        skillsInFolder.forEachIndexed { index, skill ->
                                            val position = when {
                                                skillsInFolder.size == 1 -> ItemPosition.ONLY
                                                index == 0 -> ItemPosition.FIRST
                                                index == skillsInFolder.lastIndex -> ItemPosition.LAST
                                                else -> ItemPosition.MIDDLE
                                            }

                                            SkillRow(
                                                skill = skill,
                                                position = position,
                                                isSelectionMode = isSelectionMode,
                                                isSelected = selectedSkillIds.contains(skill.id),
                                                onToggleSelected = { selected ->
                                                    selectedSkillIds = if (selected) {
                                                        selectedSkillIds + skill.id
                                                    } else {
                                                        selectedSkillIds - skill.id
                                                    }
                                                },
                                                onRequestDelete = { deletingSkill = skill },
                                                scriptEnabled = settings.enabledSkillScriptIds.contains(skill.id),
                                                scriptToggleEnabled = settings.enableSkillScriptExecution,
                                                onToggleScriptEnabled = { enabled ->
                                                    vm.updateSettings { old ->
                                                        val updated = if (enabled) {
                                                            old.enabledSkillScriptIds + skill.id
                                                        } else {
                                                            old.enabledSkillScriptIds - skill.id
                                                        }
                                                        old.copy(enabledSkillScriptIds = updated)
                                                    }
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    val hasUngroupedAfter = folderIndex == settings.skillFolders.lastIndex && ungroupedSkills.isNotEmpty()
                    if (folderIndex != settings.skillFolders.lastIndex || hasUngroupedAfter) {
                        item(key = "folder_spacer_${folder.id}") {
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                }

                if (ungroupedSkills.isNotEmpty()) {
                    item(key = "folder_group_ungrouped") {
                        Column {
                            if (isSelectionMode) {
                                val isGroupSelected = ungroupedSkills.all { selectedSkillIds.contains(it.id) }
                                ListSelectableItem(
                                    isSelected = isGroupSelected,
                                    onSelectChange = { selected ->
                                        val ids = ungroupedSkills.map { it.id }.toSet()
                                        selectedSkillIds = if (selected) {
                                            selectedSkillIds + ids
                                        } else {
                                            selectedSkillIds - ids
                                        }
                                    }
                                ) {
                                    FolderHeader(
                                        title = stringResource(R.string.skills_folder_ungrouped),
                                        count = ungroupedSkills.size,
                                        expanded = ungroupedExpanded,
                                        onToggleExpanded = { ungroupedExpanded = !ungroupedExpanded },
                                        onRename = null,
                                        clickEnabled = false,
                                    )
                                }
                            } else {
                                FolderHeader(
                                    title = stringResource(R.string.skills_folder_ungrouped),
                                    count = ungroupedSkills.size,
                                    expanded = ungroupedExpanded,
                                    onToggleExpanded = { ungroupedExpanded = !ungroupedExpanded },
                                    onRename = null,
                                    clickEnabled = true,
                                )
                            }

                            AnimatedVisibility(
                                visible = ungroupedExpanded,
                                enter = expandVertically(
                                    animationSpec = spring(dampingRatio = 0.7f, stiffness = 300f)
                                ) + fadeIn(
                                    animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f)
                                ),
                                exit = shrinkVertically(
                                    animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f)
                                ) + fadeOut(),
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    ungroupedSkills.forEachIndexed { index, skill ->
                                        val position = when {
                                            ungroupedSkills.size == 1 -> ItemPosition.ONLY
                                            index == 0 -> ItemPosition.FIRST
                                            index == ungroupedSkills.lastIndex -> ItemPosition.LAST
                                            else -> ItemPosition.MIDDLE
                                        }

                                        SkillRow(
                                            skill = skill,
                                            position = position,
                                            isSelectionMode = isSelectionMode,
                                            isSelected = selectedSkillIds.contains(skill.id),
                                            onToggleSelected = { selected ->
                                                selectedSkillIds = if (selected) {
                                                    selectedSkillIds + skill.id
                                                } else {
                                                    selectedSkillIds - skill.id
                                                }
                                            },
                                            onRequestDelete = { deletingSkill = skill },
                                            scriptEnabled = settings.enabledSkillScriptIds.contains(skill.id),
                                            scriptToggleEnabled = settings.enableSkillScriptExecution,
                                            onToggleScriptEnabled = { enabled ->
                                                vm.updateSettings { old ->
                                                    val updated = if (enabled) {
                                                        old.enabledSkillScriptIds + skill.id
                                                    } else {
                                                        old.enabledSkillScriptIds - skill.id
                                                    }
                                                    old.copy(enabledSkillScriptIds = updated)
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        deletingSkill?.let { skill ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { deletingSkill = null },
                title = { Text(stringResource(R.string.skills_delete_title)) },
                text = { Text(stringResource(R.string.skills_delete_desc, skill.name.ifBlank { skill.id.toString() })) },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            haptics.perform(HapticPattern.Thud)
                            deleteSkill(skill)
                            deletingSkill = null
                        }
                    ) { Text(stringResource(R.string.delete)) }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { deletingSkill = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        if (showBatchDeleteDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showBatchDeleteDialog = false },
                title = { Text(stringResource(R.string.skills_delete_multiple_title, selectedSkillIds.size + selectedFolderIds.size)) },
                text = { Text(stringResource(R.string.skills_delete_multiple_desc, selectedSkillIds.size + selectedFolderIds.size)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val skillIds = selectedSkillIds
                            val folderIds = selectedFolderIds
                            showBatchDeleteDialog = false
                            exitSelectionMode()
                            deleteSelectedItems(skillIds = skillIds, folderIds = folderIds)
                        }
                    ) { Text(stringResource(R.string.delete)) }
                },
                dismissButton = {
                    TextButton(onClick = { showBatchDeleteDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        if (showEnableScriptExecutionDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showEnableScriptExecutionDialog = false },
                title = { Text(stringResource(R.string.skill_scripts_risk_title)) },
                text = { Text(stringResource(R.string.skill_scripts_risk_message)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            haptics.perform(HapticPattern.Thud)
                            vm.updateSettings { old -> old.copy(enableSkillScriptExecution = true) }
                            showEnableScriptExecutionDialog = false
                            toaster.show(message = context.getString(R.string.skill_scripts_enabled_success))
                        }
                    ) { Text(stringResource(R.string.skill_scripts_enable_action)) }
                },
                dismissButton = {
                    TextButton(onClick = { showEnableScriptExecutionDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        if (creatingFolder) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = {
                    creatingFolder = false
                    newFolderName = ""
                },
                title = { Text(stringResource(R.string.skills_folder_create_title)) },
                text = {
                    OutlinedTextField(
                        value = newFolderName,
                        onValueChange = { newFolderName = it },
                        label = { Text(stringResource(R.string.skills_folder_name_label)) },
                        singleLine = true,
                        shape = AppShapes.InputField,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val name = newFolderName.trim()
                            when {
                                name.isBlank() -> {
                                    haptics.perform(HapticPattern.Error)
                                    toaster.show(message = context.getString(R.string.skills_folder_name_empty))
                                }
                                isFolderNameUsed(name) -> {
                                    haptics.perform(HapticPattern.Error)
                                    toaster.show(message = context.getString(R.string.skills_folder_name_exists, name))
                                }
                                else -> {
                                    haptics.perform(HapticPattern.Success)
                                    vm.updateSettings { old ->
                                        old.copy(skillFolders = old.skillFolders + SkillFolder(name = name))
                                    }
                                    creatingFolder = false
                                    newFolderName = ""
                                }
                            }
                        }
                    ) { Text(stringResource(R.string.skills_folder_create_action)) }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            creatingFolder = false
                            newFolderName = ""
                        }
                    ) { Text(stringResource(R.string.cancel)) }
                }
            )
        }

        renamingFolder?.let { folder ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = {
                    renamingFolder = null
                    renameFolderName = ""
                },
                title = { Text(stringResource(R.string.skills_folder_rename_title)) },
                text = {
                    OutlinedTextField(
                        value = renameFolderName,
                        onValueChange = { renameFolderName = it },
                        label = { Text(stringResource(R.string.skills_folder_name_label)) },
                        singleLine = true,
                        shape = AppShapes.InputField,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val name = renameFolderName.trim()
                            when {
                                name.isBlank() -> {
                                    haptics.perform(HapticPattern.Error)
                                    toaster.show(message = context.getString(R.string.skills_folder_name_empty))
                                }
                                isFolderNameUsed(name, excludeId = folder.id) -> {
                                    haptics.perform(HapticPattern.Error)
                                    toaster.show(message = context.getString(R.string.skills_folder_name_exists, name))
                                }
                                else -> {
                                    haptics.perform(HapticPattern.Success)
                                    vm.updateSettings { old ->
                                        old.copy(
                                            skillFolders = old.skillFolders.map { f ->
                                                if (f.id == folder.id) f.copy(name = name) else f
                                            }
                                        )
                                    }
                                    renamingFolder = null
                                    renameFolderName = ""
                                }
                            }
                        }
                    ) { Text(stringResource(R.string.skills_folder_rename_action)) }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            renamingFolder = null
                            renameFolderName = ""
                        }
                    ) { Text(stringResource(R.string.cancel)) }
                }
            )
        }

        if (showMoveSheet) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { showMoveSheet = false },
                sheetState = sheetState,
                shape = AppShapes.BottomSheet,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.skills_move_to_folder_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(AppShapes.CardMedium),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        SettingSheetItem(
                            title = stringResource(R.string.skills_folder_ungrouped),
                            onClick = {
                                val ids = selectedSkillIds
                                showMoveSheet = false
                                exitSelectionMode()
                                moveSkills(ids, folderId = null)
                            }
                        )
                        settings.skillFolders.forEach { folder ->
                            SettingSheetItem(
                                title = folder.name.ifBlank { stringResource(R.string.skills_folder_unnamed) },
                                onClick = {
                                    val ids = selectedSkillIds
                                    showMoveSheet = false
                                    exitSelectionMode()
                                    moveSkills(ids, folderId = folder.id)
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun SkillRow(
    skill: Skill,
    position: ItemPosition,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelected: (Boolean) -> Unit,
    onRequestDelete: () -> Unit,
    scriptEnabled: Boolean,
    scriptToggleEnabled: Boolean,
    onToggleScriptEnabled: ((Boolean) -> Unit)?,
) {
    if (isSelectionMode) {
        ListSelectableItem(
            isSelected = isSelected,
            onSelectChange = onToggleSelected,
            modifier = Modifier.fillMaxWidth(),
        ) {
            SkillRowContent(
                skill = skill,
                scriptEnabled = scriptEnabled,
                scriptToggleEnabled = false,
                onToggleScriptEnabled = null,
            )
        }
        return
    }

    PhysicsSwipeToDelete(
        position = position,
        deleteEnabled = true,
        onDelete = onRequestDelete,
        modifier = Modifier.fillMaxWidth()
    ) {
        SkillCard(
            skill = skill,
            position = position,
            scriptEnabled = scriptEnabled,
            scriptToggleEnabled = scriptToggleEnabled,
            onToggleScriptEnabled = onToggleScriptEnabled,
        )
    }
}

@Composable
private fun SkillRowContent(
    skill: Skill,
    scriptEnabled: Boolean,
    scriptToggleEnabled: Boolean,
    onToggleScriptEnabled: ((Boolean) -> Unit)?,
) {
    val haptics = rememberPremiumHaptics()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = skill.name.ifBlank { stringResource(R.string.skills_unnamed) },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = skill.description.trim().ifBlank { stringResource(R.string.skills_no_description) },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = skill.id.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
        )

        if (onToggleScriptEnabled != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.skill_scripts_skill_toggle_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Switch(
                    checked = scriptEnabled,
                    onCheckedChange = { checked ->
                        haptics.perform(HapticPattern.Pop)
                        onToggleScriptEnabled(checked)
                    },
                    enabled = scriptToggleEnabled,
                )
            }
        }
    }
}

@Composable
private fun SkillCard(
    skill: Skill,
    position: ItemPosition,
    scriptEnabled: Boolean,
    scriptToggleEnabled: Boolean,
    onToggleScriptEnabled: ((Boolean) -> Unit)?,
) {
    val cornerRadius = 28.dp
    val smallCorner = 8.dp
    val shape = when (position) {
        ItemPosition.ONLY -> androidx.compose.foundation.shape.RoundedCornerShape(cornerRadius)
        ItemPosition.FIRST -> androidx.compose.foundation.shape.RoundedCornerShape(
            topStart = cornerRadius, topEnd = cornerRadius,
            bottomStart = smallCorner, bottomEnd = smallCorner
        )
        ItemPosition.MIDDLE -> androidx.compose.foundation.shape.RoundedCornerShape(smallCorner)
        ItemPosition.LAST -> androidx.compose.foundation.shape.RoundedCornerShape(
            topStart = smallCorner, topEnd = smallCorner,
            bottomStart = cornerRadius, bottomEnd = cornerRadius
        )
    }

    androidx.compose.material3.Card(
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        SkillRowContent(
            skill = skill,
            scriptEnabled = scriptEnabled,
            scriptToggleEnabled = scriptToggleEnabled,
            onToggleScriptEnabled = onToggleScriptEnabled,
        )
    }
}

@Composable
private fun FolderHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onRename: (() -> Unit)?,
    clickEnabled: Boolean,
) {
    val haptics = rememberPremiumHaptics()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed && clickEnabled) 0.98f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "folder_header_scale"
    )

    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "folder_header_arrow_rotation"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (clickEnabled) {
                    Modifier
                        .clip(AppShapes.ListItem)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                        ) {
                            haptics.perform(HapticPattern.Pop)
                            onToggleExpanded()
                        }
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onRename != null) {
            HapticIconButton(onClick = onRename) {
                Icon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = stringResource(R.string.edit),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HapticIconButton(onClick = onToggleExpanded) {
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = stringResource(if (expanded) R.string.a11y_collapse else R.string.a11y_expand),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer {
                        rotationZ = arrowRotation
                    }
            )
        }
    }
}

@Composable
private fun EmptyFolderHint() {
    Text(
        text = stringResource(R.string.skills_folder_empty),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 30.dp, top = 2.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingSheetItem(
    title: String,
    onClick: () -> Unit,
) {
    val haptics = rememberPremiumHaptics()
    Card(
        onClick = {
            haptics.perform(HapticPattern.Pop)
            onClick()
        },
        shape = AppShapes.ListItem,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        )
    }
}

@Composable
private fun HapticIconButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val haptics = rememberPremiumHaptics()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "icon_button_scale"
    )

    IconButton(
        onClick = {
            haptics.perform(HapticPattern.Pop)
            onClick()
        },
        modifier = Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
        interactionSource = interactionSource
    ) {
        content()
    }
}
