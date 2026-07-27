package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.content.Context
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.SandboxRootfsStatus
import me.rerere.rikkahub.data.db.entity.WorkspaceType
import me.rerere.rikkahub.data.db.entity.toolDefaultNeedsApproval
import me.rerere.rikkahub.data.db.entity.workspaceToolNames
import me.rerere.rikkahub.data.repository.SafRepository
import me.rerere.rikkahub.data.repository.Workspace
import me.rerere.rikkahub.data.repository.WorkspaceFileEntry
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.repository.SandboxMountDraft
import me.rerere.rikkahub.data.db.entity.SandboxWorkspaceMountEntity
import me.rerere.rikkahub.workspace.SandboxRootfsInstallProgress
import me.rerere.rikkahub.workspace.SandboxRootfsInstallStage
import me.rerere.rikkahub.workspace.SandboxStorageArea
import me.rerere.rikkahub.workspace.SandboxBindMount

class WorkspaceDetailVM(
    private val workspaceId: String,
    private val repository: WorkspaceRepository,
    private val safRepository: SafRepository,
    private val context: Context,
) : ViewModel() {
    val workspace: StateFlow<Workspace?> = repository.listFlow().map { list -> list.firstOrNull { it.id == workspaceId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val friendlyRootPath: StateFlow<String?> = workspace.map { ws ->
        ws?.treeUri?.let(repository::friendlyShortPath)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _toolApprovals = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val toolApprovals = _toolApprovals.asStateFlow()

    private val _filesState = MutableStateFlow(FilesState())
    val filesState = _filesState.asStateFlow()

    private val _installState = MutableStateFlow(SandboxInstallState())
    val installState = _installState.asStateFlow()

    private val transferController = WorkspaceTransferController(repository, viewModelScope)
    val transferState = transferController.transferState

    private var safRoot: DocumentFile? = null

    init {
        viewModelScope.launch {
            workspace.collect { ws ->
                _toolApprovals.value = ws?.toolApprovalOverrides().orEmpty()
                if (ws != null) {
                    if (ws.type != WorkspaceType.SANDBOX && _filesState.value.area != SandboxStorageArea.FILES) {
                        _filesState.value = FilesState()
                    }
                    refreshFiles()
                }
            }
        }
    }

    fun toolNames(): List<String> = workspace.value?.let { workspaceToolNames(it.type) }.orEmpty()
    fun needsApproval(toolName: String): Boolean = _toolApprovals.value[toolName] ?: toolDefaultNeedsApproval(toolName)
    fun allNeedApproval(): Boolean = toolNames().all(::needsApproval)

    fun open(entry: WorkspaceFileEntry) {
        if (entry.isDirectory) {
            _filesState.update { it.copy(path = entry.path, error = null) }
            refreshFiles()
        }
    }

    fun selectArea(area: SandboxStorageArea) {
        val ws = workspace.value ?: return
        if (ws.type != WorkspaceType.SANDBOX || _filesState.value.area == area) return
        _filesState.value = FilesState(area = area, loading = true)
        refreshFiles()
    }

    fun goUp() {
        val path = _filesState.value.path
        if (path.isNotBlank()) {
            _filesState.update { it.copy(path = path.substringBeforeLast('/', ""), error = null) }
            refreshFiles()
        }
    }

    fun refreshFiles() {
        val ws = workspace.value ?: return
        _filesState.update { it.copy(loading = true, error = null) }
        val request = _filesState.value
        viewModelScope.launch {
            runCatching {
                val entries = when (ws.type) {
                    WorkspaceType.LIGHTWEIGHT -> withContext(Dispatchers.IO) {
                        val root = safRoot ?: repository.resolveRoot(ws.treeUri ?: error("Workspace folder is missing")).also { safRoot = it }
                            ?: error("Workspace folder is not accessible")
                        safRepository.listChildren(root, request.path)
                    }
                    WorkspaceType.SANDBOX -> {
                        if (
                            request.area == SandboxStorageArea.ROOTFS &&
                            (
                                ws.sandboxStatus == SandboxRootfsStatus.DISABLED ||
                                    ws.sandboxStatus == SandboxRootfsStatus.INSTALLING
                                )
                        ) {
                            error(context.getString(R.string.workspace_rootfs_not_installed))
                        }
                        repository.listSandboxFiles(ws.id, request.path, request.area)
                    }
                }
                _filesState.update { current ->
                    if (current.area == request.area && current.path == request.path) {
                        current.copy(entries = entries, loading = false, error = null)
                    } else {
                        current
                    }
                }
            }.onFailure { error ->
                _filesState.update { current ->
                    if (current.area == request.area && current.path == request.path) {
                        current.copy(loading = false, error = error.message ?: error.javaClass.simpleName)
                    } else {
                        current
                    }
                }
            }
        }
    }

    fun hasAllFilesAccess(): Boolean = repository.hasAllFilesAccess()

    suspend fun terminalBindMounts(): List<SandboxBindMount> = repository.getSandboxBindMounts(workspaceId)

    fun prepareMount(treeUri: String, onResult: (Result<SandboxMountDraft>) -> Unit) {
        viewModelScope.launch {
            val result = runCatching { repository.prepareSandboxMount(workspaceId, treeUri) }
            if (result.isFailure) repository.releaseUnusedMountPermission(treeUri)
            onResult(result)
        }
    }

    fun createMount(
        draft: SandboxMountDraft,
        parentPath: String,
        name: String,
        onResult: (Result<SandboxWorkspaceMountEntity>) -> Unit,
    ) {
        viewModelScope.launch {
            val result = runCatching {
                repository.createSandboxMount(
                    id = workspaceId,
                    treeUri = draft.source.treeUri,
                    parentPath = parentPath,
                    name = name,
                )
            }
            result.onSuccess { refreshFiles() }
            onResult(result)
        }
    }

    fun cancelMountDraft(draft: SandboxMountDraft) {
        viewModelScope.launch { repository.releaseUnusedMountPermission(draft.source.treeUri) }
    }

    fun unmount(entry: WorkspaceFileEntry) {
        val mountId = entry.mountId ?: return
        viewModelScope.launch {
            runCatching { repository.removeSandboxMount(workspaceId, mountId) }
                .onSuccess { refreshFiles() }
                .onFailure { error ->
                    _filesState.update { it.copy(error = error.message ?: "Unmount failed") }
                }
        }
    }

    fun importFile(input: InputStream, displayName: String) {
        val ws = workspace.value ?: run { input.close(); return }
        val target = _filesState.value
        _filesState.update { it.copy(loading = true) }
        viewModelScope.launch {
            runCatching {
                when (ws.type) {
                    WorkspaceType.LIGHTWEIGHT -> withContext(Dispatchers.IO) {
                        val root = safRoot ?: repository.resolveRoot(ws.treeUri ?: error("Workspace folder is missing")).also { safRoot = it }
                            ?: error("Workspace folder is not accessible")
                        safRepository.importFromUri(root, target.path, input, displayName) ?: error("Import failed")
                    }
                    WorkspaceType.SANDBOX -> repository.importSandboxFile(
                        ws.id,
                        target.path,
                        displayName,
                        input,
                        target.area,
                    )
                }
            }.onSuccess { refreshFiles() }.onFailure { error ->
                input.close()
                _filesState.update { it.copy(loading = false, error = error.message ?: "Import failed") }
            }
        }
    }

    fun deleteFile(entry: WorkspaceFileEntry) {
        val ws = workspace.value ?: return
        val area = _filesState.value.area
        _filesState.update { it.copy(loading = true) }
        viewModelScope.launch {
            runCatching {
                when (ws.type) {
                    WorkspaceType.LIGHTWEIGHT -> withContext(Dispatchers.IO) {
                        val root = safRoot ?: error("Workspace folder is not accessible")
                        check(safRepository.delete(root, entry.path)) { "Delete failed" }
                    }
                    WorkspaceType.SANDBOX -> check(
                        repository.deleteSandboxFile(
                            ws.id,
                            entry.path,
                            entry.isDirectory,
                            area,
                        )
                    ) { "Delete failed" }
                }
            }.onSuccess { refreshFiles() }.onFailure { error ->
                _filesState.update { it.copy(loading = false, error = error.message ?: "Delete failed") }
            }
        }
    }

    fun exportFile(entry: WorkspaceFileEntry, output: java.io.OutputStream) {
        val ws = workspace.value ?: run { output.close(); return }
        val area = _filesState.value.area
        viewModelScope.launch {
            runCatching {
                when (ws.type) {
                    WorkspaceType.LIGHTWEIGHT -> withContext(Dispatchers.IO) {
                        val root = safRoot ?: repository.resolveRoot(ws.treeUri ?: error("Workspace folder is missing")).also { safRoot = it }
                            ?: error("Workspace folder is not accessible")
                        safRepository.resolve(root, entry.path)?.uri?.let { uri ->
                            context.contentResolver.openInputStream(uri)?.use { input -> output.use(input::copyTo) }
                                ?: error("Export failed")
                        } ?: error("File not found")
                    }
                    WorkspaceType.SANDBOX -> output.use {
                        repository.exportSandboxFile(ws.id, entry.path, it, area)
                    }
                }
            }.onFailure { output.close() }
        }
    }

    suspend fun resolveFileUri(entry: WorkspaceFileEntry): android.net.Uri? = withContext(Dispatchers.IO) {
        if (entry.isDirectory) return@withContext null
        val ws = workspace.value ?: return@withContext null
        val area = _filesState.value.area
        when (ws.type) {
            WorkspaceType.LIGHTWEIGHT -> {
                val root = safRoot ?: repository.resolveRoot(ws.treeUri ?: return@withContext null).also { safRoot = it }
                root?.let { safRepository.resolve(it, entry.path)?.uri }
            }
            WorkspaceType.SANDBOX -> runCatching {
                val share = File(context.cacheDir, "sandbox_share/${ws.id}/${entry.name}").apply { parentFile?.mkdirs() }
                share.outputStream().use {
                    repository.exportSandboxFile(ws.id, entry.path, it, area)
                }
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", share)
            }.getOrNull()
        }
    }

    fun installRootfs(url: String) {
        val ws = workspace.value ?: return
        if (ws.type != WorkspaceType.SANDBOX || _installState.value.installing) return
        viewModelScope.launch {
            _installState.value = SandboxInstallState(
                installing = true,
                progress = SandboxRootfsInstallProgress(stage = SandboxRootfsInstallStage.DOWNLOADING),
            )
            runCatching {
                repository.installSandboxRootfs(ws.id, url) { progress ->
                    _installState.value = SandboxInstallState(installing = true, progress = progress)
                }
            }.onFailure { error ->
                _installState.value = SandboxInstallState(error = error.message ?: "Rootfs installation failed")
            }.onSuccess {
                _installState.value = SandboxInstallState()
                refreshFiles()
            }
        }
    }

    fun dismissInstallError() { _installState.update { it.copy(error = null) } }

    fun exportWorkspace(output: OutputStream, onResult: (Result<Unit>) -> Unit) {
        val ws = workspace.value
        if (ws == null) {
            output.close()
            return
        }
        transferController.exportWorkspace(ws, output, onResult)
    }

    fun cancelTransfer() = transferController.cancelTransfer()

    fun setToolApproval(toolName: String, needsApproval: Boolean) {
        _toolApprovals.update { it + (toolName to needsApproval) }
        persistApprovals()
    }

    fun setAll(approveAll: Boolean) {
        _toolApprovals.update { current -> toolNames().associateWith { approveAll } + current.filterKeys { it !in toolNames() } }
        persistApprovals()
    }

    private fun persistApprovals() = viewModelScope.launch { repository.setToolApprovals(workspaceId, _toolApprovals.value) }
    fun rename(name: String) = viewModelScope.launch { repository.rename(workspaceId, name) }
    fun delete(onDone: () -> Unit) = viewModelScope.launch { repository.delete(workspaceId); onDone() }

    data class FilesState(
        val area: SandboxStorageArea = SandboxStorageArea.FILES,
        val path: String = "",
        val entries: List<WorkspaceFileEntry> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
    )
    data class SandboxInstallState(
        val installing: Boolean = false,
        val progress: SandboxRootfsInstallProgress? = null,
        val error: String? = null,
    )
}
