package me.rerere.rikkahub.ui.pages.developer

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.MemoryConsolidationScheduler
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.MemorySummaryRepository
import me.rerere.rikkahub.data.repository.OldEpisodeCleanupPreview
import me.rerere.rikkahub.data.repository.OldEpisodeCleanupResult
import me.rerere.rikkahub.data.repository.oldEpisodeCleanupCutoff
import me.rerere.rikkahub.data.ai.AILoggingManager
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.rikkahub.utils.UpdateInfo
import me.rerere.rikkahub.utils.UpdateSource
import me.rerere.rikkahub.web.NsdServiceRegistrar

data class DeveloperIpv6DebugState(
    val isLoading: Boolean = false,
    val hasChecked: Boolean = false,
    val httpIpv6: String? = null,
    val systemIpv6: String? = null,
)

data class OldEpisodeCleanupUiState(
    val isLoading: Boolean = false,
    val preview: OldEpisodeCleanupPreview? = null,
    val result: OldEpisodeCleanupResult? = null,
    val error: String? = null,
)

data class ManualConsolidationCancellationUiState(
    val isCancelling: Boolean = false,
    val cancelledCount: Int? = null,
    val error: String? = null,
)

class DeveloperVM(
    private val aiLoggingManager: AILoggingManager,
    private val settingsStore: SettingsStore,
    private val context: Context,
    private val updateChecker: UpdateChecker,
    private val memoryRepository: MemoryRepository,
    private val memorySummaryRepository: MemorySummaryRepository,
) : ViewModel() {
    val logs = aiLoggingManager.getLogs()
    val settings = settingsStore.settingsFlow
    private val nsdServiceRegistrar by lazy { NsdServiceRegistrar(context.applicationContext) }

    private val _ipv6DebugState = MutableStateFlow(DeveloperIpv6DebugState())
    val ipv6DebugState = _ipv6DebugState.asStateFlow()

    private val _updateState = MutableStateFlow<UiState<UpdateInfo>?>(null)
    val updateState = _updateState.asStateFlow()

    private val _selectedSource = MutableStateFlow(UpdateSource.GITHUB)
    val selectedSource = _selectedSource.asStateFlow()
    private val memoryConsolidationScheduler = MemoryConsolidationScheduler(context)

    val manualConsolidationWorkCount = memoryConsolidationScheduler.observeManualWorkCount()
    private val _manualConsolidationCancellationState = MutableStateFlow(ManualConsolidationCancellationUiState())
    val manualConsolidationCancellationState = _manualConsolidationCancellationState.asStateFlow()
    private val _oldEpisodeCleanupState = MutableStateFlow(OldEpisodeCleanupUiState())
    val oldEpisodeCleanupState = _oldEpisodeCleanupState.asStateFlow()

    fun updateSettings(update: (Settings) -> Settings) {
        viewModelScope.launch {
            settingsStore.update(update)
        }
    }

    fun detectIpv6() {
        viewModelScope.launch {
            _ipv6DebugState.value = _ipv6DebugState.value.copy(
                isLoading = true,
                hasChecked = true,
            )

            val httpIpv6 = nsdServiceRegistrar.findHttpIpv6Address()?.hostAddress
            val systemIpv6 = nsdServiceRegistrar.findSystemIpv6Address()?.hostAddress

            _ipv6DebugState.value = DeveloperIpv6DebugState(
                isLoading = false,
                hasChecked = true,
                httpIpv6 = httpIpv6,
                systemIpv6 = systemIpv6,
            )
        }
    }

    fun selectSource(source: UpdateSource) {
        _selectedSource.value = source
    }

    fun checkForUpdates(source: UpdateSource) {
        viewModelScope.launch {
            updateChecker.checkUpdate(source).collect { state ->
                _updateState.value = state
            }
        }
    }

    fun previewOldEpisodes() {
        viewModelScope.launch {
            _oldEpisodeCleanupState.value = OldEpisodeCleanupUiState(isLoading = true)
            runCatching {
                memoryRepository.previewOldEpisodes(oldEpisodeCleanupCutoff(System.currentTimeMillis()))
            }.onSuccess { preview ->
                _oldEpisodeCleanupState.value = OldEpisodeCleanupUiState(preview = preview)
            }.onFailure { error ->
                _oldEpisodeCleanupState.value = OldEpisodeCleanupUiState(error = error.message ?: "Unknown error")
            }
        }
    }

    fun deleteOldEpisodes() {
        viewModelScope.launch {
            _oldEpisodeCleanupState.value = _oldEpisodeCleanupState.value.copy(isLoading = true, error = null)
            runCatching {
                memoryRepository.deleteOldEpisodes(oldEpisodeCleanupCutoff(System.currentTimeMillis()))
            }.onSuccess { result ->
                scheduleNextMemorySummaryCycle(result)
                _oldEpisodeCleanupState.value = OldEpisodeCleanupUiState(result = result)
            }.onFailure { error ->
                _oldEpisodeCleanupState.value = _oldEpisodeCleanupState.value.copy(
                    isLoading = false,
                    error = error.message ?: "Unknown error",
                )
            }
        }
    }

    fun cancelAllManualConsolidations() {
        viewModelScope.launch {
            _manualConsolidationCancellationState.value = ManualConsolidationCancellationUiState(isCancelling = true)
            runCatching {
                memoryConsolidationScheduler.cancelAllManual()
            }.onSuccess { cancelledCount ->
                _manualConsolidationCancellationState.value = ManualConsolidationCancellationUiState(
                    cancelledCount = cancelledCount,
                )
            }.onFailure { error ->
                _manualConsolidationCancellationState.value = ManualConsolidationCancellationUiState(
                    error = error.message ?: "Unknown error",
                )
            }
        }
    }

    fun clearManualConsolidationCancellationResult() {
        _manualConsolidationCancellationState.value = ManualConsolidationCancellationUiState()
    }

    fun clearOldEpisodeCleanupResult() {
        _oldEpisodeCleanupState.value = OldEpisodeCleanupUiState()
    }

    private fun scheduleNextMemorySummaryCycle(result: OldEpisodeCleanupResult) {
        val settingsSnapshot = settings.value
        result.assistantIds.forEach { assistantId ->
            val assistant = settingsSnapshot.assistants.firstOrNull { it.id.toString() == assistantId } ?: return@forEach
            if (!assistant.enableMemorySummary || !assistant.enableAutoMemorySummary) return@forEach
            memorySummaryRepository.scheduleAutomaticAfter(
                assistantId = assistantId,
                delayMillis = assistant.memorySummaryIntervalDays.coerceIn(1, 30) * DAY_MILLIS,
            )
        }
    }

    private companion object {
        const val DAY_MILLIS = 24L * 60L * 60L * 1000L
    }
}
