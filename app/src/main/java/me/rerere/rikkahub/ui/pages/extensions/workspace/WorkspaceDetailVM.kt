package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository

class WorkspaceDetailVM(
    private val workspaceId: String,
    private val repository: WorkspaceRepository,
) : ViewModel() {

    val workspace: StateFlow<WorkspaceEntity?> = kotlinx.coroutines.flow.flow {
        emit(repository.getById(workspaceId))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _toolApprovals = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val toolApprovals: StateFlow<Map<String, Boolean>> = _toolApprovals.asStateFlow()

    init {
        viewModelScope.launch {
            _toolApprovals.value = repository.getById(workspaceId)?.toolApprovalOverrides() ?: emptyMap()
        }
    }

    fun setToolApproval(toolName: String, needsApproval: Boolean) {
        _toolApprovals.update { it + (toolName to needsApproval) }
    }

    /** allowAll=true 表示全部免审批（needsApproval=false） */
    fun setAll(allowAll: Boolean) {
        val allTools = WORKSPACE_TOOL_NAMES
        _toolApprovals.update { current ->
            allTools.associateWith { !allowAll } + current.filterKeys { it !in allTools }
        }
    }

    fun saveApprovals(onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { repository.setToolApprovals(workspaceId, _toolApprovals.value) }
            onDone()
        }
    }

    fun rename(name: String) {
        viewModelScope.launch {
            runCatching { repository.rename(workspaceId, name) }
        }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { repository.delete(workspaceId) }
            onDone()
        }
    }

    companion object {
        val WORKSPACE_TOOL_NAMES = listOf(
            "workspace_list",
            "workspace_read_file",
            "workspace_write_file",
            "workspace_mkdir",
            "workspace_delete",
            "workspace_rename",
            "eval_python",
            "run_skill_script",
        )
    }
}