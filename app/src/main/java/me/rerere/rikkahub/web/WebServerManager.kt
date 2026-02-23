package me.rerere.rikkahub.web

import android.content.Context
import android.util.Log
import io.ktor.http.HttpHeaders
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.singlePageApplication
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

data class WebServerState(
    val isRunning: Boolean = false,
    val isLoading: Boolean = false,
    val port: Int = 8080,
    val serviceName: String = DEFAULT_SERVICE_NAME,
    val hostname: String? = null,
    val address: String? = null,
    val error: String? = null
)

class WebServerManager(
    private val context: Context,
    private val appScope: AppScope,
    private val chatService: ChatService,
    private val conversationRepo: ConversationRepository,
    private val settingsStore: SettingsStore,
) {
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val nsdRegistrar = NsdServiceRegistrar(context)

    private val _state = MutableStateFlow(WebServerState())
    val state: StateFlow<WebServerState> = _state.asStateFlow()

    fun start(
        port: Int = 8080,
        serviceName: String = DEFAULT_SERVICE_NAME
    ) {
        if (server != null) {
            Log.w(TAG, "Server already running")
            return
        }

        appScope.launch {
            try {
                _state.value = _state.value.copy(isLoading = true)
                Log.i(TAG, "Starting web server on port $port")
                if (!isPortAvailable(port)) {
                    Log.w(TAG, "Port $port is already in use")
                    _state.value = WebServerState(
                        isRunning = false,
                        port = port,
                        serviceName = serviceName,
                        error = "Port $port is already in use"
                    )
                    return@launch
                }
                server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
                    install(Compression)
                    install(CORS) {
                        allowHeader(HttpHeaders.ContentType)
                        allowHeader(HttpHeaders.Authorization)
                        allowNonSimpleContentTypes = true
                        anyHost()
                        anyMethod()
                    }
                    install(SSE)
                    install(DefaultHeaders)
                    routing {
                        staticResources("/", "static") {
                            default("index.html")
                            enableAutoHeadResponse()
                            singlePageApplication()
                        }
                    }
                    configureWebApi(context, chatService, conversationRepo, settingsStore)
                }.start(wait = false)

                _state.value = WebServerState(
                    isRunning = true,
                    port = port,
                    serviceName = serviceName
                )
                runCatching {
                    nsdRegistrar.register(
                        port = port,
                        serviceName = serviceName,
                        onRegistered = { info ->
                            _state.value = _state.value.copy(
                                serviceName = info.serviceName,
                                hostname = info.hostname,
                                address = info.address.hostAddress
                            )
                        }
                    )
                }.onFailure {
                    Log.w(TAG, "NSD register failed", it)
                }
                Log.i(TAG, "Web server started successfully on port $port")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start web server", e)
                _state.value = WebServerState(
                    isRunning = false,
                    port = port,
                    serviceName = serviceName,
                    error = e.message
                )
            }
        }
    }

    fun stop() {
        _state.value =
            _state.value.copy(isRunning = false, isLoading = true, hostname = null, address = null, error = null)
        appScope.launch {
            try {
                Log.i(TAG, "Stopping web server")
                server?.stop(1000, 2000)
                server = null
                runCatching {
                    nsdRegistrar.unregister()
                }.onFailure {
                    Log.w(TAG, "NSD unregister failed", it)
                }
                _state.value = _state.value.copy(isLoading = false)
                Log.i(TAG, "Web server stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop web server", e)
                _state.value = _state.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    fun restart(
        port: Int = _state.value.port,
        serviceName: String = _state.value.serviceName
    ) {
        stop()
        start(port, serviceName)
    }

    private fun isPortAvailable(port: Int): Boolean {
        return try {
            ServerSocket(port).use { true }
        } catch (e: Exception) {
            false
        }
    }
}
