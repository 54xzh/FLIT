package me.rerere.rikkahub.web

import android.content.Context
import android.util.Log
import io.ktor.server.engine.EmbeddedServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import java.net.ServerSocket

private const val TAG = "WebServerManager"

enum class WebServerPhase {
    Idle,
    Starting,
    Running,
    Stopping,
    Error,
}

data class WebServerState(
    val phase: WebServerPhase = WebServerPhase.Idle,
    val port: Int = 8080,
    val serviceName: String = DEFAULT_SERVICE_NAME,
    val hostname: String? = null,
    val ipv4Address: String? = null,
    val ipv6Address: String? = null,
    val error: String? = null,
) {
    val isRunning: Boolean get() = phase == WebServerPhase.Running
    val isLoading: Boolean get() = phase == WebServerPhase.Starting || phase == WebServerPhase.Stopping
    val preferredUrl: String?
        get() = hostname?.takeIf { it.isNotBlank() }?.let { formatWebServerUrl(it, port) }
            ?: ipv4Address?.takeIf { it.isNotBlank() }?.let { formatWebServerUrl(it, port) }
            ?: ipv6Address?.takeIf { it.isNotBlank() }?.let { formatWebServerUrl(it, port) }
}

fun formatWebServerUrl(host: String, port: Int): String {
    val normalizedHost = if (host.contains(":") && !host.startsWith("[")) {
        "[$host]"
    } else {
        host
    }
    return "http://$normalizedHost:$port"
}

class WebServerManager(
    private val context: Context,
    private val appScope: AppScope,
    private val chatService: ChatService,
    private val conversationRepo: ConversationRepository,
    private val settingsStore: SettingsStore,
) {
    private var server: EmbeddedServer<*, *>? = null
    private val nsdRegistrar = NsdServiceRegistrar(context)

    private val _state = MutableStateFlow(WebServerState())
    val state: StateFlow<WebServerState> = _state.asStateFlow()

    fun start(
        port: Int = 8080,
        serviceName: String = DEFAULT_SERVICE_NAME,
    ) {
        if (server != null || _state.value.phase == WebServerPhase.Starting) {
            Log.w(TAG, "Web server already running")
            return
        }

        appScope.launch(Dispatchers.IO) {
            try {
                _state.value = _state.value.copy(
                    phase = WebServerPhase.Starting,
                    error = null,
                    port = port,
                    serviceName = serviceName,
                )

                if (!isPortAvailable(port)) {
                    _state.value = WebServerState(
                        phase = WebServerPhase.Error,
                        port = port,
                        serviceName = serviceName,
                        error = "Port $port is already in use",
                    )
                    return@launch
                }

                server = startWebServer(port = port) {
                    configureWebApi(
                        context = context,
                        chatService = chatService,
                        conversationRepo = conversationRepo,
                        settingsStore = settingsStore,
                    )
                }.start(wait = false)

                val lanAddressInfo = nsdRegistrar.findLanAddresses()
                _state.value = WebServerState(
                    phase = WebServerPhase.Running,
                    port = port,
                    serviceName = serviceName,
                    ipv4Address = lanAddressInfo.ipv4Address?.hostAddress,
                    ipv6Address = lanAddressInfo.ipv6Address?.hostAddress,
                )

                runCatching {
                    nsdRegistrar.register(
                        port = port,
                        serviceName = serviceName,
                    ) { info ->
                        val currentState = _state.value
                        _state.value = currentState.copy(
                            serviceName = info.serviceName,
                            hostname = info.hostname,
                            ipv4Address = info.address.hostAddress,
                        )
                    }
                }.onFailure {
                    Log.w(TAG, "mDNS registration failed", it)
                }

                Log.i(TAG, "Web server started on port $port")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start web server", e)
                server = null
                _state.value = WebServerState(
                    phase = WebServerPhase.Error,
                    port = port,
                    serviceName = serviceName,
                    error = e.message ?: "Failed to start web server",
                )
            }
        }
    }

    fun stop() {
        if (server == null && !_state.value.isRunning) {
            val currentState = _state.value
            _state.value = currentState.copy(
                phase = WebServerPhase.Idle,
                error = null,
                hostname = null,
                ipv4Address = null,
                ipv6Address = null,
            )
            return
        }

        val currentState = _state.value
        _state.value = currentState.copy(
            phase = WebServerPhase.Stopping,
            error = null,
            hostname = null,
            ipv4Address = null,
            ipv6Address = null,
        )

        appScope.launch(Dispatchers.IO) {
            try {
                server?.stop(1000, 2000)
                server = null
                runCatching { nsdRegistrar.unregister() }
                    .onFailure { Log.w(TAG, "mDNS unregister failed", it) }
                val stoppedState = _state.value
                _state.value = stoppedState.copy(
                    phase = WebServerPhase.Idle,
                )
                Log.i(TAG, "Web server stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop web server", e)
                val failedState = _state.value
                _state.value = failedState.copy(
                    phase = WebServerPhase.Error,
                    error = e.message ?: "Failed to stop web server",
                )
            }
        }
    }

    private fun isPortAvailable(port: Int): Boolean {
        return try {
            ServerSocket(port).use { true }
        } catch (_: Exception) {
            false
        }
    }
}
