package me.rerere.rikkahub.ui.pages.setting


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
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.files.SkillPaths
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
import org.koin.compose.koinInject
import java.io.File
import kotlin.uuid.Uuid

@Composable
fun SettingSkillsPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val conversationRepository = koinInject<me.rerere.rikkahub.data.repository.ConversationRepository>()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val toaster = LocalToaster.current
    val haptics = rememberPremiumHaptics()

    var deletingSkill by remember { mutableStateOf<Skill?>(null) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedSkillNames by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedFolderIds by remember { mutableStateOf<Set<Uuid>>(emptySet()) }

    var expandedFolderIds by remember { mutableStateOf<Set<Uuid>>(emptySet()) }
    var ungroupedExpanded by remember { mutableStateOf(false) }

    var showMoveSheet by remember { mutableStateOf(false) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }

    var creatingFolder by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var renamingFolder by remember { mutableStateOf<SkillFolder?>(null) }
    var renameFolderName by remember { mutableStateOf("") }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            when (val result = SkillZipImport.importFromUri(
                context = context,
                uri = uri,
                existingSkillNames = settings.skills.map { it.name }.toSet(),
            )) {
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

    fun requestImport() {
        haptics.perform(HapticPattern.Tick)
        importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
    }

    fun exitSelectionMode() {
        isSelectionMode = false
        selectedSkillNames = emptySet()
        selectedFolderIds = emptySet()
    }

    fun deleteSelectedItems(skillNames: Set<String>, folderIds: Set<Uuid>) {
        if (skillNames.isEmpty() && folderIds.isEmpty()) return
        scope.launch {
            // 删除后剩余的有效技能名，用于清理会话侧失效引用（每条会话单独存了一份 explicitSkillContexts）。
            val remainingSkillNames = settings.skills
                .filter { it.name !in skillNames }
                .map { it.name }
                .toSet()

            // 1) Update settings first (so UI/assistant state is consistent immediately).
            vm.updateSettings { old ->
                val deletedSkills = old.skills.filter { it.name in skillNames }
                val affectedFolderIds = deletedSkills.mapNotNull { it.folderId }.toSet()

                var remainingSkills = old.skills.filter { it.name !in skillNames }

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
                        assistant.copy(enabledSkills = assistant.enabledSkills - skillNames)
                    },
                )
            }

            // 2) Remove files on IO dispatcher.
            withContext(Dispatchers.IO) {
                val skillsRoot = File(context.filesDir, "skills")
                skillNames.forEach { name ->
                    runCatching {
                        SkillPaths.resolveSkillDir(skillsRoot, name)?.deleteRecursively()
                    }
                }

                // 3) 清理会话侧被删技能的失效引用（一致性保护：会话存的 explicitSkillContexts 不会随 settings 自动更新）。
                runCatching {
                    conversationRepository.removeInvalidSkillContexts(remainingSkillNames)
                }.getOrNull()
            }
        }
    }

    fun deleteSkill(skill: Skill) {
        deleteSelectedItems(skillNames = setOf(skill.name), folderIds = emptySet())
    }

    fun deleteSkills(skillNames: Set<String>) {
        deleteSelectedItems(skillNames = skillNames, folderIds = emptySet())
    }

    fun moveSkills(skillNames: Set<String>, folderId: Uuid?) {
        if (skillNames.isEmpty()) return
        scope.launch {
            vm.updateSettings { old ->
                old.copy(
                    skills = old.skills.map { skill ->
                        if (skill.name in skillNames) skill.copy(folderId = folderId) else skill
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
        if (selectedSkillNames.isNotEmpty()) {
            val validNames = settings.skills.map { it.name }.toSet()
            val cleaned = selectedSkillNames.intersect(validNames)
            if (cleaned != selectedSkillNames) {
                selectedSkillNames = cleaned
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
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            OneUITopAppBar(
                title = when {
                    isSelectionMode -> stringResource(
                        R.string.skills_selected_count,
                        selectedSkillNames.size + selectedFolderIds.size
                    )
                    else -> stringResource(R.string.skills_page_title)
                },
                scrollBehavior = scrollBehavior,
                expandedTitleHorizontalPadding = 32.dp,
                navigationIcon = {
                    when {
                        isSelectionMode -> {
                            HapticIconButton(onClick = { exitSelectionMode() }) {
                                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cancel))
                            }
                        }
                        else -> BackButton()
                    }
                },
                actions = {
                    if (isSelectionMode) {
                        val allSkillNames = settings.skills.map { it.name }.toSet()
                        val emptyFolderIds = settings.skillFolders
                            .filter { folder -> settings.skills.none { it.folderId == folder.id } }
                            .map { it.id }
                            .toSet()

                        val allSelected = (allSkillNames.isNotEmpty() || emptyFolderIds.isNotEmpty()) &&
                            selectedSkillNames.containsAll(allSkillNames) &&
                            selectedFolderIds.containsAll(emptyFolderIds)

                        HapticIconButton(
                            onClick = {
                                if (allSelected) {
                                    selectedSkillNames = emptySet()
                                    selectedFolderIds = emptySet()
                                } else {
                                    selectedSkillNames = allSkillNames
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
                                    selectedSkillNames = emptySet()
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
                val selectedCount = selectedSkillNames.size + selectedFolderIds.size
                val hasSkillSelection = selectedSkillNames.isNotEmpty()
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
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(
                    top = paddingValues.calculateTopPadding() + 16.dp,
                    bottom = paddingValues.calculateBottomPadding() + if (isSelectionMode) 16.dp else 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
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
                                    skillsInFolder.all { selectedSkillNames.contains(it.name) }
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
                                            val names = skillsInFolder.map { it.name }.toSet()
                                            selectedSkillNames = if (selected) {
                                                selectedSkillNames + names
                                            } else {
                                                selectedSkillNames - names
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
                                                isSelected = selectedSkillNames.contains(skill.name),
                                                onToggleSelected = { selected ->
                                                    selectedSkillNames = if (selected) {
                                                        selectedSkillNames + skill.name
                                                    } else {
                                                        selectedSkillNames - skill.name
                                                    }
                                                },
                                                onRequestDelete = { deletingSkill = skill },
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
                                val isGroupSelected = ungroupedSkills.all { selectedSkillNames.contains(it.name) }
                                ListSelectableItem(
                                    isSelected = isGroupSelected,
                                    onSelectChange = { selected ->
                                        val names = ungroupedSkills.map { it.name }.toSet()
                                        selectedSkillNames = if (selected) {
                                            selectedSkillNames + names
                                        } else {
                                            selectedSkillNames - names
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
                                            isSelected = selectedSkillNames.contains(skill.name),
                                            onToggleSelected = { selected ->
                                                selectedSkillNames = if (selected) {
                                                    selectedSkillNames + skill.name
                                                } else {
                                                    selectedSkillNames - skill.name
                                                }
                                            },
                                            onRequestDelete = { deletingSkill = skill },
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
                text = { Text(stringResource(R.string.skills_delete_desc, skill.name)) },
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
                title = { Text(stringResource(R.string.skills_delete_multiple_title, selectedSkillNames.size + selectedFolderIds.size)) },
                text = { Text(stringResource(R.string.skills_delete_multiple_desc, selectedSkillNames.size + selectedFolderIds.size)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val skillNames = selectedSkillNames
                            val folderIds = selectedFolderIds
                            showBatchDeleteDialog = false
                            exitSelectionMode()
                            deleteSelectedItems(skillNames = skillNames, folderIds = folderIds)
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
                                val ids = selectedSkillNames
                                showMoveSheet = false
                                exitSelectionMode()
                                moveSkills(ids, folderId = null)
                            }
                        )
                        settings.skillFolders.forEach { folder ->
                            SettingSheetItem(
                                title = folder.name.ifBlank { stringResource(R.string.skills_folder_unnamed) },
                                onClick = {
                                    val ids = selectedSkillNames
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
) {
    if (isSelectionMode) {
        ListSelectableItem(
            isSelected = isSelected,
            onSelectChange = onToggleSelected,
            modifier = Modifier.fillMaxWidth(),
        ) {
            SkillRowContent(
                skill = skill,
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
        )
    }
}

@Composable
private fun SkillRowContent(
    skill: Skill,
) {
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
    }
}

@Composable
private fun SkillCard(
    skill: Skill,
    position: ItemPosition,
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
