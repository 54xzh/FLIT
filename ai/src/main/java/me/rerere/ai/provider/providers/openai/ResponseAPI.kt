package me.rerere.ai.provider.providers.openai

import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
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
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
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
        Log.i(TAG, "generateText: $bodyStr")
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

        Log.i(TAG, "streamText: $requestBodyJson")
        val rawEventBuffer = StringBuilder()

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                Log.d(TAG, "onEvent: $id/$type $data")
                if (rawEventBuffer.isNotEmpty()) rawEventBuffer.append("\n")
                rawEventBuffer.append(data)
                val eventData = data.trim().removePrefix("data:").trim()
                if (eventData == "[DONE]") {
                    close()
                    return
                }
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
                val chunk = runCatching { parseResponseDelta(json) }
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

        val eventSource =
            SSEEventSource.factory(client.configureClientWithProxy(providerSetting.proxy))
                .newEventSource(request, listener)

        awaitClose {
            println("[awaitClose] 关闭eventSource ")
            eventSource.cancel()
        }
    }

    private fun buildRequestBody(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        stream: Boolean
    ): JsonObject {
        return buildJsonObject {
            put("model", params.model.modelId)
            put("stream", stream)

            if (isModelAllowTemperature(params.model)) {
                if (params.temperature != null) put("temperature", params.temperature)
                if (params.topP != null) put("top_p", params.topP)
            }
            if (params.maxTokens != null) put("max_output_tokens", params.maxTokens)

            // system instructions
            if (messages.any { it.role == MessageRole.SYSTEM }) {
                val parts = messages.first { it.role == MessageRole.SYSTEM }.parts
                put(
                    "instructions",
                    parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text })
            }

            // messages
            put("input", buildMessages(messages))

            // reasoning
            if (params.model.abilities.contains(ModelAbility.REASONING)) {
                val level = ReasoningLevel.fromBudgetTokens(params.thinkingBudget ?: 0)
                put("reasoning", buildJsonObject {
                    put("summary", "auto")
                    if (level != ReasoningLevel.AUTO) {
                        put("effort", level.effort)
                    }
                })
            }

            // tools
            val hasGrokWebSearch = params.model.tools.contains(BuiltInTools.GrokWebSearch)
            val hasGrokXSearch = params.model.tools.contains(BuiltInTools.GrokXSearch)
            val hasFunctionTools = params.model.abilities.contains(ModelAbility.TOOL) && params.tools.isNotEmpty()

            if (hasFunctionTools || hasGrokWebSearch || hasGrokXSearch) {
                putJsonArray("tools") {
                    if (hasGrokWebSearch) {
                        add(buildJsonObject { put("type", "web_search") })
                    }
                    if (hasGrokXSearch) {
                        add(buildJsonObject { put("type", "x_search") })
                    }
                    if (hasFunctionTools) {
                        params.tools.forEach { tool ->
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
        messages
            .filter {
                it.isValidToUpload() && it.role != MessageRole.SYSTEM
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
                                                put(
                                                    "type",
                                                    if (message.role == MessageRole.USER) "input_image" else "output_image"
                                                )
                                                put("image_url", it)
                                            }.onFailure {
                                                it.printStackTrace()
                                                println("encode image failed: ${part.url}")

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
                                                println("encode audio failed: ${part.url}")

                                                put("type", "input_text")
                                                put(
                                                    "text",
                                                    "Error: Failed to encode audio to base64"
                                                )
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

    private fun extractStreamError(jsonObject: JsonObject): Exception? {
        val chunkType = jsonObject["type"]?.jsonPrimitive?.contentOrNull?.trim()
        if (chunkType != "response.failed" && chunkType != "error") {
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

    private fun parseResponseDelta(jsonObject: JsonObject): MessageChunk? {
        val chunkType = jsonObject["type"]?.jsonPrimitive?.content ?: error("chunk type not found")

        when (chunkType) {
            "response.output_text.delta" -> {
                return MessageChunk(
                    id = jsonObject["item_id"]?.jsonPrimitive?.contentOrNull ?: "",
                    model = "",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = UIMessage.assistant(
                                jsonObject["delta"]?.jsonPrimitive?.contentOrNull ?: ""
                            ),
                            message = null,
                            finishReason = null
                        )
                    )
                )
            }

            "response.reasoning_summary_text.delta" -> {
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
                                        UIMessagePart.ToolCall(
                                            toolCallId = id,
                                            toolName = item["name"]?.jsonPrimitive?.content ?: "",
                                            arguments = item["arguments"]?.jsonPrimitive?.content
                                                ?: ""
                                        )
                                    )
                                ),
                                finishReason = null
                            )
                        )
                    )
                } else if(type == "reasoning") {
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
                                        )
                                    )
                                ),
                                finishReason = null,
                            )
                        )
                    )
                }
            }

            "response.function_call_arguments.done" -> {
                val toolCallId =
                    jsonObject["item_id"]?.jsonPrimitive?.content ?: error("item_id not found")
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

    private fun parseResponseOutput(jsonObject: JsonObject): MessageChunk {
        println(jsonObject)
        val outputs = jsonObject["output"]?.jsonArray ?: error("output not found")
        val parts = arrayListOf<UIMessagePart>()

        outputs.forEach { outputItem ->
            val output = outputItem.jsonObject
            val type = output["type"]?.jsonPrimitive?.content ?: error("output type not found")
            when (type) {
                "reasoning" -> {
                    val summary = output["summary"]?.jsonArray ?: error("summary not found")
                    summary.map { it.jsonObject }.forEach { part ->
                        val partType = part["type"]?.jsonPrimitive?.content ?: error("part type not found")
                        when (partType) {
                            "summary_text" -> {
                                val text = part["text"]?.jsonPrimitive?.content ?: error("text not found")
                                parts.add(
                                    UIMessagePart.Reasoning(
                                        reasoning = text,
                                        createdAt = Clock.System.now(),
                                        finishedAt = Clock.System.now()
                                    )
                                )
                            }
                        }
                    }
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
