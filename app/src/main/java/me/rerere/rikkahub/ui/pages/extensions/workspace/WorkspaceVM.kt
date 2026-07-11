package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.repository.Workspace
import me.rerere.rikkahub.workspace.WorkspaceTransferProgress

enum class WorkspaceTransferOperation { IMPORT, EXPORT }

data class WorkspaceTransferUiState(
    val operation: WorkspaceTransferOperation? = null,
    val progress: WorkspaceTransferProgress? = null,
) {
    val active: Boolean get() = operation != null
}

class WorkspaceVM(
    private val repository: WorkspaceRepository,
) : ViewModel() {
    private val _transferState = kotlinx.coroutines.flow.MutableStateFlow(WorkspaceTransferUiState())
    val transferState = _transferState.asStateFlow()
    private var transferJob: Job? = null

    val workspaces: StateFlow<List<Workspace>> =
        repository.listFlow().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList(),
        )

    fun createLightweight(name: String, treeUri: String, onResult: (Result<Workspace>) -> Unit) {
        viewModelScope.launch {
            runCatching { repository.createLightweight(name, treeUri) }
                .onFailure { onResult(Result.failure(it)) }
                .onSuccess { onResult(Result.success(it)) }
        }
    }

    fun createSandbox(name: String, onResult: (Result<Workspace>) -> Unit) {
        viewModelScope.launch {
            runCatching { repository.createSandbox(name) }
                .onFailure { onResult(Result.failure(it)) }
                .onSuccess { onResult(Result.success(it)) }
        }
    }

    fun rename(workspace: Workspace, name: String) {
        viewModelScope.launch {
            runCatching { repository.rename(workspace.id, name) }
        }
    }

    fun delete(workspace: Workspace) {
        viewModelScope.launch {
            runCatching { repository.delete(workspace.id) }
        }
    }

    fun importWorkspace(input: InputStream, onResult: (Result<Workspace>) -> Unit) {
        if (_transferState.value.active) {
            input.close()
            return
        }
        transferJob = viewModelScope.launch {
            _transferState.value = WorkspaceTransferUiState(operation = WorkspaceTransferOperation.IMPORT)
            try {
                val workspace = input.use { stream ->
                    repository.importSandboxWorkspace(stream) { progress ->
                        _transferState.value = WorkspaceTransferUiState(
                            operation = WorkspaceTransferOperation.IMPORT,
                            progress = progress,
                        )
                    }
                }
                onResult(Result.success(workspace))
            } catch (error: CancellationException) {
                onResult(Result.failure(error))
                throw error
            } catch (error: Throwable) {
                onResult(Result.failure(error))
            } finally {
                _transferState.value = WorkspaceTransferUiState()
                transferJob = null
            }
        }
    }

    fun cancelTransfer() {
        transferJob?.cancel()
    }
}
