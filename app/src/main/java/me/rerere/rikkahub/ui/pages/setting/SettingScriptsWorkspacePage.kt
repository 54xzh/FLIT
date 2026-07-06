package me.rerere.rikkahub.ui.pages.setting

import android.content.Intent
import android.net.Uri
import android.os.Build
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.clearRememberedWorkspaceForNewChats
import me.rerere.rikkahub.data.model.PythonWheel
import me.rerere.rikkahub.data.repository.ChaquoPypiRepository
import me.rerere.rikkahub.data.repository.PythonPackageRequirement
import me.rerere.rikkahub.data.repository.PythonWheelInstaller
import me.rerere.rikkahub.data.repository.PythonWheelMetadataParser
import me.rerere.rikkahub.data.repository.PythonWheelRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.components.ui.HapticSwitch
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.setting.components.SettingGroupItem
import me.rerere.rikkahub.ui.pages.setting.components.SettingsGroup
import me.rerere.rikkahub.ui.theme.AppShapes
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.ui.context.LocalToaster
import okhttp3.OkHttpClient
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.io.File

@Composable
fun SettingScriptsWorkspacePage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val haptics = rememberPremiumHaptics()

    val okHttpClient: OkHttpClient = koinInject()
    val chaquoPypiRepository = remember(okHttpClient) { ChaquoPypiRepository(okHttpClient) }

    val wheelRepository = remember { PythonWheelRepository(context) }
    val wheelInstaller = remember { PythonWheelInstaller(context, wheelRepository) }

    var pythonWheels by remember { mutableStateOf<List<PythonWheel>>(emptyList()) }
    var deletingPythonWheel by remember { mutableStateOf<PythonWheel?>(null) }
    var dependencySheetWheel by remember { mutableStateOf<PythonWheel?>(null) }
    var dependencySheetLoading by remember { mutableStateOf(false) }
    var dependencySheetErrorMessage by remember { mutableStateOf<String?>(null) }
    var dependencySheetItems by remember { mutableStateOf<List<PythonPackageRequirement>>(emptyList()) }
    var dependencySheetNoticeMessage by remember { mutableStateOf<String?>(null) }
    var dependencySheetNoticeIsError by remember { mutableStateOf(false) }
    var installingDependencyName by remember { mutableStateOf<String?>(null) }

    var showEnableScriptExecutionDialog by remember { mutableStateOf(false) }
    var showWheelImportRiskDialog by remember { mutableStateOf(false) }
    var showWheelImportResultDialog by remember { mutableStateOf(false) }
    var wheelImportReport by remember { mutableStateOf<PythonWheelInstaller.BatchResult?>(null) }

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

                    SettingGroupItem(
                        title = stringResource(R.string.python_wheels_online_fetch_title),
                        subtitle = stringResource(R.string.python_wheels_online_fetch_description),
                        onClick = { navController.navigate(Screen.SettingChaquoPypi) }
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
                                onCheckDependencies = {
                                    dependencySheetWheel = wheel
                                    dependencySheetLoading = true
                                    dependencySheetErrorMessage = null
                                    dependencySheetItems = emptyList()
                                    dependencySheetNoticeMessage = null
                                    dependencySheetNoticeIsError = false
                                    val targetWheelId = wheel.id
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            runCatching {
                                                PythonWheelMetadataParser.readRequiresDistFromWheel(
                                                    unpackedDir = wheelRepository.wheelUnpackedDir(targetWheelId),
                                                    sysPaths = wheel.sysPaths,
                                                )
                                            }
                                        }
                                        if (dependencySheetWheel?.id != targetWheelId) return@launch
                                        dependencySheetItems = result.getOrDefault(emptyList())
                                        dependencySheetErrorMessage = result.exceptionOrNull()?.message
                                        dependencySheetLoading = false
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
        }

        dependencySheetWheel?.let { wheel ->
            WheelDependenciesBottomSheet(
                wheel = wheel,
                installedWheels = pythonWheels,
                loading = dependencySheetLoading,
                errorMessage = dependencySheetErrorMessage,
                requirements = dependencySheetItems,
                noticeMessage = dependencySheetNoticeMessage,
                noticeIsError = dependencySheetNoticeIsError,
                installingDependencyName = installingDependencyName,
                onDismiss = {
                    dependencySheetWheel = null
                    dependencySheetLoading = false
                    dependencySheetErrorMessage = null
                    dependencySheetItems = emptyList()
                    dependencySheetNoticeMessage = null
                    dependencySheetNoticeIsError = false
                },
                onInstall = { requirement ->
                    val normalized = requirement.normalizedName
                    if (!installingDependencyName.isNullOrBlank()) return@WheelDependenciesBottomSheet

                    installingDependencyName = normalized
                    scope.launch {
                        try {
                            val report = withContext(Dispatchers.IO) {
                                val bestWheel = chaquoPypiRepository.resolveBestWheel(
                                    packageName = requirement.name,
                                    version = requirement.exactVersionOrNull(),
                                    pythonVersionMajorMinor = CHAQUOPY_PYTHON_VERSION_MAJOR_MINOR,
                                    preferredAbis = Build.SUPPORTED_ABIS.toList(),
                                    sdkInt = Build.VERSION.SDK_INT,
                                )

                                val tempDir = File(context.cacheDir, "python/wheels/online-deps").apply { mkdirs() }
                                val tempFile = File(tempDir, bestWheel.fileName)
                                runCatching { if (tempFile.exists()) tempFile.delete() }
                                chaquoPypiRepository.downloadWheel(bestWheel.url, tempFile)
                                try {
                                    wheelInstaller.importFromFiles(listOf(tempFile))
                                } finally {
                                    runCatching { tempFile.delete() }
                                }
                            }

                            pythonWheels = withContext(Dispatchers.IO) {
                                wheelRepository.listWheels().sortedByDescending { it.installedAt }
                            }

                            val installedSuccessfully = report.success.isNotEmpty()
                            val message = when {
                                installedSuccessfully -> context.getString(
                                    R.string.python_wheels_dependency_install_success_restart_required,
                                    requirement.name,
                                )

                                report.duplicated.isNotEmpty() -> context.getString(
                                    R.string.python_wheels_dependency_install_duplicated,
                                    requirement.name,
                                )

                                report.failed.isNotEmpty() -> context.getString(
                                    R.string.python_wheels_dependency_install_failed,
                                    requirement.name,
                                )

                                else -> context.getString(
                                    R.string.python_wheels_dependency_install_failed,
                                    requirement.name,
                                )
                            }
                            dependencySheetNoticeMessage = message
                            dependencySheetNoticeIsError = !installedSuccessfully && report.failed.isNotEmpty()
                        } catch (e: Exception) {
                            dependencySheetNoticeMessage = e.message ?: context.getString(R.string.unknown)
                            dependencySheetNoticeIsError = true
                        } finally {
                            installingDependencyName = null
                        }
                    }
                },
            )
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
                                    "*/*",
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
    }
}

@Composable
private fun WheelInstalledItem(
    wheel: PythonWheel,
    onToggleEnabled: (Boolean) -> Unit,
    onCheckDependencies: () -> Unit,
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
                    onClick = onCheckDependencies,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AccountTree,
                        contentDescription = stringResource(R.string.python_wheels_dependency_check_action),
                    )
                }
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
private fun WheelDependenciesBottomSheet(
    wheel: PythonWheel,
    installedWheels: List<PythonWheel>,
    loading: Boolean,
    errorMessage: String?,
    requirements: List<PythonPackageRequirement>,
    noticeMessage: String?,
    noticeIsError: Boolean,
    installingDependencyName: String?,
    onDismiss: () -> Unit,
    onInstall: (PythonPackageRequirement) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val installedSet = remember(installedWheels) {
        installedWheels
            .mapNotNull { it.packageName?.takeIf { name -> name.isNotBlank() } }
            .map(PythonWheelMetadataParser::normalizePackageName)
            .toSet()
    }

    val wheelLabel = wheel.packageName?.takeIf { it.isNotBlank() } ?: wheel.displayName

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 24.dp, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "header") {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.python_wheels_dependency_check_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = wheelLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            noticeMessage?.takeIf { it.isNotBlank() }?.let { msg ->
                item(key = "notice") {
                    Surface(
                        color = if (noticeIsError) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                        contentColor = if (noticeIsError) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                        shape = AppShapes.CardMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
            }

            if (loading) {
                item(key = "loading") {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(
                            text = stringResource(R.string.python_wheels_dependency_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            errorMessage?.takeIf { it.isNotBlank() }?.let { msg ->
                item(key = "error") {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            if (!loading && errorMessage.isNullOrBlank()) {
                if (requirements.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = stringResource(R.string.python_wheels_dependency_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                } else {
                    item(key = "deps_group") {
                        SettingsGroup(title = stringResource(R.string.python_wheels_dependency_list_title)) {
                            val required = requirements.filterNot { it.isOptional }
                            val optional = requirements.filter { it.isOptional }

                            required.forEach { req ->
                                val installed = req.normalizedName in installedSet
                                val isInstalling = installingDependencyName == req.normalizedName
                                val isBusy = !installingDependencyName.isNullOrBlank() && !isInstalling
                                val status = if (installed) {
                                    stringResource(R.string.python_wheels_dependency_status_installed)
                                } else {
                                    stringResource(R.string.python_wheels_dependency_status_missing)
                                }

                                val title = buildString {
                                    append(req.name)
                                    req.extras?.takeIf { it.isNotBlank() }?.let { extras ->
                                        append("[").append(extras).append("]")
                                    }
                                }

                                val subtitle = buildString {
                                    append(status)
                                    req.versionSpec?.takeIf { it.isNotBlank() }?.let { spec ->
                                        append(" · ").append(spec)
                                    }
                                    req.marker?.takeIf { it.isNotBlank() }?.let { marker ->
                                        append(" · ").append(marker)
                                    }
                                }

                                SettingGroupItem(
                                    title = title,
                                    subtitle = subtitle,
                                    trailing = {
                                        when {
                                            installed -> {
                                                Text(
                                                    text = stringResource(R.string.python_wheels_dependency_status_installed),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }

                                            isInstalling -> {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(20.dp),
                                                    strokeWidth = 2.dp,
                                                )
                                            }

                                            isBusy -> {
                                                Icon(
                                                    imageVector = Icons.Rounded.Download,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(24.dp),
                                                )
                                            }

                                            else -> {
                                                HapticIconButton(
                                                    hapticPattern = HapticPattern.Thud,
                                                    onClick = { onInstall(req) },
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.Download,
                                                        contentDescription = stringResource(R.string.python_wheels_dependency_install_action),
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    onClick = null,
                                )
                            }

                            if (optional.isNotEmpty()) {
                                var optionalExpanded by remember(wheel.id) { mutableStateOf(false) }

                                SettingGroupItem(
                                    title = stringResource(
                                        R.string.python_wheels_dependency_optional_title,
                                        optional.size,
                                    ),
                                    subtitle = if (optionalExpanded) {
                                        stringResource(R.string.python_wheels_dependency_optional_hide)
                                    } else {
                                        stringResource(R.string.python_wheels_dependency_optional_show)
                                    },
                                    trailing = {
                                        Icon(
                                            imageVector = if (optionalExpanded) {
                                                Icons.Rounded.KeyboardArrowUp
                                            } else {
                                                Icons.Rounded.KeyboardArrowDown
                                            },
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    },
                                    onClick = { optionalExpanded = !optionalExpanded },
                                )

                                if (optionalExpanded) {
                                    optional.forEach { req ->
                                        val installed = req.normalizedName in installedSet
                                        val isInstalling = installingDependencyName == req.normalizedName
                                        val isBusy = !installingDependencyName.isNullOrBlank() && !isInstalling
                                        val status = if (installed) {
                                            stringResource(R.string.python_wheels_dependency_status_installed)
                                        } else {
                                            stringResource(R.string.python_wheels_dependency_status_missing)
                                        }

                                        val title = buildString {
                                            append(req.name)
                                            req.extras?.takeIf { it.isNotBlank() }?.let { extras ->
                                                append("[").append(extras).append("]")
                                            }
                                        }

                                        val subtitle = buildString {
                                            append(status)
                                            req.versionSpec?.takeIf { it.isNotBlank() }?.let { spec ->
                                                append(" · ").append(spec)
                                            }
                                            req.marker?.takeIf { it.isNotBlank() }?.let { marker ->
                                                append(" · ").append(marker)
                                            }
                                        }

                                        SettingGroupItem(
                                            title = title,
                                            subtitle = subtitle,
                                            trailing = {
                                                when {
                                                    installed -> {
                                                        Text(
                                                            text = stringResource(R.string.python_wheels_dependency_status_installed),
                                                            style = MaterialTheme.typography.labelMedium,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                    }

                                                    isInstalling -> {
                                                        CircularProgressIndicator(
                                                            modifier = Modifier.size(20.dp),
                                                            strokeWidth = 2.dp,
                                                        )
                                                    }

                                                    isBusy -> {
                                                        Icon(
                                                            imageVector = Icons.Rounded.Download,
                                                            contentDescription = null,
                                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            modifier = Modifier.size(24.dp),
                                                        )
                                                    }

                                                    else -> {
                                                        HapticIconButton(
                                                            hapticPattern = HapticPattern.Thud,
                                                            onClick = { onInstall(req) },
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Rounded.Download,
                                                                contentDescription = stringResource(R.string.python_wheels_dependency_install_action),
                                                            )
                                                        }
                                                    }
                                                }
                                            },
                                            onClick = null,
                                        )
                                    }
                                }
                            }
                        }
                    }
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

private const val CHAQUOPY_PYTHON_VERSION_MAJOR_MINOR = "3.13"
