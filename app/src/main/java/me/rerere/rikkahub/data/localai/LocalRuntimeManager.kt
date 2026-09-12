package me.rerere.rikkahub.data.localai

import android.content.ComponentCallbacks2
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import me.rerere.ai.core.ReasoningLevel
import kotlin.uuid.Uuid

sealed interface LocalRuntimeState {
    data object Missing : LocalRuntimeState
    data object Ready : LocalRuntimeState
    data class Loading(val modelId: Uuid) : LocalRuntimeState
    data class Loaded(val modelId: Uuid) : LocalRuntimeState
    data class Failed(val message: String) : LocalRuntimeState
}

class LocalRuntimeUnavailableException(message: String) : IllegalStateException(message)

data class LocalInferenceRequest(
    val modelFile: File,
    val format: LocalModelFormat,
    val messages: List<LocalInferenceMessage>,
    val temperature: Float?,
    val topP: Float?,
    val maxTokens: Int?,
    val reasoningLevel: ReasoningLevel,
    val toolsPrompt: String = "",
)

/** Provider-neutral turn data; the loaded runtime applies the model's own chat template. */
data class LocalInferenceMessage(
    val role: String,
    val content: String,
)

sealed interface LocalInferenceEvent {
    data class Text(val value: String) : LocalInferenceEvent
    data class ToolCall(val id: String, val name: String, val arguments: String) : LocalInferenceEvent
    /**
     * Carries the native generation outcome to the Provider. Without this, reaching the local
     * output budget was indistinguishable from a model that had completed a reply.
     */
    data class Finished(val reason: String) : LocalInferenceEvent
}

/** The optional native package supplies this bridge after its signed bundle has been installed. */
interface LocalInferenceBridge {
    suspend fun load(request: LocalInferenceRequest)
    fun generate(request: LocalInferenceRequest): Flow<LocalInferenceEvent>
    suspend fun cancel()
    suspend fun release()
}

private class UnavailableLocalInferenceBridge : LocalInferenceBridge {
    override suspend fun load(request: LocalInferenceRequest): Nothing = throw LocalRuntimeUnavailableException(
        "Local runtime support is not installed or is incompatible with this app version.",
    )

    override fun generate(request: LocalInferenceRequest): Flow<LocalInferenceEvent> = kotlinx.coroutines.flow.flow {
        throw LocalRuntimeUnavailableException("Local runtime support is not installed")
    }

    override suspend fun cancel() = Unit
    override suspend fun release() = Unit
}

/**
 * Serializes model lifetime. The actual native implementation is intentionally delivered outside
 * the APK; tests and the future JNI bridge can replace [bridgeFactory].
 */
class LocalRuntimeManager(
    private val context: Context,
    private val appScope: CoroutineScope,
    private val bridgeFactory: (() -> LocalInferenceBridge)? = null,
) : DefaultLifecycleObserver, ComponentCallbacks2 {
    private val lock = Mutex()
    /** A generation owns this lock until its flow completes, so release never races native calls. */
    private val generationLock = Mutex()
    private val inferenceDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "local-model-inference").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val _state = MutableStateFlow<LocalRuntimeState>(initialState())
    val state: StateFlow<LocalRuntimeState> = _state.asStateFlow()
    private var bridge: LocalInferenceBridge? = null
    @Volatile
    private var activeBridge: LocalInferenceBridge? = null
    private var loadedModelId: Uuid? = null
    private var backgroundRelease: Job? = null

    fun isReady(): Boolean = runtimeMarker().isFile

    /** Refreshes the UI after an installer has written or replaced the runtime marker. */
    fun refresh() {
        if (loadedModelId == null) _state.value = initialState()
    }

    suspend fun <T> withLoadedModel(
        modelId: Uuid,
        request: LocalInferenceRequest,
        block: suspend (LocalInferenceBridge) -> T,
    ): T = generationLock.withLock {
        val loadedBridge = lock.withLock {
            check(isReady()) { "Download runtime support before starting a local model." }
            val currentBridge = bridge ?: createBridge().also { bridge = it }
            if (loadedModelId != modelId) {
                _state.value = LocalRuntimeState.Loading(modelId)
                if (loadedModelId != null) withContext(Dispatchers.IO) { currentBridge.release() }
                withContext(Dispatchers.IO) { currentBridge.load(request) }
                loadedModelId = modelId
            }
            _state.value = LocalRuntimeState.Loaded(modelId)
            currentBridge
        }
        activeBridge = loadedBridge
        try {
            withContext(inferenceDispatcher) { block(loadedBridge) }
        } finally {
            activeBridge = null
        }
    }

    suspend fun release() {
        backgroundRelease?.cancel()
        // Ask an active generation to finish before the native instance is destroyed.
        activeBridge?.cancel()
        generationLock.withLock {
            lock.withLock {
                withContext(Dispatchers.IO) { bridge?.release() }
                bridge = null
                loadedModelId = null
                _state.value = initialState()
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        backgroundRelease?.cancel()
        backgroundRelease = null
    }

    override fun onStop(owner: LifecycleOwner) {
        backgroundRelease?.cancel()
        backgroundRelease = appScope.launchAfterDelay { release() }
    }

    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            appScope.launchAfterDelay(delayMillis = 0L) {
                release()
            }
        }
    }

    override fun onLowMemory() {
        appScope.launchAfterDelay(delayMillis = 0L) { release() }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) = Unit

    private fun initialState(): LocalRuntimeState = if (isReady()) LocalRuntimeState.Ready else LocalRuntimeState.Missing

    private fun runtimeMarker(): File = File(context.noBackupFilesDir, "local-ai/runtime/installed.json")

    private fun createBridge(): LocalInferenceBridge {
        bridgeFactory?.let { return it() }
        val version = runtimeMarker().takeIf(File::isFile)?.readText()
            ?.let { Regex("\\\"version\\\"\\s*:\\s*\\\"([A-Za-z0-9._-]+)\\\"").find(it)?.groupValues?.getOrNull(1) }
            ?: return UnavailableLocalInferenceBridge()
        val library = File(context.noBackupFilesDir, "local-ai/runtime/$version/lib/libflit_local_llama.so")
        return if (library.isFile) LlamaCppRuntimeBridge.create(library) else UnavailableLocalInferenceBridge()
    }

    private fun CoroutineScope.launchAfterDelay(
        delayMillis: Long = BACKGROUND_RELEASE_DELAY_MS,
        block: suspend () -> Unit,
    ): Job = this.launch {
        if (delayMillis > 0) delay(delayMillis)
        block()
    }

    companion object {
        const val BACKGROUND_RELEASE_DELAY_MS = 5 * 60 * 1_000L
    }
}
