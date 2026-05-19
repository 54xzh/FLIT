package me.rerere.ai.provider.providers

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.parametersOrEmptyObject
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.ImageGenerationResult
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.configureClientWithProxy
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.encodeBase64
import me.rerere.ai.util.HttpStatusException
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.parseErrorDetail
import me.rerere.ai.util.RawResponseException
import me.rerere.ai.util.stringSafe
import me.rerere.ai.util.toHeaders
import me.rerere.common.http.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import kotlin.time.Clock

private const val TAG = "ClaudeProvider"
private const val ANTHROPIC_VERSION = "2023-06-01"
private const val CLAUDE_WEB_SEARCH_TOOL_NAME = "web_search"
private const val CLAUDE_WEB_SEARCH_TOOL_TYPE = "web_search_20250305"
private const val CLAUDE_WEB_SEARCH_MAX_USES = 5
private const val META_ANTHROPIC_TYPE = "anthropic_type"
private const val TYPE_SERVER_TOOL_USE = "server_tool_use"
private const val TYPE_WEB_SEARCH_TOOL_RESULT = "web_search_tool_result"

class ClaudeProvider(private val client: OkHttpClient) : Provider<ProviderSetting.Claude> {
    private val keyRoulette = KeyRoulette.default()

    override suspend fun listModels(providerSetting: ProviderSetting.Claude): List<Model> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${providerSetting.baseUrl}/models")
                .addHeader("x-api-key", keyRoulette.next(providerSetting))
                .addHeader("anthropic-version", ANTHROPIC_VERSION)
                .get()
                .build()

            val response =
                client.configureClientWithProxy(providerSetting.proxy).newCall(request).execute()
            if (!response.isSuccessful) {
                error("Failed to get models: ${response.code} ${response.body?.string()}")
            }

            val bodyStr = response.body?.string() ?: ""
            val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
            val data = bodyJson["data"]?.jsonArray ?: return@withContext emptyList()

            data.mapNotNull { modelJson ->
                val modelObj = modelJson.jsonObject
                val id = modelObj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val displayName = modelObj["display_name"]?.jsonPrimitive?.contentOrNull ?: id

                Model(
                    modelId = id,
                    displayName = displayName,
                    tools = if (ModelRegistry.CLAUDE_SERIES.match(id)) {
                        setOf(BuiltInTools.ClaudeWebSearch)
                    } else {
                        emptySet()
                    },
                )
            }
        }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams
    ): ImageGenerationResult {
        error("Claude provider does not support image generation")
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.Claude,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): MessageChunk = withContext(Dispatchers.IO) {
        val requestBody = buildMessageRequest(messages, params)
        val requestBodyJson = json.encodeToString(requestBody)
        params.onRequestBody?.invoke(requestBodyJson)
        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/messages")
            .headers(params.customHeaders.toHeaders())
            .post(requestBodyJson.toRequestBody("application/json".toMediaType()))
            .addHeader("x-api-key", keyRoulette.next(providerSetting))
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        Log.i(TAG, "generateText: $requestBodyJson")

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
            json.parseToJsonElement(bodyStr).jsonObject
        }.getOrElse { throwable ->
            throw RawResponseException(
                message = "Failed to parse response body: ${throwable.message}",
                rawResponse = bodyStr,
                cause = throwable,
            )
        }

        runCatching {
            // 从 JsonObject 中提取必要的信息
            val id = bodyJson["id"]?.jsonPrimitive?.contentOrNull ?: ""
            val model = bodyJson["model"]?.jsonPrimitive?.contentOrNull ?: ""
            val content = bodyJson["content"]?.jsonArray ?: JsonArray(emptyList())
            val stopReason = bodyJson["stop_reason"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            val usage = parseTokenUsage(bodyJson["usage"] as? JsonObject)

            MessageChunk(
                id = id,
                model = model,
                choices = listOf(
                    UIMessageChoice(
                        index = 0,
                        delta = null,
                        message = parseMessage(content),
                        finishReason = stopReason
                    )
                ),
                usage = usage,
                finishReasons = stopReason
                    .takeIf { reason -> reason.isNotBlank() && reason != "unknown" }
                    ?.let { setOf(it) }
                    ?: emptySet(),
                rawResponse = bodyStr,
            )
        }.getOrElse { throwable ->
            throw RawResponseException(
                message = "Failed to parse response payload: ${throwable.message}",
                rawResponse = bodyStr,
                cause = throwable,
            )
        }
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.Claude,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<MessageChunk> = callbackFlow {
        val requestBody = buildMessageRequest(messages, params, stream = true)
        val requestBodyJson = json.encodeToString(requestBody)
        params.onRequestBody?.invoke(requestBodyJson)
        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/messages")
            .headers(params.customHeaders.toHeaders())
            .post(requestBodyJson.toRequestBody("application/json".toMediaType()))
            .addHeader("x-api-key", keyRoulette.next(providerSetting))
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .addHeader("Content-Type", "application/json")
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        Log.i(TAG, "streamText: $requestBodyJson")

        requestBody["messages"]!!.jsonArray.forEach {
            Log.i(TAG, "streamText: $it")
        }
        val rawEventBuffer = StringBuilder()

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                Log.d(TAG, "onEvent: type=$type, data=$data")
                if (rawEventBuffer.isNotEmpty()) rawEventBuffer.append("\n")
                rawEventBuffer.append(data)

                val dataJson = runCatching { json.parseToJsonElement(data).jsonObject }
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

                if (type == "error") {
                    val error = dataJson["error"]?.parseErrorDetail()
                    close(
                        RawResponseException(
                            message = error?.message ?: "Claude stream error",
                            rawResponse = rawEventBuffer.toString(),
                            cause = error,
                        )
                    )
                    return
                }

                val messageChunk = runCatching {
                    val deltaMessage = parseMessage(buildJsonArray {
                        val contentBlockObj = dataJson["content_block"]?.jsonObject
                        val deltaObj = dataJson["delta"]?.jsonObject
                        if (contentBlockObj != null) {
                            add(contentBlockObj)
                        }
                        if (deltaObj != null) {
                            add(deltaObj)
                        }
                    })
                    val tokenUsage = parseTokenUsage(
                        dataJson["usage"]?.jsonObject ?: dataJson["message"]?.jsonObject?.get("usage")?.jsonObject
                    )
                    val finishReason = dataJson["delta"]?.jsonObject
                        ?.get("stop_reason")
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?: dataJson["message"]?.jsonObject
                            ?.get("stop_reason")
                            ?.jsonPrimitive
                            ?.contentOrNull
                        ?: dataJson["stop_reason"]?.jsonPrimitive?.contentOrNull
                    MessageChunk(
                        id = id ?: "",
                        model = "",
                        choices = listOf(
                            UIMessageChoice(
                                index = 0,
                                delta = deltaMessage,
                                message = null,
                                finishReason = finishReason
                            )
                        ),
                        usage = tokenUsage,
                        finishReasons = finishReason
                            ?.takeIf { reason -> reason.isNotBlank() && reason != "unknown" }
                            ?.let { setOf(it) }
                            ?: emptySet(),
                        rawResponse = data,
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

                if (type == "message_stop") {
                    Log.d(TAG, "Stream ended")
                    close()
                }

                trySend(messageChunk)
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                var exception = t
                var rawFailureResponse = ""

                t?.printStackTrace()
                Log.e(TAG, "onFailure: ${t?.javaClass?.name} ${t?.message} / $response")

                val bodyRaw = response?.body?.stringSafe()
                rawFailureResponse = bodyRaw.orEmpty()
                try {
                    if (!bodyRaw.isNullOrBlank()) {
                        val bodyElement = Json.parseToJsonElement(bodyRaw)
                        Log.i(TAG, "Error response: $bodyElement")
                        exception = bodyElement.parseErrorDetail()
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "onFailure: failed to parse from $bodyRaw")
                    e.printStackTrace()
                } finally {
                    val exceptionWithStatus = response?.let { resp ->
                        HttpStatusException(
                            statusCode = resp.code,
                            message = exception?.message ?: "HTTP ${resp.code}",
                            cause = exception,
                        )
                    } ?: exception
                    close(
                        RawResponseException(
                            message = exceptionWithStatus?.message ?: "Claude stream failed",
                            rawResponse = rawFailureResponse.takeIf { it.isNotBlank() } ?: rawEventBuffer.toString(),
                            cause = exceptionWithStatus,
                        )
                    )
                }
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        }

        val eventSource =
            EventSources.createFactory(client.configureClientWithProxy(providerSetting.proxy))
                .newEventSource(request, listener)

        awaitClose {
            Log.d(TAG, "Closing eventSource")
            eventSource.cancel()
        }
    }

    private fun buildMessageRequest(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        stream: Boolean = false
    ): JsonObject {
        return buildJsonObject {
            put("model", params.model.modelId)
            put("messages", buildMessages(messages))
            put("max_tokens", params.maxTokens ?: 64_000)

            if (params.temperature != null && (params.thinkingBudget ?: 0) == 0) put(
                "temperature",
                params.temperature
            )
            if (params.topP != null) put("top_p", params.topP)

            put("stream", stream)

            // system prompt
            val systemMessage = messages.firstOrNull { it.role == MessageRole.SYSTEM }
            if (systemMessage != null) {
                put("system", buildJsonArray {
                    systemMessage.parts.filterIsInstance<UIMessagePart.Text>().forEach { part ->
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", part.text)
                        })
                    }
                })
            }

            // 处理 thinking budget
            if (params.model.abilities.contains(ModelAbility.REASONING)) {
                val level = ReasoningLevel.fromBudgetTokens(params.thinkingBudget ?: 0)
                put("thinking", buildJsonObject {
                    if (level == ReasoningLevel.OFF) {
                        put("type", "disabled")
                    } else {
                        put("type", "enabled")
                        if (level != ReasoningLevel.AUTO) put("budget_tokens", params.thinkingBudget ?: 0)
                    }
                })
            }

            val builtInTools = buildClaudeBuiltInTools(params.model)
            // 处理工具
            if ((params.model.abilities.contains(ModelAbility.TOOL) && params.tools.isNotEmpty()) || builtInTools.isNotEmpty()) {
                putJsonArray("tools") {
                    params.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("input_schema", json.encodeToJsonElement(tool.parametersOrEmptyObject()))
                        })
                    }
                    builtInTools.forEach { add(it) }
                }
            }
        }.mergeCustomBody(params.customBody)
    }

    private fun buildMessages(messages: List<UIMessage>) = buildJsonArray {
        messages
            .filter { it.isValidToUpload() && it.role != MessageRole.SYSTEM }
            .forEach { message ->
                if (message.role == MessageRole.TOOL) {
                    message.getToolResults().forEach { result ->
                        add(buildJsonObject {
                            put("role", "user")
                            putJsonArray("content") {
                                add(buildJsonObject {
                                    put("type", "tool_result")
                                    put("tool_use_id", result.toolCallId)
                                    put("content", json.encodeToString(result.content))
                                })
                            }
                        })
                    }
                    return@forEach
                }

                add(buildJsonObject {
                    // role
                    put("role", JsonPrimitive(message.role.name.lowercase()))

                    // content
                    putJsonArray("content") {
                        message.parts.forEach { part ->
                            when (part) {
                                is UIMessagePart.Text -> {
                                    add(buildJsonObject {
                                        put("type", "text")
                                        put("text", part.text)
                                        part.metadata?.get("citations")?.let { citations ->
                                            put("citations", citations)
                                        }
                                    })
                                }

                                is UIMessagePart.Image -> {
                                    add(buildJsonObject {
                                        part.encodeBase64().onSuccess { base64Data ->
                                            put("type", "image")
                                            put("source", buildJsonObject {
                                                put("type", "base64")
                                                put(
                                                    "media_type",
                                                    "image/jpeg"
                                                ) // 默认为 jpeg，可能需要根据实际情况调整
                                                put(
                                                    "data",
                                                    base64Data.substringAfter(",")
                                                ) // 移除 data:image/jpeg;base64, 前缀
                                            })
                                        }.onFailure {
                                            it.printStackTrace()
                                            Log.w(TAG, "encode image failed: ${part.url}")
                                            // 如果图片编码失败，添加一个空文本块
                                            put("type", "text")
                                            put("text", "")
                                        }
                                    })
                                }

                                is UIMessagePart.ToolCall -> {
                                    add(buildJsonObject {
                                        val anthropicType =
                                            part.metadata?.get(META_ANTHROPIC_TYPE)?.jsonPrimitive?.contentOrNull
                                        val parsedInput = runCatching {
                                            json.parseToJsonElement(part.arguments.ifBlank { "{}" })
                                        }.getOrElse {
                                            JsonObject(emptyMap())
                                        }
                                        put(
                                            "type",
                                            if (anthropicType == TYPE_SERVER_TOOL_USE) {
                                                TYPE_SERVER_TOOL_USE
                                            } else {
                                                "tool_use"
                                            }
                                        )
                                        put("id", part.toolCallId)
                                        put("name", part.toolName)
                                        put("input", parsedInput)
                                    })
                                }

                                is UIMessagePart.ToolResult -> {
                                    val anthropicType =
                                        part.metadata?.get(META_ANTHROPIC_TYPE)?.jsonPrimitive?.contentOrNull
                                    if (anthropicType == TYPE_WEB_SEARCH_TOOL_RESULT) {
                                        add(buildJsonObject {
                                            put("type", TYPE_WEB_SEARCH_TOOL_RESULT)
                                            put("tool_use_id", part.toolCallId)
                                            put("content", part.content)
                                            part.metadata?.forEach { entry ->
                                                if (
                                                    entry.key != META_ANTHROPIC_TYPE &&
                                                    entry.key != "type" &&
                                                    entry.key != "tool_use_id" &&
                                                    entry.key != "content"
                                                ) {
                                                    put(entry.key, entry.value)
                                                }
                                            }
                                        })
                                    } else {
                                        Log.w(TAG, "buildMessages: assistant tool_result not supported: $part")
                                    }
                                }

                                is UIMessagePart.Reasoning -> {
                                    add(buildJsonObject {
                                        put("type", "thinking")
                                        put("thinking", part.reasoning)
                                        part.metadata?.let {
                                            it.forEach { entry ->
                                                put(entry.key, entry.value)
                                            }
                                        }
                                    })
                                }

                                else -> {
                                    Log.w(TAG, "buildMessages: message part not supported: $part")
                                    // DO NOTHING
                                }
                            }
                        }
                    }
                })
            }
    }

    private fun parseMessage(content: JsonArray): UIMessage {
        val parts = mutableListOf<UIMessagePart>()
        val annotations = mutableListOf<UIMessageAnnotation>()

        content.forEach { contentBlock ->
            val block = contentBlock.jsonObject
            val type = block["type"]?.jsonPrimitive?.contentOrNull

            when (type) {
                "text", "text_delta" -> {
                    val text = block["text"]?.jsonPrimitive?.contentOrNull ?: ""
                    val citations = block["citations"] as? JsonArray
                    val metadata = citations?.takeIf { it.isNotEmpty() }?.let { citationArray ->
                        buildJsonObject {
                            put("citations", citationArray)
                        }
                    }
                    parts.add(UIMessagePart.Text(text, metadata = metadata))
                    annotations += parseTextCitations(citations)
                }

                "thinking", "thinking_delta", "signature_delta" -> {
                    val thinking = block["thinking"]?.jsonPrimitive?.contentOrNull ?: ""
                    val signature = block["signature"]?.jsonPrimitive?.contentOrNull
                    val reasoning = UIMessagePart.Reasoning(
                        reasoning = thinking,
                        createdAt = Clock.System.now(),
                    )
                    if (signature != null) {
                        reasoning.metadata = buildJsonObject {
                            put("signature", signature)
                        }
                    }
                    parts.add(reasoning)
                }

                "redacted_thinking" -> {
                    error("redacted_thinking detected, not support yet!")
                }

                "tool_use" -> {
                    val id = block["id"]?.jsonPrimitive?.contentOrNull ?: ""
                    val name = block["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    val input = block["input"]
                    val inputText = when (input) {
                        null -> ""
                        is JsonObject -> if (input.isEmpty()) "" else json.encodeToString(input)
                        else -> json.encodeToString(input)
                    }
                    val metadata = if (name == CLAUDE_WEB_SEARCH_TOOL_NAME) {
                        buildJsonObject {
                            put(META_ANTHROPIC_TYPE, TYPE_SERVER_TOOL_USE)
                        }
                    } else {
                        null
                    }
                    parts.add(
                        UIMessagePart.ToolCall(
                            toolCallId = id,
                            toolName = name,
                            arguments = inputText,
                            metadata = metadata
                        )
                    )
                }

                TYPE_SERVER_TOOL_USE -> {
                    val id = block["id"]?.jsonPrimitive?.contentOrNull ?: ""
                    val name = block["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    val input = block["input"]
                    val inputText = when (input) {
                        null -> ""
                        is JsonObject -> if (input.isEmpty()) "" else json.encodeToString(input)
                        else -> json.encodeToString(input)
                    }
                    parts.add(
                        UIMessagePart.ToolCall(
                            toolCallId = id,
                            toolName = name,
                            arguments = inputText,
                            metadata = buildJsonObject {
                                put(META_ANTHROPIC_TYPE, TYPE_SERVER_TOOL_USE)
                            }
                        )
                    )
                }

                "input_json_delta" -> {
                    val input = block["partial_json"]?.jsonPrimitive?.contentOrNull
                    parts.add(
                        UIMessagePart.ToolCall(
                            toolCallId = "",
                            toolName = "",
                            arguments = input ?: ""
                        )
                    )
                }

                TYPE_WEB_SEARCH_TOOL_RESULT -> {
                    val toolUseId = block["tool_use_id"]?.jsonPrimitive?.contentOrNull ?: ""
                    val contentElement = block["content"] ?: JsonArray(emptyList())
                    val metadata = buildJsonObject {
                        put(META_ANTHROPIC_TYPE, TYPE_WEB_SEARCH_TOOL_RESULT)
                        block.forEach { entry ->
                            if (
                                entry.key != "type" &&
                                entry.key != "tool_use_id" &&
                                entry.key != "content"
                            ) {
                                put(entry.key, entry.value)
                            }
                        }
                    }
                    parts.add(
                        UIMessagePart.ToolResult(
                            toolCallId = toolUseId,
                            toolName = CLAUDE_WEB_SEARCH_TOOL_NAME,
                            content = contentElement,
                            arguments = JsonObject(emptyMap()),
                            metadata = metadata
                        )
                    )
                }
            }
        }

        return UIMessage(
            role = MessageRole.ASSISTANT,
            parts = parts,
            annotations = annotations.distinct()
        )
    }

    private fun parseTextCitations(citations: JsonArray?): List<UIMessageAnnotation> {
        if (citations == null || citations.isEmpty()) return emptyList()
        return citations.mapNotNull { citation ->
            val citationObject = citation as? JsonObject ?: return@mapNotNull null
            val url = citationObject["url"]?.jsonPrimitive?.contentOrNull
                ?: citationObject["source_url"]?.jsonPrimitive?.contentOrNull
            if (url.isNullOrBlank()) return@mapNotNull null
            val title = citationObject["title"]?.jsonPrimitive?.contentOrNull
                ?: citationObject["source"]?.jsonPrimitive?.contentOrNull
                ?: url
            UIMessageAnnotation.UrlCitation(
                title = title,
                url = url
            )
        }
    }

    private fun buildClaudeBuiltInTools(model: Model): List<JsonObject> {
        if (!model.tools.contains(BuiltInTools.ClaudeWebSearch)) return emptyList()
        return listOf(
            buildJsonObject {
                put("type", CLAUDE_WEB_SEARCH_TOOL_TYPE)
                put("name", CLAUDE_WEB_SEARCH_TOOL_NAME)
                put("max_uses", CLAUDE_WEB_SEARCH_MAX_USES)
            }
        )
    }

    private fun parseTokenUsage(jsonObject: JsonObject?): TokenUsage? {
        if (jsonObject == null) return null
        return TokenUsage(
            promptTokens = jsonObject["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            completionTokens = jsonObject["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            totalTokens = (jsonObject["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0) +
                (jsonObject["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0)
        )
    }
}
