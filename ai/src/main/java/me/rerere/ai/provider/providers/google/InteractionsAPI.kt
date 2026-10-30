package me.rerere.ai.provider.providers.google

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.BuildConfig
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.parametersOrEmptyObject
import me.rerere.ai.provider.AgentPlatformMode
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.GooglePlatform
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.vertex.ServiceAccountTokenProvider
import me.rerere.ai.ui.GoogleThoughtMetadata
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.contentForModelInput
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.HttpStatusException
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.RawResponseException
import me.rerere.ai.util.configureClientWithProxy
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.encodeBase64
import me.rerere.ai.util.encodeBase64ForToolResult
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.removeElements
import me.rerere.ai.util.stringSafe
import me.rerere.ai.util.toHeaders
import me.rerere.common.http.await
import me.rerere.common.http.jsonPrimitiveOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.apache.commons.text.StringEscapeUtils
import kotlin.uuid.Uuid

private const val TAG = "InteractionsAPI"


class InteractionsAPI(
    private val client: OkHttpClient,
    private val keyRoulette: KeyRoulette,
    private val serviceAccountTokenProvider: ServiceAccountTokenProvider,
) {

    private fun buildUrl(providerSetting: ProviderSetting.Google): HttpUrl {
        return when {
            providerSetting.platform != GooglePlatform.AGENT_PLATFORM -> {
                val key = keyRoulette.next(providerSetting)
                "${providerSetting.baseUrl}/interactions".toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("key", key)
                    .build()
            }

            providerSetting.agentPlatformMode == AgentPlatformMode.EXPRESS -> {
                "https://aiplatform.googleapis.com/v1beta1/interactions".toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("key", keyRoulette.next(providerSetting))
                    .build()
            }

            else -> {
                "https://aiplatform.googleapis.com/v1beta1/projects/${providerSetting.projectId}/locations/${providerSetting.location}/interactions".toHttpUrl()
            }
        }
    }

    private suspend fun transformRequest(
        providerSetting: ProviderSetting.Google,
        request: Request
    ): Request {
        return if (
            providerSetting.platform == GooglePlatform.AGENT_PLATFORM &&
            providerSetting.agentPlatformMode == AgentPlatformMode.STANDARD
        ) {
            val accessToken = serviceAccountTokenProvider.fetchAccessToken(
                serviceAccountEmail = providerSetting.serviceAccountEmail.trim(),
                privateKeyPem = StringEscapeUtils.unescapeJson(providerSetting.privateKey.trim()),
            )
            request.newBuilder()
                .addHeader("Authorization", "Bearer $accessToken")
                .build()
        } else {
            request.newBuilder().build()
        }
    }

    suspend fun generateText(
        providerSetting: ProviderSetting.Google,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk = withContext(Dispatchers.IO) {
        val requestBody = buildRequestBody(messages, params, stream = false)
        val requestBodyJson = json.encodeToString(requestBody)
        params.onRequestBody?.invoke(requestBodyJson)

        val url = buildUrl(providerSetting)
        val request = transformRequest(
            providerSetting = providerSetting,
            request = Request.Builder()
                .url(url)
                .headers(params.customHeaders.toHeaders())
                .post(requestBodyJson.toRequestBody("application/json".toMediaType()))
                .configureReferHeaders(providerSetting.baseUrl)
                .build()
        )

        val response = client.configureClientWithProxy(providerSetting.proxy).newCall(request).await()
        if (!response.isSuccessful) {
            val body = response.body?.string().orEmpty()
            val detail = body.ifBlank { response.message }
            throw HttpStatusException(
                statusCode = response.code,
                message = "Failed to get response: ${response.code} $detail",
            )
        }

        val bodyStr = response.body?.string() ?: ""
        val bodyJson = runCatching {
            json.parseToJsonElement(bodyStr) as? JsonObject
                ?: error("Interactions API response is not a JSON object")
        }.getOrElse { throwable ->
            throw RawResponseException(
                message = "Failed to parse response body: ${throwable.message}",
                rawResponse = bodyStr,
                cause = throwable,
            )
        }

        parseNonStreamResponse(bodyJson, params.model.modelId, bodyStr)
    }

    suspend fun streamText(
        providerSetting: ProviderSetting.Google,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk> = callbackFlow {
        val requestBody = buildRequestBody(messages, params, stream = true)
        val requestBodyJson = json.encodeToString(requestBody)
        params.onRequestBody?.invoke(requestBodyJson)

        val url = buildUrl(providerSetting)
        val request = transformRequest(
            providerSetting = providerSetting,
            request = Request.Builder()
                .url(url)
                .headers(params.customHeaders.toHeaders())
                .post(requestBodyJson.toRequestBody("application/json".toMediaType()))
                .configureReferHeaders(providerSetting.baseUrl)
                .build()
        )

        if (BuildConfig.DEBUG) Log.i(TAG, "streamText request body: ${requestBodyJson.length} chars")
        val rawEventBuffer = StringBuilder()
        val stepTracker = StepContextTracker()

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                if (BuildConfig.DEBUG) Log.i(TAG, "onEvent [$type]: $data")
                if (rawEventBuffer.isNotEmpty()) rawEventBuffer.append("\n")
                rawEventBuffer.append(data)

                val jsonData = runCatching { parseStreamEventData(data) }
                    .getOrElse { throwable ->
                        sendSearchFinalization()
                        close(
                            RawResponseException(
                                message = "Failed to parse stream event: ${throwable.message}",
                                rawResponse = rawEventBuffer.toString(),
                                cause = throwable,
                            )
                        )
                        return
                    }
                if (jsonData == null) {
                    sendSearchFinalization()
                    close()
                    return
                }

                val messageChunk = runCatching {
                    parseStreamMessageChunk(
                        jsonData = jsonData,
                        eventType = type,
                        modelId = params.model.modelId,
                        rawResponse = data,
                        stepTracker = stepTracker,
                    )
                }.getOrElse { throwable ->
                    sendSearchFinalization()
                    close(
                        RawResponseException(
                            message = "Failed to parse stream payload: ${throwable.message}",
                            rawResponse = rawEventBuffer.toString(),
                            cause = throwable,
                        )
                    )
                    return
                }

                if (messageChunk != null) {
                    trySend(messageChunk)
                }
                if (type == "error" || jsonData["event_type"]?.jsonPrimitiveOrNull?.contentOrNull == "error") {
                    sendSearchFinalization()
                    close(RawResponseException(
                        message = (jsonData["error"] as? JsonObject)?.get("message")?.jsonPrimitiveOrNull?.contentOrNull ?: "Interactions stream failed",
                        rawResponse = rawEventBuffer.toString(),
                    ))
                }
            }

            private fun sendSearchFinalization() {
                val parts = stepTracker.finishSearches()
                if (parts.isNotEmpty()) trySend(MessageChunk(
                    id = Uuid.random().toString(),
                    model = params.model.modelId,
                    choices = listOf(UIMessageChoice(index = 0,
                        delta = UIMessage(role = MessageRole.ASSISTANT, parts = parts), message = null, finishReason = null)),
                ))
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?
            ) {
                sendSearchFinalization()
                var exception = t
                var rawFailureResponse = ""
                t?.printStackTrace()
                if (BuildConfig.DEBUG) Log.e(TAG, "[onFailure] error: ${t?.message}")

                try {
                    if (t == null && response != null) {
                        val bodyStr = response.body.stringSafe()
                        rawFailureResponse = bodyStr.orEmpty()
                        if (!bodyStr.isNullOrEmpty()) {
                            val bodyElement = json.parseToJsonElement(bodyStr)
                            if (bodyElement is JsonObject) {
                                exception = Exception(
                                    (bodyElement["error"] as? JsonObject)
                                        ?.get("message")
                                        ?.jsonPrimitiveOrNull
                                        ?.contentOrNull
                                        ?: "unknown"
                                )
                            }
                        } else {
                            exception = Exception("Unknown error: ${response.code}")
                        }
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                    exception = e
                } finally {
                    val exceptionWithStatus = response?.let { resp ->
                        HttpStatusException(
                            statusCode = resp.code,
                            message = exception?.message ?: "HTTP ${resp.code}",
                            cause = exception,
                        )
                    } ?: exception
                    val raw = rawFailureResponse.takeIf { it.isNotBlank() } ?: rawEventBuffer.toString()
                    close(
                        RawResponseException(
                            message = exceptionWithStatus?.message ?: "Stream failed",
                            rawResponse = raw,
                            cause = exceptionWithStatus,
                        )
                    )
                }
            }

            override fun onClosed(eventSource: EventSource) {
                if (BuildConfig.DEBUG) Log.i(TAG, "[onClosed] stream closed")
                sendSearchFinalization()
                close()
            }
        }

        val eventSource = EventSources.createFactory(client.configureClientWithProxy(providerSetting.proxy))
            .newEventSource(request, listener)

        awaitClose {
            if (BuildConfig.DEBUG) Log.i(TAG, "[awaitClose] cancel eventSource")
            eventSource.cancel()
        }
    }.buffer(Channel.UNLIMITED)

    internal fun parseStreamEventData(data: String): JsonObject? {
        if (data.trim() == "[DONE]") return null
        return json.parseToJsonElement(data) as? JsonObject
            ?: error("Interactions API stream event is not a JSON object")
    }

    internal fun buildRequestBody(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        stream: Boolean,
    ): JsonObject = buildJsonObject {
        put("model", params.model.modelId)
        put("store", false)
        if (stream) {
            put("stream", true)
        }

        // System instruction
        val systemMessage = messages.firstOrNull { it.role == MessageRole.SYSTEM }
        val systemText = systemMessage?.parts
            ?.filterIsInstance<UIMessagePart.Text>()
            ?.joinToString("\n") { it.text }
            ?.takeIf { it.isNotBlank() }
        if (systemText != null) {
            put("system_instruction", systemText)
        }

        // Multi-turn input steps
        put("input", buildInputSteps(messages, params.model))

        // Generation Config
        put("generation_config", buildJsonObject {
            if (params.temperature != null) put("temperature", params.temperature)
            if (params.topP != null) put("top_p", params.topP)
            if (params.maxTokens != null) put("max_output_tokens", params.maxTokens)

            if (params.model.abilities.contains(ModelAbility.REASONING)) {
                googleThinkingLevel(params.model.modelId, params.reasoningLevel)?.let {
                    put("thinking_level", it)
                }
                put("thinking_summaries", "auto")
            }
        })

        // Flat tools array containing both built-in tools and custom functions
        val hasCustomTools = params.tools.isNotEmpty() && params.model.abilities.contains(ModelAbility.TOOL)
        val hasBuiltInTools = params.model.tools.isNotEmpty()
        if (hasCustomTools || hasBuiltInTools) {
            putJsonArray("tools") {
                // Built-in tools: { "type": "google_search" }
                if (hasBuiltInTools) {
                    params.model.tools.forEach { builtInTool ->
                        when (builtInTool) {
                            BuiltInTools.Search -> {
                                add(buildJsonObject {
                                    put("type", "google_search")
                                })
                            }
                            BuiltInTools.UrlContext -> {
                                add(buildJsonObject {
                                    put("type", "url_context")
                                })
                            }
                            else -> Unit
                        }
                    }
                }
                // Custom function tools: { "type": "function", "name": ..., "description": ..., "parameters": ... }
                if (hasCustomTools) {
                    params.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("name", tool.name)
                            put("description", tool.description)
                            put(
                                "parameters",
                                json.encodeToJsonElement(tool.parametersOrEmptyObject()).removeElements(
                                    listOf(
                                        "const",
                                        "exclusiveMaximum",
                                        "exclusiveMinimum",
                                        "format",
                                        "additionalProperties",
                                        "enum"
                                    )
                                )
                            )
                        })
                    }
                }
            }
        }
    }.mergeCustomBody(params.customBody)

    private fun buildInputSteps(messages: List<UIMessage>, model: Model): JsonArray {
        return buildJsonArray {
            messages
                .filter { it.role != MessageRole.SYSTEM }
                .forEach { message ->
                    when (message.role) {
                        MessageRole.USER -> {
                            val contentList = buildJsonArray {
                                message.parts.forEach { part ->
                                    when (part) {
                                        is UIMessagePart.Text -> {
                                            if (part.text.isNotEmpty()) {
                                                add(buildJsonObject {
                                                    put("type", "text")
                                                    put("text", part.text)
                                                })
                                            }
                                        }
                                        is UIMessagePart.Image -> {
                                            part.encodeBase64(false).onSuccess { base64Data ->
                                                add(buildJsonObject {
                                                    put("type", "image")
                                                    put("data", base64Data.substringAfter("base64,"))
                                                    put("mime_type", "image/png")
                                                })
                                            }
                                        }
                                        is UIMessagePart.Video -> {
                                            part.encodeBase64(false).onSuccess { base64Data ->
                                                add(buildJsonObject {
                                                    put("type", "video")
                                                    put("data", base64Data.substringAfter("base64,"))
                                                    put("mime_type", "video/mp4")
                                                })
                                            }
                                        }
                                        is UIMessagePart.Audio -> {
                                            part.encodeBase64(false).onSuccess { base64Data ->
                                                add(buildJsonObject {
                                                    put("type", "audio")
                                                    put("data", base64Data.substringAfter("base64,"))
                                                    put("mime_type", "audio/mp3")
                                                })
                                            }
                                        }
                                        else -> Unit
                                    }
                                }
                            }
                            if (contentList.isNotEmpty()) {
                                add(buildJsonObject {
                                    put("type", "user_input")
                                    put("content", contentList)
                                })
                            }
                        }

                        MessageRole.ASSISTANT -> {
                            val indexedSteps = message.parts.flatMap {
                                it.metadataAs<GoogleThoughtMetadata>()?.interactionSteps.orEmpty()
                            }.groupBy { it.turnId to it.index }.values.map { snapshots ->
                                snapshots.maxBy { it.revision }
                            }
                            indexedSteps.groupBy { it.turnId }.values.forEach { turn ->
                                turn.sortedBy { it.index }.forEach { add(it.step) }
                            }
                            message.parts.forEach messagePart@ { part ->
                                val googleMetadata = part.metadataAs<GoogleThoughtMetadata>()
                                if (!googleMetadata?.interactionSteps.isNullOrEmpty()) return@messagePart
                                val interactionStep = googleMetadata?.interactionStep
                                if (interactionStep != null) {
                                    // Interactions 的 thought 与内置工具步骤带有服务端签名，
                                    // 无状态续聊时必须逐字保留，不能按通用消息模型重新拼装。
                                    add(interactionStep)
                                    return@messagePart
                                }

                                when (part) {
                                    is UIMessagePart.Reasoning -> {
                                        val signature = part.metadataAs<GoogleThoughtMetadata>()
                                            ?.thoughtSignature
                                            ?.takeIf { it.isNotBlank() }
                                            ?: return@messagePart
                                        add(buildJsonObject {
                                            put("type", "thought")
                                            put("signature", signature)
                                            if (part.reasoning.isNotBlank()) {
                                                putJsonArray("summary") {
                                                    add(buildJsonObject {
                                                        put("type", "text")
                                                        put("text", part.reasoning)
                                                    })
                                                }
                                            }
                                        })
                                    }

                                    is UIMessagePart.Text -> {
                                        if (part.text.isNotEmpty()) {
                                            addModelOutputStep(buildJsonObject {
                                                put("type", "text")
                                                put("text", part.text)
                                            })
                                        }
                                    }

                                    is UIMessagePart.Image -> {
                                        part.toInteractionMediaContent(type = "image", fallbackMimeType = "image/png")
                                            ?.let { content -> addModelOutputStep(content) }
                                    }

                                    is UIMessagePart.Audio -> {
                                        part.toInteractionMediaContent(type = "audio", fallbackMimeType = "audio/mp3")
                                            ?.let { content -> addModelOutputStep(content) }
                                    }

                                    is UIMessagePart.ToolCall -> {
                                        add(buildJsonObject {
                                            put("type", "function_call")
                                            put("name", part.toolName)
                                            put("id", part.toolCallId.ifBlank { "call_${Uuid.random()}" })
                                            val argsJson = runCatching {
                                                json.parseToJsonElement(part.arguments)
                                            }.getOrDefault(buildJsonObject {})
                                            put("arguments", argsJson)
                                        })
                                    }

                                    else -> Unit
                                }
                            }
                        }

                        MessageRole.TOOL -> {
                            message.parts.filterIsInstance<UIMessagePart.ToolResult>().forEach { result ->
                                add(buildJsonObject {
                                    put("type", "function_result")
                                    put("name", result.toolName)
                                    put("call_id", result.toolCallId.ifBlank { "call_default" })
                                    val contentList = buildJsonArray {
                                        val text = when (val res = result.contentForModelInput(model)) {
                                            is JsonPrimitive -> res.content
                                            else -> json.encodeToString(res)
                                        }
                                        add(buildJsonObject {
                                            put("type", "text")
                                            put("text", text)
                                        })
                                        result.images
                                            .filter { it.includeInModel && Modality.IMAGE in model.inputModalities }
                                            .forEach { img ->
                                                img.encodeBase64ForToolResult(withPrefix = false).onSuccess { data ->
                                                    add(buildJsonObject {
                                                        put("type", "image")
                                                        put("data", data)
                                                        put("mime_type", img.mimeType)
                                                    })
                                                }
                                            }
                                    }
                                    put("result", contentList)
                                })
                            }
                        }

                        else -> Unit
                    }
                }
        }
    }

    private fun kotlinx.serialization.json.JsonArrayBuilder.addModelOutputStep(content: JsonObject) {
        add(buildJsonObject {
            put("type", "model_output")
            putJsonArray("content") {
                add(content)
            }
        })
    }

    private fun UIMessagePart.Image.toInteractionMediaContent(
        type: String,
        fallbackMimeType: String,
    ): JsonObject? = toInteractionMediaContent(url, type, fallbackMimeType) {
        encodeBase64(withPrefix = false).getOrNull()
    }

    private fun UIMessagePart.Audio.toInteractionMediaContent(
        type: String,
        fallbackMimeType: String,
    ): JsonObject? = toInteractionMediaContent(url, type, fallbackMimeType) {
        encodeBase64(withPrefix = false).getOrNull()
    }

    private fun toInteractionMediaContent(
        url: String,
        type: String,
        fallbackMimeType: String,
        encodeFallback: () -> String?,
    ): JsonObject? {
        val data = if (url.startsWith("data:")) {
            url.substringAfter("base64,", missingDelimiterValue = "")
        } else {
            encodeFallback()
        }.orEmpty()
        if (data.isBlank()) return null

        val mimeType = if (url.startsWith("data:")) {
            url.substringAfter("data:").substringBefore(";", missingDelimiterValue = fallbackMimeType)
        } else {
            fallbackMimeType
        }
        return buildJsonObject {
            put("type", type)
            put("data", data)
            put("mime_type", mimeType)
        }
    }

    internal fun parseNonStreamResponse(
        bodyJson: JsonObject,
        modelId: String,
        rawResponse: String,
    ): MessageChunk {
        val tracker = StepContextTracker()
        var message = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
        (bodyJson["steps"] as? JsonArray).orEmpty().forEachIndexed { index, element ->
            val step = element as? JsonObject ?: return@forEachIndexed
            listOf(
                buildJsonObject { put("index", index); put("step", step); put("event_type", "step.start") },
                buildJsonObject { put("index", index); put("event_type", "step.stop") },
            ).forEach { event ->
                parseStreamMessageChunk(event, null, modelId, rawResponse, tracker)?.let { message += it }
            }
        }
        val terminalEvent = buildJsonObject { put("interaction", bodyJson); put("event_type", "interaction.completed") }
        parseStreamMessageChunk(terminalEvent, null, modelId, rawResponse, tracker)?.let { message += it }
        val reason = bodyJson["status"]?.jsonPrimitiveOrNull?.contentOrNull
        return MessageChunk(
            id = bodyJson["id"]?.jsonPrimitiveOrNull?.contentOrNull ?: Uuid.random().toString(),
            model = modelId,
            choices = listOf(UIMessageChoice(index = 0, delta = null, message = message, finishReason = reason)),
            usage = parseUsage(bodyJson["usage"] as? JsonObject ?: bodyJson["usageMetadata"] as? JsonObject),
            finishReasons = reason?.let { setOf(it) } ?: emptySet(),
            rawResponse = rawResponse,
        )
    }

    internal fun parseStreamMessageChunk(
        jsonData: JsonObject,
        eventType: String?,
        modelId: String,
        rawResponse: String,
        stepTracker: StepContextTracker,
    ): MessageChunk? {
        val (parts, annotations) = stepTracker.consume(jsonData, eventType)
        val interaction = jsonData["interaction"] as? JsonObject
        val status = interaction?.get("status")?.jsonPrimitiveOrNull?.contentOrNull
            ?: jsonData["status"]?.jsonPrimitiveOrNull?.contentOrNull
        // 只有整个 interaction 的终态才结束回答，步骤结束不等于回答结束。
        val reason = status?.takeIf { it in setOf("completed", "incomplete", "failed", "cancelled", "requires_action") }
        val usage = parseUsage(jsonData["usage"] as? JsonObject
            ?: jsonData["usageMetadata"] as? JsonObject ?: interaction?.get("usage") as? JsonObject)
        if (parts.isEmpty() && annotations.isEmpty() && usage == null && reason == null) return null
        return MessageChunk(
            id = interaction?.get("id")?.jsonPrimitiveOrNull?.contentOrNull ?: Uuid.random().toString(),
            model = modelId,
            choices = if (parts.isNotEmpty() || annotations.isNotEmpty() || reason != null) {
                listOf(UIMessageChoice(
                    index = 0,
                    delta = UIMessage(role = MessageRole.ASSISTANT, parts = parts, annotations = annotations),
                    message = null,
                    finishReason = reason,
                ))
            } else emptyList(),
            usage = usage,
            finishReasons = reason?.let { setOf(it) } ?: emptySet(),
            rawResponse = rawResponse,
        )
    }

    private fun parseUsage(usageObj: JsonObject?): TokenUsage? {
        if (usageObj == null) return null
        val promptTokens = usageObj["total_input_tokens"]?.jsonPrimitiveOrNull?.intOrNull
            ?: usageObj["prompt_tokens"]?.jsonPrimitiveOrNull?.intOrNull
            ?: usageObj["promptTokenCount"]?.jsonPrimitiveOrNull?.intOrNull
            ?: 0
        val completionTokens = usageObj["total_output_tokens"]?.jsonPrimitiveOrNull?.intOrNull
            ?: usageObj["completion_tokens"]?.jsonPrimitiveOrNull?.intOrNull
            ?: usageObj["candidatesTokenCount"]?.jsonPrimitiveOrNull?.intOrNull
            ?: 0
        val cachedTokens = usageObj["total_cached_tokens"]?.jsonPrimitiveOrNull?.intOrNull
            ?: usageObj["cached_tokens"]?.jsonPrimitiveOrNull?.intOrNull
            ?: usageObj["cachedContentTokenCount"]?.jsonPrimitiveOrNull?.intOrNull
            ?: 0
        val totalTokens = usageObj["total_tokens"]?.jsonPrimitiveOrNull?.intOrNull
            ?: usageObj["totalTokenCount"]?.jsonPrimitiveOrNull?.intOrNull
            ?: (promptTokens + completionTokens)

        return TokenUsage(
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            cachedTokens = cachedTokens,
            totalTokens = totalTokens
        )
    }

    internal class StepContextTracker : GoogleInteractionState()
}
