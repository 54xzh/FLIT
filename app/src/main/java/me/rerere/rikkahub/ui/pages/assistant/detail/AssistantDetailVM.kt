package me.rerere.rikkahub.ui.pages.assistant.detail

import android.app.Application
import android.util.Log
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.ChatEpisodeDAO
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryRetrievalMode
import me.rerere.rikkahub.data.model.effectiveMemoryRetrievalMode
import me.rerere.rikkahub.data.model.requiresEmbedding
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.data.repository.AssistantMemoryStats
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.MemoryConsolidationScheduler
import me.rerere.rikkahub.data.repository.MemorySummaryRepository
import me.rerere.rikkahub.data.repository.MemorySummaryStatus
import me.rerere.rikkahub.data.repository.MemorySummaryUpdateOptions
import me.rerere.rikkahub.data.repository.MemorySummaryVersionOperationResult
import me.rerere.rikkahub.data.db.entity.MemorySummaryVersionEntity
import me.rerere.rikkahub.data.repository.MemoryRetrievalHit
import me.rerere.rikkahub.data.repository.MemoryRetrievalRequest
import me.rerere.rikkahub.data.repository.MemoryRetrievalService
import me.rerere.rikkahub.service.MemorySummaryRequirementChangeResult
import me.rerere.rikkahub.service.MemorySummaryRequirementChangeService
import me.rerere.rikkahub.service.MemorySummaryWorker
import me.rerere.rikkahub.utils.deleteChatFiles
import kotlin.uuid.Uuid

private const val TAG = "AssistantDetailVM"
private const val UI_EPISODE_LIMIT = 400
private const val UI_EPISODE_CONTENT_PREVIEW_LIMIT = 1200

class AssistantDetailVM(
    private val id: String,
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val memorySummaryRepository: MemorySummaryRepository,
    private val memoryRetrievalService: MemoryRetrievalService,
    private val conversationRepository: me.rerere.rikkahub.data.repository.ConversationRepository,
    private val context: Application,
    private val chatEpisodeDAO: ChatEpisodeDAO,
    private val memorySummaryRequirementChangeService: MemorySummaryRequirementChangeService,
) : ViewModel() {
    private val assistantId = Uuid.parse(id)
    private val memoryConsolidationScheduler = MemoryConsolidationScheduler(context)

    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Lazily, Settings.dummy())

    val mcpServerConfigs = settingsStore
        .settingsFlow.map { settings ->
            settings.mcpServers
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Lazily, initialValue = emptyList()
        )

    val assistant: StateFlow<Assistant> = settingsStore
        .settingsFlow
        .map { settings ->
            settings.assistants.find { it.id == assistantId } ?: Assistant()
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Lazily, initialValue = Assistant()
        )

    private val _memorySearchQuery = MutableStateFlow("")
    val memorySearchQuery = _memorySearchQuery.asStateFlow()

    fun updateMemorySearchQuery(query: String) {
        _memorySearchQuery.value = query
    }

    fun getPagedMemories(
        memoryType: Int,
        sortOrder: Int,
        searchQuery: String = memorySearchQuery.value,
    ): Flow<PagingData<AssistantMemory>> = memoryRepository.getAssistantMemoriesPaging(
        assistantId = assistantId.toString(),
        memoryType = memoryType,
        searchQuery = searchQuery,
        sortOrder = sortOrder
    ).cachedIn(viewModelScope)

    private val uiEpisodesFlow = chatEpisodeDAO.getEpisodesForUiFlow(
        assistantId = assistantId.toString(),
        limit = UI_EPISODE_LIMIT,
        contentPreviewLimit = UI_EPISODE_CONTENT_PREVIEW_LIMIT,
    )

    val memoryStats = memoryRepository.getAssistantMemoryStatsFlow(assistantId.toString()).stateIn(
        scope = viewModelScope,
        started = SharingStarted.Lazily,
        initialValue = AssistantMemoryStats()
    )

    val memorySummaryStatus: StateFlow<MemorySummaryStatus> = memorySummaryRepository
        .observeStatus(assistantId.toString())
        .stateIn(viewModelScope, SharingStarted.Lazily, MemorySummaryStatus())

    val memorySummaryVersions: StateFlow<List<MemorySummaryVersionEntity>> = memorySummaryRepository
        .observeVersions(assistantId.toString())
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val isManualMemorySummaryRunning: StateFlow<Boolean> = memorySummaryRepository
        .observeManualRunning(assistantId.toString())
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    private var manualSummaryObservationJob: Job? = null

    init {
        viewModelScope.launch {
            val runningWork = memorySummaryRepository.getUnfinishedManualWork(assistantId.toString())
            if (runningWork != null) {
                observeManualSummaryWork(runningWork.id)
            }
        }
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

    private var memorySummaryRequirementChangeJob: Job? = null

    fun updateMemorySummaryRequirement(requirement: String) {
        if (!_isMemorySummaryRequirementChangeRunning.value) {
            _memorySummaryRequirement.value = requirement
        }
    }

    fun submitMemorySummaryRequirementChange() {
        val requirement = _memorySummaryRequirement.value.trim()
        if (requirement.isEmpty() || _isMemorySummaryRequirementChangeRunning.value) return
        memorySummaryRequirementChangeJob = viewModelScope.launch {
            _isMemorySummaryRequirementChangeRunning.value = true
            try {
                when (val result = memorySummaryRequirementChangeService.revise(
                    assistantId = assistantId.toString(),
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
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.e(TAG, "Failed to change memory summary from requirement", error)
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

    val isManualMemoryConsolidationRunning: StateFlow<Boolean> = memoryConsolidationScheduler
        .observeFullScan(assistantId.toString())
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    // Current embedding model ID for this assistant (for detecting model mismatch)
    val currentEmbeddingModelId: StateFlow<String> = combine(
        assistant,
        settings
    ) { assistant, settings ->
        (assistant.embeddingModelId ?: settings.embeddingModelId).toString()
    }.stateIn(
        scope = viewModelScope, started = SharingStarted.Lazily, initialValue = ""
    )

    val episodes = uiEpisodesFlow
        .stateIn(
            scope = viewModelScope, started = SharingStarted.Lazily, initialValue = emptyList()
        )

    val episodeStats = combine(
        chatEpisodeDAO.getEpisodeOverviewStatsFlow(assistantId.toString()),
        memoryStats
    ) { episodeOverview, stats ->
        val totalEpisodes = episodeOverview.totalEpisodes
        val avgSig = episodeOverview.averageSignificance ?: 0.0
        val coreCount = stats.coreCount
        EpisodeStats(totalEpisodes, avgSig, coreCount)
    }.stateIn(viewModelScope, SharingStarted.Lazily, EpisodeStats(0, 0.0, 0))

    val providers = settingsStore
        .settingsFlow
        .map { settings ->
            settings.providers
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Lazily, initialValue = emptyList()
        )

    val tags = settingsStore
        .settingsFlow
        .map { settings ->
            settings.assistantTags
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Lazily, initialValue = emptyList()
        )

    fun updateTags(tagIds: List<Uuid>, tags: List<Tag>) {
        viewModelScope.launch {
            // First, update the global tags list
            val currentSettings = settingsStore.settingsFlow.value
            settingsStore.update(
                settings = currentSettings.copy(
                    assistantTags = tags
                )
            )
            
            // Then, update this assistant's tags
            val updatedAssistant = assistant.value.copy(tags = tagIds.toList())
            val latestSettings = settingsStore.settingsFlow.value
            settingsStore.update(
                settings = latestSettings.copy(
                    assistants = latestSettings.assistants.map {
                        if (it.id == updatedAssistant.id) updatedAssistant else it
                    }
                )
            )
            
            Log.d(TAG, "updateTags: ${tagIds.joinToString(",")}")
            
            // Now cleanup unused tags using the fresh state
            cleanupUnusedTagsInternal()
        }
    }

    private suspend fun cleanupUnusedTagsInternal() {
        // Use fresh settings after all updates
        val settings = settingsStore.settingsFlow.value
        val validTagIds = settings.assistantTags.map { it.id }.toSet()

        // 清理 assistant 中的无效 tag id
        val cleanedAssistants = settings.assistants.map { assistant ->
            val validTags = assistant.tags.filter { tagId ->
                validTagIds.contains(tagId)
            }
            if (validTags.size != assistant.tags.size) {
                assistant.copy(tags = validTags)
            } else {
                assistant
            }
        }

        // 获取清理后的 assistant 中使用的 tag id
        val usedTagIds = cleanedAssistants.flatMap { it.tags }.toSet()

        // 清理未使用的 tags
        val cleanedTags = settings.assistantTags.filter { tag ->
            usedTagIds.contains(tag.id)
        }

        // 检查是否需要更新
        val needUpdateAssistants = cleanedAssistants != settings.assistants
        val needUpdateTags = cleanedTags.size != settings.assistantTags.size

        if (needUpdateAssistants || needUpdateTags) {
            settingsStore.update(
                settings = settings.copy(
                    assistants = cleanedAssistants,
                    assistantTags = cleanedTags
                )
            )
            Log.d(TAG, "cleanupUnusedTags: removed ${settings.assistantTags.size - cleanedTags.size} unused tags")
        }
    }

    fun cleanupUnusedTags() {
        viewModelScope.launch {
            cleanupUnusedTagsInternal()
        }
    }

    fun update(assistant: Assistant) {
        viewModelScope.launch {
            val currentSettings = settingsStore.settingsFlow.value
            val previousAssistant = currentSettings.assistants.firstOrNull { it.id == assistant.id }
            settingsStore.update(
                settings = currentSettings.copy(
                    assistants = currentSettings.assistants.map {
                        if (it.id == assistant.id) {
                            checkAvatarDelete(old = it, new = assistant) // 删除旧头像
                            checkBackgroundDelete(old = it, new = assistant) // 删除旧背景
                            assistant
                        } else {
                            it
                        }
                    })
            )
            if (
                assistant.enableMemory &&
                assistant.effectiveMemoryRetrievalMode().requiresEmbedding &&
                previousAssistant?.effectiveMemoryRetrievalMode()?.requiresEmbedding != true
            ) {
                memoryRepository.scheduleEmbeddingBackfillIfNeeded(
                    assistantId = assistant.id.toString(),
                    includeCore = assistant.ragIncludeCore,
                    includeEpisodes = assistant.ragIncludeEpisodes,
                )
            }
            if (
                assistant.enableMemorySummary &&
                assistant.enableAutoMemorySummary &&
                (
                    previousAssistant?.enableMemory != assistant.enableMemory ||
                    previousAssistant?.enableMemorySummary != assistant.enableMemorySummary ||
                        previousAssistant?.enableAutoMemorySummary != assistant.enableAutoMemorySummary ||
                        previousAssistant?.memorySummaryChangeThreshold != assistant.memorySummaryChangeThreshold ||
                        previousAssistant?.memorySummaryIntervalDays != assistant.memorySummaryIntervalDays
                    )
            ) {
                memorySummaryRepository.scheduleAutomaticCheck(assistant.id.toString())
            }
        }
    }

    fun addMemory(memory: AssistantMemory) {
        viewModelScope.launch {
            val normalizedContent = memory.content.trim()
            if (normalizedContent.isEmpty()) return@launch
            memoryRepository.addMemory(
                assistantId = assistantId.toString(),
                content = normalizedContent,
                pinned = memory.pinned,
                generateEmbedding = assistant.value.effectiveMemoryRetrievalMode().requiresEmbedding,
            )
        }
    }

    fun updateMemory(memory: AssistantMemory) {
        viewModelScope.launch {
            val normalizedContent = memory.content.trim()
            if (normalizedContent.isEmpty()) return@launch
            if (memory.id < 0) {
                memoryRepository.updateEpisodeContent(
                    id = -memory.id,
                    content = normalizedContent,
                    generateEmbedding = assistant.value.effectiveMemoryRetrievalMode().requiresEmbedding,
                )
            } else {
                memoryRepository.updateCoreMemory(
                    id = memory.id,
                    content = normalizedContent,
                    pinned = memory.pinned,
                    generateEmbedding = assistant.value.effectiveMemoryRetrievalMode().requiresEmbedding,
                )
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
            if (fullEpisode == null) {
                onResolved(memory)
                return@launch
            }
            onResolved(fullEpisode)
        }
    }

    fun resolveMemoryByRoute(
        memoryId: Int,
        initialMemoryTab: Int?,
        onResolved: (AssistantMemory?) -> Unit,
    ) {
        viewModelScope.launch {
            val resolved = withContext(Dispatchers.IO) {
                val shouldResolveEpisode = memoryId < 0 || initialMemoryTab == 1
                if (shouldResolveEpisode) {
                    memoryRepository.getEpisodeMemoryById(kotlin.math.abs(memoryId))
                } else {
                    memoryRepository.getCoreMemoryById(memoryId)
                }
            }
            onResolved(resolved)
        }
    }

    fun applyBackgroundPromptToAll(prompt: String) {
        viewModelScope.launch {
            val currentSettings = settingsStore.settingsFlow.value
            settingsStore.update(
                settings = currentSettings.copy(
                    assistants = currentSettings.assistants.map { it.copy(backgroundPrompt = prompt) }
                )
            )
        }
    }

    fun applyConsolidationPromptToAll(prompt: String) {
        viewModelScope.launch {
            val currentSettings = settingsStore.settingsFlow.value
            settingsStore.update(
                settings = currentSettings.copy(
                    assistants = currentSettings.assistants.map { it.copy(consolidationPrompt = prompt) }
                )
            )
        }
    }

    fun applyMemorySummaryPromptToAll(prompt: String) {
        viewModelScope.launch {
            val currentSettings = settingsStore.settingsFlow.value
            settingsStore.update(
                settings = currentSettings.copy(
                    assistants = currentSettings.assistants.map { it.copy(memorySummaryPrompt = prompt) }
                )
            )
        }
    }

    fun applyContextSummaryPromptToAll(prompt: String) {
        viewModelScope.launch {
            val currentSettings = settingsStore.settingsFlow.value
            settingsStore.update(
                settings = currentSettings.copy(
                    assistants = currentSettings.assistants.map { it.copy(contextSummaryPrompt = prompt) }
                )
            )
        }
    }

    fun deleteMemory(memory: AssistantMemory) {
        viewModelScope.launch {
            if (memory.id > 0) {
                memoryRepository.deleteMemory(id = memory.id)
            } else if (memory.id < 0 && memory.type == 1) {
                memoryRepository.deleteEpisodeMemory(id = -memory.id)
            }
        }
    }

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage = _snackbarMessage.asStateFlow()

    fun clearSnackbarMessage() {
        _snackbarMessage.value = null
    }

    private val _embeddingProgress = MutableStateFlow<EmbeddingProgress?>(null)
    val embeddingProgress = _embeddingProgress.asStateFlow()

    // Check if any memories need embedding (just checks if embedding exists, cache handles model switching)
    val needsEmbeddingRegeneration: StateFlow<Boolean> = memoryRepository
        .hasPendingEmbeddingsFlow(assistantId.toString())
        .stateIn(
        scope = viewModelScope,
        started = SharingStarted.Lazily,
        initialValue = false
    )

    private val _retrievalResults = MutableStateFlow<List<MemoryRetrievalHit>>(emptyList())
    val retrievalResults = _retrievalResults.asStateFlow()

    fun testRetrieval(query: String) {
        viewModelScope.launch {
            try {
                val normalizedQuery = query.trim()
                val currentAssistant = assistant.value
                val limit = currentAssistant.ragLimit.coerceIn(0, 50)
                
                val results = memoryRetrievalService.retrieve(
                    MemoryRetrievalRequest(
                        assistantId = assistantId.toString(),
                        mode = currentAssistant.effectiveMemoryRetrievalMode(),
                        query = normalizedQuery,
                        limit = limit,
                        similarityThreshold = 0f,
                        includeCore = currentAssistant.ragIncludeCore,
                        includeEpisodes = currentAssistant.ragIncludeEpisodes,
                    )
                )
                _retrievalResults.value = results.hits
            } catch (e: Exception) {
                Log.e(TAG, "Failed to test retrieval", e)
                _snackbarMessage.value = "Retrieval failed: ${e.message}"
            }
        }
    }

    fun clearRetrievalResults() {
        _retrievalResults.value = emptyList()
    }

    fun scheduleEmbeddingBackfillForRetrieval(mode: MemoryRetrievalMode = assistant.value.effectiveMemoryRetrievalMode()) {
        val currentAssistant = assistant.value
        if (!currentAssistant.enableMemory || !mode.requiresEmbedding) return
        memoryRepository.scheduleEmbeddingBackfillIfNeeded(
            assistantId = assistantId.toString(),
            includeCore = currentAssistant.ragIncludeCore,
            includeEpisodes = currentAssistant.ragIncludeEpisodes,
        )
    }

    fun regenerateEmbeddings() {
        if (!assistant.value.enableMemory || !assistant.value.effectiveMemoryRetrievalMode().requiresEmbedding) return
        viewModelScope.launch {
            try {
                _embeddingProgress.value = EmbeddingProgress(0, 1, true)
                
                val (success, failure) = memoryRepository.regenerateEmbeddings(
                    assistantId = assistantId.toString(),
                    onProgress = { current, total ->
                        _embeddingProgress.value = EmbeddingProgress(current, total, true)
                    }
                )
                
                _embeddingProgress.value = null
                if (failure > 0) {
                    _snackbarMessage.value = "Completed: $success success, $failure failed. Check your API key or Model settings."
                } else if (success > 0) {
                    _snackbarMessage.value = "Successfully generated $success embeddings."
                } else {
                    _snackbarMessage.value = "No embeddings needed regeneration."
                }
                Log.i(TAG, "Regenerated embeddings: $success success, $failure failed")
            } catch (e: Exception) {
                _embeddingProgress.value = null
                _snackbarMessage.value = "Error: ${e.message}"
                Log.e(TAG, "Failed to regenerate embeddings", e)
            }
        }
    }

    fun consolidateAllMemories() {
        memoryConsolidationScheduler.enqueueFullScan(assistantId.toString())
        _snackbarMessage.value = context.getString(R.string.assistant_page_memory_consolidation_started)
    }

    fun cancelMemoryConsolidation() {
        viewModelScope.launch {
            memoryConsolidationScheduler.cancelFullScan(assistantId.toString())
            _snackbarMessage.value = context.getString(R.string.assistant_page_memory_consolidation_cancelled)
        }
    }

    fun cancelMemorySummary() {
        viewModelScope.launch {
            manualSummaryObservationJob?.cancel()
            manualSummaryObservationJob = null
            memorySummaryRepository.cancelManualUpdate(assistantId.toString())
            _snackbarMessage.value = context.getString(R.string.assistant_page_memory_summary_update_cancelled)
            memorySummaryRepository.scheduleAutomaticCheck(assistantId.toString())
        }
    }

    fun updateMemorySummary(options: MemorySummaryUpdateOptions) {
        val workId = memorySummaryRepository.requestManualUpdate(assistantId.toString(), options)
        _snackbarMessage.value = context.getString(R.string.assistant_page_memory_summary_update_started)
        observeManualSummaryWork(workId)
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

    fun activateMemorySummaryVersion(versionId: Long) {
        viewModelScope.launch {
            when (withContext(Dispatchers.IO) {
                memorySummaryRepository.activateVersion(assistantId.toString(), versionId)
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

    fun saveManualMemorySummaryVersion(baseVersionId: Long, content: String) {
        viewModelScope.launch {
            when (withContext(Dispatchers.IO) {
                memorySummaryRepository.createManualVersion(
                    assistantId = assistantId.toString(),
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
                MemorySummaryVersionOperationResult.STALE_ACTIVE_VERSION -> Unit
            }
        }
    }

    fun deleteMemorySummaryHistoryVersion(versionId: Long) {
        viewModelScope.launch {
            when (withContext(Dispatchers.IO) {
                memorySummaryRepository.deleteHistoryVersion(assistantId.toString(), versionId)
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

    fun checkAvatarDelete(old: Assistant, new: Assistant) {
        if (old.avatar is Avatar.Image && old.avatar != new.avatar) {
            context.deleteChatFiles(listOf(old.avatar.url.toUri()))
        }
    }

    fun checkBackgroundDelete(old: Assistant, new: Assistant) {
        val oldBackground = old.background
        val newBackground = new.background

        if (oldBackground != null && oldBackground != newBackground) {
            try {
                val oldUri = oldBackground.toUri()
                if (oldUri.scheme == "content" || oldUri.scheme == "file") {
                    context.deleteChatFiles(listOf(oldUri))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete background file: $oldBackground", e)
            }
        }
    }

    // Token Estimation Logic
    fun estimateTokens(text: String): Int = text.length / 4

    val averageMessageLength = conversationRepository.getAverageMessageLength(assistantId)
        .stateIn(viewModelScope, SharingStarted.Lazily, 100)

    val averageMemoryLength = memoryRepository.getAverageMemoryLength(assistantId.toString())
        .stateIn(viewModelScope, SharingStarted.Lazily, 150)

    val systemPromptTokenCount = assistant.map {
        estimateTokens(it.systemPrompt)
    }.stateIn(viewModelScope, SharingStarted.Lazily, 0)

    val smartMinTokenUsage = combine(
        assistant,
        averageMessageLength,
        averageMemoryLength
    ) { assistant, avgMsgLen, avgMemLen ->
        val sysPrompt = estimateTokens(assistant.systemPrompt)
        
        // Dynamic estimates based on history
        val avgMsgTokens = (avgMsgLen / 4).coerceAtLeast(10)
        val avgMemTokens = (avgMemLen / 4).coerceAtLeast(10)

        val minHistory = avgMsgTokens * 2 // At least 2 messages
        val minMemory = if (assistant.enableMemory) avgMemTokens * 2 else 0 // At least 2 memories
        val buffer = 200
        sysPrompt + minHistory + minMemory + buffer
    }.stateIn(viewModelScope, SharingStarted.Lazily, 1000)

    val estimatedMemoryCapacity = combine(
        assistant,
        smartMinTokenUsage,
        averageMemoryLength
    ) { assistant, minUsage, avgMemLen ->
        val total = assistant.maxTokenUsage
        val available = (total - minUsage).coerceAtLeast(0)
        val avgMemTokens = (avgMemLen / 4).coerceAtLeast(10)
        
        // If RAG is enabled, how many memories can we fit in the remaining space?
        // This is a rough upper bound for the slider
        (available / avgMemTokens).coerceAtLeast(5) // Minimum 5
    }.stateIn(viewModelScope, SharingStarted.Lazily, 10)

    val estimatedAllocation = combine(
        assistant,
        averageMessageLength,
        averageMemoryLength
    ) { assistant, avgMsgLen, avgMemLen ->
        val total = assistant.maxTokenUsage
        val sysPrompt = estimateTokens(assistant.systemPrompt)
        val remaining = total - sysPrompt

        if (remaining <= 0) {
            "System prompt consumes all tokens!"
        } else {
            val avgMsgTokens = (avgMsgLen / 4).coerceAtLeast(10)
            val avgMemTokens = (avgMemLen / 4).coerceAtLeast(10)

            // Calculate how many messages OR memories can fit in the remaining space
            val estHistoryMsgs = remaining / avgMsgTokens
            val estMemories = remaining / avgMemTokens

            "Est. History: ~$estHistoryMsgs msgs or Memories: ~$estMemories"
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, "Calculating...")

    override fun onCleared() {
        super.onCleared()
        manualSummaryObservationJob?.cancel()
    }
}

data class EmbeddingProgress(
    val current: Int,
    val total: Int,
    val isRunning: Boolean
)

data class EpisodeStats(
    val totalEpisodes: Int,
    val averageSignificance: Double,
    val coreMemoryCount: Int
)
