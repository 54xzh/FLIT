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
import me.rerere.ai.core.ReasoningLevel
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
import me.rerere.ai.ui.WebSearchAction
import me.rerere.ai.ui.WebSearchStatus
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
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "InteractionsAPI"

private fun String.isServerToolStepType(): Boolean {
    return this !in setOf("function_call", "function_result", "model_output", "thought", "user_input") &&
        (endsWith("_call") || endsWith("_result"))
}

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

                val jsonData = runCatching {
                    json.parseToJsonElement(data) as? JsonObject
                        ?: error("Interactions API stream event is not a JSON object")
                }
                    .getOrElse { throwable ->
                        close(
                            RawResponseException(
                                message = "Failed to parse stream event: ${throwable.message}",
                                rawResponse = rawEventBuffer.toString(),
                                cause = throwable,
                            )
                        )
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
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?
            ) {
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
                when (params.reasoningLevel) {
                    ReasoningLevel.AUTO -> {}
                    ReasoningLevel.OFF -> put("thinking_level", "minimal")
                    ReasoningLevel.LOW -> put("thinking_level", "low")
                    ReasoningLevel.MEDIUM -> put("thinking_level", "medium")
                    else -> put("thinking_level", "high")
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
                            message.parts.forEach messagePart@ { part ->
                                val interactionStep = part.metadataAs<GoogleThoughtMetadata>()?.interactionStep
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
        rawResponse: String
    ): MessageChunk {
        val steps = bodyJson["steps"] as? JsonArray ?: emptyList()
        val parts = mutableListOf<UIMessagePart>()
        var finishReason: String? = null

        steps.forEach { stepElement ->
            val stepObj = stepElement as? JsonObject ?: return@forEach
            val stepType = stepObj["type"]?.jsonPrimitiveOrNull?.contentOrNull ?: ""
            when (stepType) {
                "thought" -> {
                    val summaryArr = stepObj["summary"] as? JsonArray
                        ?: stepObj["thought_summary"] as? JsonArray
                    val summaryTextFromArr = summaryArr?.mapNotNull { elem ->
                        (elem as? JsonObject)?.get("text")?.jsonPrimitiveOrNull?.contentOrNull
                            ?: (elem as? JsonPrimitive)?.contentOrNull
                    }?.joinToString("\n")?.takeIf { it.isNotEmpty() }

                    val contentArr = stepObj["content"] as? JsonArray
                    val contentTextFromArr = contentArr?.mapNotNull { elem ->
                        (elem as? JsonObject)?.get("text")?.jsonPrimitiveOrNull?.contentOrNull
                            ?: (elem as? JsonPrimitive)?.contentOrNull
                    }?.joinToString("\n")?.takeIf { it.isNotEmpty() }

                    val thoughtText = summaryTextFromArr
                        ?: stepObj["summary"]?.jsonPrimitiveOrNull?.contentOrNull
                        ?: stepObj["thought_summary"]?.jsonPrimitiveOrNull?.contentOrNull
                        ?: contentTextFromArr
                        ?: stepObj["text"]?.jsonPrimitiveOrNull?.contentOrNull
                        ?: ""
                    val signature = stepObj["signature"]?.jsonPrimitiveOrNull?.contentOrNull
                        ?: stepObj["thought_signature"]?.jsonPrimitiveOrNull?.contentOrNull
                    if (thoughtText.isNotEmpty() || signature != null) {
                        parts.add(
                            UIMessagePart.Reasoning(
                                reasoning = thoughtText,
                                createdAt = Clock.System.now(),
                                finishedAt = Clock.System.now(),
                                metadata = GoogleThoughtMetadata(
                                    thoughtSignature = signature,
                                    interactionStep = stepObj,
                                ).toMetadata(),
                            )
                        )
                    }
                }

                "model_output" -> {
                    parts += parseModelOutputParts(stepObj["content"] as? JsonArray)
                }

                "function_call" -> {
                    val name = stepObj["name"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty()
                    val id = stepObj["call_id"]?.jsonPrimitiveOrNull?.contentOrNull
                        ?: stepObj["id"]?.jsonPrimitiveOrNull?.contentOrNull
                        ?: ""
                    val argsElement = stepObj["arguments"]
                    val argsStr = when (argsElement) {
                        is JsonObject -> json.encodeToString(argsElement)
                        is JsonPrimitive -> argsElement.content
                        else -> "{}"
                    }
                    parts.add(
                        UIMessagePart.ToolCall(
                            toolCallId = id,
                            toolName = name,
                            arguments = argsStr
                        )
                    )
                }

                else -> {
                    parseServerToolStep(stepObj)?.let(parts::add)
                        ?: stepObj["text"]?.jsonPrimitiveOrNull?.contentOrNull
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { text -> parts.add(UIMessagePart.Text(text = text)) }
                }
            }

            val status = stepObj["status"]?.jsonPrimitiveOrNull?.contentOrNull
            if (status != null && status != "in_progress") {
                finishReason = status
            }
        }

        if (finishReason == null) {
            finishReason = bodyJson["status"]?.jsonPrimitiveOrNull?.contentOrNull
        }

        val usage = parseUsage(
            bodyJson["usage"] as? JsonObject ?: bodyJson["usageMetadata"] as? JsonObject
        )

        return MessageChunk(
            id = bodyJson["id"]?.jsonPrimitiveOrNull?.contentOrNull ?: Uuid.random().toString(),
            model = modelId,
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = null,
                    message = UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = parts
                    ),
                    finishReason = finishReason
                )
            ),
            usage = usage,
            finishReasons = finishReason?.let { setOf(it) } ?: emptySet(),
            rawResponse = rawResponse,
        )
    }

    private fun parseModelOutputParts(content: JsonArray?): List<UIMessagePart> = content.orEmpty()
        .mapNotNull { contentElement ->
            val contentObject = contentElement as? JsonObject ?: return@mapNotNull null
            when (contentObject["type"]?.jsonPrimitiveOrNull?.contentOrNull ?: "text") {
                "text" -> contentObject["text"]?.jsonPrimitiveOrNull?.contentOrNull
                    ?.takeIf { it.isNotEmpty() }
                    ?.let(UIMessagePart::Text)

                "image" -> contentObject.toMediaDataUrl(defaultMimeType = "image/png")
                    ?.let(UIMessagePart::Image)

                "audio" -> contentObject.toMediaDataUrl(defaultMimeType = "audio/mp3")
                    ?.let(UIMessagePart::Audio)

                else -> null
            }
        }

    private fun JsonObject.toMediaDataUrl(defaultMimeType: String): String? {
        val data = this["data"]?.jsonPrimitiveOrNull?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return null
        if (data.startsWith("data:")) return data
        val mimeType = this["mime_type"]?.jsonPrimitiveOrNull?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: defaultMimeType
        return "data:$mimeType;base64,$data"
    }

    private fun parseServerToolStep(step: JsonObject): UIMessagePart.WebSearch? {
        val type = step["type"]?.jsonPrimitiveOrNull?.contentOrNull ?: return null
        if (!type.isServerToolStepType()) return null
        val id = step[if (type.endsWith("_result")) "call_id" else "id"]
            ?.jsonPrimitiveOrNull
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val status = if (type.endsWith("_result")) {
            WebSearchStatus.Completed
        } else {
            WebSearchStatus.Searching
        }
        val action = when {
            type.startsWith("google_search") -> WebSearchAction.Search
            type.startsWith("url_context") -> WebSearchAction.OpenPage
            else -> WebSearchAction.Unknown
        }
        return UIMessagePart.WebSearch(
            callId = "google_interactions:$type:$id",
            status = status,
            action = action,
            finishedAt = if (status == WebSearchStatus.Completed) Clock.System.now() else null,
            metadata = GoogleThoughtMetadata(interactionStep = step).toMetadata(),
        )
    }

    internal fun parseStreamMessageChunk(
        jsonData: JsonObject,
        eventType: String?,
        modelId: String,
        rawResponse: String,
        stepTracker: StepContextTracker,
    ): MessageChunk? {
        val stepObj = jsonData["step"] as? JsonObject
        val deltaObj = jsonData["delta"] as? JsonObject

        // Handle step.start / step.stop or step update
        if (stepObj != null) {
            val stepType = stepObj["type"]?.jsonPrimitiveOrNull?.contentOrNull
            val stepId = jsonData["index"]?.jsonPrimitiveOrNull?.contentOrNull
                ?: stepObj["id"]?.jsonPrimitiveOrNull?.contentOrNull
                ?: "step_${stepType ?: "active"}"
            if (stepType != null) {
                stepTracker.registerStep(stepId, stepType)
                if (stepType == "thought") {
                    stepTracker.recordThoughtStep(stepObj)
                } else if (stepType.isServerToolStepType()) {
                    stepTracker.recordServerToolStep(stepObj)
                }
            }
            if (stepType == "function_call") {
                val callId = stepObj["call_id"]?.jsonPrimitiveOrNull?.contentOrNull
                    ?: stepObj["id"]?.jsonPrimitiveOrNull?.contentOrNull
                val name = stepObj["name"]?.jsonPrimitiveOrNull?.contentOrNull
                stepTracker.recordFunctionCall(callId, name)
            }
        }

        val parts = mutableListOf<UIMessagePart>()
        var finishReason: String? = null

        // 1. Check if delta exists
        if (deltaObj != null) {
            val deltaType = deltaObj["type"]?.jsonPrimitiveOrNull?.contentOrNull
            val currentType = deltaType ?: stepTracker.currentStepType
            val deltaText = deltaObj["text"]?.jsonPrimitiveOrNull?.contentOrNull
            val deltaContentText = deltaObj["content"]?.let { contentElem ->
                when (contentElem) {
                    is JsonObject -> contentElem["text"]?.jsonPrimitiveOrNull?.contentOrNull
                    is JsonArray -> contentElem.mapNotNull {
                        (it as? JsonObject)?.get("text")?.jsonPrimitiveOrNull?.contentOrNull
                            ?: (it as? JsonPrimitive)?.contentOrNull
                    }.joinToString("\n").takeIf { it.isNotEmpty() }
                    is JsonPrimitive -> contentElem.contentOrNull
                }
            }
            val summary = deltaContentText
                ?: deltaObj["summary"]?.jsonPrimitiveOrNull?.contentOrNull
                ?: deltaObj["thought_summary"]?.jsonPrimitiveOrNull?.contentOrNull
            val signature = deltaObj["signature"]?.jsonPrimitiveOrNull?.contentOrNull
                ?: deltaObj["thought_signature"]?.jsonPrimitiveOrNull?.contentOrNull

            val partialArgs = deltaObj["partial_arguments"]?.jsonPrimitiveOrNull?.contentOrNull
                ?: deltaObj["arguments"]?.let {
                    if (it is JsonPrimitive) it.content else json.encodeToString(it)
                }

            val serverToolPart = stepTracker.updateServerToolStep(deltaObj)?.let(::parseServerToolStep)
            if (serverToolPart != null) {
                parts.add(serverToolPart)
            } else if (stepTracker.currentStepType == "thought" ||
                currentType in setOf("thought", "thought_summary", "thought_signature") ||
                summary != null || signature != null
            ) {
                stepTracker.recordThoughtDelta(deltaObj)
                val text = summary ?: deltaText ?: ""
                val thoughtMetadata = stepTracker.currentThoughtMetadata()
                if (text.isNotEmpty() || thoughtMetadata != null) {
                    parts.add(
                        UIMessagePart.Reasoning(
                            reasoning = text,
                            createdAt = Clock.System.now(),
                            finishedAt = null,
                            metadata = thoughtMetadata?.toMetadata(),
                        )
                    )
                }
            } else if (currentType == "function_call" || currentType == "arguments_delta" || partialArgs != null) {
                val name = deltaObj["name"]?.jsonPrimitiveOrNull?.contentOrNull
                    ?: stepTracker.currentFunctionName
                    ?: ""
                val callId = deltaObj["call_id"]?.jsonPrimitiveOrNull?.contentOrNull
                    ?: deltaObj["id"]?.jsonPrimitiveOrNull?.contentOrNull
                    ?: stepTracker.currentFunctionCallId
                    ?: ""
                if (name.isNotEmpty() || !partialArgs.isNullOrEmpty()) {
                    parts.add(
                        UIMessagePart.ToolCall(
                            toolCallId = callId,
                            toolName = name,
                            arguments = partialArgs ?: ""
                        )
                    )
                }
            } else if (deltaType == "image") {
                deltaObj.toMediaDataUrl(defaultMimeType = "image/png")?.let { dataUrl ->
                    parts.add(UIMessagePart.Image(dataUrl))
                }
            } else if (deltaType == "audio") {
                deltaObj.toMediaDataUrl(defaultMimeType = "audio/mp3")?.let { dataUrl ->
                    parts.add(UIMessagePart.Audio(dataUrl))
                }
            } else if (!deltaText.isNullOrEmpty()) {
                parts.add(
                    UIMessagePart.Text(
                        text = deltaText,
                    )
                )
            }
        }

        // 2. Check direct step payload (if step carries content or status)
        if (stepObj != null) {
            val status = stepObj["status"]?.jsonPrimitiveOrNull?.contentOrNull
            if (status == "completed" || status == "stop") {
                finishReason = status
            }
            when (stepObj["type"]?.jsonPrimitiveOrNull?.contentOrNull) {
                "thought" -> {
                    stepTracker.currentThoughtMetadata()?.let { metadata ->
                        parts.add(
                            UIMessagePart.Reasoning(
                                reasoning = "",
                                createdAt = Clock.System.now(),
                                finishedAt = null,
                                metadata = metadata.toMetadata(),
                            )
                        )
                    }
                }

                "model_output" -> parts += parseModelOutputParts(stepObj["content"] as? JsonArray)

                "function_call" -> if (parts.isEmpty()) {
                    val name = stepObj["name"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty()
                    val id = stepObj["call_id"]?.jsonPrimitiveOrNull?.contentOrNull
                        ?: stepObj["id"]?.jsonPrimitiveOrNull?.contentOrNull
                        ?: ""
                    val argsObj = stepObj["arguments"] as? JsonObject
                    val argsStr = if (argsObj != null && argsObj.isNotEmpty()) {
                        json.encodeToString(argsObj)
                    } else {
                        ""
                    }
                    parts.add(
                        UIMessagePart.ToolCall(
                            toolCallId = id,
                            toolName = name,
                            arguments = argsStr
                        )
                    )
                }

                else -> parseServerToolStep(stepTracker.currentServerToolStep ?: stepObj)?.let(parts::add)
            }
        }

        val interactionObj = jsonData["interaction"] as? JsonObject
        if (finishReason == null) {
            val interactionStatus = interactionObj?.get("status")?.jsonPrimitiveOrNull?.contentOrNull
                ?: jsonData["status"]?.jsonPrimitiveOrNull?.contentOrNull
            if (!interactionStatus.isNullOrEmpty()) {
                finishReason = interactionStatus
            }
        }

        val usage = parseUsage(
            jsonData["usage"] as? JsonObject
                ?: jsonData["usageMetadata"] as? JsonObject
                ?: interactionObj?.get("usage") as? JsonObject
        )

        if (parts.isEmpty() && usage == null && finishReason == null) {
            return null
        }

        val chunkId = interactionObj?.get("id")?.jsonPrimitiveOrNull?.contentOrNull
            ?: jsonData["id"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: Uuid.random().toString()

        return MessageChunk(
            id = chunkId,
            model = modelId,
            choices = if (parts.isNotEmpty() || finishReason != null) {
                listOf(
                    UIMessageChoice(
                        index = 0,
                        delta = if (parts.isNotEmpty()) {
                            UIMessage(
                                role = MessageRole.ASSISTANT,
                                parts = parts
                            )
                        } else null,
                        message = null,
                        finishReason = finishReason
                    )
                )
            } else {
                emptyList()
            },
            usage = usage,
            finishReasons = finishReason?.let { setOf(it) } ?: emptySet(),
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

    class StepContextTracker {
        private var _currentStepId: String? = null
        private var _currentStepType: String? = null
        private var _currentFunctionCallId: String? = null
        private var _currentFunctionName: String? = null
        private var _currentThoughtSignature: String? = null
        private val currentThoughtSummary = mutableListOf<JsonElement>()
        private var _currentServerToolStep: JsonObject? = null

        val currentStepId: String? get() = _currentStepId
        val currentStepType: String? get() = _currentStepType
        val currentFunctionCallId: String? get() = _currentFunctionCallId
        val currentFunctionName: String? get() = _currentFunctionName
        val currentServerToolStep: JsonObject? get() = _currentServerToolStep

        fun registerStep(stepId: String?, stepType: String) {
            _currentStepId = stepId
            _currentStepType = stepType
            if (stepType != "thought") {
                _currentThoughtSignature = null
                currentThoughtSummary.clear()
            }
            if (!stepType.isServerToolStepType()) {
                _currentServerToolStep = null
            }
        }

        fun recordFunctionCall(callId: String?, functionName: String?) {
            if (!callId.isNullOrBlank()) {
                _currentFunctionCallId = callId
            }
            if (!functionName.isNullOrBlank()) {
                _currentFunctionName = functionName
            }
        }

        fun recordThoughtStep(step: JsonObject) {
            _currentThoughtSignature = step["signature"]?.jsonPrimitiveOrNull?.contentOrNull
                ?: step["thought_signature"]?.jsonPrimitiveOrNull?.contentOrNull
            currentThoughtSummary.clear()
            (step["summary"] as? JsonArray)?.let(currentThoughtSummary::addAll)
        }

        fun recordThoughtDelta(delta: JsonObject) {
            delta["signature"]?.jsonPrimitiveOrNull?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?.let { _currentThoughtSignature = it }
            delta["thought_signature"]?.jsonPrimitiveOrNull?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?.let { _currentThoughtSignature = it }
            delta["content"]?.let { content ->
                if (content is JsonArray) {
                    currentThoughtSummary.addAll(content)
                } else {
                    currentThoughtSummary.add(content)
                }
            }
        }

        fun currentThoughtMetadata(): GoogleThoughtMetadata? {
            if (_currentStepType != "thought") return null
            return GoogleThoughtMetadata(
                thoughtSignature = _currentThoughtSignature,
                interactionStep = buildJsonObject {
                    put("type", "thought")
                    _currentThoughtSignature?.let { put("signature", it) }
                    if (currentThoughtSummary.isNotEmpty()) {
                        putJsonArray("summary") {
                            currentThoughtSummary.forEach { add(it) }
                        }
                    }
                },
            )
        }

        fun recordServerToolStep(step: JsonObject) {
            _currentServerToolStep = step
        }

        fun updateServerToolStep(delta: JsonObject): JsonObject? {
            val current = _currentServerToolStep ?: return null
            val currentType = current["type"]?.jsonPrimitiveOrNull?.contentOrNull ?: return null
            if (!currentType.isServerToolStepType()) return null
            val merged = current.toMutableMap()
            delta.forEach { (key, value) ->
                if (key != "type") {
                    merged[key] = value
                }
            }
            return JsonObject(merged).also { _currentServerToolStep = it }
        }
    }
}
