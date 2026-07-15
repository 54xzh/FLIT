package me.rerere.ai.provider.providers.openai

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
import kotlinx.serialization.json.add
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
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.configureClientWithProxy
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.encodeBase64
import me.rerere.ai.util.HttpStatusException
import me.rerere.ai.util.isLikelySsePayload
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.parseErrorDetail
import me.rerere.ai.util.RawResponseException
import me.rerere.ai.util.SSEEventSource
import me.rerere.ai.util.stringSafe
import me.rerere.ai.util.toHeaders
import me.rerere.common.http.await
import me.rerere.common.http.jsonArrayOrNull
import me.rerere.common.http.jsonObjectOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import kotlin.time.Clock

private const val TAG = "ChatCompletionsAPI"

class ChatCompletionsAPI(
    private val client: OkHttpClient,
    private val keyRoulette: KeyRoulette
) : OpenAIImpl {
    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk = withContext(Dispatchers.IO) {
        val requestBody =
            buildChatCompletionRequest(
                messages = messages,
                params = params,
                providerSetting = providerSetting
            )
        val requestBodyJson = json.encodeToString(requestBody)
        params.onRequestBody?.invoke(requestBodyJson)

        val proxyClient = client.configureClientWithProxy(providerSetting.proxy)

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}${providerSetting.chatCompletionsPath}")
            .headers(params.customHeaders.toHeaders())
            .post(requestBodyJson.toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer ${keyRoulette.next(providerSetting)}")
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        Log.i(TAG, "generateText: $requestBodyJson")

        val response = proxyClient.newCall(request).await()
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

        // 从 JsonObject 中提取必要的信息
        runCatching {
            val id = bodyJson["id"]?.jsonPrimitive?.contentOrNull ?: ""
            val model = bodyJson["model"]?.jsonPrimitive?.contentOrNull ?: ""
            val choice =
                bodyJson["choices"]?.jsonArray?.get(0)?.jsonObject ?: error("choices is null")

            val message = choice["message"]?.jsonObject ?: throw Exception("message is null")
            val finishReason = choice["finish_reason"]
                ?.jsonPrimitive
                ?.content
                ?: "unknown"
            val usage = parseTokenUsage(bodyJson["usage"] as? JsonObject)

            MessageChunk(
                id = id,
                model = model,
                choices = listOf(
                    UIMessageChoice(
                        index = 0,
                        delta = null,
                        message = parseMessage(message),
                        finishReason = finishReason
                    )
                ),
                usage = usage,
                finishReasons = finishReason
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
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk> = callbackFlow {
        val requestBody = buildChatCompletionRequest(
            messages = messages,
            params = params,
            providerSetting = providerSetting,
            stream = true,
        )
        val requestBodyJson = json.encodeToString(requestBody)
        params.onRequestBody?.invoke(requestBodyJson)

        val proxyClient = client.configureClientWithProxy(providerSetting.proxy)

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}${providerSetting.chatCompletionsPath}")
            .headers(params.customHeaders.toHeaders())
            .post(requestBodyJson.toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer ${keyRoulette.next(providerSetting)}")
            .addHeader("Content-Type", "application/json")
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        Log.i(TAG, "streamText: $requestBodyJson")

        // just for debugging response body
        // println(client.newCall(request).await().body?.string())
        val rawEventBuffer = StringBuilder()

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                val eventLines = data
                    .trim()
                    .split("\n")
                    .map { it.trim().removePrefix("data:").trim() }
                    .filter { it.isNotBlank() }
                val hasDoneEvent = eventLines.any { it == "[DONE]" }
                Log.d(TAG, "onEvent: $data")
                if (rawEventBuffer.isNotEmpty()) rawEventBuffer.append("\n")
                rawEventBuffer.append(data)
                val payloads = runCatching {
                    eventLines
                        .filter { it != "[DONE]" }
                        .map { json.parseToJsonElement(it).jsonObject }
                }.getOrElse { throwable ->
                    close(
                        RawResponseException(
                            message = "Failed to parse stream event: ${throwable.message}",
                            rawResponse = rawEventBuffer.toString(),
                            cause = throwable,
                        )
                    )
                    return
                }
                if (payloads.isEmpty() && hasDoneEvent) {
                    println("[onEvent] (done) 结束流: $data")
                    close()
                    return
                }

                payloads.forEach { payload ->
                    val messageChunk = runCatching {
                        if (payload["error"] != null) {
                            throw payload["error"]!!.parseErrorDetail()
                        }
                        val payloadId = payload["id"]?.jsonPrimitive?.contentOrNull ?: ""
                        val model = payload["model"]?.jsonPrimitive?.contentOrNull ?: ""

                        val choices = payload["choices"]?.jsonArray ?: JsonArray(emptyList())
                        val choiceList = buildList {
                            if (choices.isNotEmpty()) {
                                val choice = choices[0].jsonObject
                                val message =
                                    choice["delta"]?.jsonObject ?: choice["message"]?.jsonObject
                                    ?: throw Exception("delta/message is null")
                                val finishReason =
                                    choice["finish_reason"]?.jsonPrimitive?.contentOrNull
                                        ?: "unknown"
                                add(
                                    UIMessageChoice(
                                        index = 0,
                                        delta = parseMessage(message),
                                        message = null,
                                        finishReason = finishReason,
                                    )
                                )
                            }
                        }
                        val usage = parseTokenUsage(payload["usage"] as? JsonObject)

                        MessageChunk(
                            id = payloadId,
                            model = model,
                            choices = choiceList,
                            usage = usage,
                            finishReasons = choiceList
                                .mapNotNull { choice -> choice.finishReason?.takeIf { it.isNotBlank() && it != "unknown" } }
                                .toSet(),
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
                    trySend(messageChunk)
                }
                if (hasDoneEvent) {
                    println("[onEvent] (done) 结束流: $data")
                    close()
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                var exception = t
                var rawFailureResponse = ""

                t?.printStackTrace()
                println("[onFailure] 发生错误: ${t?.javaClass?.name} ${t?.message} / $response")

                val bodyRaw = response?.body?.stringSafe()
                rawFailureResponse = bodyRaw.orEmpty()
                try {
                    if (!bodyRaw.isNullOrBlank()) {
                        if (bodyRaw.isLikelySsePayload()) {
                            Log.w(TAG, "onFailure: skipped JSON error parse for SSE response")
                        } else {
                            val bodyElement = Json.parseToJsonElement(bodyRaw)
                            println(bodyElement)
                            exception = bodyElement.parseErrorDetail()
                            Log.i(TAG, "onFailure: $exception")
                        }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "onFailure: failed to parse from $bodyRaw", e)
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
                            message = exceptionWithStatus?.message ?: "OpenAI stream failed",
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

        val eventSource = SSEEventSource.factory(proxyClient).newEventSource(request, listener)

        awaitClose {
            println("[awaitClose] 关闭eventSource ")
            eventSource.cancel()
        }
    }

    private fun buildChatCompletionRequest(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        providerSetting: ProviderSetting.OpenAI,
        stream: Boolean = false,
    ): JsonObject {
        val host = providerSetting.baseUrl.toHttpUrl().host
        return buildJsonObject {
            put("model", params.model.modelId)
            put("messages", buildMessages(messages, params.model.modelId))

            if (isModelAllowTemperature(params.model)) {
                if (params.temperature != null) put("temperature", params.temperature)
                if (params.topP != null) put("top_p", params.topP)
            }
            if (params.maxTokens != null) put("max_tokens", params.maxTokens)

            put("stream", stream)
            if (stream) {
                // Some providers don't support stream_options
                if (host != "api.mistral.ai" && host != "open.bigmodel.cn") {
                    put("stream_options", buildJsonObject {
                        put("include_usage", true)
                    })
                }
            }

            // open router适配
            if(host == "openrouter.ai") {
                if(params.model.outputModalities.contains(Modality.IMAGE)) {
                    put("modalities", buildJsonArray {
                        add("image")
                        add("text")
                    })
                }
            }

            if (params.model.abilities.contains(ModelAbility.REASONING)) {
                applyChatCompletionsReasoning(
                    host = host,
                    modelId = params.model.modelId,
                    level = params.reasoningLevel,
                )
            }

            if (params.model.abilities.contains(ModelAbility.TOOL) && params.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    params.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                put(
                                    "parameters",
                                    json.encodeToJsonElement(
                                        tool.parametersOrEmptyObject()
                                    )
                                )
                            })
                        })
                    }
                }
            }
        }.mergeCustomBody(params.customBody)
    }

    private fun isModelAllowTemperature(model: Model): Boolean {
        return !ModelRegistry.OPENAI_O_MODELS.match(model.modelId) && !ModelRegistry.GPT_5.match(model.modelId)
    }

    private fun buildMessages(
        messages: List<UIMessage>,
        modelId: String
    ) = buildJsonArray {
        val lastUserMessageIndex = messages.indexOfLast { it.role == MessageRole.USER }
        val requireReasoningContentForToolCalls =
            modelId.contains("deepseek", ignoreCase = true) ||
                modelId.contains("kimi", ignoreCase = true) ||
                modelId.contains("mimo", ignoreCase = true)

        // Identify indices belonging to turns (between user messages) that contain tool calls.
        // DeepSeek/Kimi/MiMo require reasoning_content from ALL assistant messages in such turns
        // to be passed back in subsequent requests, not just the current turn.
        val toolCallTurnIndices = if (requireReasoningContentForToolCalls) {
            val indices = mutableSetOf<Int>()
            val userIndices = messages.mapIndexedNotNull { i, m ->
                if (m.role == MessageRole.USER) i else null
            }
            for (i in userIndices.indices) {
                val turnStart = if (i == 0) 0 else userIndices[i - 1] + 1
                val turnEnd = userIndices[i]
                val turnHasToolCalls = (turnStart until turnEnd).any { idx ->
                    messages[idx].role == MessageRole.ASSISTANT && messages[idx].getToolCalls().isNotEmpty()
                }
                if (turnHasToolCalls) {
                    (turnStart until turnEnd).forEach { indices.add(it) }
                }
            }
            indices
        } else {
            emptySet()
        }

        messages.forEachIndexed { index, message ->
            if (!message.isValidToUpload()) return@forEachIndexed

                if (message.role == MessageRole.TOOL) {
                    message.getToolResults().forEach { result ->
                        add(buildJsonObject {
                            put("role", "tool")
                            put("name", result.toolName)
                            put("tool_call_id", result.toolCallId)
                            put("content", json.encodeToString(result.content))
                        })
                    }
                    return@forEachIndexed
                }
                add(buildJsonObject {
                    // role
                    put("role", JsonPrimitive(message.role.name.lowercase()))

                    // content
                    if (message.parts.isOnlyTextPart()) {
                        // 如果只是纯文本，直接赋值给content
                        put(
                            "content",
                            message.parts.filterIsInstance<UIMessagePart.Text>().first().text
                        )
                    } else {
                        // 否则，使用parts构建
                        putJsonArray("content") {
                            message.parts.forEach { part ->
                                when (part) {
                                    is UIMessagePart.Text -> {
                                        add(buildJsonObject {
                                            put("type", "text")
                                            put("text", part.text)
                                        })
                                    }

                                    is UIMessagePart.Image -> {
                                        add(buildJsonObject {
                                            part.encodeBase64().onSuccess {
                                                put("type", "image_url")
                                                put("image_url", buildJsonObject {
                                                    put("url", it)
                                                })
                                            }.onFailure {
                                                it.printStackTrace()
                                                println("encode image failed: ${part.url}")

                                                put("type", "text")
                                                put("text", "")
                                            }
                                        })
                                    }

                                    is UIMessagePart.Audio -> {
                                        add(buildJsonObject {
                                            part.encodeBase64(withPrefix = false).onSuccess {
                                                put("type", "input_audio")
                                                put("input_audio", buildJsonObject {
                                                    put("data", it)
                                                    put("format", "mp3")
                                                })
                                            }.onFailure {
                                                it.printStackTrace()
                                                println("encode audio failed: ${part.url}")

                                                put("type", "text")
                                                put("text", "")
                                            }
                                        })
                                    }

                                    else -> {
                                        Log.w(
                                            TAG,
                                            "buildMessages: message part not supported: $part"
                                        )
                                        // DO NOTHING
                                    }
                                }
                            }
                        }
                    }

                    // tool_calls
                    message.getToolCalls()
                        .takeIf { it.isNotEmpty() }
                        ?.let { toolCalls ->
                            put("tool_calls", buildJsonArray {
                                toolCalls.forEach { toolCall ->
                                    add(buildJsonObject {
                                        put("id", toolCall.toolCallId)
                                        put("type", "function")
                                        put("function", buildJsonObject {
                                            put("name", toolCall.toolName)
                                            put("arguments", toolCall.arguments)
                                        })
                                    })
                                }
                            })
                        }

                    val reasoning = message.parts
                        .filterIsInstance<UIMessagePart.Reasoning>()
                        .firstOrNull()
                        ?.reasoning
                    val inToolCallTurn = index in toolCallTurnIndices
                    val hasToolCalls = message.getToolCalls().isNotEmpty()
                    val shouldAttachReasoningContent =
                        message.role == MessageRole.ASSISTANT &&
                            (inToolCallTurn && (!reasoning.isNullOrBlank() || hasToolCalls) || (
                                index > lastUserMessageIndex &&
                                    (!reasoning.isNullOrBlank() || (requireReasoningContentForToolCalls && hasToolCalls))
                                ))
                    if (shouldAttachReasoningContent) {
                        put("reasoning_content", reasoning ?: "")
                    }
                })
        }
    }

    private fun parseMessage(jsonObject: JsonObject): UIMessage {
        val role = MessageRole.valueOf(
            jsonObject["role"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: "ASSISTANT"
        )

        // 也许支持其他模态的输出content? 暂时只支持文本吧
        val content = jsonObject["content"]?.jsonPrimitive?.contentOrNull ?: ""
        val reasoning = jsonObject["reasoning_content"]?.jsonPrimitive?.contentOrNull
            ?: jsonObject["reasoning"]?.jsonPrimitive?.contentOrNull
        val toolCalls = jsonObject["tool_calls"] as? JsonArray ?: JsonArray(emptyList())
        val images = jsonObject["images"] as? JsonArray ?: JsonArray(emptyList())

        return UIMessage(
            role = role,
            parts = buildList {
                if (!reasoning.isNullOrEmpty()) {
                    add(
                        UIMessagePart.Reasoning(
                            reasoning = reasoning,
                            createdAt = Clock.System.now(),
                            finishedAt = null
                        )
                    )
                }
                toolCalls.forEach { toolCalls ->
                    val type = toolCalls.jsonObject["type"]?.jsonPrimitive?.contentOrNull
                    if (!type.isNullOrEmpty() && type != "function") error("tool call type not supported: $type")
                    val toolCallId = toolCalls.jsonObject["id"]?.jsonPrimitive?.contentOrNull
                    val toolName =
                        toolCalls.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                    val arguments =
                        toolCalls.jsonObject["function"]?.jsonObject?.get("arguments")?.jsonPrimitive?.contentOrNull
                    add(
                        UIMessagePart.ToolCall(
                            toolCallId = toolCallId ?: "",
                            toolName = toolName ?: "",
                            arguments = arguments ?: ""
                        )
                    )
                }
                add(UIMessagePart.Text(content))
                images.forEach { image ->
                    val imageObject = image.jsonObjectOrNull ?: return@forEach
                    val type = imageObject["type"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    if (type != "image_url") return@forEach
                    val url = imageObject["image_url"]?.jsonObjectOrNull?.get("url")?.jsonPrimitive?.contentOrNull ?: return@forEach
                    require(url.startsWith("data:image")) { "Only data uri is supported" }
                    add(UIMessagePart.Image(url.substringAfter("data:image/png;base64,")))
                }
            },
            annotations = parseAnnotations(
                jsonArray = jsonObject["annotations"]?.jsonArrayOrNull ?: JsonArray(
                    emptyList()
                )
            ),
        )
    }

    private fun parseAnnotations(jsonArray: JsonArray): List<UIMessageAnnotation> {
        return jsonArray.map { element ->
            val type =
                element.jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: error("type is null")
            when (type) {
                "url_citation" -> {
                    UIMessageAnnotation.UrlCitation(
                        title = element.jsonObject["url_citation"]?.jsonObject?.get("title")?.jsonPrimitive?.contentOrNull
                            ?: "",
                        url = element.jsonObject["url_citation"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                            ?: "",
                    )
                }

                else -> error("unknown annotation type: $type")
            }
        }
    }

    private fun parseTokenUsage(jsonObject: JsonObject?): TokenUsage? {
        if (jsonObject == null) return null
        return TokenUsage(
            promptTokens = jsonObject["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            completionTokens = jsonObject["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            totalTokens = jsonObject["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            cachedTokens = jsonObject["prompt_tokens_details"]?.jsonObjectOrNull?.get("cached_tokens")?.jsonPrimitive?.intOrNull
                ?: 0
        )
    }

    private fun List<UIMessagePart>.isOnlyTextPart(): Boolean {
        val gonnaSend = filter { it is UIMessagePart.Text || it is UIMessagePart.Image || it is UIMessagePart.Audio || it is UIMessagePart.Video }.size
        val texts = filter { it is UIMessagePart.Text }.size
        return gonnaSend == texts && texts == 1
    }
}

/**
 * 各家 Chat Completions 的推理开关协议不同。按 host 分流；未知 host 走 OpenAI 风格兜底。
 * 只面向较新模型（GPT-5.1+ / 近年国内混合思考模型），不再为更老的 effort 枚举做降级。
 */
private fun kotlinx.serialization.json.JsonObjectBuilder.applyChatCompletionsReasoning(
    host: String,
    modelId: String,
    level: ReasoningLevel,
) {
    when (classifyCompletionsReasoningHost(host)) {
        CompletionsReasoningHost.OPENROUTER -> {
            // https://openrouter.ai/docs/use-cases/reasoning-tokens
            put("reasoning", buildJsonObject {
                when (level) {
                    ReasoningLevel.OFF -> put("effort", "none")
                    ReasoningLevel.AUTO -> put("enabled", true)
                    else -> put("effort", level.effort)
                }
            })
        }

        CompletionsReasoningHost.DASHSCOPE -> {
            // 阿里云百炼 / 国际站
            put("enable_thinking", level.isEnabled)
            if (level.isEnabled && level != ReasoningLevel.AUTO) {
                put("thinking_budget", level.budgetTokens)
            }
        }

        CompletionsReasoningHost.VOLCENGINE -> {
            // 火山方舟：thinking.type = enabled | disabled | auto
            put("thinking", buildJsonObject {
                put(
                    "type",
                    when (level) {
                        ReasoningLevel.OFF -> "disabled"
                        ReasoningLevel.AUTO -> "auto"
                        else -> "enabled"
                    }
                )
            })
        }

        CompletionsReasoningHost.MISTRAL -> {
            // 不支持推理强度控制
        }

        CompletionsReasoningHost.INTERN -> {
            put("thinking_mode", level.isEnabled)
        }

        CompletionsReasoningHost.SILICONFLOW -> {
            // https://docs.siliconflow.cn/cn/userguide/capabilities/reasoning
            if (modelId in SILICONFLOW_THINKING_MODELS) {
                put("enable_thinking", level.isEnabled)
            }
        }

        CompletionsReasoningHost.ENABLE_THINKING -> {
            put("enable_thinking", level.isEnabled)
        }

        CompletionsReasoningHost.THINKING_TYPE -> {
            // DeepSeek 官方 / 智谱 / Kimi 等：thinking.type 开关
            put("thinking", buildJsonObject {
                put("type", if (!level.isEnabled) "disabled" else "enabled")
            })
            if (host.contains("deepseek", ignoreCase = true) &&
                level.isEnabled &&
                level != ReasoningLevel.AUTO
            ) {
                // DeepSeek 官方 effort 只有 high / max；其余档位映射到 high
                put("reasoning_effort", level.deepseekEffort())
            }
        }

        CompletionsReasoningHost.MINIMAX -> {
            // MiniMax M3：disabled / adaptive；未知是否支持 enabled
            put("thinking", buildJsonObject {
                put("type", if (!level.isEnabled) "disabled" else "adaptive")
            })
        }

        CompletionsReasoningHost.NVIDIA -> {
            if ("deepseek-v4" in modelId.lowercase()) {
                if (level != ReasoningLevel.AUTO) {
                    put(
                        "reasoning_effort",
                        when (level) {
                            ReasoningLevel.OFF -> "none"
                            ReasoningLevel.XHIGH, ReasoningLevel.MAX -> "max"
                            else -> "high"
                        }
                    )
                }
            } else {
                putOpenAiStyleReasoningEffort(level)
            }
        }

        CompletionsReasoningHost.OPENAI_COMPAT -> {
            // OpenAI 官方、OpenCode，以及未命中 host 的通用兜底
            putOpenAiStyleReasoningEffort(level)
        }
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putOpenAiStyleReasoningEffort(
    level: ReasoningLevel,
) {
    // 较新模型支持 none；AUTO 不传，让服务端默认
    if (level != ReasoningLevel.AUTO) {
        put("reasoning_effort", level.effort)
    }
}

private fun ReasoningLevel.deepseekEffort(): String = when (this) {
    ReasoningLevel.XHIGH, ReasoningLevel.MAX -> "max"
    else -> "high"
}

private enum class CompletionsReasoningHost {
    OPENROUTER,
    DASHSCOPE,
    VOLCENGINE,
    MISTRAL,
    INTERN,
    SILICONFLOW,
    ENABLE_THINKING,
    THINKING_TYPE,
    MINIMAX,
    NVIDIA,
    OPENAI_COMPAT,
}

private fun classifyCompletionsReasoningHost(host: String): CompletionsReasoningHost {
    val h = host.lowercase()
    return when {
        h.contains("openrouter.ai") -> CompletionsReasoningHost.OPENROUTER
        h.contains("dashscope") -> CompletionsReasoningHost.DASHSCOPE
        h.contains("volces.com") ||
            h.contains("bytepluses.com") ||
            h.contains("volcengine") ||
            h.contains("bytedance.net") -> CompletionsReasoningHost.VOLCENGINE
        h.contains("mistral.ai") -> CompletionsReasoningHost.MISTRAL
        h.contains("intern-ai") -> CompletionsReasoningHost.INTERN
        h.contains("siliconflow") -> CompletionsReasoningHost.SILICONFLOW
        h.contains("aiping.cn") -> CompletionsReasoningHost.ENABLE_THINKING
        h.contains("bigmodel.cn") ||
            h.contains("zhipuai") -> CompletionsReasoningHost.THINKING_TYPE
        h.contains("moonshot") ||
            h.contains("kimi.com") ||
            h.contains("kimi.ai") -> CompletionsReasoningHost.THINKING_TYPE
        h.contains("deepseek.com") -> CompletionsReasoningHost.THINKING_TYPE
        h.contains("minimax") -> CompletionsReasoningHost.MINIMAX
        h.contains("nvidia.com") -> CompletionsReasoningHost.NVIDIA
        h.contains("opencode.ai") -> CompletionsReasoningHost.OPENAI_COMPAT
        else -> CompletionsReasoningHost.OPENAI_COMPAT
    }
}

private val SILICONFLOW_THINKING_MODELS = setOf(
    "Pro/moonshotai/Kimi-K2.5",
    "Pro/zai-org/GLM-5",
    "Pro/zai-org/GLM-5.1",
    "Pro/zai-org/GLM-4.7",
    "deepseek-ai/DeepSeek-V3.2",
    "Pro/deepseek-ai/DeepSeek-V3.2",
    "Qwen/Qwen3.5-397B-A17B",
    "Qwen/Qwen3.5-122B-A10B",
    "Qwen/Qwen3.5-35B-A3B",
    "Qwen/Qwen3.5-27B",
    "Qwen/Qwen3.5-9B",
    "Qwen/Qwen3.5-4B",
    "zai-org/GLM-4.6",
    "Qwen/Qwen3-8B",
    "Qwen/Qwen3-14B",
    "Qwen/Qwen3-32B",
    "Qwen/Qwen3-30B-A3B",
    "tencent/Hunyuan-A13B-Instruct",
    "zai-org/GLM-4.5V",
    "deepseek-ai/DeepSeek-V3.1-Terminus",
    "Pro/deepseek-ai/DeepSeek-V3.1-Terminus",
    "deepseek-ai/DeepSeek-V4-Flash",
    "Pro/deepseek-ai/DeepSeek-V4-Flash",
    "deepseek-ai/DeepSeek-V4-Pro",
    "Pro/deepseek-ai/DeepSeek-V4-Pro",
)
