package me.rerere.rikkahub.ui.pages.backup

import me.rerere.rikkahub.ui.theme.LocalDarkMode

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import me.rerere.rikkahub.ui.components.ui.ToastType
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.ObjectStorageConfig
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.sync.ObjectStorageBackupItem
import me.rerere.rikkahub.data.sync.WebDavBackupItem
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.HapticSwitch
import me.rerere.rikkahub.ui.components.ui.StickyHeader
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.appLanguageUpdateGate
import me.rerere.rikkahub.utils.fileSizeToString
import me.rerere.rikkahub.utils.onError
import me.rerere.rikkahub.utils.onLoading
import me.rerere.rikkahub.utils.onSuccess
import me.rerere.rikkahub.utils.toLocalDateTime
import org.koin.androidx.compose.koinViewModel
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun BackupPage(vm: BackupVM = koinViewModel()) {
    val pagerState = rememberPagerState { 3 }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val pendingRestoreResult by vm.pendingRestoreResult.collectAsStateWithLifecycle()
    val languageRestartRequired by appLanguageUpdateGate.isRestartRequired.collectAsStateWithLifecycle()
    var showBackupLogs by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.backup_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    IconButton(
                        onClick = { showBackupLogs = true }
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.History,
                            contentDescription = stringResource(R.string.backup_page_backup_logs),
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = pagerState.currentPage == 0,
                    icon = {
                        Icon(Icons.Rounded.CloudSync, null)
                    },
                    label = {
                        Text(stringResource(R.string.backup_page_webdav_backup))
                    },
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(0) }
                    },
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 1,
                    icon = {
                        Icon(Icons.Rounded.Cloud, null)
                    },
                    label = {
                        Text(stringResource(R.string.backup_page_object_storage_backup))
                    },
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(1) }
                    },
                )
                NavigationBarItem(
                    selected = pagerState.currentPage == 2,
                    icon = {
                        Icon(Icons.Rounded.Folder, null)
                    },
                    label = {
                        Text(stringResource(R.string.backup_page_import_export))
                    },
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(2) }
                    },
                )
            }
        }
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = it
        ) { page ->
            when (page) {
                0 -> {
                    WebDavPage(vm)
                }

                1 -> {
                    ObjectStoragePage(vm)
                }

                2 -> {
                    ImportExportPage(vm)
                }
            }
        }
    }

    if (showBackupLogs) {
        ModalBottomSheet(
            onDismissRequest = {
                showBackupLogs = false
            },
            sheetState = rememberModalBottomSheetState(
                skipPartiallyExpanded = true
            ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f)
            ) {
                BackupLogsPage(vm)
            }
        }
    }

    if (pendingRestoreResult != null || languageRestartRequired) {
        BackupDialog(
            result = pendingRestoreResult,
            onConfirm = { vm.restartApp(context) },
        )
    }
}

@Composable
private fun WebDavPage(
    vm: BackupVM
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val webDavConfig = settings.webDavConfig
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showBackupFiles by remember { mutableStateOf(false) }
    var restoringItemId by remember { mutableStateOf<String?>(null) }
    var isBackingUp by remember { mutableStateOf(false) }

    fun updateWebDavConfig(newConfig: WebDavConfig) {
        vm.updateSettings(settings.copy(webDavConfig = newConfig))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(
            shape = me.rerere.rikkahub.ui.theme.AppShapes.CardLarge,
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = if (me.rerere.rikkahub.ui.theme.LocalDarkMode.current) androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow else androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FormItem(
                    label = { Text(stringResource(R.string.backup_page_webdav_server_address)) }
                ) {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = webDavConfig.url,
                        onValueChange = { updateWebDavConfig(webDavConfig.copy(url = it.trim())) },
                       // Placeholder = { Text("https://example.com/dav") },
                        singleLine = true
                    )
                }
                FormItem(
                    label = { Text(stringResource(R.string.backup_page_username)) }
                ) {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = webDavConfig.username,
                        onValueChange = {
                            updateWebDavConfig(
                                webDavConfig.copy(
                                    username = it.trim()
                                )
                            )
                        },
                        singleLine = true
                    )
                }
                FormItem(
                    label = { Text(stringResource(R.string.backup_page_password)) }
                ) {
                    var passwordVisible by remember { mutableStateOf(false) }
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = webDavConfig.password,
                        onValueChange = { updateWebDavConfig(webDavConfig.copy(password = it)) },
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            val image = if (passwordVisible)
                                Icons.Rounded.VisibilityOff
                            else
                                Icons.Rounded.Visibility
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(imageVector = image, null)
                            }
                        },
                        singleLine = true
                    )
                }
                FormItem(
                    label = { Text(stringResource(R.string.backup_page_path)) }
                ) {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = webDavConfig.path,
                        onValueChange = { updateWebDavConfig(webDavConfig.copy(path = it.trim())) },
                        singleLine = true
                    )
                }
            }
        }

        Card(
            shape = me.rerere.rikkahub.ui.theme.AppShapes.CardLarge,
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = if (me.rerere.rikkahub.ui.theme.LocalDarkMode.current) androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow else androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            FormItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                label = {
                    Text(stringResource(R.string.backup_page_backup_items))
                }
            ) {
                MultiChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    WebDavConfig.BackupItem.entries.forEachIndexed { index, item ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = WebDavConfig.BackupItem.entries.size
                            ),
                            onCheckedChange = {
                                val newItems = if (it) {
                                    webDavConfig.items + item
                                } else {
                                    webDavConfig.items - item
                                }
                                updateWebDavConfig(webDavConfig.copy(items = newItems))
                            },
                            checked = item in webDavConfig.items
                        ) {
                            Text(
                                when (item) {
                                    WebDavConfig.BackupItem.DATABASE -> stringResource(R.string.backup_page_chat_records)
                                    WebDavConfig.BackupItem.FILES -> stringResource(R.string.backup_page_files)
                                }
                            )
                        }
                    }
                }
            }
        }

        Card(
            shape = me.rerere.rikkahub.ui.theme.AppShapes.CardLarge,
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = if (me.rerere.rikkahub.ui.theme.LocalDarkMode.current) androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow else androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FormItem(
                    label = { Text(stringResource(R.string.backup_page_auto_backup)) },
                    description = { Text(stringResource(R.string.backup_page_auto_backup_desc)) },
                    tail = {
                        HapticSwitch(
                            checked = webDavConfig.autoEnabled,
                            onCheckedChange = { enabled ->
                                updateWebDavConfig(webDavConfig.copy(autoEnabled = enabled))
                            }
                        )
                    }
                )

                if (webDavConfig.autoEnabled) {
                    FormItem(
                        label = { Text(stringResource(R.string.backup_page_auto_interval_days)) },
                        description = {
                            Text(
                                stringResource(
                                    R.string.backup_page_auto_interval_days_desc,
                                    webDavConfig.autoIntervalDays
                                )
                            )
                        },
                    ) {
                        var intervalDaysText by remember(webDavConfig.autoIntervalDays) {
                            mutableStateOf(webDavConfig.autoIntervalDays.toString())
                        }

                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = intervalDaysText,
                            onValueChange = { value ->
                                val filtered = value.filter { it.isDigit() }
                                val parsed = filtered.toIntOrNull()
                                val safe = parsed?.coerceAtLeast(1)
                                intervalDaysText = (safe ?: filtered).toString()
                                if (safe != null && safe != webDavConfig.autoIntervalDays) {
                                    updateWebDavConfig(webDavConfig.copy(autoIntervalDays = safe))
                                }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }

                    FormItem(
                        label = { Text(stringResource(R.string.backup_page_auto_max_count)) },
                        description = {
                            Text(
                                if (webDavConfig.autoMaxCount <= 0) {
                                    stringResource(R.string.backup_page_auto_max_count_desc_unlimited)
                                } else {
                                    stringResource(
                                        R.string.backup_page_auto_max_count_desc,
                                        webDavConfig.autoMaxCount
                                    )
                                }
                            )
                        },
                    ) {
                        var maxCountText by remember(webDavConfig.autoMaxCount) {
                            mutableStateOf(webDavConfig.autoMaxCount.toString())
                        }

                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = maxCountText,
                            onValueChange = { value ->
                                val filtered = value.filter { it.isDigit() }
                                val parsed = filtered.toIntOrNull()
                                val safe = parsed?.coerceAtLeast(0)
                                maxCountText = (safe ?: filtered).toString()
                                if (safe != null && safe != webDavConfig.autoMaxCount) {
                                    updateWebDavConfig(webDavConfig.copy(autoMaxCount = safe))
                                }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                }

                val lastSuccessText = webDavConfig.lastAutoSuccessAt
                    ?.let { ts ->
                        Instant.ofEpochMilli(ts)
                            .toLocalDateTime()
                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                    }
                    ?: stringResource(R.string.backup_page_auto_never)

                Text(
                    text = stringResource(R.string.backup_page_auto_last_success, lastSuccessText),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
        ) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        try {
                            vm.testWebDav()
                            toaster.show(
                                context.getString(R.string.backup_page_connection_success),
                                type = ToastType.Success
                            )
                        } catch (e: Exception) {
                            e.printStackTrace()
                            toaster.show(
                                context.getString(
                                    R.string.backup_page_connection_failed,
                                    e.message ?: ""
                                ), type = ToastType.Error
                            )
                        }
                    }
                }
            ) {
                Text(stringResource(R.string.backup_page_test_connection))
            }
            OutlinedButton(
                onClick = {
                    showBackupFiles = true
                }
            ) {
                Text(stringResource(R.string.backup_page_restore))
            }

            Button(
                onClick = {
                    scope.launch {
                        isBackingUp = true
                        runCatching {
                            vm.backup()
                            vm.loadBackupFileItems()
                            toaster.show(
                                context.getString(R.string.backup_page_backup_success),
                                type = ToastType.Success
                            )
                        }.onFailure {
                            it.printStackTrace()
                            toaster.show(
                                it.message ?: context.getString(R.string.backup_page_unknown_error),
                                type = ToastType.Error
                            )
                        }
                        isBackingUp = false
                    }
                },
                enabled = !isBackingUp
            ) {
                if (isBackingUp) {
                    CircularWavyProgressIndicator(
                        modifier = Modifier.size(18.dp)
                    )
                } else {
                    Icon(Icons.Rounded.CloudUpload, null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(if (isBackingUp) stringResource(R.string.backup_page_backing_up) else stringResource(R.string.backup_page_backup_now))
            }
        }
    }

    if (showBackupFiles) {
        ModalBottomSheet(
            onDismissRequest = {
                showBackupFiles = false
            },
            sheetState = rememberModalBottomSheetState(
                skipPartiallyExpanded = true
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.8f)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    stringResource(R.string.backup_page_webdav_backup_files),
                    modifier = Modifier.fillMaxWidth()
                )
                val backupItems by vm.webDavBackupItems.collectAsStateWithLifecycle()
                backupItems.onSuccess {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(it) { item ->
                            BackupItemCard(
                                item = item,
                                isRestoring = restoringItemId == item.displayName,
                                onDelete = {
                                    scope.launch {
                                        runCatching {
                                            vm.deleteWebDavBackupFile(item)
                                            toaster.show(
                                                context.getString(R.string.backup_page_delete_success),
                                                type = ToastType.Success
                                            )
                                            vm.loadBackupFileItems()
                                        }.onFailure { err ->
                                            err.printStackTrace()
                                            toaster.show(
                                                context.getString(
                                                    R.string.backup_page_delete_failed,
                                                    err.message ?: ""
                                                ),
                                                type = ToastType.Error
                                            )
                                        }
                                    }
                                },
                                onRestore = { item ->
                                    scope.launch {
                                        restoringItemId = item.displayName
                                        runCatching {
                                            vm.restore(item = item)
                                            toaster.show(
                                                context.getString(R.string.backup_page_restore_success),
                                                type = ToastType.Success
                                            )
                                            showBackupFiles = false
                                        }.onFailure { err ->
                                            err.printStackTrace()
                                            toaster.show(
                                                context.getString(
                                                    R.string.backup_page_restore_failed,
                                                    err.message ?: ""
                                                ),
                                                type = ToastType.Error
                                            )
                                        }
                                        restoringItemId = null
                                    }
                                },
                            )
                        }
                    }
                }.onError {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.backup_page_loading_failed, it.message ?: ""),
                            color = Color.Red
                        )
                    }
                }.onLoading {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularWavyProgressIndicator()
                    }
                }
            }
        }
    }

}

@Composable
private fun ObjectStoragePage(
    vm: BackupVM,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val config = settings.objectStorageConfig
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showBackupFiles by remember { mutableStateOf(false) }
    var restoringKey by remember { mutableStateOf<String?>(null) }
    var isBackingUp by remember { mutableStateOf(false) }

    fun updateConfig(newConfig: ObjectStorageConfig) {
        vm.updateSettings(settings.copy(objectStorageConfig = newConfig))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(
            shape = me.rerere.rikkahub.ui.theme.AppShapes.CardLarge,
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = if (me.rerere.rikkahub.ui.theme.LocalDarkMode.current) androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow else androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FormItem(
                    label = { Text(stringResource(R.string.backup_page_object_storage_endpoint)) }
                ) {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = config.endpoint,
                        onValueChange = { updateConfig(config.copy(endpoint = it.trim())) },
                        singleLine = true
                    )
                }

                FormItem(
                    label = { Text(stringResource(R.string.backup_page_object_storage_access_key_id)) }
                ) {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = config.accessKeyId,
                        onValueChange = { updateConfig(config.copy(accessKeyId = it.trim())) },
                        singleLine = true
                    )
                }

                FormItem(
                    label = { Text(stringResource(R.string.backup_page_object_storage_secret_access_key)) }
                ) {
                    var secretVisible by remember { mutableStateOf(false) }
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = config.secretAccessKey,
                        onValueChange = { updateConfig(config.copy(secretAccessKey = it)) },
                        visualTransformation = if (secretVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            val image = if (secretVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility
                            IconButton(onClick = { secretVisible = !secretVisible }) {
                                Icon(imageVector = image, null)
                            }
                        },
                        singleLine = true
                    )
                }

                FormItem(
                    label = { Text(stringResource(R.string.backup_page_object_storage_bucket)) }
                ) {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = config.bucket,
                        onValueChange = { updateConfig(config.copy(bucket = it.trim())) },
                        singleLine = true
                    )
                }

                FormItem(
                    label = { Text(stringResource(R.string.backup_page_object_storage_region)) }
                ) {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = config.region,
                        onValueChange = { updateConfig(config.copy(region = it.trim())) },
                        singleLine = true
                    )
                }
            }
        }

        Card(
            shape = me.rerere.rikkahub.ui.theme.AppShapes.CardLarge,
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = if (me.rerere.rikkahub.ui.theme.LocalDarkMode.current) androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow else androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            FormItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                label = {
                    Text(stringResource(R.string.backup_page_backup_items))
                }
            ) {
                MultiChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    WebDavConfig.BackupItem.entries.forEachIndexed { index, item ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = WebDavConfig.BackupItem.entries.size
                            ),
                            onCheckedChange = {
                                val newItems = if (it) {
                                    config.items + item
                                } else {
                                    config.items - item
                                }
                                updateConfig(config.copy(items = newItems))
                            },
                            checked = item in config.items
                        ) {
                            Text(
                                when (item) {
                                    WebDavConfig.BackupItem.DATABASE -> stringResource(R.string.backup_page_chat_records)
                                    WebDavConfig.BackupItem.FILES -> stringResource(R.string.backup_page_files)
                                }
                            )
                        }
                    }
                }
            }
        }

        Card(
            shape = me.rerere.rikkahub.ui.theme.AppShapes.CardLarge,
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = if (me.rerere.rikkahub.ui.theme.LocalDarkMode.current) androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow else androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FormItem(
                    label = { Text(stringResource(R.string.backup_page_auto_backup)) },
                    description = { Text(stringResource(R.string.backup_page_auto_backup_desc)) },
                    tail = {
                        HapticSwitch(
                            checked = config.autoEnabled,
                            onCheckedChange = { enabled ->
                                updateConfig(config.copy(autoEnabled = enabled))
                            }
                        )
                    }
                )

                if (config.autoEnabled) {
                    FormItem(
                        label = { Text(stringResource(R.string.backup_page_auto_interval_days)) },
                        description = {
                            Text(
                                stringResource(
                                    R.string.backup_page_auto_interval_days_desc,
                                    config.autoIntervalDays
                                )
                            )
                        },
                    ) {
                        var intervalDaysText by remember(config.autoIntervalDays) {
                            mutableStateOf(config.autoIntervalDays.toString())
                        }

                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = intervalDaysText,
                            onValueChange = { value ->
                                val filtered = value.filter { it.isDigit() }
                                val parsed = filtered.toIntOrNull()
                                val safe = parsed?.coerceAtLeast(1)
                                intervalDaysText = (safe ?: filtered).toString()
                                if (safe != null && safe != config.autoIntervalDays) {
                                    updateConfig(config.copy(autoIntervalDays = safe))
                                }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }

                    FormItem(
                        label = { Text(stringResource(R.string.backup_page_auto_max_count)) },
                        description = {
                            Text(
                                if (config.autoMaxCount <= 0) {
                                    stringResource(R.string.backup_page_auto_max_count_desc_unlimited)
                                } else {
                                    stringResource(
                                        R.string.backup_page_auto_max_count_desc,
                                        config.autoMaxCount
                                    )
                                }
                            )
                        },
                    ) {
                        var maxCountText by remember(config.autoMaxCount) {
                            mutableStateOf(config.autoMaxCount.toString())
                        }

                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = maxCountText,
                            onValueChange = { value ->
                                val filtered = value.filter { it.isDigit() }
                                val parsed = filtered.toIntOrNull()
                                val safe = parsed?.coerceAtLeast(0)
                                maxCountText = (safe ?: filtered).toString()
                                if (safe != null && safe != config.autoMaxCount) {
                                    updateConfig(config.copy(autoMaxCount = safe))
                                }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                }

                val lastSuccessText = config.lastAutoSuccessAt
                    ?.let { ts ->
                        Instant.ofEpochMilli(ts)
                            .toLocalDateTime()
                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                    }
                    ?: stringResource(R.string.backup_page_auto_never)

                Text(
                    text = stringResource(R.string.backup_page_auto_last_success, lastSuccessText),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
        ) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        try {
                            vm.testObjectStorage()
                            toaster.show(
                                context.getString(R.string.backup_page_connection_success),
                                type = ToastType.Success
                            )
                        } catch (e: Exception) {
                            e.printStackTrace()
                            toaster.show(
                                context.getString(
                                    R.string.backup_page_connection_failed,
                                    e.message ?: ""
                                ),
                                type = ToastType.Error
                            )
                        }
                    }
                }
            ) {
                Text(stringResource(R.string.backup_page_test_connection))
            }

            OutlinedButton(
                onClick = {
                    vm.loadObjectStorageBackupFileItems()
                    showBackupFiles = true
                }
            ) {
                Text(stringResource(R.string.backup_page_restore))
            }

            Button(
                onClick = {
                    scope.launch {
                        isBackingUp = true
                        runCatching {
                            vm.backupToObjectStorage()
                            vm.loadObjectStorageBackupFileItems()
                            toaster.show(
                                context.getString(R.string.backup_page_backup_success),
                                type = ToastType.Success
                            )
                        }.onFailure {
                            it.printStackTrace()
                            toaster.show(
                                it.message ?: context.getString(R.string.backup_page_unknown_error),
                                type = ToastType.Error
                            )
                        }
                        isBackingUp = false
                    }
                },
                enabled = !isBackingUp
            ) {
                if (isBackingUp) {
                    CircularWavyProgressIndicator(
                        modifier = Modifier.size(18.dp)
                    )
                } else {
                    Icon(Icons.Rounded.CloudUpload, null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(if (isBackingUp) stringResource(R.string.backup_page_backing_up) else stringResource(R.string.backup_page_backup_now))
            }
        }
    }

    if (showBackupFiles) {
        ModalBottomSheet(
            onDismissRequest = {
                showBackupFiles = false
            },
            sheetState = rememberModalBottomSheetState(
                skipPartiallyExpanded = true
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.8f)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    stringResource(R.string.backup_page_object_storage_backup_files),
                    modifier = Modifier.fillMaxWidth()
                )
                val backupItems by vm.objectStorageBackupItems.collectAsStateWithLifecycle()
                backupItems.onSuccess {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(it) { item ->
                            ObjectStorageBackupItemCard(
                                item = item,
                                isRestoring = restoringKey == item.key,
                                onDelete = {
                                    scope.launch {
                                        runCatching {
                                            vm.deleteObjectStorageBackupFile(item)
                                            toaster.show(
                                                context.getString(R.string.backup_page_delete_success),
                                                type = ToastType.Success
                                            )
                                            vm.loadObjectStorageBackupFileItems()
                                        }.onFailure { err ->
                                            err.printStackTrace()
                                            toaster.show(
                                                context.getString(
                                                    R.string.backup_page_delete_failed,
                                                    err.message ?: ""
                                                ),
                                                type = ToastType.Error
                                            )
                                        }
                                    }
                                },
                                onRestore = {
                                    scope.launch {
                                        restoringKey = item.key
                                        runCatching {
                                            vm.restoreFromObjectStorage(item)
                                            toaster.show(
                                                context.getString(R.string.backup_page_restore_success),
                                                type = ToastType.Success
                                            )
                                            showBackupFiles = false
                                        }.onFailure { err ->
                                            err.printStackTrace()
                                            toaster.show(
                                                context.getString(
                                                    R.string.backup_page_restore_failed,
                                                    err.message ?: ""
                                                ),
                                                type = ToastType.Error
                                            )
                                        }
                                        restoringKey = null
                                    }
                                },
                            )
                        }
                    }
                }.onError {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.backup_page_loading_failed, it.message ?: ""),
                            color = Color.Red
                        )
                    }
                }.onLoading {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularWavyProgressIndicator()
                    }
                }
            }
        }
    }

}

@Composable
private fun BackupItemCard(
    item: WebDavBackupItem,
    isRestoring: Boolean = false,
    onDelete: (WebDavBackupItem) -> Unit = {},
    onRestore: (WebDavBackupItem) -> Unit = {},
) {
    Card(
        shape = me.rerere.rikkahub.ui.theme.AppShapes.CardLarge,
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = if (me.rerere.rikkahub.ui.theme.LocalDarkMode.current) androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow else androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = item.displayName,
                style = MaterialTheme.typography.titleMedium
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.lastModified.toLocalDateTime(),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = item.size.fileSizeToString(),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = {
                    onDelete(item)
                },
                enabled = !isRestoring
            ) {
                Text(stringResource(R.string.backup_page_delete))
            }
            Button(
                onClick = {
                    onRestore(item)
                },
                enabled = !isRestoring
            ) {
                if (isRestoring) {
                    CircularWavyProgressIndicator(
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (isRestoring) stringResource(R.string.backup_page_restoring) else stringResource(R.string.backup_page_restore_now))
            }
        }
    }
}

@Composable
private fun ObjectStorageBackupItemCard(
    item: ObjectStorageBackupItem,
    isRestoring: Boolean = false,
    onDelete: (ObjectStorageBackupItem) -> Unit = {},
    onRestore: (ObjectStorageBackupItem) -> Unit = {},
) {
    Card(
        shape = me.rerere.rikkahub.ui.theme.AppShapes.CardLarge,
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = if (me.rerere.rikkahub.ui.theme.LocalDarkMode.current) androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow else androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = item.displayName,
                style = MaterialTheme.typography.titleMedium
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.lastModified.toLocalDateTime(),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = item.size.fileSizeToString(),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = {
                    onDelete(item)
                },
                enabled = !isRestoring
            ) {
                Text(stringResource(R.string.backup_page_delete))
            }
            Button(
                onClick = {
                    onRestore(item)
                },
                enabled = !isRestoring
            ) {
                if (isRestoring) {
                    CircularWavyProgressIndicator(
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (isRestoring) stringResource(R.string.backup_page_restoring) else stringResource(R.string.backup_page_restore_now))
            }
        }
    }
}

@Composable
private fun BackupLogsPage(
    vm: BackupVM,
) {
    val logs by vm.backupLogs.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val context = LocalContext.current

    fun triggerText(trigger: String): String = when (trigger) {
        "AUTO" -> context.getString(R.string.backup_log_trigger_auto)
        "MANUAL" -> context.getString(R.string.backup_log_trigger_manual)
        else -> trigger
    }

    fun backendText(backend: String): String = when (backend) {
        "WEBDAV" -> context.getString(R.string.backup_log_backend_webdav)
        "OBJECT_STORAGE" -> context.getString(R.string.backup_log_backend_object_storage)
        else -> backend
    }

    fun statusText(status: String): String = when (status) {
        "SUCCESS" -> context.getString(R.string.backup_log_status_success)
        "FAILED" -> context.getString(R.string.backup_log_status_failed)
        "SKIPPED" -> context.getString(R.string.backup_log_status_skipped)
        else -> status
    }

    val statusSuccessColor = MaterialTheme.colorScheme.primary
    val statusFailedColor = MaterialTheme.colorScheme.error
    val statusNeutralColor = MaterialTheme.colorScheme.onSurfaceVariant

    fun statusColor(status: String): Color = when (status) {
        "SUCCESS" -> statusSuccessColor
        "FAILED" -> statusFailedColor
        else -> statusNeutralColor
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(16.dp)
    ) {
        stickyHeader {
            StickyHeader {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.backup_page_backup_logs))
                    TextButton(
                        onClick = {
                            scope.launch {
                                vm.clearBackupLogs()
                                toaster.show(
                                    context.getString(R.string.backup_page_backup_logs_cleared),
                                    type = ToastType.Success
                                )
                            }
                        },
                        enabled = logs.isNotEmpty()
                    ) {
                        Text(stringResource(R.string.backup_page_backup_logs_clear))
                    }
                }
            }
        }

        if (logs.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.backup_page_backup_logs_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(
                items = logs,
                key = { it.id }
            ) { log ->
                val timeText = Instant.ofEpochMilli(log.createdAt)
                    .toLocalDateTime()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

                Card(
                    shape = me.rerere.rikkahub.ui.theme.AppShapes.CardLarge,
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    ListItem(
                        headlineContent = {
                            Text(
                                text = "${triggerText(log.trigger)} · ${backendText(log.backend)} · ${statusText(log.status)}",
                                color = statusColor(log.status),
                            )
                        },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(log.message)
                                Text(
                                    text = timeText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (!log.error.isNullOrBlank()) {
                                    Text(
                                        text = log.error,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportExportPage(
    vm: BackupVM
) {
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var isExporting by remember { mutableStateOf(false) }
    var isRestoring by remember { mutableStateOf(false) }

    // 导入类型：local 为本地备份，chatbox 为 Chatbox 导入
    var importType by remember { mutableStateOf("local") }

    // 创建文件保存的launcher
    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let { targetUri ->
            scope.launch {
                isExporting = true
                runCatching {
                    // 导出文件
                    val exportFile = vm.exportToFile()

                    // 复制到用户选择的位置
                    context.contentResolver.openOutputStream(targetUri)?.use { outputStream ->
                        FileInputStream(exportFile).use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }

                    // 清理临时文件
                    exportFile.delete()

                    toaster.show(
                        context.getString(R.string.backup_page_backup_success),
                        type = ToastType.Success
                    )
                }.onFailure { e ->
                    e.printStackTrace()
                    toaster.show(
                        context.getString(R.string.backup_page_restore_failed, e.message ?: ""),
                        type = ToastType.Error
                    )
                }
                isExporting = false
            }
        }
    }

    // RikkaHub 兼容导出 launcher (文件名用原版 backup_ 前缀)
    val createCompatDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let { targetUri ->
            scope.launch {
                isExporting = true
                runCatching {
                    val exportFile = vm.exportRikkaHubCompat()
                    context.contentResolver.openOutputStream(targetUri)?.use { outputStream ->
                        FileInputStream(exportFile).use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    exportFile.delete()
                    toaster.show(
                        context.getString(R.string.backup_page_backup_success),
                        type = ToastType.Success
                    )
                }.onFailure { e ->
                    e.printStackTrace()
                    toaster.show(
                        context.getString(R.string.backup_page_restore_failed, e.message ?: ""),
                        type = ToastType.Error
                    )
                }
                isExporting = false
            }
        }
    }

    // 创建文件选择的launcher
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->

        uri?.let { sourceUri ->
            scope.launch {
                isRestoring = true
                runCatching {
                    when (importType) {
                        "local" -> {
                            // 本地备份导入：处理zip文件
                            val tempFile =
                                File(context.cacheDir, "temp_restore_${System.currentTimeMillis()}.zip")
                            var restoreOwnsTempFile = false
                            try {
                                context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                                    FileOutputStream(tempFile).use { outputStream ->
                                        inputStream.copyTo(outputStream)
                                    }
                                }

                                // 恢复任务接管文件后，即使页面销毁也由 BackupVM 在任务结束时清理。
                                restoreOwnsTempFile = true
                                vm.restoreFromLocalFile(
                                    file = tempFile,
                                    deleteAfterRestore = true,
                                )
                            } finally {
                                if (!restoreOwnsTempFile) tempFile.delete()
                            }
                        }
                    }

                    toaster.show(
                        context.getString(R.string.backup_page_restore_success),
                        type = ToastType.Success
                    )
                }.onFailure { e ->
                    e.printStackTrace()
                    toaster.show(
                        context.getString(R.string.backup_page_restore_failed, e.message ?: ""),
                        type = ToastType.Error
                    )
                }
                isRestoring = false
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(16.dp)
    ) {
        stickyHeader {
            StickyHeader {
                Text(stringResource(R.string.backup_page_local_backup_export))
            }
        }

        item {
            Card(
                shape = me.rerere.rikkahub.ui.theme.AppShapes.CardLarge,
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.workspace_transfer_backup_notice_title)) },
                    supportingContent = { Text(stringResource(R.string.workspace_transfer_backup_notice_desc)) },
                    leadingContent = { Icon(Icons.Rounded.Info, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }

        item {
            Card(
                shape = me.rerere.rikkahub.ui.theme.AppShapes.CardLarge,
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = if (me.rerere.rikkahub.ui.theme.LocalDarkMode.current) androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow else androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                onClick = {
                    if (!isExporting) {
                        val timestamp = LocalDateTime.now()
                            .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))
                        createDocumentLauncher.launch("FLIT_backup_$timestamp.zip")
                    }
                }
            ) {
                ListItem(
                    headlineContent = {
                        Text(stringResource(R.string.backup_page_local_backup_export))
                    },
                    supportingContent = {
                        Text(
                            if (isExporting) stringResource(R.string.backup_page_exporting) else stringResource(
                                R.string.backup_page_export_desc
                            )
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = {
                        if (isExporting) {
                            CircularWavyProgressIndicator(
                                modifier = Modifier.size(24.dp)
                            )
                        } else {
                            Icon(Icons.Rounded.FileUpload, null)
                        }
                    }
                )
            }
        }

        item {
            Card(
                shape = me.rerere.rikkahub.ui.theme.AppShapes.CardLarge,
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = if (me.rerere.rikkahub.ui.theme.LocalDarkMode.current) androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow else androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                onClick = {
                    if (!isRestoring) {
                        importType = "local"
                        openDocumentLauncher.launch(arrayOf("application/zip"))
                    }
                }
            ) {
                ListItem(
                    headlineContent = {
                        Text(stringResource(R.string.backup_page_local_backup_import))
                    },
                    supportingContent = {
                        Text(
                            if (isRestoring) stringResource(R.string.backup_page_importing) else stringResource(
                                R.string.backup_page_import_desc
                            )
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = {
                        if (isRestoring) {
                            CircularWavyProgressIndicator(
                                modifier = Modifier.size(24.dp)
                            )
                        } else {
                            Icon(Icons.Rounded.SystemUpdateAlt, null)
                        }
                    }
                )
            }
        }

        item {
            Card(
                shape = me.rerere.rikkahub.ui.theme.AppShapes.CardLarge,
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = if (me.rerere.rikkahub.ui.theme.LocalDarkMode.current) androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow else androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                onClick = {
                    if (!isExporting) {
                        val timestamp = LocalDateTime.now()
                            .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                        createCompatDocumentLauncher.launch("backup_$timestamp.zip")
                    }
                }
            ) {
                ListItem(
                    headlineContent = {
                        Text(stringResource(R.string.backup_page_compat_export))
                    },
                    supportingContent = {
                        Text(
                            if (isExporting) stringResource(R.string.backup_page_exporting) else stringResource(
                                R.string.backup_page_compat_export_desc
                            )
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = {
                        if (isExporting) {
                            CircularWavyProgressIndicator(
                                modifier = Modifier.size(24.dp)
                            )
                        } else {
                            Icon(Icons.Rounded.FileUpload, null)
                        }
                    }
                )
            }
        }
    }

}

@Composable
private fun BackupDialog(
    result: me.rerere.rikkahub.data.sync.WebdavSync.RestoreResult?,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {}, // Disallow dismissing by clicking outside
        title = { Text(stringResource(R.string.backup_page_restart_app)) },
        text = { 
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.backup_page_restart_desc))
                
                result?.let {
                    if (it.sanitization.skippedRows > 0 || it.settingsCleanup.totalIssuesFixed > 0 || it.settingsCleanup.unsupportedZipEntriesBytes > 0) {
                        Card(
                            colors = androidx.compose.material3.CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(
                                    text = stringResource(R.string.backup_restore_report_title),
                                    style = MaterialTheme.typography.labelMedium
                                )
                                if (it.sanitization.skippedRows > 0) {
                                    Text(stringResource(R.string.backup_restore_removed_invalid_items, it.sanitization.skippedRows))
                                }
                                if (it.settingsCleanup.invalidSearchModeCount > 0) {
                                    Text(stringResource(R.string.backup_restore_reset_search_settings, it.settingsCleanup.invalidSearchModeCount))
                                }
                                if (it.settingsCleanup.orphanedTagReferences > 0) {
                                    Text(stringResource(R.string.backup_restore_removed_tag_references, it.settingsCleanup.orphanedTagReferences))
                                }
                                if (it.settingsCleanup.orphanedModelReferences > 0) {
                                    Text(stringResource(R.string.backup_restore_removed_model_references, it.settingsCleanup.orphanedModelReferences))
                                }
                                if (it.settingsCleanup.fixedAvatarPaths > 0) {
                                    Text(stringResource(R.string.backup_restore_updated_avatar_paths, it.settingsCleanup.fixedAvatarPaths))
                                }
                                if (it.settingsCleanup.fixedAssistantBackgrounds > 0) {
                                    Text(stringResource(R.string.backup_restore_updated_backgrounds, it.settingsCleanup.fixedAssistantBackgrounds))
                                }
                                if (it.settingsCleanup.unsupportedRikkaHubSettings > 0) {
                                    Text(stringResource(R.string.backup_restore_skipped_rikkahub_settings, it.settingsCleanup.unsupportedRikkaHubSettings))
                                }
                                if (it.settingsCleanup.unsupportedZipEntriesBytes > 0) {
                                    Text(stringResource(R.string.backup_restore_skipped_unsupported_data, it.settingsCleanup.unsupportedZipEntriesBytes.fileSizeToString()))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm
            ) {
                Text(stringResource(R.string.backup_page_restart_app))
            }
        },
    )
}
