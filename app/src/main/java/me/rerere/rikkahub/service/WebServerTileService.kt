package me.rerere.rikkahub.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.web.WebServerManager
import me.rerere.rikkahub.web.WebServerPhase
import org.koin.android.ext.android.inject

class WebServerTileService : TileService() {

    private val webServerManager: WebServerManager by inject()
    private val settingsStore: SettingsStore by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        // Sync current state immediately (synchronous) so the tile never shows a stale
        // STATE_UNAVAILABLE left over from a previous session where the panel closed
        // mid-transition before the coroutine could update the tile.
        updateTile(webServerManager.state.value.phase)
        stateJob?.cancel()
        stateJob = serviceScope.launch {
            webServerManager.state.collect { state ->
                updateTile(state.phase)
            }
        }
    }

    override fun onStopListening() {
        super.onStopListening()
        stateJob?.cancel()
        stateJob = null
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        val state = webServerManager.state.value
        if (state.isLoading) return
        if (state.isRunning) {
            WebServerService.stop(this)
        } else {
            serviceScope.launch {
                val settings = settingsStore.settingsFlowRaw.first()
                settingsStore.update { it.copy(webServerEnabled = true) }
                WebServerService.start(this@WebServerTileService, settings.webServerPort)
            }
        }
    }

    private fun updateTile(phase: WebServerPhase) {
        val tile = qsTile ?: return
        when (phase) {
            WebServerPhase.Running -> {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = getString(R.string.setting_page_web_server_status_running)
            }
            WebServerPhase.Starting -> {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = getString(R.string.setting_page_web_server_status_starting)
            }
            WebServerPhase.Stopping -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = getString(R.string.setting_page_web_server_status_stopping)
            }
            WebServerPhase.Error -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = getString(R.string.setting_page_web_server_status_error)
            }
            WebServerPhase.Idle -> {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = getString(R.string.setting_page_web_server_status_idle)
            }
        }
        tile.updateTile()
    }
}
