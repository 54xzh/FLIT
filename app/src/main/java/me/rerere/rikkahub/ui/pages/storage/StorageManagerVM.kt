package me.rerere.rikkahub.ui.pages.storage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.repository.StorageManagerRepository
import me.rerere.rikkahub.data.repository.StorageOverview
import me.rerere.rikkahub.utils.UiState

class StorageManagerVM(
    private val storageRepo: StorageManagerRepository,
) : ViewModel() {
    private val _overview = MutableStateFlow<UiState<StorageOverview>>(UiState.Idle)
    val overview: StateFlow<UiState<StorageOverview>> = _overview.asStateFlow()

    init {
        refresh()
    }

    fun refresh(force: Boolean = false) {
        viewModelScope.launch {
            // Prefer the on-disk snapshot on cold start so the page renders instantly instead of
            // spinning while the fresh (and potentially heavy) overview is recomputed.
            val cached = storageRepo.peekDiskOverviewCache()
            _overview.value = cached?.let { UiState.Success(it) } ?: UiState.Loading
            _overview.value = runCatching { storageRepo.loadOverview(forceRefresh = force) }
                .fold(
                    onSuccess = { UiState.Success(it) },
                    onFailure = { UiState.Error(it) },
                )
        }
    }
}
