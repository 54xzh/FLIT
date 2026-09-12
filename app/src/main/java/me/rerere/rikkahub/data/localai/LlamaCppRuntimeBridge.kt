package me.rerere.rikkahub.data.localai

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.rerere.ai.core.ReasoningLevel
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** JNI adapter for the downloaded GGUF runtime. No native library is linked into the APK. */
class LlamaCppRuntimeBridge private constructor(
    private val libraryFile: File,
) : LocalInferenceBridge {
    /** Null means use all room remaining in the model's currently allocated context. */
    private var requestedMaxTokens: Int? = null

    override suspend fun load(request: LocalInferenceRequest) {
        require(request.format == LocalModelFormat.GGUF) { "This runtime only supports GGUF models" }
        loadLibrary(libraryFile)
        nativeInitialize()
        requestedMaxTokens = request.maxTokens?.takeIf { it > 0 }
        nativeLoad(
            path = request.modelFile.absolutePath,
            // Zero asks the runtime to use the GGUF model's declared training context instead
            // of imposing an arbitrary application-wide 4K window.
            contextSize = 0,
            threads = min(MAX_THREADS, max(MIN_THREADS, Runtime.getRuntime().availableProcessors() - 2)),
            temperature = request.temperature ?: DEFAULT_TEMPERATURE,
            topP = request.topP ?: DEFAULT_TOP_P,
        )
    }

    override fun generate(request: LocalInferenceRequest): Flow<LocalInferenceEvent> = flow {
        val prompt = nativeFormatPrompt(
            roles = request.messages.map(LocalInferenceMessage::role).toTypedArray(),
            contents = request.messages.map(LocalInferenceMessage::content).toTypedArray(),
            disableThinking = request.reasoningLevel == ReasoningLevel.OFF,
        )
        val availableTokens = nativeStart(prompt)
        check(availableTokens > 0) { "No generation space remains in this model's context." }
        val generationLimit = requestedMaxTokens?.coerceAtMost(availableTokens) ?: availableTokens
        val turnBoundaryParser = TurnBoundaryParser()
        repeat(generationLimit) {
            currentCoroutineContext().ensureActive()
            val token = nativeNextToken()
            if (token == null) {
                turnBoundaryParser.finish().takeIf(String::isNotEmpty)?.let { emit(LocalInferenceEvent.Text(it)) }
                emit(LocalInferenceEvent.Finished(reason = FINISH_REASON_STOP))
                return@flow
            }
            val parsed = turnBoundaryParser.append(token)
            if (parsed.text.isNotEmpty()) emit(LocalInferenceEvent.Text(parsed.text))
            if (parsed.finished) {
                nativeCancel()
                emit(LocalInferenceEvent.Finished(reason = FINISH_REASON_STOP))
                return@flow
            }
        }
        turnBoundaryParser.finish().takeIf(String::isNotEmpty)?.let { emit(LocalInferenceEvent.Text(it)) }
        emit(LocalInferenceEvent.Finished(reason = FINISH_REASON_LENGTH))
    }

    override suspend fun cancel() = nativeCancel()

    override suspend fun release() = nativeRelease()

    private external fun nativeInitialize()
    private external fun nativeLoad(
        path: String,
        contextSize: Int,
        threads: Int,
        temperature: Float,
        topP: Float,
    )

    /** Starts the prompt and returns the number of output tokens that still fit in context. */
    private external fun nativeStart(prompt: String): Int
    private external fun nativeFormatPrompt(
        roles: Array<String>,
        contents: Array<String>,
        disableThinking: Boolean,
    ): String
    private external fun nativeNextToken(): String?
    private external fun nativeCancel()
    private external fun nativeRelease()

    companion object {
        private const val MIN_THREADS = 2
        private const val MAX_THREADS = 4
        private const val DEFAULT_TEMPERATURE = 0.7f
        private const val DEFAULT_TOP_P = 0.95f
        private const val FINISH_REASON_STOP = "stop"
        private const val FINISH_REASON_LENGTH = "length"

        private var loadedLibraryPath: String? = null

        fun create(libraryFile: File): LlamaCppRuntimeBridge {
            require(libraryFile.isFile) { "The GGUF runtime library is missing" }
            loadLibrary(libraryFile)
            return LlamaCppRuntimeBridge(libraryFile)
        }

        @Synchronized
        private fun loadLibrary(libraryFile: File) {
            val path = libraryFile.canonicalPath
            check(loadedLibraryPath == null || loadedLibraryPath == path) {
                "A different local runtime version is already loaded. Restart FLIT to switch runtime versions."
            }
            if (loadedLibraryPath == null) {
                System.load(path)
                loadedLibraryPath = path
            }
        }
    }

    /**
     * Stops before a model starts a synthetic next user turn. The small retained suffix keeps
     * role markers intact even when they are split over multiple generated tokens.
     */
    private class TurnBoundaryParser {
        private val pending = StringBuilder()

        fun append(fragment: String): ParsedTurn {
            pending.append(fragment)
            val boundary = STOP_MARKERS.mapNotNull { marker ->
                pending.indexOf(marker).takeIf { it >= 0 }?.let { index -> index to marker }
            }.minByOrNull { it.first }
            if (boundary != null) {
                val text = pending.substring(0, boundary.first)
                pending.clear()
                return ParsedTurn(text = text, finished = true)
            }

            val safeLength = (pending.length - MAX_MARKER_LENGTH + 1).coerceAtLeast(0)
            if (safeLength == 0) return ParsedTurn()
            val text = pending.substring(0, safeLength)
            pending.delete(0, safeLength)
            return ParsedTurn(text = text)
        }

        fun finish(): String = pending.toString().also { pending.clear() }

        private companion object {
            val STOP_MARKERS = listOf(
                "<|im_end|>",
                "<|im_start|>user",
                "\nUser:",
                "\n用户：",
                "\n用户:",
            )
            val MAX_MARKER_LENGTH = STOP_MARKERS.maxOf(String::length)
        }
    }

    private data class ParsedTurn(
        val text: String = "",
        val finished: Boolean = false,
    )
}
