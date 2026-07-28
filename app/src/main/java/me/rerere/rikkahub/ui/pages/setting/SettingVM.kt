package me.rerere.rikkahub.ui.pages.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.hasMcpRuntimeScopeChanged
import me.rerere.rikkahub.data.ai.mcp.hasStdioLaunchChanged
import me.rerere.rikkahub.data.ai.mcp.withMcpSelectionRemoved
import me.rerere.rikkahub.data.db.dao.ScheduledTaskDao

class SettingVM(
    private val settingsStore: SettingsStore,
    private val mcpManager: McpManager,
    private val scheduledTaskDao: ScheduledTaskDao,
) :
    ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings(init = true, providers = emptyList()))

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }

    fun updateSettings(fn: (Settings) -> Settings) {
        viewModelScope.launch {
            settingsStore.update(fn)
        }
    }

    fun saveMcpConfig(config: McpServerConfig) {
        viewModelScope.launch {
            var clearOldSelection = false
            val oldBeforeUpdate = settingsStore.settingsFlow.value.mcpServers.firstOrNull { it.id == config.id }
            val scheduledSelectionCleared = oldBeforeUpdate != null &&
                hasMcpRuntimeScopeChanged(oldBeforeUpdate, config)
            if (scheduledSelectionCleared) {
                scheduledTaskDao.removeMcpServerOverride(config.id.toString())
            }
            settingsStore.update { latest ->
                val old = latest.mcpServers.firstOrNull { it.id == config.id }
                clearOldSelection = old != null && hasMcpRuntimeScopeChanged(old, config)
                val storedConfig = if (config is McpServerConfig.StdioServer) {
                    val keepTools = old == null || !hasStdioLaunchChanged(old, config)
                    config.copy(
                        commonOptions = config.commonOptions.copy(
                            headers = emptyList(),
                            oauth = null,
                            tools = config.commonOptions.tools.takeIf { keepTools }.orEmpty(),
                        ),
                    )
                } else {
                    config
                }
                latest.copy(
                    mcpServers = if (old == null) {
                        latest.mcpServers + storedConfig
                    } else {
                        latest.mcpServers.map { if (it.id == config.id) storedConfig else it }
                    },
                ).let { updated ->
                    if (clearOldSelection) updated.withMcpSelectionRemoved(config.id) else updated
                }
            }
            if (clearOldSelection && !scheduledSelectionCleared) {
                scheduledTaskDao.removeMcpServerOverride(config.id.toString())
            }
            runCatching { mcpManager.addClient(config) }
        }
    }

    fun importMcpConfigs(configs: List<McpServerConfig>) {
        if (configs.isEmpty()) return
        viewModelScope.launch {
            // McpManager observes settingsFlow.mcpServers and reconciles new servers automatically
            settingsStore.update { latest ->
                latest.copy(mcpServers = latest.mcpServers + configs)
            }
        }
    }

    fun deleteMcpConfig(config: McpServerConfig) {
        viewModelScope.launch {
            settingsStore.update { latest ->
                latest.copy(
                    mcpServers = latest.mcpServers.filter { it.id != config.id },
                ).withMcpSelectionRemoved(config.id)
            }
            scheduledTaskDao.removeMcpServerOverride(config.id.toString())
            mcpManager.removeClient(config)
        }
    }

}
