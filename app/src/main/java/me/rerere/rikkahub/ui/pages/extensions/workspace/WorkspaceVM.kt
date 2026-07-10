package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.repository.Workspace

class WorkspaceVM(
    private val repository: WorkspaceRepository,
) : ViewModel() {
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
}
