package me.rerere.ai.provider.providers.codex

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
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
import kotlin.uuid.Uuid

class OpenAICodexProvider(
    client: OkHttpClient,
    private val sessionProvider: CodexSessionProvider,
    private val protocolClient: CodexProtocolClient = CodexProtocolClient(client),
) : Provider<ProviderSetting.OpenAICodex> {
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
            CustomBody("include", JsonArray(listOf(JsonPrimitive("reasoning.encrypted_content")))),
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
}
