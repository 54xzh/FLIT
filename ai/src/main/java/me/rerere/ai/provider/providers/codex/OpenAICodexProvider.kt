package me.rerere.ai.provider.providers.codex

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.BuildConfig
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderNativeToolFactory
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.ImageGenerationResult
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.util.HttpStatusException
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import java.io.IOException
import kotlin.uuid.Uuid

private const val TAG = "OpenAICodexProvider"

class OpenAICodexProvider(
    client: OkHttpClient,
    private val sessionProvider: CodexSessionProvider,
    private val protocolClient: CodexProtocolClient = CodexProtocolClient(client),
) : Provider<ProviderSetting.OpenAICodex>, ProviderNativeToolFactory {
    private val responseApi = ResponseAPI(client, KeyRoulette.default())

    override suspend fun listModels(providerSetting: ProviderSetting.OpenAICodex): List<Model> =
        authenticatedCall(providerSetting) { credential ->
            protocolClient.listModels(credential, providerSetting.proxy)
        }

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAICodex,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk> = flow {
        var credential = sessionProvider.requireValidCredential(providerSetting)
        var retriedAfterUnauthorized = false
        while (true) {
            var emitted = false
            try {
                responseApi.streamText(
                    providerSetting = transportSetting(providerSetting, credential),
                    messages = messages.ensureSystemInstructions(),
                    params = codexParams(
                        params = params,
                        credential = credential,
                        messages = messages,
                        installationId = providerSetting.id.toString(),
                    ),
                ).collect { chunk ->
                    emitted = true
                    emit(chunk)
                }
                break
            } catch (error: Throwable) {
                if (!emitted && !retriedAfterUnauthorized && error.hasHttpStatus(401)) {
                    credential = sessionProvider.forceRefreshCredential(
                        providerSetting,
                        failedAccessToken = credential.accessToken,
                    )
                    retriedAfterUnauthorized = true
                    continue
                }
                throw error
            }
        }
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAICodex,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk {
        var assembled = UIMessage.assistant("")
        var lastChunk: MessageChunk? = null
        streamText(providerSetting, messages, params).collect { chunk ->
            assembled += chunk
            lastChunk = chunk
        }
        val last = lastChunk ?: return MessageChunk(
            id = Uuid.random().toString(),
            model = params.model.modelId,
            choices = listOf(UIMessageChoice(0, null, assembled, "stop")),
        )
        return last.copy(
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = null,
                    message = assembled,
                    finishReason = last.choices.firstOrNull()?.finishReason,
                )
            )
        )
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): ImageGenerationResult = throw UnsupportedOperationException(
        "Codex subscription providers do not support image generation"
    )

    override suspend fun createEmbedding(
        providerSetting: ProviderSetting.OpenAICodex,
        input: List<String>,
        model: Model,
        callTimeoutMillis: Long?,
    ): List<List<Float>> = throw UnsupportedOperationException(
        "Codex subscription providers do not support embeddings"
    )

    override fun createNativeTools(
        provider: ProviderSetting,
        model: Model,
        messages: List<UIMessage>,
        maxOutputTokens: Int?,
    ): List<Tool> {
        val providerSetting = provider as? ProviderSetting.OpenAICodex ?: return emptyList()
        if (!model.tools.contains(BuiltInTools.CodexWebSearch)) {
            return emptyList()
        }

        // Keep these immutable values for the whole generation. The same tool closure is reused by
        // GenerationHandler's existing tool loop, which also keeps the server-side search session coherent.
        val input = responseApi.buildMessages(messages.ensureSystemInstructions())
        val requestMetadata = codexRequestMetadata(messages, providerSetting.id.toString())
        val searchId = Uuid.random().toString()
        return listOf(
            Tool(
                name = CODEX_WEB_RUN_TOOL_NAME,
                description = CODEX_WEB_RUN_DESCRIPTION.trim(),
                parameters = ::codexWebRunParameters,
                systemPrompt = { _, _ -> CODEX_WEB_RUN_SYSTEM_PROMPT.trim() },
                execute = { arguments ->
                    val commands = arguments as? JsonObject
                        ?: return@Tool codexSearchError(
                            code = "invalid_request",
                            retryable = false,
                            message = "web.run arguments must be a JSON object.",
                        )
                    val unsupportedCommands = commands.keys - CODEX_WEB_COMMANDS
                    if (commands.isEmpty() || unsupportedCommands.isNotEmpty()) {
                        return@Tool codexSearchError(
                            code = "invalid_request",
                            retryable = false,
                            message = "web.run contains no supported command.",
                            commands = commands,
                        )
                    }
                    try {
                        val result = authenticatedCall(providerSetting) { credential ->
                            protocolClient.search(
                                credential = credential,
                                proxy = providerSetting.proxy,
                                request = CodexSearchRequest(
                                    id = searchId,
                                    model = model.modelId,
                                    input = input,
                                    commands = commands,
                                    maxOutputTokens = maxOutputTokens,
                                ),
                                metadata = CodexSearchRequestMetadata(
                                    sessionId = requestMetadata.sessionId,
                                    threadId = requestMetadata.threadId,
                                    clientRequestId = Uuid.random().toString(),
                                    windowId = requestMetadata.windowId,
                                    turnMetadata = requestMetadata.clientMetadata,
                                ),
                            )
                        }
                        codexSearchSuccess(result, commands)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        logSearchFailure(error)
                        codexSearchError(
                            code = error.toCodexSearchErrorCode(),
                            retryable = error.isCodexSearchRetryable(),
                            message = error.toCodexSearchErrorMessage(),
                            commands = commands,
                        )
                    }
                },
            )
        )
    }

    private suspend fun <T> authenticatedCall(
        providerSetting: ProviderSetting.OpenAICodex,
        block: suspend (CodexCredential) -> T,
    ): T {
        val credential = sessionProvider.requireValidCredential(providerSetting)
        return try {
            block(credential)
        } catch (error: CodexProtocolException) {
            if (error.statusCode != 401) throw error
            block(
                sessionProvider.forceRefreshCredential(
                    providerSetting,
                    failedAccessToken = credential.accessToken,
                )
            )
        }
    }

    private fun transportSetting(
        source: ProviderSetting.OpenAICodex,
        credential: CodexCredential,
    ) = ProviderSetting.OpenAI(
        id = source.id,
        enabled = source.enabled,
        name = source.name,
        models = source.models,
        quotaGroups = source.quotaGroups,
        proxy = source.proxy,
        tags = source.tags,
        customIconUri = source.customIconUri,
        apiKey = credential.accessToken,
        baseUrl = CodexProtocolConfig.RESPONSES_BASE_URL,
        useResponseApi = true,
    )

    private fun codexParams(
        params: TextGenerationParams,
        credential: CodexCredential,
        messages: List<UIMessage>,
        installationId: String,
    ): TextGenerationParams {
        val reservedHeaders = setOf(
            "authorization",
            "chatgpt-account-id",
            "originator",
            "version",
            "openai-beta",
            "user-agent",
            "accept",
            "content-type",
            "session-id",
            "thread-id",
            "x-client-request-id",
            "x-codex-window-id",
        )
        val metadata = codexRequestMetadata(messages, installationId)
        val headers = params.customHeaders.filterNot { it.name.lowercase() in reservedHeaders } + listOf(
            CustomHeader("ChatGPT-Account-ID", credential.accountId),
            CustomHeader("originator", CodexProtocolConfig.ORIGINATOR),
            CustomHeader("version", CodexProtocolConfig.COMPATIBILITY_VERSION),
            CustomHeader("User-Agent", CodexProtocolConfig.USER_AGENT),
            CustomHeader("session-id", metadata.sessionId),
            CustomHeader("thread-id", metadata.threadId),
            CustomHeader("x-client-request-id", metadata.threadId),
            CustomHeader("x-codex-window-id", metadata.windowId),
        )
        val reservedBodyKeys = setOf(
            "store",
            "include",
            "tool_choice",
            "parallel_tool_calls",
            "client_metadata",
            "stream",
        )
        val body = params.customBody.filterNot { it.key in reservedBodyKeys } + listOf(
            CustomBody("store", JsonPrimitive(false)),
            CustomBody(
                "include",
                JsonArray(buildList {
                    add(JsonPrimitive("reasoning.encrypted_content"))
                    if (params.model.tools.contains(BuiltInTools.CodexWebSearch)) {
                        add(JsonPrimitive("web_search_call.action.sources"))
                    }
                }),
            ),
            CustomBody("tool_choice", JsonPrimitive("auto")),
            CustomBody("parallel_tool_calls", JsonPrimitive(true)),
            CustomBody("client_metadata", metadata.clientMetadata),
        )
        return params.copy(
            proMode = false,
            fastMode = false,
            customHeaders = headers,
            customBody = body,
        )
    }

    /**
     * The Codex backend expects the same lightweight request identity that the
     * official client sends. These values are not account data: the provider UUID
     * is a stable client installation identity, while message UUIDs identify the current chat and turn.
     */
    private fun codexRequestMetadata(
        messages: List<UIMessage>,
        installationId: String,
    ): CodexRequestMetadata {
        val conversationId = messages
            .firstOrNull { it.role != me.rerere.ai.core.MessageRole.SYSTEM }
            ?.id
            ?.toString()
            ?: Uuid.random().toString()
        val turnId = messages
            .lastOrNull { it.role != me.rerere.ai.core.MessageRole.SYSTEM }
            ?.id
            ?.toString()
            ?: conversationId
        val windowId = Uuid.random().toString()
        return CodexRequestMetadata(
            sessionId = conversationId,
            threadId = conversationId,
            windowId = windowId,
            clientMetadata = buildJsonObject {
                put("x-codex-installation-id", installationId)
                put("session_id", conversationId)
                put("thread_id", conversationId)
                put("turn_id", turnId)
                put("x-codex-window-id", windowId)
            },
        )
    }

    private data class CodexRequestMetadata(
        val sessionId: String,
        val threadId: String,
        val windowId: String,
        val clientMetadata: JsonObject,
    )

    private fun List<UIMessage>.ensureSystemInstructions(): List<UIMessage> =
        if (any { it.role == me.rerere.ai.core.MessageRole.SYSTEM }) this
        else listOf(UIMessage.system("You are a helpful assistant.")) + this

    private fun Throwable.hasHttpStatus(statusCode: Int): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is HttpStatusException && current.statusCode == statusCode) return true
            current = current.cause
        }
        return false
    }

    private fun logSearchFailure(error: Throwable) {
        if (BuildConfig.DEBUG) {
            val status = (error as? CodexProtocolException)?.statusCode
            // Debug logging must never replace the structured tool error (notably in local JVM tests
            // where android.util.Log may be an unmocked stub).
            runCatching {
                Log.w(TAG, "Codex native web search failed, status=$status", error)
            }
        }
    }
}

private val CODEX_WEB_COMMANDS = setOf(
    "search_query",
    "image_query",
    "open",
    "click",
    "find",
    "screenshot",
    "finance",
    "weather",
    "sports",
    "time",
    "response_length",
)

private fun codexSearchSuccess(result: kotlinx.serialization.json.JsonElement, commands: JsonObject): JsonObject {
    val response = result as? JsonObject ?: buildJsonObject { put("result", result) }
    return JsonObject(
        response + ("_tool_result_metadata" to buildJsonObject {
            put("type", "codex_web_search")
            put("status", "completed")
            put("actions", JsonArray(commands.keys.map(::JsonPrimitive)))
            put("sources", extractCodexSearchSources(response))
        })
    )
}

private fun codexSearchError(
    code: String,
    retryable: Boolean,
    message: String,
    commands: JsonObject = JsonObject(emptyMap()),
): JsonObject {
    val error = buildJsonObject {
        put("type", "codex_web_search_error")
        put("code", code)
        put("retryable", retryable)
        put("message", message)
    }
    return buildJsonObject {
        put("error", error)
        put("_tool_result_metadata", buildJsonObject {
            put("type", "codex_web_search")
            put("status", "failed")
            put("actions", JsonArray(commands.keys.map(::JsonPrimitive)))
            put("error", error)
        })
    }
}

private fun extractCodexSearchSources(response: JsonObject): JsonArray {
    val unique = linkedMapOf<String, JsonObject>()
    val pending = ArrayDeque<Pair<kotlinx.serialization.json.JsonElement, Int>>().apply {
        add(response to 0)
    }
    while (pending.isNotEmpty() && unique.size < 64) {
        val (candidate, depth) = pending.removeFirst()
        when (candidate) {
            is JsonObject -> {
                val url = (candidate["url"] as? JsonPrimitive)?.content?.trim().orEmpty()
                if (url.isNotBlank()) {
                    unique.putIfAbsent(url, buildJsonObject {
                        put("url", url)
                        (candidate["title"] as? JsonPrimitive)?.content?.trim()
                            ?.takeIf { it.isNotBlank() }
                            ?.let { put("title", it) }
                    })
                }
                if (depth < 4) {
                    CODEX_SEARCH_RESULT_CHILD_KEYS.forEach { key ->
                        candidate[key]?.let { child -> pending.add(child to depth + 1) }
                    }
                }
            }

            is JsonArray -> if (depth < 4) {
                candidate.forEach { child -> pending.add(child to depth + 1) }
            }

            else -> Unit
        }
    }
    return JsonArray(unique.values.toList())
}

private val CODEX_SEARCH_RESULT_CHILD_KEYS = setOf(
    "result",
    "results",
    "sources",
    "items",
    "output",
    "content",
    "citations",
    "data",
)

private fun Throwable.toCodexSearchErrorCode(): String {
    val status = (this as? CodexProtocolException)?.statusCode
    return when (status) {
        400 -> "invalid_request"
        401 -> "unauthorized"
        403, 404 -> "protocol_unsupported"
        408, 429 -> if (status == 429) "rate_limited" else "network"
        in 500..599 -> "network"
        null -> if (this is IOException) "network" else "unknown"
        else -> "unknown"
    }
}

private fun Throwable.isCodexSearchRetryable(): Boolean {
    val status = (this as? CodexProtocolException)?.statusCode
    return status == null || status == 408 || status == 429 || status in 500..599
}

private fun Throwable.toCodexSearchErrorMessage(): String {
    return when (toCodexSearchErrorCode()) {
        "invalid_request" -> "The native web search request was rejected as invalid."
        "unauthorized" -> "Codex login could not be renewed for web search."
        "protocol_unsupported" -> "This Codex service version does not support the requested web action."
        "rate_limited" -> "Codex web search is temporarily rate limited."
        "network" -> "Codex web search is temporarily unavailable due to a network or service error."
        else -> "Codex web search could not be completed."
    }
}
