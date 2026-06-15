package me.rerere.rikkahub.ui.pages.storage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.AssistantChatCleanupMode
import me.rerere.rikkahub.data.repository.AssistantAttachmentStats
import me.rerere.rikkahub.data.repository.AssistantFileEntry
import me.rerere.rikkahub.data.repository.AssistantImageEntry
import me.rerere.rikkahub.data.repository.CacheTopLevelUsage
import me.rerere.rikkahub.data.repository.ChatRecordsMonthEntry
import me.rerere.rikkahub.data.repository.DeleteResult
import me.rerere.rikkahub.data.repository.LightConversationEntity
import me.rerere.rikkahub.data.repository.OrphanScanResult
import me.rerere.rikkahub.data.repository.StorageCategoryKey
import me.rerere.rikkahub.data.repository.StorageCategoryUsage
import me.rerere.rikkahub.data.repository.StorageManagerRepository
import me.rerere.rikkahub.utils.UiState
import kotlin.uuid.Uuid

data class AttachmentListState<T>(
    val items: List<T>,
    val totalCount: Int,
    val totalBytes: Long,
    val hasMore: Boolean,
    val isLoadingMore: Boolean,
)

internal fun <T> buildAttachmentListState(
    allItems: List<T>,
    limit: Int,
    totalBytes: Long,
    isLoadingMore: Boolean = false,
): AttachmentListState<T> {
    val safeLimit = limit.coerceAtLeast(0)
    val totalCount = allItems.size
    val items = if (safeLimit >= totalCount) allItems else allItems.take(safeLimit)
    return AttachmentListState(
        items = items,
        totalCount = totalCount,
        totalBytes = totalBytes,
        hasMore = items.size < totalCount,
        isLoadingMore = isLoadingMore,
    )
}

class StorageCategoryVM(
    categoryKey: String,
    private val settingsStore: SettingsStore,
    private val storageRepo: StorageManagerRepository,
) : ViewModel() {
    companion object {
        private const val GLOBAL_ATTACHMENT_PAGE_SIZE = 20
        private const val DELETE_RECONCILE_DEBOUNCE_MS = 400L
    }

    val category: StorageCategoryKey = StorageCategoryKey.fromKeyOrNull(categoryKey)
        ?: StorageCategoryKey.CHAT_RECORDS

    val assistants: StateFlow<List<Assistant>> = settingsStore.settingsFlow
        .map { settings ->
            buildList {
                addAll(settings.assistants)
                settings.groupChatTemplates.forEach { template ->
                    if (none { it.id == template.id }) {
                        add(
                            Assistant(
                                id = template.id,
                                name = template.name,
                            )
                        )
                    }
                }
            }
        }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private val _selectedAssistantId = MutableStateFlow<Uuid?>(null)
    val selectedAssistantId: StateFlow<Uuid?> = _selectedAssistantId.asStateFlow()

    private val _categoryUsage = MutableStateFlow<UiState<StorageCategoryUsage>>(UiState.Idle)
    val categoryUsage: StateFlow<UiState<StorageCategoryUsage>> = _categoryUsage.asStateFlow()

    private val _assistantAttachmentStats = MutableStateFlow<UiState<AssistantAttachmentStats>>(UiState.Idle)
    val assistantAttachmentStats: StateFlow<UiState<AssistantAttachmentStats>> = _assistantAttachmentStats.asStateFlow()

    private val _assistantConversationCount = MutableStateFlow<UiState<Int>>(UiState.Idle)
    val assistantConversationCount: StateFlow<UiState<Int>> = _assistantConversationCount.asStateFlow()

    private val _assistantImages = MutableStateFlow<UiState<AttachmentListState<AssistantImageEntry>>>(UiState.Idle)
    val assistantImages: StateFlow<UiState<AttachmentListState<AssistantImageEntry>>> = _assistantImages.asStateFlow()

    private val _assistantFiles = MutableStateFlow<UiState<AttachmentListState<AssistantFileEntry>>>(UiState.Idle)
    val assistantFiles: StateFlow<UiState<AttachmentListState<AssistantFileEntry>>> = _assistantFiles.asStateFlow()

    private val _chatRecordMonths = MutableStateFlow<UiState<List<ChatRecordsMonthEntry>>>(UiState.Idle)
    val chatRecordMonths: StateFlow<UiState<List<ChatRecordsMonthEntry>>> = _chatRecordMonths.asStateFlow()

    private val _orphanScan = MutableStateFlow<UiState<OrphanScanResult>>(UiState.Idle)
    val orphanScan: StateFlow<UiState<OrphanScanResult>> = _orphanScan.asStateFlow()

    private val _cacheTopLevelUsage = MutableStateFlow<UiState<List<CacheTopLevelUsage>>>(UiState.Idle)
    val cacheTopLevelUsage: StateFlow<UiState<List<CacheTopLevelUsage>>> = _cacheTopLevelUsage.asStateFlow()

    private val _action = MutableStateFlow<UiState<DeleteResult>>(UiState.Idle)
    val action: StateFlow<UiState<DeleteResult>> = _action.asStateFlow()

    private var globalImageEntries: List<AssistantImageEntry> = emptyList()
    private var globalImageBytes: Long = 0L
    private var globalImageLimit: Int = GLOBAL_ATTACHMENT_PAGE_SIZE

    private var globalFileEntries: List<AssistantFileEntry> = emptyList()
    private var globalFileBytes: Long = 0L
    private var globalFileLimit: Int = GLOBAL_ATTACHMENT_PAGE_SIZE
    private var pendingImageReconcileJob: Job? = null
    private var pendingFileReconcileJob: Job? = null

    init {
        refresh()
    }

    fun refresh(force: Boolean = false) {
        refreshUsage(force = force)

        val assistantId = _selectedAssistantId.value
        when (category) {
            StorageCategoryKey.IMAGES -> {
                if (assistantId == null) {
                    loadGlobalImages(forceRefresh = force)
                } else {
                    reloadAssistantData(assistantId, forceRefresh = force)
                }
            }

            StorageCategoryKey.FILES -> {
                if (assistantId == null) {
                    loadGlobalFiles(forceRefresh = force)
                } else {
                    reloadAssistantData(assistantId, forceRefresh = force)
                }
            }

            StorageCategoryKey.CHAT_RECORDS -> {
                loadChatRecordMonths(assistantId = assistantId)
                if (assistantId != null) {
                    reloadAssistantData(assistantId, forceRefresh = force)
                }
            }

            StorageCategoryKey.CACHE -> refreshCacheTopLevelUsage()

            else -> Unit
        }
    }

    fun refreshUsage(force: Boolean = false) {
        viewModelScope.launch {
            val cached = storageRepo.peekDiskOverviewCache()
                ?.categories
                ?.firstOrNull { it.category == category }
            _categoryUsage.value = cached?.let { UiState.Success(it) } ?: UiState.Loading
            _categoryUsage.value = runCatching {
                when (category) {
                    StorageCategoryKey.CACHE -> storageRepo.getCacheUsage()
                    StorageCategoryKey.CHAT_RECORDS -> storageRepo.getChatRecordsUsage()
                    StorageCategoryKey.LOGS -> storageRepo.getLogsUsage()
                    else -> storageRepo.loadOverview(forceRefresh = force).categories.first { it.category == category }
                }
            }.fold(
                onSuccess = { UiState.Success(it) },
                onFailure = { UiState.Error(it) },
            )
        }
    }

    private fun refreshCacheTopLevelUsage() {
        if (category != StorageCategoryKey.CACHE) return

        viewModelScope.launch {
            _cacheTopLevelUsage.value = UiState.Loading
            _cacheTopLevelUsage.value = runCatching { storageRepo.getCacheTopLevelUsage() }
                .fold(
                    onSuccess = { UiState.Success(it) },
                    onFailure = { UiState.Error(it) },
                )
        }
    }

    fun selectAssistant(id: Uuid?) {
        _selectedAssistantId.value = id
        if (id == null) {
            _assistantAttachmentStats.value = UiState.Idle
            _assistantConversationCount.value = UiState.Idle
            _assistantImages.value = UiState.Idle
            _assistantFiles.value = UiState.Idle

            when (category) {
                StorageCategoryKey.IMAGES -> loadGlobalImages()
                StorageCategoryKey.FILES -> loadGlobalFiles()
                StorageCategoryKey.CHAT_RECORDS -> loadChatRecordMonths(assistantId = null)
                else -> Unit
            }
            return
        }

        when (category) {
            StorageCategoryKey.IMAGES,
            StorageCategoryKey.FILES,
            StorageCategoryKey.CHAT_RECORDS,
            StorageCategoryKey.HISTORY_FILES,
            -> reloadAssistantData(id)

            else -> Unit
        }

        if (category == StorageCategoryKey.CHAT_RECORDS) {
            loadChatRecordMonths(assistantId = id)
        }
    }

    private fun reloadAssistantData(
        assistantId: Uuid,
        forceRefresh: Boolean = false,
        keepCurrentState: Boolean = false,
    ) {
        viewModelScope.launch {
            val loadAttachmentStats = category == StorageCategoryKey.FILES || category == StorageCategoryKey.CHAT_RECORDS
            val loadConversationCount = category == StorageCategoryKey.CHAT_RECORDS
            val loadImages = category == StorageCategoryKey.IMAGES
            val loadFiles = category == StorageCategoryKey.FILES

            val cachedImages = if (loadImages) storageRepo.peekAssistantImageEntriesCache(assistantId) else null
            val cachedFiles = if (loadFiles) storageRepo.peekAssistantFileEntriesCache(assistantId) else null

            val currentAttachmentStats = _assistantAttachmentStats.value
            val currentConversationCount = _assistantConversationCount.value
            val currentImages = _assistantImages.value
            val currentFiles = _assistantFiles.value

            _assistantAttachmentStats.value = when {
                !loadAttachmentStats -> UiState.Idle
                keepCurrentState && currentAttachmentStats is UiState.Success -> currentAttachmentStats
                else -> UiState.Loading
            }
            _assistantConversationCount.value = when {
                !loadConversationCount -> UiState.Idle
                keepCurrentState && currentConversationCount is UiState.Success -> currentConversationCount
                else -> UiState.Loading
            }
            _assistantImages.value = when {
                !loadImages -> UiState.Idle
                keepCurrentState && currentImages is UiState.Success -> currentImages
                cachedImages != null -> UiState.Success(
                    buildAttachmentListState(
                        allItems = cachedImages,
                        limit = cachedImages.size,
                        totalBytes = cachedImages.sumOf { it.bytes },
                    )
                )

                else -> UiState.Loading
            }
            _assistantFiles.value = when {
                !loadFiles -> UiState.Idle
                keepCurrentState && currentFiles is UiState.Success -> currentFiles
                cachedFiles != null -> UiState.Success(
                    buildAttachmentListState(
                        allItems = cachedFiles,
                        limit = cachedFiles.size,
                        totalBytes = cachedFiles.sumOf { it.bytes },
                    )
                )

                else -> UiState.Loading
            }

            val statsState = if (loadAttachmentStats) {
                runCatching { storageRepo.getAssistantAttachmentStats(assistantId) }
                    .fold(
                        onSuccess = { UiState.Success(it) },
                        onFailure = { UiState.Error(it) },
                    )
            } else {
                UiState.Idle
            }
            val countState = if (loadConversationCount) {
                runCatching { storageRepo.getAssistantConversationCount(assistantId) }
                    .fold(
                        onSuccess = { UiState.Success(it) },
                        onFailure = { UiState.Error(it) },
                    )
            } else {
                UiState.Idle
            }
            val imagesState = if (loadImages) {
                runCatching { storageRepo.getAssistantImageEntries(assistantId, forceRefresh = forceRefresh) }
                    .fold(
                        onSuccess = { entries ->
                            val bytes = entries.sumOf { it.bytes }
                            UiState.Success(buildAttachmentListState(entries, entries.size, bytes))
                        },
                        onFailure = {
                            if (keepCurrentState && _assistantImages.value is UiState.Success) {
                                _assistantImages.value
                            } else {
                                UiState.Error(it)
                            }
                        },
                    )
            } else {
                UiState.Idle
            }

            val filesState = if (loadFiles) {
                runCatching { storageRepo.getAssistantFileEntries(assistantId, forceRefresh = forceRefresh) }
                    .fold(
                        onSuccess = { entries ->
                            val bytes = entries.sumOf { it.bytes }
                            UiState.Success(buildAttachmentListState(entries, entries.size, bytes))
                        },
                        onFailure = {
                            if (keepCurrentState && _assistantFiles.value is UiState.Success) {
                                _assistantFiles.value
                            } else {
                                UiState.Error(it)
                            }
                        },
                    )
            } else {
                UiState.Idle
            }

            _assistantAttachmentStats.value = statsState
            _assistantConversationCount.value = countState
            _assistantImages.value = imagesState
            _assistantFiles.value = filesState
        }
    }

    private fun loadChatRecordMonths(assistantId: Uuid?) {
        if (category != StorageCategoryKey.CHAT_RECORDS) return
        viewModelScope.launch {
            _chatRecordMonths.value = UiState.Loading
            _chatRecordMonths.value = runCatching { storageRepo.getChatRecordsMonthEntries(assistantId) }
                .fold(
                    onSuccess = { UiState.Success(it) },
                    onFailure = { UiState.Error(it) },
                )
        }
    }

    suspend fun getChatRecordConversationsByYearMonth(
        assistantId: Uuid?,
        yearMonth: String,
    ): List<LightConversationEntity> {
        if (category != StorageCategoryKey.CHAT_RECORDS) return emptyList()
        return storageRepo.getChatRecordConversationsByYearMonth(
            assistantId = assistantId,
            yearMonth = yearMonth,
        )
    }

    fun clearChatRecordsByYearMonths(assistantId: Uuid?, yearMonths: Set<String>) {
        if (category != StorageCategoryKey.CHAT_RECORDS) return
        if (yearMonths.isEmpty()) return

        viewModelScope.launch {
            _action.value = UiState.Loading
            _action.value = runCatching {
                storageRepo.clearChatRecordsByYearMonths(
                    assistantId = assistantId,
                    yearMonths = yearMonths,
                )
            }.fold(
                onSuccess = { UiState.Success(it) },
                onFailure = { UiState.Error(it) },
            )
            refreshUsage()
            loadChatRecordMonths(assistantId)
            if (assistantId != null) {
                reloadAssistantData(assistantId)
            }
        }
    }

    fun clearChatRecordsSelection(
        assistantId: Uuid?,
        yearMonths: Set<String>,
        conversationIds: Set<String>,
    ) {
        if (category != StorageCategoryKey.CHAT_RECORDS) return
        if (yearMonths.isEmpty() && conversationIds.isEmpty()) return

        viewModelScope.launch {
            _action.value = UiState.Loading
            _action.value = runCatching {
                val byYearMonths = if (yearMonths.isEmpty()) {
                    DeleteResult(0, 0, 0L)
                } else {
                    storageRepo.clearChatRecordsByYearMonths(
                        assistantId = assistantId,
                        yearMonths = yearMonths,
                    )
                }

                val byConversationIds = if (conversationIds.isEmpty()) {
                    DeleteResult(0, 0, 0L)
                } else {
                    storageRepo.clearChatRecordsByConversationIds(conversationIds)
                }

                DeleteResult(
                    deletedCount = byYearMonths.deletedCount + byConversationIds.deletedCount,
                    failedCount = byYearMonths.failedCount + byConversationIds.failedCount,
                    deletedBytes = byYearMonths.deletedBytes + byConversationIds.deletedBytes,
                )
            }.fold(
                onSuccess = { UiState.Success(it) },
                onFailure = { UiState.Error(it) },
            )
            refreshUsage()
            loadChatRecordMonths(assistantId)
            if (assistantId != null) {
                reloadAssistantData(assistantId)
            }
        }
    }

    fun clearAssistantImages(assistantId: Uuid) {
        viewModelScope.launch {
            _action.value = UiState.Loading
            _action.value = runCatching {
                storageRepo.clearAssistantChatAttachments(
                    assistantId = assistantId,
                    clearImages = true,
                    clearFiles = false,
                )
            }.fold(
                onSuccess = { UiState.Success(it) },
                onFailure = { UiState.Error(it) },
            )
            refreshUsage()
            reloadAssistantData(assistantId)
        }
    }

    fun deleteImages(assistantId: Uuid?, absolutePaths: List<String>) {
        val targets = absolutePaths.asSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (targets.isEmpty()) return
        viewModelScope.launch {
            _action.value = UiState.Loading
            _action.value = runCatching { storageRepo.deleteAssistantImageEntries(targets.toList()) }
                .fold(
                    onSuccess = { UiState.Success(it) },
                    onFailure = { UiState.Error(it) },
                )

            val actionState = _action.value
            when (actionState) {
                is UiState.Success -> {
                    if (actionState.data.failedCount == 0) {
                        applyOptimisticImageDeletion(assistantId = assistantId, targetPaths = targets)
                        scheduleImageReconcile(assistantId)
                    } else {
                        forceRefreshImagesSilently(assistantId)
                    }
                }

                is UiState.Error -> forceRefreshImagesSilently(assistantId)
                else -> Unit
            }
        }
    }

    fun deleteFiles(assistantId: Uuid?, absolutePaths: List<String>) {
        val targets = absolutePaths.asSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (targets.isEmpty()) return
        viewModelScope.launch {
            _action.value = UiState.Loading
            _action.value = runCatching { storageRepo.deleteAssistantFileEntries(targets.toList()) }
                .fold(
                    onSuccess = { UiState.Success(it) },
                    onFailure = { UiState.Error(it) },
                )

            val actionState = _action.value
            when (actionState) {
                is UiState.Success -> {
                    if (actionState.data.failedCount == 0) {
                        applyOptimisticFileDeletion(assistantId = assistantId, targetPaths = targets)
                        scheduleFileReconcile(assistantId)
                    } else {
                        forceRefreshFilesSilently(assistantId)
                    }
                }

                is UiState.Error -> forceRefreshFilesSilently(assistantId)
                else -> Unit
            }
        }
    }

    private fun applyOptimisticImageDeletion(assistantId: Uuid?, targetPaths: Set<String>) {
        if (assistantId == null) {
            val remaining = globalImageEntries.filterNot { it.absolutePath in targetPaths }
            if (remaining.size == globalImageEntries.size) return
            globalImageEntries = remaining
            globalImageBytes = remaining.sumOf { it.bytes }
            _assistantImages.value = UiState.Success(
                buildAttachmentListState(
                    allItems = globalImageEntries,
                    limit = globalImageLimit,
                    totalBytes = globalImageBytes,
                )
            )
            return
        }

        val current = _assistantImages.value as? UiState.Success ?: return
        val updatedItems = current.data.items.filterNot { it.absolutePath in targetPaths }
        if (updatedItems.size == current.data.items.size) return
        val updatedBytes = updatedItems.sumOf { it.bytes }
        _assistantImages.value = UiState.Success(
            buildAttachmentListState(
                allItems = updatedItems,
                limit = updatedItems.size,
                totalBytes = updatedBytes,
            )
        )
    }

    private fun applyOptimisticFileDeletion(assistantId: Uuid?, targetPaths: Set<String>) {
        if (assistantId == null) {
            val remaining = globalFileEntries.filterNot { it.absolutePath in targetPaths }
            if (remaining.size == globalFileEntries.size) return
            globalFileEntries = remaining
            globalFileBytes = remaining.sumOf { it.bytes }
            _assistantFiles.value = UiState.Success(
                buildAttachmentListState(
                    allItems = globalFileEntries,
                    limit = globalFileLimit,
                    totalBytes = globalFileBytes,
                )
            )
            return
        }

        val current = _assistantFiles.value as? UiState.Success ?: return
        val updatedItems = current.data.items.filterNot { it.absolutePath in targetPaths }
        if (updatedItems.size == current.data.items.size) return
        val updatedBytes = updatedItems.sumOf { it.bytes }
        _assistantFiles.value = UiState.Success(
            buildAttachmentListState(
                allItems = updatedItems,
                limit = updatedItems.size,
                totalBytes = updatedBytes,
            )
        )
    }

    private fun scheduleImageReconcile(assistantId: Uuid?) {
        pendingImageReconcileJob?.cancel()
        pendingImageReconcileJob = viewModelScope.launch {
            delay(DELETE_RECONCILE_DEBOUNCE_MS)
            forceRefreshImagesSilently(assistantId)
        }
    }

    private fun scheduleFileReconcile(assistantId: Uuid?) {
        pendingFileReconcileJob?.cancel()
        pendingFileReconcileJob = viewModelScope.launch {
            delay(DELETE_RECONCILE_DEBOUNCE_MS)
            forceRefreshFilesSilently(assistantId)
        }
    }

    private fun forceRefreshImagesSilently(assistantId: Uuid?) {
        pendingImageReconcileJob?.cancel()
        if (assistantId == null) {
            loadGlobalImages(forceRefresh = true, keepCurrentState = true, resetLimit = false)
        } else {
            reloadAssistantData(
                assistantId = assistantId,
                forceRefresh = true,
                keepCurrentState = true,
            )
        }
    }

    private fun forceRefreshFilesSilently(assistantId: Uuid?) {
        pendingFileReconcileJob?.cancel()
        if (assistantId == null) {
            loadGlobalFiles(forceRefresh = true, keepCurrentState = true, resetLimit = false)
        } else {
            reloadAssistantData(
                assistantId = assistantId,
                forceRefresh = true,
                keepCurrentState = true,
            )
        }
    }

    fun clearAssistantFiles(assistantId: Uuid) {
        viewModelScope.launch {
            _action.value = UiState.Loading
            _action.value = runCatching {
                storageRepo.clearAssistantChatAttachments(
                    assistantId = assistantId,
                    clearImages = false,
                    clearFiles = true,
                )
            }.fold(
                onSuccess = { UiState.Success(it) },
                onFailure = { UiState.Error(it) },
            )
            refreshUsage()
            reloadAssistantData(assistantId)
        }
    }

    fun clearAssistantChats(assistantId: Uuid, mode: AssistantChatCleanupMode) {
        viewModelScope.launch {
            _action.value = UiState.Loading
            _action.value = runCatching {
                storageRepo.clearAssistantChats(
                    assistantId = assistantId,
                    mode = mode,
                )
            }.fold(
                onSuccess = { UiState.Success(it) },
                onFailure = { UiState.Error(it) },
            )
            refreshUsage()
            reloadAssistantData(assistantId)
        }
    }

    fun scanOrphans() {
        viewModelScope.launch {
            _orphanScan.value = UiState.Loading
            _orphanScan.value = runCatching { storageRepo.scanOrphans() }
                .fold(
                    onSuccess = { UiState.Success(it) },
                    onFailure = { UiState.Error(it) },
                )
        }
    }

    fun clearAllOrphans() {
        viewModelScope.launch {
            _action.value = UiState.Loading
            _action.value = runCatching { storageRepo.clearAllOrphans() }
                .fold(
                    onSuccess = { UiState.Success(it) },
                    onFailure = { UiState.Error(it) },
                )
            refreshUsage()
            scanOrphans()
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            _action.value = UiState.Loading
            _action.value = runCatching { storageRepo.clearCache() }
                .fold(
                    onSuccess = { UiState.Success(it) },
                    onFailure = { UiState.Error(it) },
                )
            refreshUsage()
            refreshCacheTopLevelUsage()
        }
    }

    fun loadMoreImages() {
        if (category != StorageCategoryKey.IMAGES) return
        if (_selectedAssistantId.value != null) return
        val current = _assistantImages.value as? UiState.Success ?: return
        val data = current.data
        if (!data.hasMore || data.isLoadingMore) return

        _assistantImages.value = UiState.Success(data.copy(isLoadingMore = true))
        viewModelScope.launch {
            globalImageLimit = minOf(globalImageLimit + GLOBAL_ATTACHMENT_PAGE_SIZE, globalImageEntries.size)
            _assistantImages.value = UiState.Success(
                buildAttachmentListState(
                    allItems = globalImageEntries,
                    limit = globalImageLimit,
                    totalBytes = globalImageBytes,
                )
            )
        }
    }

    fun loadMoreFiles() {
        if (category != StorageCategoryKey.FILES) return
        if (_selectedAssistantId.value != null) return
        val current = _assistantFiles.value as? UiState.Success ?: return
        val data = current.data
        if (!data.hasMore || data.isLoadingMore) return

        _assistantFiles.value = UiState.Success(data.copy(isLoadingMore = true))
        viewModelScope.launch {
            globalFileLimit = minOf(globalFileLimit + GLOBAL_ATTACHMENT_PAGE_SIZE, globalFileEntries.size)
            _assistantFiles.value = UiState.Success(
                buildAttachmentListState(
                    allItems = globalFileEntries,
                    limit = globalFileLimit,
                    totalBytes = globalFileBytes,
                )
            )
        }
    }

    private fun loadGlobalImages(
        forceRefresh: Boolean = false,
        keepCurrentState: Boolean = false,
        resetLimit: Boolean = true,
    ) {
        if (resetLimit) {
            globalImageLimit = GLOBAL_ATTACHMENT_PAGE_SIZE
        }

        val current = _assistantImages.value
        val cached = storageRepo.peekAllImageEntriesCache()
        if (cached != null) {
            globalImageEntries = cached
            globalImageBytes = cached.sumOf { it.bytes }
            if (!keepCurrentState || current !is UiState.Success) {
                _assistantImages.value = UiState.Success(
                    buildAttachmentListState(
                        allItems = cached,
                        limit = globalImageLimit,
                        totalBytes = globalImageBytes,
                    )
                )
            }
        }

        viewModelScope.launch {
            if (cached == null && (!keepCurrentState || current !is UiState.Success)) {
                _assistantImages.value = UiState.Loading
            }
            _assistantImages.value = runCatching {
                val entries = storageRepo.getAllImageEntries(forceRefresh = forceRefresh)
                globalImageEntries = entries
                globalImageBytes = entries.sumOf { it.bytes }
                buildAttachmentListState(
                    allItems = entries,
                    limit = globalImageLimit,
                    totalBytes = globalImageBytes,
                )
            }.fold(
                onSuccess = { UiState.Success(it) },
                onFailure = {
                    if (keepCurrentState && _assistantImages.value is UiState.Success) {
                        _assistantImages.value
                    } else {
                        UiState.Error(it)
                    }
                },
            )
        }
    }

    private fun loadGlobalFiles(
        forceRefresh: Boolean = false,
        keepCurrentState: Boolean = false,
        resetLimit: Boolean = true,
    ) {
        if (resetLimit) {
            globalFileLimit = GLOBAL_ATTACHMENT_PAGE_SIZE
        }

        val current = _assistantFiles.value
        val cached = storageRepo.peekAllFileEntriesCache()
        if (cached != null) {
            globalFileEntries = cached
            globalFileBytes = cached.sumOf { it.bytes }
            if (!keepCurrentState || current !is UiState.Success) {
                _assistantFiles.value = UiState.Success(
                    buildAttachmentListState(
                        allItems = cached,
                        limit = globalFileLimit,
                        totalBytes = globalFileBytes,
                    )
                )
            }
        }

        viewModelScope.launch {
            if (cached == null && (!keepCurrentState || current !is UiState.Success)) {
                _assistantFiles.value = UiState.Loading
            }
            _assistantFiles.value = runCatching {
                val entries = storageRepo.getAllFileEntries(forceRefresh = forceRefresh)
                globalFileEntries = entries
                globalFileBytes = entries.sumOf { it.bytes }
                buildAttachmentListState(
                    allItems = entries,
                    limit = globalFileLimit,
                    totalBytes = globalFileBytes,
                )
            }.fold(
                onSuccess = { UiState.Success(it) },
                onFailure = {
                    if (keepCurrentState && _assistantFiles.value is UiState.Success) {
                        _assistantFiles.value
                    } else {
                        UiState.Error(it)
                    }
                },
            )
        }
    }

    override fun onCleared() {
        pendingImageReconcileJob?.cancel()
        pendingFileReconcileJob?.cancel()
        super.onCleared()
    }
}
