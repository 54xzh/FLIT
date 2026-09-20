package me.rerere.rikkahub.data.localai

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.withContext
import me.rerere.ai.core.ReasoningLevel
import java.io.File

/** The Kotlin API stays in the APK; its matching native library is installed on demand. */
class LiteRtLmRuntimeBridge(private val libraryFile: File) : LocalInferenceBridge {
    private var engine: Engine? = null
    private val conversationLock = Any()
    private var activeConversation: Conversation? = null

    override suspend fun load(request: LocalInferenceRequest) = withContext(Dispatchers.IO) {
        require(request.format == LocalModelFormat.LITERT_LM)
        loadLibrary(libraryFile)
        val newEngine = Engine(
            EngineConfig(
                modelPath = request.modelFile.absolutePath,
                backend = Backend.CPU(threadCount = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 4)),
                // Keep the engine/model default context; maxTokens is an output limit only.
                cacheDir = request.modelFile.parentFile?.absolutePath,
            ),
        )
        try {
            newEngine.initialize()
            engine = newEngine
        } catch (error: Throwable) {
            if (newEngine.isInitialized()) newEngine.close()
            throw error
        }
    }

    override fun generate(request: LocalInferenceRequest): Flow<LocalInferenceEvent> = flow {
        currentCoroutineContext().ensureActive()
        val loadedEngine = checkNotNull(engine) { "LiteRT-LM model is not loaded" }
        val turns = liteRtMessages(request.messages)
        require(turns.isNotEmpty()) { "The conversation has no messages" }
        // Rebuild the conversation from the app's current history. This also handles edits,
        // regeneration and switching chats without reusing a stale native conversation cache.
        val config = ConversationConfig(
            initialMessages = turns.dropLast(1),
            samplerConfig = SamplerConfig(
                topK = 40,
                topP = (request.topP ?: 0.95f).toDouble(),
                temperature = (request.temperature ?: 0.7f).toDouble(),
            ),
            automaticToolCalling = false,
            maxOutputToken = request.maxTokens?.takeIf { it > 0 },
            thinkingConfig = ThinkingConfig(enableThinking = request.reasoningLevel != ReasoningLevel.OFF),
        )
        var conversation: Conversation? = null
        emitAll(localInferenceStream(
            start = { onEvent, onComplete ->
                val callback = object : MessageCallback {
                    override fun onMessage(message: Message) {
                        message.channels.values.forEach { value ->
                            if (value.isNotEmpty()) onEvent(LocalInferenceEvent.Reasoning(value))
                        }
                        message.contents.contents.filterIsInstance<Content.Text>().forEach { content ->
                            onEvent(LocalInferenceEvent.Text(content.text))
                        }
                    }
                    override fun onDone() = onComplete(null)
                    override fun onError(throwable: Throwable) = onComplete(throwable)
                }
                synchronized(conversationLock) {
                    val created = loadedEngine.createConversation(config)
                    conversation = created
                    activeConversation = created
                    created.sendMessageAsync(turns.last(), callback)
                }
            },
            cancel = { cancelConversation() },
            close = {
                synchronized(conversationLock) {
                    activeConversation = null
                    conversation?.close()
                }
            },
        ))
    }

    override suspend fun cancel() = cancelConversation()

    private fun cancelConversation() {
        synchronized(conversationLock) { activeConversation?.cancelProcess() }
    }

    override suspend fun release() = withContext(Dispatchers.IO) {
        // LocalRuntimeManager waits for generate's cleanup before calling release.
        engine?.close()
        engine = null
    }

    companion object {
        private var loadedLibraryPath: String? = null

        @Synchronized
        private fun loadLibrary(file: File) {
            val path = file.canonicalPath
            check(loadedLibraryPath == null || loadedLibraryPath == path) {
                "Restart FLIT before switching LiteRT-LM runtime versions."
            }
            if (loadedLibraryPath == null) {
                check(file.isFile) { "LiteRT-LM runtime library is missing" }
                // The pinned SDK's NativeLibraryLoader detects this preloaded library through
                // nativeCheckLoaded(), so it does not require a library inside the APK.
                System.load(path)
                loadedLibraryPath = path
            }
        }
    }
}

internal fun liteRtMessages(messages: List<LocalInferenceMessage>): List<Message> = messages.map { message ->
    when (message.role) {
        "system" -> Message.system(message.content)
        "user" -> Message.user(message.content)
        "assistant" -> Message.model(message.content)
        "tool" -> Message.tool(Contents.of(message.content))
        else -> error("Unsupported local message role: ${message.role}")
    }
}
