package me.rerere.rikkahub.ui.pages.setting

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import me.rerere.rikkahub.data.model.PythonWheel
import me.rerere.rikkahub.data.repository.PythonWheelInstaller
import me.rerere.rikkahub.data.repository.PythonWheelRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.components.ui.HapticSwitch
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.pages.setting.components.SettingGroupItem
import me.rerere.rikkahub.ui.pages.setting.components.SettingsGroup
import me.rerere.rikkahub.ui.theme.AppShapes
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingScriptsWorkspacePage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val toaster = LocalToaster.current
    val haptics = rememberPremiumHaptics()

    val wheelRepository = remember { PythonWheelRepository(context) }
    val wheelInstaller = remember { PythonWheelInstaller(context, wheelRepository) }

    var pythonWheels by remember { mutableStateOf<List<PythonWheel>>(emptyList()) }
    var deletingPythonWheel by remember { mutableStateOf<PythonWheel?>(null) }

    var showEnableScriptExecutionDialog by remember { mutableStateOf(false) }
    var showWheelImportRiskDialog by remember { mutableStateOf(false) }
    var showWheelImportResultDialog by remember { mutableStateOf(false) }
    var wheelImportReport by remember { mutableStateOf<PythonWheelInstaller.BatchResult?>(null) }
    var showWorkspaceFileToolsAllowAllDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        pythonWheels = withContext(Dispatchers.IO) {
            wheelRepository.listWheels().sortedByDescending { it.installedAt }
        }
    }

    val wheelImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val report = withContext(Dispatchers.IO) {
                wheelInstaller.importFromUris(uris)
            }
            wheelImportReport = report
            showWheelImportResultDialog = true
            pythonWheels = withContext(Dispatchers.IO) {
                wheelRepository.listWheels().sortedByDescending { it.installedAt }
            }

            val message = context.getString(
                R.string.python_wheels_import_summary,
                report.success.size,
                report.duplicated.size,
                report.failed.size,
            )
            toaster.show(message = message)
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
            val preservedKeys = old.conversationWorkspaceRoots.keys
            val preservedConversationWorkDirs = old.conversationWorkDirs.filterKeys { it in preservedKeys }
            old.copy(
                workspaceRootTreeUri = uri.toString(),
                conversationWorkDirs = preservedConversationWorkDirs,
            )
        }
        haptics.perform(HapticPattern.Success)
        toaster.show(message = context.getString(R.string.workspace_root_set_success))
    }

    fun clearWorkspaceRoot() {
        val rootUriString = settings.workspaceRootTreeUri?.trim().orEmpty()
        val usedByConversationRoots = settings.conversationWorkspaceRoots.values.any { it.trim() == rootUriString }
        if (rootUriString.isNotBlank() && !usedByConversationRoots) {
            val uri = runCatching { Uri.parse(rootUriString) }.getOrNull()
            if (uri != null) {
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }
            }
        }
        vm.updateSettings { old ->
            val preservedKeys = old.conversationWorkspaceRoots.keys
            val preservedConversationWorkDirs = old.conversationWorkDirs.filterKeys { it in preservedKeys }
            old.copy(
                workspaceRootTreeUri = null,
                conversationWorkDirs = preservedConversationWorkDirs,
            )
        }
        toaster.show(message = context.getString(R.string.workspace_root_reset_desc))
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            OneUITopAppBar(
                title = stringResource(R.string.skills_scripts_workspace_title),
                scrollBehavior = scrollBehavior,
                expandedTitleHorizontalPadding = 32.dp,
                navigationIcon = { BackButton() },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(
                top = 16.dp,
                bottom = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item(key = "scripts_group") {
                SettingsGroup(title = stringResource(R.string.skills_scripts_workspace_section_scripts)) {
                    SettingGroupItem(
                        title = stringResource(R.string.skill_scripts_title),
                        subtitle = stringResource(R.string.skill_scripts_description),
                        trailing = {
                            HapticSwitch(
                                checked = settings.enableSkillScriptExecution,
                                onCheckedChange = { checked ->
                                    if (checked && !settings.enableSkillScriptExecution) {
                                        showEnableScriptExecutionDialog = true
                                    } else {
                                        vm.updateSettings { old -> old.copy(enableSkillScriptExecution = checked) }
                                    }
                                },
                            )
                        }
                    )

                    SettingGroupItem(
                        title = stringResource(R.string.python_wheels_title),
                        subtitle = stringResource(R.string.python_wheels_description),
                        onClick = { showWheelImportRiskDialog = true }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            item(key = "installed_wheels_group") {
                SettingsGroup(title = stringResource(R.string.skills_scripts_workspace_installed_deps_title)) {
                    if (pythonWheels.isEmpty()) {
                        SettingGroupItem(
                            title = stringResource(R.string.python_wheels_empty),
                            onClick = null,
                        )
                    } else {
                        pythonWheels.forEach { wheel ->
                            WheelInstalledItem(
                                wheel = wheel,
                                onToggleEnabled = { checked ->
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            wheelRepository.setWheelEnabled(wheel.id, checked)
                                        }
                                        pythonWheels = withContext(Dispatchers.IO) {
                                            wheelRepository.listWheels().sortedByDescending { it.installedAt }
                                        }
                                    }
                                },
                                onDelete = {
                                    deletingPythonWheel = wheel
                                },
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            item(key = "workspace_group") {
                SettingsGroup(title = stringResource(R.string.skills_scripts_workspace_section_workspace)) {
                    SettingGroupItem(
                        title = stringResource(R.string.workspace_root_title),
                        subtitle = if (settings.workspaceRootTreeUri.isNullOrBlank()) {
                            stringResource(R.string.workspace_root_not_set)
                        } else {
                            stringResource(R.string.workspace_root_set)
                        },
                        onClick = { workspaceRootLauncher.launch(null) }
                    )

                    if (!settings.workspaceRootTreeUri.isNullOrBlank()) {
                        SettingGroupItem(
                            title = stringResource(R.string.workspace_root_reset_title),
                            subtitle = stringResource(R.string.workspace_root_reset_desc),
                            onClick = {
                                haptics.perform(HapticPattern.Thud)
                                clearWorkspaceRoot()
                            }
                        )
                    }

                    SettingGroupItem(
                        title = stringResource(R.string.workspace_file_tools_allow_all_title),
                        subtitle = stringResource(R.string.workspace_file_tools_allow_all_desc),
                        trailing = {
                            HapticSwitch(
                                checked = settings.workspaceFileToolsAllowAll,
                                onCheckedChange = { checked ->
                                    if (checked && !settings.workspaceFileToolsAllowAll) {
                                        showWorkspaceFileToolsAllowAllDialog = true
                                    } else {
                                        vm.updateSettings { old -> old.copy(workspaceFileToolsAllowAll = checked) }
                                    }
                                },
                            )
                        }
                    )
                }
            }
        }

        if (showEnableScriptExecutionDialog) {
            AlertDialog(
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

        deletingPythonWheel?.let { wheel ->
            AlertDialog(
                onDismissRequest = { deletingPythonWheel = null },
                title = { Text(stringResource(R.string.python_wheels_delete_title)) },
                text = {
                    val label = wheel.packageName?.takeIf { it.isNotBlank() } ?: wheel.displayName
                    Text(stringResource(R.string.python_wheels_delete_desc, label))
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            haptics.perform(HapticPattern.Thud)
                            val target = wheel
                            deletingPythonWheel = null
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { wheelRepository.deleteWheel(target.id) }
                                pythonWheels = withContext(Dispatchers.IO) {
                                    wheelRepository.listWheels().sortedByDescending { it.installedAt }
                                }
                                toaster.show(
                                    message = context.getString(
                                        if (ok) R.string.python_wheels_deleted_success else R.string.python_wheels_deleted_failed
                                    )
                                )
                            }
                        }
                    ) { Text(stringResource(R.string.delete)) }
                },
                dismissButton = {
                    TextButton(onClick = { deletingPythonWheel = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        if (showWheelImportRiskDialog) {
            AlertDialog(
                onDismissRequest = { showWheelImportRiskDialog = false },
                title = { Text(stringResource(R.string.python_wheels_risk_title)) },
                text = { Text(stringResource(R.string.python_wheels_risk_message)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            haptics.perform(HapticPattern.Thud)
                            showWheelImportRiskDialog = false
                            wheelImportLauncher.launch(
                                arrayOf(
                                    "application/zip",
                                    "application/x-zip-compressed",
                                    "application/octet-stream",
                                )
                            )
                        }
                    ) { Text(stringResource(R.string.python_wheels_import_action)) }
                },
                dismissButton = {
                    TextButton(onClick = { showWheelImportRiskDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        if (showWheelImportResultDialog) {
            val report = wheelImportReport
            val summary = context.getString(
                R.string.python_wheels_import_summary,
                report?.success?.size ?: 0,
                report?.duplicated?.size ?: 0,
                report?.failed?.size ?: 0,
            )
            val failedDetails = report?.failed
                ?.take(5)
                ?.joinToString(separator = "\n") { item ->
                    val name = item.displayName?.takeIf { it.isNotBlank() } ?: context.getString(R.string.unknown)
                    "$name: ${item.reason}"
                }
                .orEmpty()

            AlertDialog(
                onDismissRequest = { showWheelImportResultDialog = false },
                title = { Text(stringResource(R.string.python_wheels_import_result_title)) },
                text = {
                    Text(if (failedDetails.isBlank()) summary else "$summary\n\n$failedDetails")
                },
                confirmButton = {
                    TextButton(onClick = { showWheelImportResultDialog = false }) {
                        Text(stringResource(R.string.done))
                    }
                }
            )
        }

        if (showWorkspaceFileToolsAllowAllDialog) {
            AlertDialog(
                onDismissRequest = { showWorkspaceFileToolsAllowAllDialog = false },
                title = { Text(stringResource(R.string.workspace_file_tools_allow_all_risk_title)) },
                text = { Text(stringResource(R.string.workspace_file_tools_allow_all_risk_message)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            haptics.perform(HapticPattern.Thud)
                            vm.updateSettings { old -> old.copy(workspaceFileToolsAllowAll = true) }
                            showWorkspaceFileToolsAllowAllDialog = false
                            toaster.show(message = context.getString(R.string.workspace_file_tools_allow_all_enabled_success))
                        }
                    ) { Text(stringResource(R.string.workspace_file_tools_allow_all_enable_action)) }
                },
                dismissButton = {
                    TextButton(onClick = { showWorkspaceFileToolsAllowAllDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }
    }
}

@Composable
private fun WheelInstalledItem(
    wheel: PythonWheel,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val now = System.currentTimeMillis()
    val sizeText = wheel.fileSizeBytes?.takeIf { it > 0 }?.let { bytes ->
        runCatching { Formatter.formatShortFileSize(context, bytes) }.getOrNull()
    }
    val relativeTime = runCatching {
        DateUtils.getRelativeTimeSpanString(
            wheel.installedAt,
            now,
            DateUtils.MINUTE_IN_MILLIS,
        ).toString()
    }.getOrNull()
    val sysPathHint = wheel.sysPaths.size
        .takeIf { it > 0 }
        ?.let { count -> "sys.path +$count" }

    val hasPackageInfo = wheel.packageName?.isNotBlank() == true || wheel.packageVersion?.isNotBlank() == true
    val titleText = listOfNotNull(
        wheel.packageName?.takeIf { it.isNotBlank() },
        wheel.packageVersion?.takeIf { it.isNotBlank() },
    ).joinToString(" ").ifBlank { wheel.displayName }

    val metaParts = buildList {
        if (hasPackageInfo) add(wheel.displayName)
        sizeText?.let(::add)
        sysPathHint?.let(::add)
        relativeTime?.let(::add)
    }

    Surface(
        color = if (LocalDarkMode.current) {
            MaterialTheme.colorScheme.surfaceContainerLow
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = titleText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (metaParts.isNotEmpty()) {
                    Text(
                        text = metaParts.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (wheel.hasNativeCode) {
                    Text(
                        text = stringResource(R.string.python_wheels_native_code_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HapticSwitch(
                    checked = wheel.enabled,
                    onCheckedChange = onToggleEnabled,
                )
                HapticIconButton(
                    hapticPattern = HapticPattern.Thud,
                    onClick = onDelete,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.delete),
                    )
                }
            }
        }
    }
}

@Composable
private fun HapticIconButton(
    hapticPattern: HapticPattern = HapticPattern.Pop,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val haptics = rememberPremiumHaptics()
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1f,
        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "icon_button_scale",
    )

    IconButton(
        onClick = {
            haptics.perform(hapticPattern)
            onClick()
        },
        modifier = Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
        interactionSource = interactionSource,
    ) {
        content()
    }
}
