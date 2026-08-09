package me.rerere.rikkahub.ui.pages.backup

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.backup.BackupCoordinator
import me.rerere.rikkahub.data.backup.BackupLogManager
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.sync.WebDavBackupItem
import me.rerere.rikkahub.data.sync.ObjectStorageBackupItem
import me.rerere.rikkahub.data.sync.ObjectStorageSync
import me.rerere.rikkahub.data.sync.WebdavSync
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.UiState
import java.io.File

private const val TAG = "BackupVM"

class BackupVM(
    private val settingsStore: SettingsStore,
    private val webdavSync: WebdavSync,
    private val objectStorageSync: ObjectStorageSync,
    private val backupCoordinator: BackupCoordinator,
    private val backupLogManager: BackupLogManager,
) : ViewModel() {
    val settings = settingsStore.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = Settings.dummy()
    )

    val webDavBackupItems = MutableStateFlow<UiState<List<WebDavBackupItem>>>(UiState.Idle)
    val objectStorageBackupItems = MutableStateFlow<UiState<List<ObjectStorageBackupItem>>>(UiState.Idle)
    val backupLogs = backupLogManager.observeRecent().stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList()
    )
    private val _pendingRestoreResult = MutableStateFlow<WebdavSync.RestoreResult?>(null)
    val pendingRestoreResult = _pendingRestoreResult.asStateFlow()

    init {
        loadBackupFileItems()
    }

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }

    fun loadBackupFileItems() {
        viewModelScope.launch {
            runCatching {
                webDavBackupItems.emit(UiState.Loading)
                webDavBackupItems.emit(
                    value = UiState.Success(
                        data = webdavSync.listBackupFiles(
                            webDavConfig = settings.value.webDavConfig
                        ).sortedByDescending { it.lastModified }
                    )
                )
            }.onFailure {
                webDavBackupItems.emit(UiState.Error(it))
            }
        }
    }

    fun loadObjectStorageBackupFileItems() {
        viewModelScope.launch {
            runCatching {
                objectStorageBackupItems.emit(UiState.Loading)
                objectStorageBackupItems.emit(
                    value = UiState.Success(
                        data = objectStorageSync.listBackupFiles(
                            config = settings.value.objectStorageConfig
                        ).sortedByDescending { it.lastModified }
                    )
                )
            }.onFailure {
                objectStorageBackupItems.emit(UiState.Error(it))
            }
        }
    }

    suspend fun testWebDav() {
        webdavSync.testWebdav(settings.value.webDavConfig)
    }

    suspend fun testObjectStorage() {
        objectStorageSync.testConnection(settings.value.objectStorageConfig)
    }

    suspend fun backup() {
        backupCoordinator.manualBackupWebDav()
    }

    suspend fun backupToObjectStorage() {
        backupCoordinator.manualBackupObjectStorage()
    }

    suspend fun restore(item: WebDavBackupItem): WebdavSync.RestoreResult {
        return runRestore {
            backupCoordinator.restoreWebDav(item)
        }
    }

    suspend fun restoreFromObjectStorage(item: ObjectStorageBackupItem): WebdavSync.RestoreResult {
        return runRestore {
            backupCoordinator.restoreObjectStorage(item)
        }
    }

    suspend fun deleteWebDavBackupFile(item: WebDavBackupItem) {
        webdavSync.deleteWebDavBackupFile(settings.value.webDavConfig, item)
    }

    suspend fun deleteObjectStorageBackupFile(item: ObjectStorageBackupItem) {
        objectStorageSync.deleteBackupFile(settings.value.objectStorageConfig, item)
    }

    suspend fun exportToFile(): File {
        return backupCoordinator.exportToFile()
    }

    suspend fun exportRikkaHubCompat(): File {
        return backupCoordinator.exportRikkaHubCompat()
    }

    suspend fun restoreFromLocalFile(
        file: File,
        deleteAfterRestore: Boolean = false,
    ): WebdavSync.RestoreResult {
        return runRestore(
            onCompletion = {
                if (deleteAfterRestore) runCatching { file.delete() }
            },
        ) {
            backupCoordinator.restoreFromLocalFile(file)
        }
    }

    private suspend fun runRestore(
        onCompletion: () -> Unit = {},
        block: suspend () -> WebdavSync.RestoreResult,
    ): WebdavSync.RestoreResult {
        val task = viewModelScope.async {
            block().also { _pendingRestoreResult.value = it }
        }
        task.invokeOnCompletion { onCompletion() }
        return task.await()
    }

    fun clearBackupLogs() {
        viewModelScope.launch {
            backupLogManager.clearAll()
        }
    }
    
    fun restartApp(context: android.content.Context) {
        val packageManager = context.packageManager
        val intent = packageManager.getLaunchIntentForPackage(context.packageName)
        val componentName = intent?.component
        val mainIntent = android.content.Intent.makeRestartActivityTask(componentName)
        context.startActivity(mainIntent)
        kotlin.system.exitProcess(0)
    }

    fun restoreFromChatBox(file: File) {
        val importProviders = arrayListOf<ProviderSetting>()

        val jsonElements = JsonInstant.parseToJsonElement(file.readText()).jsonObject
        val settingsObj = jsonElements["settings"]?.jsonObject
        if (settingsObj != null) {
            settingsObj["providers"]?.jsonObject?.let { providers ->
                providers["openai"]?.jsonObject?.let { openai ->
                    val apiHost = openai["apiHost"]?.jsonPrimitive?.contentOrNull ?: "https://api.openai.com"
                    val apiKey = openai["apiKey"]?.jsonPrimitive?.contentOrNull ?: ""
                    val models = openai["models"]?.jsonArray?.map { element ->
                        val modelId = element.jsonObject["modelId"]?.jsonPrimitive?.contentOrNull ?: ""
                        val capabilities =
                            element.jsonObject["capabilities"]?.jsonArray?.map { it.jsonPrimitive.contentOrNull }
                                ?: emptyList()
                        Model(
                            modelId = modelId,
                            displayName = modelId,
                            inputModalities = buildList {
                                if (capabilities.contains("vision")) {
                                    add(Modality.IMAGE)
                                }
                            },
                            abilities = buildList {
                                if (capabilities.contains("tool_use")) {
                                    add(ModelAbility.TOOL)
                                }
                                if (capabilities.contains("reasoning")) {
                                    add(ModelAbility.REASONING)
                                }
                            }
                        )
                    } ?: emptyList()
                    if (apiKey.isNotBlank()) importProviders.add(
                        ProviderSetting.OpenAI(
                            name = "OpenAI",
                            baseUrl = "$apiHost/v1",
                            apiKey = apiKey,
                            models = models,
                        )
                    )
                }
                providers["claude"]?.jsonObject?.let { claude ->
                    val apiHost =
                        claude["apiHost"]?.jsonPrimitive?.contentOrNull ?: "https://api.anthropic.com"
                    val apiKey = claude["apiKey"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (apiKey.isNotBlank()) importProviders.add(
                        ProviderSetting.Claude(
                            name = "Claude",
                            baseUrl = "${apiHost}/v1",
                            apiKey = apiKey,
                        )
                    )
                }
                providers["gemini"]?.jsonObject?.let { gemini ->
                    val apiHost = gemini["apiHost"]?.jsonPrimitive?.contentOrNull
                        ?: "https://generativelanguage.googleapis.com"
                    val apiKey = gemini["apiKey"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (apiKey.isNotBlank()) importProviders.add(
                        ProviderSetting.Google(
                            name = "Gemini",
                            baseUrl = "$apiHost/v1beta",
                            apiKey = apiKey,
                        )
                    )
                }
            }
        }

        Log.i(TAG, "restoreFromChatBox: import ${importProviders.size} providers: $importProviders")

        updateSettings(
            settings.value.copy(
                providers = importProviders + settings.value.providers,
            )
        )
    }
}
