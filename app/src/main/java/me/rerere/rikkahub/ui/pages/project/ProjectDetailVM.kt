package me.rerere.rikkahub.ui.pages.project

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.work.WorkInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.db.entity.MemorySummaryVersionEntity
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.Project
import me.rerere.rikkahub.data.model.effectiveMemoryRetrievalMode
import me.rerere.rikkahub.data.model.requiresEmbedding
import me.rerere.rikkahub.data.repository.AssistantMemoryStats
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.MemorySummaryRepository
import me.rerere.rikkahub.data.repository.MemorySummaryStatus
import me.rerere.rikkahub.data.repository.MemorySummaryTarget
import me.rerere.rikkahub.data.repository.MemorySummaryUpdateOptions
import me.rerere.rikkahub.data.repository.MemorySummaryVersionOperationResult
import me.rerere.rikkahub.data.repository.ProjectRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.MemorySummaryRequirementChangeResult
import me.rerere.rikkahub.service.MemorySummaryRequirementChangeService
import me.rerere.rikkahub.service.MemorySummaryWorker
import java.util.UUID
import kotlin.uuid.Uuid

private const val TAG = "ProjectDetailVM"

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectDetailVM(
    id: String,
    private val projectRepo: ProjectRepository,
    private val settingsStore: SettingsStore,
    private val chatService: ChatService,
    private val memoryRepository: MemoryRepository,
    private val memorySummaryRepository: MemorySummaryRepository,
    private val memorySummaryRequirementChangeService: MemorySummaryRequirementChangeService,
    private val context: Application,
) : ViewModel() {
    val projectId: Uuid? = runCatching { Uuid.parse(id) }.getOrNull()

    val settings: StateFlow<Settings> = settingsStore.settingsFlow

    val project: StateFlow<Project?> = if (projectId != null) {
        projectRepo.getProjectByIdFlow(projectId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    } else {
        MutableStateFlow(null)
    }

    val assistant: StateFlow<Assistant?> = combine(project, settings) { currentProject, currentSettings ->
        currentProject?.let { currentSettings.getAssistantById(it.assistantId) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val currentEmbeddingModelId: StateFlow<String> = combine(assistant, settings) { currentAssistant, currentSettings ->
        (currentAssistant?.embeddingModelId ?: currentSettings.embeddingModelId).toString()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    private val summaryTarget: StateFlow<MemorySummaryTarget.Project?> = project
        .map { currentProject ->
            currentProject?.let {
                MemorySummaryTarget.Project(
                    assistantId = it.assistantId.toString(),
                    projectId = it.id.toString(),
                )
            }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val projectMemoryStats: StateFlow<AssistantMemoryStats> = summaryTarget
        .flatMapLatest { target ->
            if (target == null) {
                flowOf(AssistantMemoryStats())
            } else {
                memoryRepository.getFilteredMemoryStatsFlow(
                    assistantId = target.assistantId,
                    projectFilterMode = 2,
                    filterProjectId = target.projectId,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AssistantMemoryStats())

    val projectSummaryStatus: StateFlow<MemorySummaryStatus> = summaryTarget
        .flatMapLatest { target ->
            if (target == null) flowOf(MemorySummaryStatus()) else memorySummaryRepository.observeStatus(target)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MemorySummaryStatus())

    val projectSummaryVersions: StateFlow<List<MemorySummaryVersionEntity>> = summaryTarget
        .flatMapLatest { target ->
            if (target == null) flowOf(emptyList()) else memorySummaryRepository.observeVersions(target)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isManualProjectSummaryRunning: StateFlow<Boolean> = summaryTarget
        .flatMapLatest { target ->
            if (target == null) flowOf(false) else memorySummaryRepository.observeManualRunning(target)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _memorySearchQuery = MutableStateFlow("")
    val memorySearchQuery = _memorySearchQuery.asStateFlow()

    fun updateMemorySearchQuery(query: String) {
        _memorySearchQuery.value = query
    }

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage = _snackbarMessage.asStateFlow()

    fun clearSnackbarMessage() {
        _snackbarMessage.value = null
    }

    private val _memorySummaryRequirement = MutableStateFlow("")
    val memorySummaryRequirement = _memorySummaryRequirement.asStateFlow()

    private val _isMemorySummaryRequirementChangeRunning = MutableStateFlow(false)
    val isMemorySummaryRequirementChangeRunning = _isMemorySummaryRequirementChangeRunning.asStateFlow()

    private val _memorySummaryRequirementChangeSuccessEvent = MutableStateFlow(0)
    val memorySummaryRequirementChangeSuccessEvent = _memorySummaryRequirementChangeSuccessEvent.asStateFlow()

    private val _latestMemorySummaryRequirementChangeVersionId = MutableStateFlow<Long?>(null)
    val latestMemorySummaryRequirementChangeVersionId =
        _latestMemorySummaryRequirementChangeVersionId.asStateFlow()

    private var manualSummaryObservationJob: Job? = null
    private var memorySummaryRequirementChangeJob: Job? = null

    init {
        viewModelScope.launch {
            val target = summaryTarget.filterNotNull().first()
            val runningWork = memorySummaryRepository.getUnfinishedManualWork(target)
            if (runningWork != null) {
                observeManualSummaryWork(runningWork.id)
            }
        }
    }

    fun getPagedMemories(
        memoryType: Int,
        sortOrder: Int,
        searchQuery: String = memorySearchQuery.value,
    ): Flow<PagingData<AssistantMemory>> {
        val currentProject = project.value ?: return flowOf(PagingData.empty())
        return memoryRepository.getAssistantMemoriesPaging(
            assistantId = currentProject.assistantId.toString(),
            memoryType = memoryType,
            searchQuery = searchQuery,
            sortOrder = sortOrder,
            projectFilterMode = 2,
            filterProjectId = currentProject.id.toString(),
        ).cachedIn(viewModelScope)
    }

    fun addMemory(memory: AssistantMemory) {
        val currentProject = project.value ?: return
        val currentAssistant = assistant.value
        val normalizedContent = memory.content.trim()
        if (normalizedContent.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                memoryRepository.addMemory(
                    assistantId = currentProject.assistantId.toString(),
                    content = normalizedContent,
                    pinned = memory.pinned,
                    generateEmbedding = currentAssistant?.effectiveMemoryRetrievalMode()?.requiresEmbedding == true,
                    projectId = currentProject.id.toString(),
                    exposeToExternal = currentProject.exposeToExternal,
                )
            }
        }
    }

    fun updateMemory(memory: AssistantMemory) {
        val currentAssistant = assistant.value
        val normalizedContent = memory.content.trim()
        if (normalizedContent.isEmpty()) return
        viewModelScope.launch {
            val generateEmbedding = currentAssistant?.effectiveMemoryRetrievalMode()?.requiresEmbedding == true
            withContext(Dispatchers.IO) {
                if (memory.id < 0) {
                    memoryRepository.updateEpisodeContent(
                        id = -memory.id,
                        content = normalizedContent,
                        generateEmbedding = generateEmbedding,
                    )
                } else {
                    memoryRepository.updateCoreMemory(
                        id = memory.id,
                        content = normalizedContent,
                        pinned = memory.pinned,
                        generateEmbedding = generateEmbedding,
                    )
                }
            }
        }
    }

    fun resolveMemoryForEditing(memory: AssistantMemory, onResolved: (AssistantMemory) -> Unit) {
        if (memory.type != 1 || memory.id >= 0) {
            onResolved(memory)
            return
        }
        viewModelScope.launch {
            val fullEpisode = withContext(Dispatchers.IO) {
                memoryRepository.getEpisodeMemoryById(-memory.id)
            }
            onResolved(fullEpisode ?: memory)
        }
    }

    fun deleteMemory(memory: AssistantMemory) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (memory.id > 0) {
                    memoryRepository.deleteMemory(id = memory.id)
                } else if (memory.id < 0 && memory.type == 1) {
                    memoryRepository.deleteEpisodeMemory(id = -memory.id)
                }
            }
        }
    }

    fun updateMemorySummaryRequirement(requirement: String) {
        if (!_isMemorySummaryRequirementChangeRunning.value) {
            _memorySummaryRequirement.value = requirement
        }
    }

    fun submitMemorySummaryRequirementChange() {
        val target = summaryTarget.value ?: return
        val requirement = _memorySummaryRequirement.value.trim()
        if (requirement.isEmpty() || _isMemorySummaryRequirementChangeRunning.value) return
        memorySummaryRequirementChangeJob = viewModelScope.launch {
            _isMemorySummaryRequirementChangeRunning.value = true
            try {
                when (val result = memorySummaryRequirementChangeService.revise(
                    target = target,
                    requirement = requirement,
                )) {
                    is MemorySummaryRequirementChangeResult.SUCCESS -> {
                        _memorySummaryRequirement.value = ""
                        _latestMemorySummaryRequirementChangeVersionId.value = result.versionId
                        _memorySummaryRequirementChangeSuccessEvent.value += 1
                        _snackbarMessage.value = context.getString(
                            R.string.assistant_page_memory_summary_requirement_change_saved,
                        )
                    }

                    MemorySummaryRequirementChangeResult.NO_ACTIVE_VERSION -> {
                        _snackbarMessage.value = context.getString(
                            R.string.assistant_page_memory_summary_version_not_found,
                        )
                    }

                    MemorySummaryRequirementChangeResult.MODEL_NOT_CONFIGURED -> {
                        _snackbarMessage.value = context.getString(
                            R.string.assistant_page_memory_summary_requirement_change_model_missing,
                        )
                    }

                    MemorySummaryRequirementChangeResult.SAVED_WITHOUT_SUMMARY_CHANGE -> {
                        _memorySummaryRequirement.value = ""
                        _memorySummaryRequirementChangeSuccessEvent.value += 1
                        _snackbarMessage.value = context.getString(
                            R.string.assistant_page_memory_summary_requirement_change_saved_without_change,
                        )
                    }

                    MemorySummaryRequirementChangeResult.STALE_ACTIVE_VERSION -> {
                        _snackbarMessage.value = context.getString(
                            R.string.assistant_page_memory_summary_requirement_change_stale,
                        )
                    }

                    MemorySummaryRequirementChangeResult.SUMMARY_DISABLED -> {
                        _snackbarMessage.value = context.getString(
                            R.string.assistant_page_memory_summary_disabled,
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.e(TAG, "Failed to change project memory summary from requirement", error)
                _snackbarMessage.value = context.getString(
                    R.string.assistant_page_memory_summary_requirement_change_failed,
                )
            } finally {
                _isMemorySummaryRequirementChangeRunning.value = false
                memorySummaryRequirementChangeJob = null
            }
        }
    }

    fun cancelMemorySummaryRequirementChange() {
        memorySummaryRequirementChangeJob?.cancel()
    }

    fun updateProjectSummary(options: MemorySummaryUpdateOptions) {
        val target = summaryTarget.value ?: return
        val workId = memorySummaryRepository.requestManualUpdate(target, options)
        _snackbarMessage.value = context.getString(R.string.assistant_page_memory_summary_update_started)
        observeManualSummaryWork(workId)
    }

    fun cancelProjectSummary() {
        val target = summaryTarget.value ?: return
        viewModelScope.launch {
            manualSummaryObservationJob?.cancel()
            manualSummaryObservationJob = null
            memorySummaryRepository.cancelManualUpdate(target)
            _snackbarMessage.value = context.getString(R.string.assistant_page_memory_summary_update_cancelled)
            memorySummaryRepository.scheduleAutomaticCheck(target)
        }
    }

    private fun observeManualSummaryWork(workId: UUID) {
        manualSummaryObservationJob?.cancel()
        manualSummaryObservationJob = viewModelScope.launch {
            memorySummaryRepository.observeWorkInfo(workId).collect { info ->
                if (info == null) return@collect
                when (info.state) {
                    WorkInfo.State.SUCCEEDED -> {
                        _snackbarMessage.value = context.getString(R.string.assistant_page_memory_summary_update_completed)
                        manualSummaryObservationJob?.cancel()
                    }

                    WorkInfo.State.FAILED -> {
                        val errorType = info.outputData.getString(MemorySummaryWorker.OUTPUT_ERROR_TYPE)
                        val message = when (errorType) {
                            MemorySummaryWorker.ERROR_TYPE_MODEL_MISSING ->
                                context.getString(R.string.assistant_page_memory_summary_requirement_change_model_missing)
                            MemorySummaryWorker.ERROR_TYPE_DISABLED ->
                                context.getString(R.string.assistant_page_memory_summary_disabled)
                            else ->
                                context.getString(R.string.assistant_page_memory_summary_update_failed)
                        }
                        _snackbarMessage.value = message
                        manualSummaryObservationJob?.cancel()
                    }

                    WorkInfo.State.CANCELLED -> {
                        _snackbarMessage.value = context.getString(R.string.assistant_page_memory_summary_update_cancelled)
                        manualSummaryObservationJob?.cancel()
                    }

                    else -> Unit
                }
            }
        }
    }

    fun activateProjectSummaryVersion(versionId: Long) {
        val target = summaryTarget.value ?: return
        viewModelScope.launch {
            when (withContext(Dispatchers.IO) {
                memorySummaryRepository.activateVersion(target, versionId)
            }) {
                MemorySummaryVersionOperationResult.SUCCESS -> {
                    _snackbarMessage.value = context.getString(R.string.assistant_page_memory_summary_version_activated)
                }

                MemorySummaryVersionOperationResult.VERSION_NOT_FOUND -> {
                    _snackbarMessage.value = context.getString(R.string.assistant_page_memory_summary_version_not_found)
                }

                else -> Unit
            }
        }
    }

    fun saveManualProjectSummaryVersion(baseVersionId: Long, content: String) {
        val target = summaryTarget.value ?: return
        viewModelScope.launch {
            when (withContext(Dispatchers.IO) {
                memorySummaryRepository.createManualVersion(
                    target = target,
                    baseVersionId = baseVersionId,
                    content = content,
                )
            }) {
                MemorySummaryVersionOperationResult.SUCCESS -> {
                    _snackbarMessage.value = context.getString(R.string.assistant_page_memory_summary_version_saved)
                }

                MemorySummaryVersionOperationResult.VERSION_NOT_FOUND -> {
                    _snackbarMessage.value = context.getString(R.string.assistant_page_memory_summary_version_not_found)
                }

                MemorySummaryVersionOperationResult.EMPTY_CONTENT -> {
                    _snackbarMessage.value = context.getString(R.string.assistant_page_memory_summary_content_empty)
                }

                MemorySummaryVersionOperationResult.UNCHANGED_CONTENT -> {
                    _snackbarMessage.value = context.getString(R.string.assistant_page_memory_summary_content_unchanged)
                }

                MemorySummaryVersionOperationResult.CANNOT_DELETE_ACTIVE,
                MemorySummaryVersionOperationResult.STALE_ACTIVE_VERSION,
                MemorySummaryVersionOperationResult.SUMMARY_DISABLED -> Unit
            }
        }
    }

    fun deleteProjectSummaryHistoryVersion(versionId: Long) {
        val target = summaryTarget.value ?: return
        viewModelScope.launch {
            when (withContext(Dispatchers.IO) {
                memorySummaryRepository.deleteHistoryVersion(target, versionId)
            }) {
                MemorySummaryVersionOperationResult.SUCCESS -> {
                    _snackbarMessage.value = context.getString(R.string.assistant_page_memory_summary_version_deleted)
                }

                MemorySummaryVersionOperationResult.CANNOT_DELETE_ACTIVE -> {
                    _snackbarMessage.value = context.getString(R.string.assistant_page_memory_summary_cannot_delete_active)
                }

                MemorySummaryVersionOperationResult.VERSION_NOT_FOUND -> {
                    _snackbarMessage.value = context.getString(R.string.assistant_page_memory_summary_version_not_found)
                }

                else -> Unit
            }
        }
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
        manualSummaryObservationJob?.cancel()
        manualSummaryObservationJob = null
        memorySummaryRequirementChangeJob?.cancel()
        memorySummaryRequirementChangeJob = null
        viewModelScope.launch {
            chatService.clearProjectMemoryState(p.id)
            projectRepo.deleteProject(p.id)
            onSuccess()
        }
    }

    override fun onCleared() {
        super.onCleared()
        manualSummaryObservationJob?.cancel()
        memorySummaryRequirementChangeJob?.cancel()
    }
}
