package me.rerere.rikkahub.ui.pages.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.AIRequestLogManager
import me.rerere.rikkahub.data.ai.AIRequestSource
import me.rerere.rikkahub.data.db.entity.AIRequestLogEntity

class RequestLogsVM(
    private val requestLogManager: AIRequestLogManager,
) : ViewModel() {
    private val rawLogs = requestLogManager.observeRecent()

    private val _sourceFilter = MutableStateFlow<AIRequestSource?>(null)
    val sourceFilter: StateFlow<AIRequestSource?> = _sourceFilter.asStateFlow()

    private val _errorOnly = MutableStateFlow(false)
    val errorOnly: StateFlow<Boolean> = _errorOnly.asStateFlow()

    init {
        viewModelScope.launch {
            requestLogManager.reclassifyRecentLogsIfNeeded()
        }
    }

    val availableSources: StateFlow<List<AIRequestSource>> = rawLogs
        .map { logs ->
            logs.mapNotNull { log ->
                runCatching { AIRequestSource.valueOf(log.source) }.getOrNull()
            }.distinct().sortedBy { it.ordinal }
        }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val logs: StateFlow<List<AIRequestLogEntity>> = combine(rawLogs, _sourceFilter, _errorOnly) { logs, filter, errOnly ->
        var result = logs
        if (filter != null) result = result.filter { it.source == filter.name }
        if (errOnly) result = result.filter { it.error != null }
        result
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    fun setSourceFilter(source: AIRequestSource?) {
        _sourceFilter.value = source
    }

    fun toggleErrorOnly() {
        _errorOnly.value = !_errorOnly.value
    }

    fun clearAll() {
        viewModelScope.launch {
            requestLogManager.clearAll()
            _sourceFilter.value = null
            _errorOnly.value = false
        }
    }
}
