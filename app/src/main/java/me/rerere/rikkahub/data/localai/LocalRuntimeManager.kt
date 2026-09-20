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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import me.rerere.ai.core.ReasoningLevel
import kotlin.uuid.Uuid

sealed interface LocalRuntimeState {
    data object Missing : LocalRuntimeState
    data object Ready : LocalRuntimeState
    data class Loading(val modelId: Uuid) : LocalRuntimeState
    data class Loaded(val modelId: Uuid) : LocalRuntimeState
    data class Failed(val message: String, val format: LocalModelFormat? = null) : LocalRuntimeState
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
    data class Reasoning(val value: String) : LocalInferenceEvent
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
    private val _state = MutableStateFlow<LocalRuntimeState>(LocalRuntimeState.Missing)
    private val _installedPackages = MutableStateFlow<Set<LocalRuntimePackage>>(emptySet())
    val installedPackages: StateFlow<Set<LocalRuntimePackage>> = _installedPackages.asStateFlow()
    val state: StateFlow<LocalRuntimeState> = _state.asStateFlow()
    private var bridge: LocalInferenceBridge? = null
    @Volatile
    private var activeBridge: LocalInferenceBridge? = null
    @Volatile
    private var activeGenerationJob: Job? = null
    private var loadedModelId: Uuid? = null
    private var loadedFormat: LocalModelFormat? = null
    private var backgroundRelease: Job? = null

    init { refresh() }

    fun isReady(): Boolean = _installedPackages.value.isNotEmpty()

    /** Disk reads stay off the main thread, including initial construction and UI refreshes. */
    fun refresh() {
        appScope.launch(Dispatchers.IO) {
            lock.withLock {
                scanInstalledPackages()
                if (loadedModelId == null) _state.value = initialState()
            }
        }
    }

    suspend fun <T> withLoadedModel(
        modelId: Uuid,
        request: LocalInferenceRequest,
        block: suspend (LocalInferenceBridge) -> T,
    ): T = generationLock.withLock {
        withContext(inferenceDispatcher) {
            activeGenerationJob = currentCoroutineContext().job
            try {
                val loadedBridge = lock.withLock {
                    val runtimePackage = LocalRuntimePackage.entries.first { it.format == request.format }
                    val library = withContext(Dispatchers.IO) {
                        scanInstalledPackages()
                        runtimePackage.libraryFile(context)
                    }
                    if (library == null && bridgeFactory == null) {
                        throw LocalRuntimeUnavailableException(
                            context.getString(me.rerere.rikkahub.R.string.local_models_runtime_required, runtimePackage.displayName),
                        )
                    }
                    if (loadedModelId != modelId || loadedFormat != request.format) {
                        _state.value = LocalRuntimeState.Loading(modelId)
                        // Always create the adapter for the requested format. Reusing the old
                        // adapter would route a LiteRT model back into llama.cpp (or vice versa).
                        val previous = bridge
                        bridge = null
                        loadedModelId = null
                        loadedFormat = null
                        withContext(Dispatchers.IO) { previous?.release() }
                        val next = bridgeFactory?.invoke() ?: when (request.format) {
                            LocalModelFormat.GGUF -> withContext(Dispatchers.IO) {
                                LlamaCppRuntimeBridge.create(requireNotNull(library))
                            }
                            LocalModelFormat.LITERT_LM -> LiteRtLmRuntimeBridge(requireNotNull(library))
                        }
                        bridge = next
                        withContext(Dispatchers.IO) { next.load(request) }
                        loadedModelId = modelId
                        loadedFormat = request.format
                    }
                    _state.value = LocalRuntimeState.Loaded(modelId)
                    checkNotNull(bridge)
                }
                activeBridge = loadedBridge
                currentCoroutineContext().ensureActive()
                block(loadedBridge)
            } catch (error: Throwable) {
                // A partial/failed load must never leave the old model ID marked as loaded.
                withContext(NonCancellable + Dispatchers.IO) {
                    lock.withLock {
                        runCatching { bridge?.release() }.onFailure { error.addSuppressed(it) }
                        bridge = null
                        loadedModelId = null
                        loadedFormat = null
                        _state.value = if (error is CancellationException) initialState()
                            else LocalRuntimeState.Failed(error.message.orEmpty(), request.format)
                    }
                }
                throw error
            } finally {
                activeBridge = null
                activeGenerationJob = null
            }
        }
    }

    suspend fun release() {
        // Do not cancel backgroundRelease here: it may be the coroutine executing this release.
        activeGenerationJob?.cancel()
        withContext(Dispatchers.IO) { activeBridge?.cancel() }
        generationLock.withLock {
            lock.withLock {
                withContext(NonCancellable + Dispatchers.IO) { bridge?.release() }
                bridge = null
                loadedModelId = null
                loadedFormat = null
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

    /** Caller holds lock and runs on Dispatchers.IO. */
    private fun scanInstalledPackages() {
        _installedPackages.value = LocalRuntimePackage.entries.filter { it.libraryFile(context) != null }.toSet()
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
