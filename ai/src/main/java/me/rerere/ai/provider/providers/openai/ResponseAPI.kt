package me.rerere.ai.provider.providers.openai

import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
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
import me.rerere.ai.BuildConfig
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.parametersOrEmptyObject
import me.rerere.ai.provider.BuiltInTools
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
import me.rerere.ai.ui.isTransientImageReference
import me.rerere.ai.util.configureClientWithProxy
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.encodeBase64
import me.rerere.ai.util.HttpStatusException
import me.rerere.ai.util.isLikelySsePayload
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.parseErrorDetail
import me.rerere.ai.util.RawResponseException
import me.rerere.ai.util.SSEEventSource
import me.rerere.ai.util.stringSafe
import me.rerere.ai.util.toHeaders
import me.rerere.common.http.await
import me.rerere.common.http.jsonObjectOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import kotlin.time.Clock

private const val TAG = "ResponseAPI"
private const val REASONING_ID_METADATA = "openai_reasoning_id"
private const val REASONING_ENCRYPTED_METADATA = "openai_reasoning_encrypted_content"

internal class ResponseStreamState {
    val functionCallIds = mutableMapOf<String, String>()
    val completedFunctionCalls = mutableSetOf<String>()
    val textItemsWithDeltas = mutableSetOf<String>()
    val reasoningItemsWithDeltas = mutableSetOf<String>()
    val pendingWebSearchAnnotations = mutableListOf<UIMessageAnnotation.UrlCitation>()
}

class ResponseAPI(
    private val client: OkHttpClient,
    private val keyRoulette: KeyRoulette,
) : OpenAIImpl {
    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): MessageChunk {
        val requestBody = buildRequestBody(
            messages = messages,
            params = params,
            stream = false,
        )
        val requestBodyJson = json.encodeToString(requestBody)
        params.onRequestBody?.invoke(requestBodyJson)
        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/responses")
            .headers(params.customHeaders.toHeaders())
            .post(requestBodyJson.toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer ${keyRoulette.next(providerSetting)}")
            .addHeader("Content-Type", "application/json")
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        if (BuildConfig.DEBUG) Log.i(TAG, "generateText: $requestBodyJson")

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
        if (BuildConfig.DEBUG) Log.i(TAG, "generateText: $bodyStr")
        val bodyJson = runCatching {
            json.parseToJsonElement(bodyStr).jsonObject
        }.getOrElse { throwable ->
            throw RawResponseException(
                message = "Failed to parse response body: ${throwable.message}",
                rawResponse = bodyStr,
                cause = throwable,
            )
        }

        return runCatching { parseResponseOutput(bodyJson).copy(rawResponse = bodyStr) }
            .getOrElse { throwable ->
                throw RawResponseException(
                    message = "Failed to parse response output: ${throwable.message}",
                    rawResponse = bodyStr,
                    cause = throwable,
                )
            }
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<MessageChunk> = callbackFlow {
        val requestBody = buildRequestBody(
            messages = messages,
            params = params,
            stream = true,
        )
        val requestBodyJson = json.encodeToString(requestBody)
        params.onRequestBody?.invoke(requestBodyJson)
        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/responses")
            .headers(params.customHeaders.toHeaders())
            .post(requestBodyJson.toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer ${keyRoulette.next(providerSetting)}")
            .addHeader("Content-Type", "application/json")
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        // 请求体可能含 base64 附件（数 MB），发布版不写入日志
        if (BuildConfig.DEBUG) Log.i(TAG, "streamText: $requestBodyJson")
        val rawEventBuffer = StringBuilder()
        val streamState = ResponseStreamState()

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                if (BuildConfig.DEBUG) Log.d(TAG, "onEvent: $id/$type $data")
                if (rawEventBuffer.isNotEmpty()) rawEventBuffer.append("\n")
                rawEventBuffer.append(data)
                val eventData = data.trim().removePrefix("data:").trim()
                if (eventData == "[DONE]") {
                    close()
                    return
                }
                // Some Codex streams use an empty event as a keepalive. It has no model
                // payload and must not be treated as malformed JSON.
                if (eventData.isEmpty()) return
                val json = runCatching { json.parseToJsonElement(eventData).jsonObject }
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
                val streamError = extractStreamError(json)
                if (streamError != null) {
                    close(
                        RawResponseException(
                            message = streamError.message ?: "OpenAI response stream failed",
                            rawResponse = rawEventBuffer.toString(),
                            cause = streamError,
                        )
                    )
                    return
                }
                val chunk = runCatching { parseResponseDelta(json, streamState) }
                    .getOrElse { throwable ->
                        close(
                            RawResponseException(
                                message = "Failed to parse stream delta: ${throwable.message}",
                                rawResponse = rawEventBuffer.toString(),
                                cause = throwable,
                            )
                        )
                        return
                }
                if (chunk != null) {
                    trySend(chunk.copy(rawResponse = eventData))
                }
                if (type == "response.completed") {
                    close()
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                var exception = t
                var rawFailureResponse = ""

                t?.printStackTrace()
                if (BuildConfig.DEBUG) println("[onFailure] 发生错误: ${t?.javaClass?.name} ${t?.message} / $response")

                val bodyRaw = response?.body?.stringSafe()
                rawFailureResponse = bodyRaw.orEmpty()
                try {
                    if (!bodyRaw.isNullOrBlank()) {
                        if (bodyRaw.isLikelySsePayload()) {
                            Log.w(TAG, "onFailure: skipped JSON error parse for SSE response")
                        } else {
                            val bodyElement = Json.parseToJsonElement(bodyRaw)
                            if (BuildConfig.DEBUG) println(bodyElement)
                            exception = bodyElement.parseErrorDetail()
                            if (BuildConfig.DEBUG) Log.i(TAG, "onFailure: $exception")
                        }
                    }
                } catch (e: Throwable) {
                    if (BuildConfig.DEBUG) Log.w(TAG, "onFailure: failed to parse from $bodyRaw", e)
                    e.printStackTrace()
                } finally {
                    val exceptionWithStatus = response?.let { resp ->
                        val contentType = resp.header("Content-Type") ?: "missing"
                        val requestId = resp.header("x-request-id")
                            ?: resp.header("x-openai-request-id")
                        val detail = buildString {
                            append(exception?.message ?: "HTTP ${resp.code}")
                            if (contentType == "missing" || exception?.message?.contains(
                                    "Invalid content-type:",
                                    ignoreCase = true,
                                ) == true
                            ) {
                                append(" (HTTP ${resp.code}, Content-Type: $contentType")
                                requestId?.let { append(", request: $it") }
                                append(")")
                            }
                        }
                        HttpStatusException(
                            statusCode = resp.code,
                            message = detail,
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

        val eventSource =
            SSEEventSource.factory(client.configureClientWithProxy(providerSetting.proxy))
                .newEventSource(request, listener)

        awaitClose {
            println("[awaitClose] 关闭eventSource ")
            eventSource.cancel()
        }
        // SSE 回调线程用 trySend 推送且不检查结果，默认 64 容量在下游繁忙时会静默丢块，放开为无限缓冲
    }.buffer(Channel.UNLIMITED)

    private fun buildRequestBody(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        stream: Boolean
    ): JsonObject {
        val hasCodexImageGeneration = params.model.tools.contains(BuiltInTools.CodexImageGeneration)
        val systemInstructions = messages
            .filter { it.role == MessageRole.SYSTEM }
            .flatMap { message -> message.parts.filterIsInstance<UIMessagePart.Text>() }
            .joinToString("\n") { it.text }
        val instructions = buildList {
            systemInstructions.takeIf { it.isNotBlank() }?.let(::add)
            if (hasCodexImageGeneration) {
                // Codex's hosted image tool is selected by the host model. The
                // backend does not accept forcing it through tool_choice.
                add(
                    "When the user asks to create, draw, render, edit, or generate an image, " +
                        "use the image_generation tool."
                )
            }
        }.joinToString("\n\n")
        return buildJsonObject {
            put("model", params.model.modelId)
            put("stream", stream)

            if (isModelAllowTemperature(params.model)) {
                if (params.temperature != null) put("temperature", params.temperature)
                if (params.topP != null) put("top_p", params.topP)
            }
            if (params.maxTokens != null) put("max_output_tokens", params.maxTokens)

            // system instructions
            if (instructions.isNotBlank()) {
                put("instructions", instructions)
            }

            // messages
            put("input", buildMessages(messages))

            // reasoning
            if (params.model.abilities.contains(ModelAbility.REASONING)) {
                val level = params.reasoningLevel
                put("reasoning", buildJsonObject {
                    put("summary", "auto")
                    if (level != ReasoningLevel.AUTO) {
                        put("effort", level.effort)
                    }
                    // Pro 模式: reasoning.mode=pro, 与 effort 同级, 独立判断
                    if (params.proMode) {
                        put("mode", "pro")
                    }
                })
            }

            // 快速模式: service_tier=fast, 顶层参数
            if (params.fastMode) {
                put("service_tier", "fast")
            }

            // tools
            val hasCodexWebSearch = params.model.tools.contains(BuiltInTools.CodexWebSearch)
            val functionTools = params.tools
            val hasGrokWebSearch = params.model.tools.contains(BuiltInTools.GrokWebSearch)
            val hasGrokXSearch = params.model.tools.contains(BuiltInTools.GrokXSearch)
            val hasFunctionTools = params.model.abilities.contains(ModelAbility.TOOL) && functionTools.isNotEmpty()

            if (
                hasFunctionTools || hasCodexWebSearch || hasCodexImageGeneration ||
                hasGrokWebSearch || hasGrokXSearch
            ) {
                putJsonArray("tools") {
                    if (hasCodexWebSearch) {
                        // The supported Responses built-in search has no cached/indexed mode;
                        // requests are live by design.
                        add(buildJsonObject { put("type", "web_search") })
                    }
                    if (hasCodexImageGeneration) {
                        add(buildJsonObject {
                            put("type", "image_generation")
                            put("model", "gpt-image-2")
                            put("size", "1024x1024")
                            put("quality", "medium")
                            put("output_format", "png")
                            put("background", "opaque")
                            put("partial_images", 0)
                        })
                    }
                    if (hasGrokWebSearch) {
                        add(buildJsonObject { put("type", "web_search") })
                    }
                    if (hasGrokXSearch) {
                        add(buildJsonObject { put("type", "x_search") })
                    }
                    if (hasFunctionTools) {
                        functionTools.forEach { tool ->
                            add(buildJsonObject {
                                put("type", "function")
                                put("name", tool.name)
                                put("description", tool.description)
                                put(
                                    "parameters",
                                    json.encodeToJsonElement(
                                        tool.parametersOrEmptyObject()
                                    )
                                )
                            })
                        }
                    }
                }
            }
        }.mergeCustomBody(params.customBody)
    }

    private fun buildMessages(messages: List<UIMessage>) = buildJsonArray {
        val latestUserMessageIndex = messages.indexOfLast { it.role == MessageRole.USER }
        messages
            .filter { message ->
                message.role != MessageRole.SYSTEM && (
                    message.isValidToUpload() || message.parts.any { part ->
                        part is UIMessagePart.Reasoning && part.metadata
                            ?.get(REASONING_ENCRYPTED_METADATA)
                            ?.jsonPrimitive
                            ?.contentOrNull
                            ?.isNotBlank() == true
                    }
                )
            }
            .forEachIndexed { index, message ->
                if (message.role == MessageRole.TOOL) {
                    message.getToolResults().forEach { result ->
                        add(buildJsonObject {
                            put("type", "function_call_output")
                            put("call_id", result.toolCallId)
                            put("output", json.encodeToString(result.content))
                        })
                    }
                    return@forEachIndexed
                }
                message.parts.filterIsInstance<UIMessagePart.Reasoning>().forEach { reasoning ->
                    val metadata = reasoning.metadata ?: return@forEach
                    val encrypted = metadata[REASONING_ENCRYPTED_METADATA]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.takeIf { it.isNotBlank() }
                        ?: return@forEach
                    add(buildJsonObject {
                        put("type", "reasoning")
                        metadata[REASONING_ID_METADATA]
                            ?.jsonPrimitive
                            ?.contentOrNull
                            ?.takeIf { it.isNotBlank() }
                            ?.let { put("id", it) }
                        put("encrypted_content", encrypted)
                        putJsonArray("summary") { }
                    })
                }
                val contentParts = message.parts.filter {
                    it is UIMessagePart.Text ||
                        (it is UIMessagePart.Image && message.role != MessageRole.ASSISTANT &&
                            (!it.isTransientImageReference() || index == latestUserMessageIndex)) ||
                        it is UIMessagePart.Audio
                }
                if (contentParts.isNotEmpty()) add(buildJsonObject {
                    // role
                    put("role", JsonPrimitive(message.role.name.lowercase()))

                    // content
                    if (contentParts.isOnlyTextPart()) {
                        // 如果只是纯文本，直接赋值给content
                        put(
                            "content",
                            contentParts.filterIsInstance<UIMessagePart.Text>().first().text
                        )
                    } else {
                        // 否则，使用parts构建
                        putJsonArray("content") {
                            contentParts.forEach { part ->
                                when (part) {
                                    is UIMessagePart.Text -> {
                                        add(buildJsonObject {
                                            put(
                                                "type",
                                                if (message.role == MessageRole.USER) "input_text" else "output_text"
                                            )
                                            put("text", part.text)
                                        })
                                    }

                                    is UIMessagePart.Image -> {
                                        add(buildJsonObject {
                                            part.encodeBase64().onSuccess {
                                                put("type", "input_image")
                                                put("image_url", it)
                                            }.onFailure {
                                                it.printStackTrace()
                                                if (BuildConfig.DEBUG) println("encode image failed: ${part.url}")

                                                put("type", "input_text")
                                                put(
                                                    "text",
                                                    "Error: Failed to encode image to base64"
                                                )
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
                                                if (BuildConfig.DEBUG) println("encode audio failed: ${part.url}")

                                                put("type", "input_text")
                                                put(
                                                    "text",
                                                    "Error: Failed to encode audio to base64"
                                                )
                                            }
                                        })
                                    }

                                    else -> {
                                        // part 可能携带 base64 附件，发布版不打印内容
                                        if (BuildConfig.DEBUG) Log.w(
                                            TAG,
                                            "buildMessages: message part not supported: $part"
                                        )
                                        // DO NOTHING
                                    }
                                }
                            }
                        }
                    }
                })
                // tool_calls
                message.getToolCalls()
                    .takeIf { it.isNotEmpty() }
                    ?.let { toolCalls ->
                        toolCalls.forEach { toolCall ->
                            add(buildJsonObject {
                                put("type", "function_call")
                                put("call_id", toolCall.toolCallId)
                                put("name", toolCall.toolName)
                                put("arguments", toolCall.arguments)
                            })
                        }
                    }
            }
    }

    internal fun extractStreamError(jsonObject: JsonObject): Exception? {
        val chunkType = jsonObject["type"]?.jsonPrimitive?.contentOrNull?.trim()
        if (
            chunkType != "response.failed" &&
            chunkType != "response.incomplete" &&
            chunkType != "error"
        ) {
            return null
        }

        jsonObject["error"]?.let { error ->
            return error.parseErrorDetail()
        }

        jsonObject["response"]?.jsonObjectOrNull?.get("error")?.let { error ->
            return error.parseErrorDetail()
        }

        val fallbackMessage = buildList {
            add(
                jsonObject["response"]?.jsonObjectOrNull
                    ?.get("incomplete_details")
                    ?.jsonObjectOrNull
                    ?.get("reason")
                    ?.jsonPrimitive
                    ?.contentOrNull
            )
            add(
                jsonObject["response"]?.jsonObjectOrNull
                    ?.get("status")
                    ?.jsonPrimitive
                    ?.contentOrNull
            )
        }.firstOrNull { value -> !value.isNullOrBlank() } ?: "OpenAI response stream failed"

        return JsonPrimitive(fallbackMessage).parseErrorDetail()
    }

    private fun reasoningMetadata(item: JsonObject): JsonObject? {
        val id = item["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val encrypted = item["encrypted_content"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }
        if (id == null && encrypted == null) return null
        return buildJsonObject {
            id?.let { put(REASONING_ID_METADATA, it) }
            encrypted?.let { put(REASONING_ENCRYPTED_METADATA, it) }
        }
    }

    internal fun parseResponseDelta(
        jsonObject: JsonObject,
        state: ResponseStreamState,
    ): MessageChunk? {
        val chunkType = jsonObject["type"]?.jsonPrimitive?.content ?: error("chunk type not found")

        when (chunkType) {
            "response.output_text.delta" -> {
                jsonObject["item_id"]?.jsonPrimitive?.contentOrNull?.let(state.textItemsWithDeltas::add)
                return MessageChunk(
                    id = jsonObject["item_id"]?.jsonPrimitive?.contentOrNull ?: "",
                    model = "",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = UIMessage(
                                role = MessageRole.ASSISTANT,
                                parts = listOf(
                                    UIMessagePart.Text(
                                        jsonObject["delta"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                    )
                                ),
                                annotations = parseUrlCitations(jsonObject["annotations"] as? JsonArray),
                            ),
                            message = null,
                            finishReason = null
                        )
                    )
                )
            }

            "response.output_text.annotation.added" -> {
                val annotation = parseUrlCitation(jsonObject["annotation"] as? JsonObject) ?: return null
                return MessageChunk(
                    id = jsonObject["item_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    model = "",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = UIMessage(
                                role = MessageRole.ASSISTANT,
                                parts = emptyList(),
                                annotations = listOf(annotation),
                            ),
                            message = null,
                            finishReason = null,
                        )
                    ),
                )
            }

            "response.reasoning_summary_text.delta" -> {
                jsonObject["item_id"]?.jsonPrimitive?.contentOrNull
                    ?.let(state.reasoningItemsWithDeltas::add)
                return MessageChunk(
                    id = jsonObject["item_id"]?.jsonPrimitive?.contentOrNull ?: "",
                    model = "",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = UIMessage(
                                role = MessageRole.ASSISTANT,
                                parts = listOf(
                                    UIMessagePart.Reasoning(
                                        reasoning = jsonObject["delta"]?.jsonPrimitive?.contentOrNull
                                            ?: "",
                                        createdAt = Clock.System.now(),
                                        finishedAt = null
                                    )
                                )
                            ),
                            message = null,
                            finishReason = null
                        )
                    )
                )
            }

            "response.output_item.added" -> {
                val item = jsonObject["item"]?.jsonObject ?: error("chunk item not found")
                val type = item["type"]?.jsonPrimitive?.content ?: error("chunk type not found")
                val id = item["id"]?.jsonPrimitive?.content ?: error("chunk id not found")
                if (type == "function_call") {
                    val callId = item["call_id"]?.jsonPrimitive?.contentOrNull
                        ?: error("function call_id not found")
                    state.functionCallIds[id] = callId
                    return MessageChunk(
                        id = callId,
                        model = "",
                        choices = listOf(
                            UIMessageChoice(
                                index = 0,
                                message = null,
                                delta = UIMessage(
                                    role = MessageRole.ASSISTANT,
                                    parts = listOf(
                                        UIMessagePart.ToolCall(
                                            toolCallId = callId,
                                            toolName = item["name"]?.jsonPrimitive?.content.orEmpty(),
                                            arguments = item["arguments"]?.jsonPrimitive?.content
                                                ?: ""
                                        )
                                    )
                                ),
                                finishReason = null
                            )
                        )
                    )
                } else if (type == "reasoning") {
                    return MessageChunk(
                        id = id,
                        model = "",
                        choices = listOf(
                            UIMessageChoice(
                                index = 0,
                                message = null,
                                delta = UIMessage(
                                    role = MessageRole.ASSISTANT,
                                    parts = listOf(
                                        UIMessagePart.Reasoning(
                                            reasoning = "",
                                            createdAt = Clock.System.now(),
                                            finishedAt = null,
                                            metadata = reasoningMetadata(item),
                                        )
                                    )
                                ),
                                finishReason = null,
                            )
                        )
                    )
                }
            }

            "response.output_item.done" -> {
                val item = jsonObject["item"]?.jsonObject ?: error("chunk item not found")
                return parseCompletedOutputItem(item, state)
            }

            "response.function_call_arguments.done" -> {
                val itemId = jsonObject["item_id"]?.jsonPrimitive?.content ?: error("item_id not found")
                val toolCallId = state.functionCallIds.remove(itemId)
                    ?: jsonObject["call_id"]?.jsonPrimitive?.contentOrNull
                    ?: error("call_id not found for $itemId")
                state.completedFunctionCalls += toolCallId
                val arguments =
                    jsonObject["arguments"]?.jsonPrimitive?.content ?: error("arguments not found")
                return MessageChunk(
                    id = toolCallId,
                    model = "",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = UIMessage(
                                role = MessageRole.ASSISTANT,
                                parts = listOf(
                                    UIMessagePart.ToolCall(
                                        toolCallId = toolCallId,
                                        toolName = "",
                                        arguments = arguments,
                                    )
                                )
                            ),
                            message = null,
                            finishReason = null
                        )
                    ),
                )
            }

            "response.completed" -> {
                val responseObject = jsonObject["response"]?.jsonObject
                val finishReason = parseResponseFinishReason(responseObject)
                return MessageChunk(
                    id = jsonObject["item_id"]?.jsonPrimitive?.contentOrNull ?: "",
                    model = "",
                    choices = emptyList(),
                    usage = parseTokenUsage(responseObject?.get("usage")?.jsonObject),
                    finishReasons = finishReason
                        ?.takeIf { reason -> reason.isNotBlank() && reason != "unknown" }
                        ?.let { setOf(it) }
                        ?: emptySet(),
                )
            }
        }

        return null
    }

    private fun parseCompletedOutputItem(
        item: JsonObject,
        state: ResponseStreamState,
    ): MessageChunk? {
        return when (item["type"]?.jsonPrimitive?.contentOrNull) {
            "function_call" -> {
                val callId = item["call_id"]?.jsonPrimitive?.contentOrNull
                    ?: error("function call_id not found")
                if (!state.completedFunctionCalls.add(callId)) return null
                MessageChunk(
                    id = callId,
                    model = "",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            message = null,
                            delta = UIMessage(
                                role = MessageRole.ASSISTANT,
                                parts = listOf(
                                    UIMessagePart.ToolCall(
                                        toolCallId = callId,
                                        toolName = item["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                        arguments = item["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                    )
                                ),
                            ),
                            finishReason = null,
                        )
                    ),
                )
            }

            "reasoning" -> MessageChunk(
                id = item["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                model = "",
                choices = listOf(
                    UIMessageChoice(
                        index = 0,
                        message = null,
                        delta = UIMessage(
                            role = MessageRole.ASSISTANT,
                            parts = listOf(
                                UIMessagePart.Reasoning(
                                    reasoning = if (
                                        item["id"]?.jsonPrimitive?.contentOrNull in
                                        state.reasoningItemsWithDeltas
                                    ) "" else reasoningSummary(item),
                                    createdAt = Clock.System.now(),
                                    finishedAt = null,
                                    metadata = reasoningMetadata(item),
                                )
                            ),
                        ),
                        finishReason = null,
                    )
                ),
            )

            "web_search_call" -> {
                state.pendingWebSearchAnnotations += parseWebSearchSources(item["action"] as? JsonObject)
                null
            }

            "image_generation_call" -> imageGenerationChunk(item)

            "message" -> {
                val itemId = item["id"]?.jsonPrimitive?.contentOrNull
                val annotations = (
                    (item["content"] as? JsonArray)
                        .orEmpty()
                        .flatMap { content ->
                            parseUrlCitations((content as? JsonObject)?.get("annotations") as? JsonArray)
                        }
                    + state.pendingWebSearchAnnotations
                ).distinct()
                state.pendingWebSearchAnnotations.clear()
                val hasStreamedText = itemId != null && itemId in state.textItemsWithDeltas
                val text = (item["content"] as? JsonArray)
                    .orEmpty()
                    .mapNotNull { content ->
                        val part = content as? JsonObject ?: return@mapNotNull null
                        if (part["type"]?.jsonPrimitive?.contentOrNull != "output_text") {
                            return@mapNotNull null
                        }
                        part["text"]?.jsonPrimitive?.contentOrNull
                    }
                    .joinToString("")
                if (hasStreamedText && annotations.isEmpty()) return null
                if (!hasStreamedText && text.isEmpty() && annotations.isEmpty()) return null
                MessageChunk(
                    id = itemId.orEmpty(),
                    model = "",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            message = null,
                            delta = UIMessage(
                                role = MessageRole.ASSISTANT,
                                parts = if (hasStreamedText) emptyList() else listOf(UIMessagePart.Text(text)),
                                annotations = annotations,
                            ),
                            finishReason = null,
                        )
                    ),
                )
            }

            else -> null
        }
    }

    private fun imageGenerationChunk(item: JsonObject): MessageChunk? {
        val image = parseGeneratedImage(item) ?: return null
        return MessageChunk(
            id = item["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            model = "",
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    message = null,
                    delta = UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(image),
                    ),
                    finishReason = null,
                )
            ),
        )
    }

    /**
     * Responses returns an image-generation result as base64 in current Codex
     * streams. Accept the equivalent URL fields too so the message renderer can
     * handle compatible Responses servers without a provider-specific branch.
     */
    private fun parseGeneratedImage(item: JsonObject): UIMessagePart.Image? {
        val imageUrlObject = item["image_url"] as? JsonObject
        val imageData = item["result"]?.jsonPrimitive?.contentOrNull
            ?: item["b64_json"]?.jsonPrimitive?.contentOrNull
            ?: item["image_base64"]?.jsonPrimitive?.contentOrNull
            ?: item["image_url"]?.jsonPrimitive?.contentOrNull
            ?: imageUrlObject?.get("url")?.jsonPrimitive?.contentOrNull
            ?: return null
        val outputFormat = item["output_format"]?.jsonPrimitive?.contentOrNull
            ?.lowercase()
            ?.takeIf { it in setOf("png", "jpeg", "webp") }
            ?: "png"
        val imageUrl = when {
            imageData.startsWith("data:") || imageData.startsWith("http:") ||
                imageData.startsWith("https:") -> imageData
            else -> "data:image/$outputFormat;base64,$imageData"
        }
        return UIMessagePart.Image(imageUrl)
    }

    private fun reasoningSummary(item: JsonObject): String =
        (item["summary"] as? JsonArray)
            .orEmpty()
            .mapNotNull { summary ->
                val part = summary as? JsonObject ?: return@mapNotNull null
                if (part["type"]?.jsonPrimitive?.contentOrNull != "summary_text") {
                    return@mapNotNull null
                }
                part["text"]?.jsonPrimitive?.contentOrNull
            }
            .joinToString("\n")

    private fun parseResponseOutput(jsonObject: JsonObject): MessageChunk {
        if (BuildConfig.DEBUG) println(jsonObject)
        val outputs = jsonObject["output"]?.jsonArray ?: error("output not found")
        val parts = arrayListOf<UIMessagePart>()
        val annotations = arrayListOf<UIMessageAnnotation>()

        outputs.forEach { outputItem ->
            val output = outputItem.jsonObject
            val type = output["type"]?.jsonPrimitive?.content ?: error("output type not found")
            when (type) {
                "reasoning" -> {
                    parts.add(
                        UIMessagePart.Reasoning(
                            reasoning = reasoningSummary(output),
                            createdAt = Clock.System.now(),
                            finishedAt = Clock.System.now(),
                            metadata = reasoningMetadata(output),
                        )
                    )
                }

                "function_call" -> {
                    val callId = output["call_id"]?.jsonPrimitive?.content ?: error("call_id not found")
                    val name = output["name"]?.jsonPrimitive?.content ?: error("name not found")
                    val arguments =
                        output["arguments"]?.jsonPrimitive?.content ?: error("arguments not found")
                    parts.add(
                        UIMessagePart.ToolCall(
                            toolCallId = callId,
                            toolName = name,
                            arguments = arguments
                        )
                    )
                }

                "web_search_call" -> {
                    annotations += parseWebSearchSources(output["action"] as? JsonObject)
                }

                "image_generation_call" -> {
                    parseGeneratedImage(output)?.let(parts::add)
                }

                "message" -> {
                    val content = output["content"]?.jsonArray ?: error("content not found")
                    content.map { it.jsonObject }.forEach { part ->
                        val partType = part["type"]?.jsonPrimitive?.content ?: error("part type not found")
                        when (partType) {
                            "output_text" -> {
                                val text = part["text"]?.jsonPrimitive?.content ?: error("text not found")
                                parts.add(
                                    UIMessagePart.Text(
                                        text = text
                                    )
                                )
                                annotations += parseUrlCitations(part["annotations"] as? JsonArray)
                            }

                            else -> error("unknown part type $partType")
                        }
                    }
                }
            }
        }

        val finishReason = parseResponseFinishReason(jsonObject)
        return MessageChunk(
            id = jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: "",
            model = jsonObject["model"]?.jsonPrimitive?.contentOrNull ?: "",
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    message = UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = parts,
                        annotations = annotations.distinct(),
                    ),
                    finishReason = finishReason,
                    delta = null
                )
            ),
            usage = parseTokenUsage(jsonObject["usage"]?.jsonObject),
            finishReasons = finishReason
                ?.takeIf { reason -> reason.isNotBlank() && reason != "unknown" }
                ?.let { setOf(it) }
                ?: emptySet(),
        )
    }

    private fun parseUrlCitations(annotations: JsonArray?): List<UIMessageAnnotation> {
        return annotations.orEmpty().mapNotNull { element ->
            parseUrlCitation(element as? JsonObject)
        }
    }

    private fun parseUrlCitation(annotation: JsonObject?): UIMessageAnnotation.UrlCitation? {
        annotation ?: return null
        if (annotation["type"]?.jsonPrimitive?.contentOrNull != "url_citation") return null
        val detail = annotation["url_citation"] as? JsonObject ?: annotation
        val url = detail["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (url.isBlank()) return null
        val title = detail["title"]?.jsonPrimitive?.contentOrNull?.trim().takeUnless { it.isNullOrBlank() }
            ?: url
        return UIMessageAnnotation.UrlCitation(title = title, url = url)
    }

    private fun parseWebSearchSources(action: JsonObject?): List<UIMessageAnnotation.UrlCitation> {
        val sources = action?.get("sources") as? JsonArray ?: return emptyList()
        return sources.mapNotNull { source ->
            val objectSource = source as? JsonObject ?: return@mapNotNull null
            val url = objectSource["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (url.isBlank()) return@mapNotNull null
            val title = objectSource["title"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: url
            UIMessageAnnotation.UrlCitation(title = title, url = url)
        }
    }

    private fun parseResponseFinishReason(response: JsonObject?): String? {
        if (response == null) return null
        val incompleteReason = response["incomplete_details"]
            ?.jsonObjectOrNull
            ?.get("reason")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
        if (!incompleteReason.isNullOrBlank()) {
            return incompleteReason
        }

        return when (response["status"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()) {
            "completed" -> "stop"
            "incomplete" -> "incomplete"
            "failed" -> "error"
            else -> null
        }
    }

    private fun parseTokenUsage(jsonObject: JsonObject?): TokenUsage? {
        if (jsonObject == null) return null
        return TokenUsage(
            promptTokens = jsonObject["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            completionTokens = jsonObject["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            totalTokens = jsonObject["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            cachedTokens = jsonObject["input_tokens_details"]?.jsonObjectOrNull?.get("cached_tokens")?.jsonPrimitive?.intOrNull
                ?: 0
        )
    }
}

private fun isModelAllowTemperature(model: Model): Boolean {
    return !ModelRegistry.OPENAI_O_MODELS.match(model.modelId) && !ModelRegistry.GPT_5.match(model.modelId)
}

private fun List<UIMessagePart>.isOnlyTextPart(): Boolean {
    val gonnaSend = filter { it is UIMessagePart.Text || it is UIMessagePart.Image || it is UIMessagePart.Audio || it is UIMessagePart.Video }.size
    val texts = filter { it is UIMessagePart.Text }.size
    return gonnaSend == texts && texts == 1
}
