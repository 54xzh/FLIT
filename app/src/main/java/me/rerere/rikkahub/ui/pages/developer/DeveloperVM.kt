package me.rerere.rikkahub.ui.pages.developer

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.ai.AILoggingManager
import me.rerere.rikkahub.web.NsdServiceRegistrar

data class DeveloperIpv6DebugState(
    val isLoading: Boolean = false,
    val hasChecked: Boolean = false,
    val httpIpv6: String? = null,
    val systemIpv6: String? = null,
)

class DeveloperVM(
    private val aiLoggingManager: AILoggingManager,
    private val settingsStore: SettingsStore,
    private val context: Context,
) : ViewModel() {
    val logs = aiLoggingManager.getLogs()
    val settings = settingsStore.settingsFlow
    private val nsdServiceRegistrar by lazy { NsdServiceRegistrar(context.applicationContext) }

    private val _ipv6DebugState = MutableStateFlow(DeveloperIpv6DebugState())
    val ipv6DebugState = _ipv6DebugState.asStateFlow()

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
}
