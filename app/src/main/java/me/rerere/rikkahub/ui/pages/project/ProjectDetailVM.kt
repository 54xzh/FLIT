package me.rerere.rikkahub.ui.pages.project

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Project
import me.rerere.rikkahub.data.repository.ProjectRepository
import me.rerere.rikkahub.service.ChatService
import kotlin.uuid.Uuid

class ProjectDetailVM(
    id: String,
    private val projectRepo: ProjectRepository,
    private val settingsStore: SettingsStore,
    private val chatService: ChatService,
) : ViewModel() {
    val projectId: Uuid? = runCatching { Uuid.parse(id) }.getOrNull()

    val settings: StateFlow<Settings> = settingsStore.settingsFlow

    val project: StateFlow<Project?> = if (projectId != null) {
        projectRepo.getProjectByIdFlow(projectId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    } else {
        MutableStateFlow(null)
    }

    fun updateName(name: String) {
        val p = project.value ?: return
        if (p.name == name.trim()) return
        viewModelScope.launch {
            projectRepo.renameProject(p.id, name)
        }
    }

    fun updateIcon(icon: String) {
        val p = project.value ?: return
        if (p.icon == icon.trim()) return
        viewModelScope.launch {
            projectRepo.updateProjectIcon(p.id, icon.trim())
        }
    }

    fun updateSystemPrompt(prompt: String) {
        val p = project.value ?: return
        viewModelScope.launch {
            projectRepo.updateProject(p.copy(systemPrompt = prompt))
        }
    }

    fun updateModelId(modelId: Uuid?) {
        val p = project.value ?: return
        viewModelScope.launch {
            projectRepo.updateProject(p.copy(modelId = modelId))
        }
    }

    fun updateEnableMemoryTools(enabled: Boolean) {
        val p = project.value ?: return
        viewModelScope.launch {
            // 开关联动：记忆工具和记忆整合都关闭时，自动关闭对外可见
            val updated = if (!enabled && !p.enableConsolidation) {
                p.copy(enableMemoryTools = false, exposeToExternal = false)
            } else {
                p.copy(enableMemoryTools = enabled)
            }
            projectRepo.updateProject(updated)
        }
    }

    fun updateEnableConsolidation(enabled: Boolean) {
        val p = project.value ?: return
        viewModelScope.launch {
            // 开关联动：记忆工具和记忆整合都关闭时，自动关闭对外可见
            val updated = if (!enabled && !p.enableMemoryTools) {
                p.copy(enableConsolidation = false, exposeToExternal = false)
            } else {
                p.copy(enableConsolidation = enabled)
            }
            projectRepo.updateProject(updated)
        }
    }

    fun updateExposeToExternal(exposed: Boolean) {
        val p = project.value ?: return
        if (!p.enableMemoryTools && !p.enableConsolidation) return
        viewModelScope.launch {
            projectRepo.updateProject(p.copy(exposeToExternal = exposed))
        }
    }

    fun updateReadExternalMemory(read: Boolean) {
        val p = project.value ?: return
        viewModelScope.launch {
            projectRepo.updateProject(p.copy(readExternalMemory = read))
        }
    }

    fun updateEnableMemorySummary(enabled: Boolean) {
        val p = project.value ?: return
        viewModelScope.launch {
            projectRepo.updateProject(p.copy(enableMemorySummary = enabled))
        }
    }

    fun deleteProject(onSuccess: () -> Unit) {
        val p = project.value ?: return
        viewModelScope.launch {
            chatService.clearProjectMemoryState(p.id)
            projectRepo.deleteProject(p.id)
            onSuccess()
        }
    }
}
