package me.rerere.rikkahub.data.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.RawResponseException
import me.rerere.rikkahub.data.db.dao.AIRequestLogDao
import me.rerere.rikkahub.data.db.entity.AIRequestLogEntity
import me.rerere.rikkahub.utils.JsonInstant

private const val REQUEST_LOG_KEEP_LATEST = 200
private const val REQUEST_LOG_MAX_JSON_CHARS = 120_000
private const val REQUEST_LOG_MAX_PREVIEW_CHARS = 240
private const val TAG = "AIRequestLogManager"

private const val REQUEST_LOG_MAX_MESSAGES = 24
private const val REQUEST_LOG_MAX_SYSTEM_PROMPT_CHARS = 16_000
private const val REQUEST_LOG_MAX_TEXT_PART_CHARS = 2_000
private const val REQUEST_LOG_MAX_TOOL_ARGS_CHARS = 4_000
private const val REQUEST_LOG_MAX_JSON_ELEMENT_CHARS = 8_000
private const val REQUEST_LOG_MAX_URL_CHARS = 240

private const val MASKED_VALUE = "********"

private val SENSITIVE_NAMES = setOf(
    "authorization",
    "x-api-key",
    "api-key",
    "apikey",
    "api_key",
    "x-auth-token",
    "cookie",
    "set-cookie",
    "token",
    "access_token",
    "refresh_token",
    "secret",
    "password",
    "private_key",
    "client_secret",
)

@Serializable
private data class TextGenerationParamsLog(
    val temperature: Float?,
    val topP: Float?,
    val maxTokens: Int?,
    val thinkingBudget: Int?,
    val toolNames: List<String>,
    val customHeaders: List<CustomHeader>,
    val customBody: List<CustomBody>,
)

@Serializable
private data class EmbeddingParamsLog(
    val inputCount: Int,
    val totalChars: Int,
)

@Serializable
private data class EmbeddingResponseLog(
    val embeddingCount: Int,
    val dimensions: Int?,
)

class AIRequestLogManager(
    private val dao: AIRequestLogDao,
) {
    @Volatile
    private var didReclassifyRecentLogs: Boolean = false

    fun observeRecent(limit: Int = REQUEST_LOG_KEEP_LATEST) = dao.observeRecent(limit)

    fun observeById(id: Long) = dao.observeById(id)

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        runCatching { dao.clearAll() }
    }

    suspend fun logTextGeneration(
        source: AIRequestSource,
        providerSetting: ProviderSetting,
        params: TextGenerationParams,
        requestMessages: List<UIMessage>,
        responseText: String,
        responseRawText: String = "",
        stream: Boolean,
        latencyMs: Long?,
        durationMs: Long?,
        error: Throwable? = null,
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val paramsJson = buildTextGenerationParamsJson(params)
                .truncateTo(REQUEST_LOG_MAX_JSON_CHARS)
            val requestMessagesJson = JsonInstant.encodeToString(
                serializer = ListSerializer(UIMessage.serializer()),
                value = sanitizeRequestMessagesForLog(requestMessages)
            )

            val requestPreview = buildRequestPreview(requestMessages)
            val normalizedResponseText = responseText.trim()
            val normalizedRawResponseText = responseRawText
                .ifBlank { error.extractRawResponseText() }
                .trim()
            val responsePreview = buildResponsePreview(
                filteredResponseText = normalizedResponseText,
                rawResponseText = normalizedRawResponseText,
            )

            val providerType = providerSetting::class.simpleName ?: "Provider"
            val requestUrl = buildTextGenerationRequestUrl(providerSetting, params)

            dao.insert(
                AIRequestLogEntity(
                    createdAt = System.currentTimeMillis(),
                    latencyMs = latencyMs,
                    durationMs = durationMs,
                    source = source.name,
                    providerName = providerSetting.name,
                    providerType = providerType,
                    modelId = params.model.modelId,
                    modelDisplayName = params.model.displayName,
                    stream = stream,
                    paramsJson = paramsJson,
                    requestMessagesJson = requestMessagesJson,
                    requestUrl = requestUrl,
                    requestPreview = requestPreview,
                    responsePreview = responsePreview,
                    responseText = normalizedResponseText.truncateTo(REQUEST_LOG_MAX_JSON_CHARS),
                    responseRawText = normalizedRawResponseText.truncateTo(REQUEST_LOG_MAX_JSON_CHARS),
                    error = error?.let { "[${it.javaClass.simpleName}] ${it.message}".take(800) },
                )
            )
            dao.pruneKeepLatest(REQUEST_LOG_KEEP_LATEST)
        }.onFailure {
            Log.w(TAG, "logTextGeneration failed: ${it.message}", it)
        }
    }

    suspend fun logEmbedding(
        source: AIRequestSource,
        providerSetting: ProviderSetting,
        model: Model,
        inputs: List<String>,
        embeddingCount: Int?,
        dimensions: Int?,
        durationMs: Long?,
        error: Throwable? = null,
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val providerType = providerSetting::class.simpleName ?: "Provider"

            val safeInputs = inputs.map { it.trim().truncateTo(2_000) }
            val requestMessagesJson = JsonInstant.encodeToString(
                serializer = ListSerializer(String.serializer()),
                value = safeInputs,
            ).truncateTo(REQUEST_LOG_MAX_JSON_CHARS)

            val paramsJson = JsonInstant.encodeToString(
                EmbeddingParamsLog.serializer(),
                EmbeddingParamsLog(
                    inputCount = inputs.size,
                    totalChars = inputs.sumOf { it.length },
                )
            ).truncateTo(REQUEST_LOG_MAX_JSON_CHARS)

            val responseJson = if (embeddingCount != null) {
                JsonInstant.encodeToString(
                    EmbeddingResponseLog.serializer(),
                    EmbeddingResponseLog(
                        embeddingCount = embeddingCount,
                        dimensions = dimensions,
                    )
                )
            } else {
                ""
            }

            val requestUrl = buildEmbeddingRequestUrl(providerSetting, model)
            val requestPreview = safeInputs.firstOrNull().orEmpty()
                .replace("\r", "")
                .replace("\n", " ")
                .take(REQUEST_LOG_MAX_PREVIEW_CHARS)

            val responsePreview = buildString {
                if (embeddingCount != null) append("embeddings=$embeddingCount")
                if (dimensions != null) {
                    if (isNotEmpty()) append(", ")
                    append("dims=$dimensions")
                }
            }.ifBlank { "-" }.take(REQUEST_LOG_MAX_PREVIEW_CHARS)

            dao.insert(
                AIRequestLogEntity(
                    createdAt = System.currentTimeMillis(),
                    latencyMs = durationMs,
                    durationMs = durationMs,
                    source = source.name,
                    providerName = providerSetting.name,
                    providerType = providerType,
                    modelId = model.modelId,
                    modelDisplayName = model.displayName,
                    stream = false,
                    paramsJson = paramsJson,
                    requestMessagesJson = requestMessagesJson,
                    requestUrl = requestUrl,
                    requestPreview = requestPreview,
                    responsePreview = responsePreview,
                    responseText = responseJson.truncateTo(REQUEST_LOG_MAX_JSON_CHARS),
                    responseRawText = "",
                    error = error?.let { "[${it.javaClass.simpleName}] ${it.message}".take(800) },
                )
            )
            dao.pruneKeepLatest(REQUEST_LOG_KEEP_LATEST)
        }.onFailure {
            Log.w(TAG, "logEmbedding failed: ${it.message}", it)
        }
    }

    suspend fun reclassifyRecentLogsIfNeeded(limit: Int = REQUEST_LOG_KEEP_LATEST) = withContext(Dispatchers.IO) {
        if (didReclassifyRecentLogs) return@withContext
        didReclassifyRecentLogs = true

        runCatching {
            val logs = dao.getRecent(limit)
            logs.forEach { log ->
                val newSource = reclassifyLogSourceOrNull(log) ?: return@forEach
                if (newSource != log.source) {
                    dao.updateSource(id = log.id, source = newSource)
                }
            }
        }.onFailure {
            Log.w(TAG, "reclassifyRecentLogsIfNeeded failed: ${it.message}", it)
        }
    }
}

private fun reclassifyLogSourceOrNull(log: AIRequestLogEntity): String? {
    if (log.source == AIRequestSource.OTHER.name && log.requestPreview.isGroupChatRoutingPreview()) {
        return AIRequestSource.GROUP_CHAT_ROUTING.name
    }

    val embeddingParams = log.paramsJson.parseEmbeddingParamsOrNull()
    if (embeddingParams != null) {
        if (log.source == AIRequestSource.MEMORY_EMBEDDING.name) {
            return AIRequestSource.MEMORY_RETRIEVAL.name.takeIf { embeddingParams.totalChars < 200 }
        }

        return AIRequestSource.MEMORY_EMBEDDING.name.takeIf {
            log.source == AIRequestSource.OTHER.name && embeddingParams.totalChars >= 200
        }
    }

    return null
}

private fun String.isGroupChatRoutingPreview(): Boolean {
    val trimmed = trim()
    if (trimmed.isBlank()) return false
    return trimmed.contains("route the speakers", ignoreCase = true)
}

private fun String.parseEmbeddingParamsOrNull(): EmbeddingParamsLog? {
    val trimmed = trim()
    if (trimmed.isBlank()) return null
    return runCatching { JsonInstant.decodeFromString(EmbeddingParamsLog.serializer(), trimmed) }.getOrNull()
}

private fun buildTextGenerationParamsJson(params: TextGenerationParams): String {
    val safeHeaders = params.customHeaders.map { header ->
        if (header.name.isSensitiveName()) header.copy(value = MASKED_VALUE) else header
    }
    val safeBodies = params.customBody.map { body ->
        if (body.key.isSensitiveName()) {
            body.copy(value = JsonPrimitive(MASKED_VALUE))
        } else {
            body.copy(value = body.value.maskSensitiveValues())
        }
    }

    val safe = TextGenerationParamsLog(
        temperature = params.temperature,
        topP = params.topP,
        maxTokens = params.maxTokens,
        thinkingBudget = params.thinkingBudget,
        toolNames = params.tools.map { it.name },
        customHeaders = safeHeaders,
        customBody = safeBodies,
    )

    return JsonInstant.encodeToString(TextGenerationParamsLog.serializer(), safe)
}

private fun buildRequestPreview(messages: List<UIMessage>): String {
    val lastUser = messages.asReversed().firstOrNull { it.role == me.rerere.ai.core.MessageRole.USER }
    val userText = lastUser?.parts
        ?.filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>()
        ?.joinToString("\n") { it.text }
        ?.trim()
        .orEmpty()
    if (userText.isNotBlank()) {
        return userText.replace("\r", "").replace("\n", " ").take(REQUEST_LOG_MAX_PREVIEW_CHARS)
    }

    val text = messages.asSequence()
        .flatMap { it.parts.asSequence() }
        .filterIsInstance<me.rerere.ai.ui.UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .trim()
    return text.replace("\r", "").replace("\n", " ").take(REQUEST_LOG_MAX_PREVIEW_CHARS)
}

private fun buildResponsePreview(filteredResponseText: String, rawResponseText: String): String {
    val normalized = filteredResponseText
        .ifBlank { rawResponseText }
        .trim()
        .replace("\r", "")
        .replace("\n", " ")
    return normalized.take(REQUEST_LOG_MAX_PREVIEW_CHARS)
}

private fun sanitizeRequestMessagesForLog(messages: List<UIMessage>): List<UIMessage> {
    val system = messages.firstOrNull { it.role == MessageRole.SYSTEM }
    val tail = messages.filterNot { it.role == MessageRole.SYSTEM }
        .takeLast(REQUEST_LOG_MAX_MESSAGES)

    return buildList {
        system?.let { add(sanitizeMessageForLog(it, system = true)) }
        tail.forEach { add(sanitizeMessageForLog(it, system = false)) }
    }
}

private fun sanitizeMessageForLog(message: UIMessage, system: Boolean): UIMessage {
    return message.copy(
        parts = message.parts.map { part -> sanitizePartForLog(part, system = system) },
        annotations = message.annotations.take(10),
        translation = message.translation?.truncateTo(REQUEST_LOG_MAX_TEXT_PART_CHARS),
    )
}

private fun sanitizePartForLog(part: UIMessagePart, system: Boolean): UIMessagePart {
    return when (part) {
        is UIMessagePart.Text -> part.copy(
            text = part.text.truncateTo(if (system) REQUEST_LOG_MAX_SYSTEM_PROMPT_CHARS else REQUEST_LOG_MAX_TEXT_PART_CHARS),
            metadata = null,
        )

        is UIMessagePart.Image -> part.copy(
            url = sanitizeUrlForLog(part.url),
            metadata = null,
        )

        is UIMessagePart.Video -> part.copy(
            url = sanitizeUrlForLog(part.url),
            metadata = null,
        )

        is UIMessagePart.Audio -> part.copy(
            url = sanitizeUrlForLog(part.url),
            metadata = null,
        )

        is UIMessagePart.Document -> part.copy(
            url = sanitizeUrlForLog(part.url),
            fileName = part.fileName.truncateInline(120),
            mime = part.mime.truncateInline(80),
            metadata = null,
        )

        is UIMessagePart.Reasoning -> part.copy(
            reasoning = part.reasoning.truncateTo(REQUEST_LOG_MAX_TEXT_PART_CHARS),
            metadata = null,
        )

        is UIMessagePart.Thinking -> part.copy(
            thinking = part.thinking.truncateTo(REQUEST_LOG_MAX_TEXT_PART_CHARS),
            metadata = null,
        )

        is UIMessagePart.ToolCall -> part.copy(
            toolCallId = part.toolCallId.truncateInline(120),
            toolName = part.toolName.truncateInline(120),
            arguments = part.arguments.truncateTo(REQUEST_LOG_MAX_TOOL_ARGS_CHARS),
            metadata = null,
        )

        is UIMessagePart.ToolApproval -> part.copy(
            toolCallId = part.toolCallId.truncateInline(120),
            toolName = part.toolName.truncateInline(120),
            metadata = null,
        )

        is UIMessagePart.ToolResult -> part.copy(
            toolCallId = part.toolCallId.truncateInline(120),
            toolName = part.toolName.truncateInline(120),
            content = part.content.truncateJsonForLog(REQUEST_LOG_MAX_JSON_ELEMENT_CHARS),
            arguments = part.arguments.truncateJsonForLog(REQUEST_LOG_MAX_JSON_ELEMENT_CHARS),
            metadata = null,
        )

        UIMessagePart.Search -> UIMessagePart.Search

        is UIMessagePart.AskUser -> part.copy(
            toolCallId = part.toolCallId.truncateInline(120),
            question = part.question.truncateTo(REQUEST_LOG_MAX_TEXT_PART_CHARS),
            options = part.options.map { it.truncateInline(120) },
            questions = part.questions?.map { q ->
                q.copy(
                    question = q.question.truncateTo(REQUEST_LOG_MAX_TEXT_PART_CHARS),
                    options = q.options.map { it.truncateInline(120) },
                )
            },
            answer = part.answer?.truncateInline(REQUEST_LOG_MAX_TEXT_PART_CHARS),
            answers = part.answers?.map { it.truncateInline(REQUEST_LOG_MAX_TEXT_PART_CHARS) },
            metadata = null,
        )
    }
}

private fun sanitizeUrlForLog(url: String): String {
    val trimmed = url.trim()
    if (trimmed.startsWith("data:", ignoreCase = true) && "base64," in trimmed) {
        val prefix = trimmed.substringBefore("base64,") + "base64,"
        val payload = trimmed.substringAfter("base64,", "")
        return prefix + payload.truncateInline(120)
    }
    return trimmed.truncateInline(REQUEST_LOG_MAX_URL_CHARS)
}

private fun JsonElement.truncateJsonForLog(maxChars: Int): JsonElement {
    val masked = maskSensitiveValues()
    val raw = runCatching { JsonInstant.encodeToString(JsonElement.serializer(), masked) }
        .getOrElse { return JsonPrimitive("(json encode failed)") }

    if (raw.length <= maxChars) return masked

    return JsonObject(
        mapOf(
            "_truncated" to JsonPrimitive(true),
            "preview" to JsonPrimitive(raw.truncateInline(maxChars)),
        )
    )
}

private fun buildTextGenerationRequestUrl(providerSetting: ProviderSetting, params: TextGenerationParams): String {
    return when (providerSetting) {
        is ProviderSetting.OpenAI -> {
            val base = providerSetting.baseUrl.trimEnd('/')
            val path = if (providerSetting.useResponseApi) {
                "/responses"
            } else {
                providerSetting.chatCompletionsPath.ifBlank { "/chat/completions" }
            }
            base + (if (path.startsWith("/")) path else "/$path")
        }

        is ProviderSetting.Google -> {
            if (providerSetting.vertexAI) {
                "https://aiplatform.googleapis.com/v1/projects/${providerSetting.projectId}/locations/${providerSetting.location}/publishers/google/models/${params.model.modelId}:generateContent"
            } else {
                val base = providerSetting.baseUrl.trimEnd('/')
                "$base/models/${params.model.modelId}:generateContent"
            }
        }

        is ProviderSetting.Claude -> {
            val base = providerSetting.baseUrl.trimEnd('/')
            "$base/messages"
        }

        else -> ""
    }
}

private fun buildEmbeddingRequestUrl(providerSetting: ProviderSetting, model: Model): String {
    return when (providerSetting) {
        is ProviderSetting.OpenAI -> {
            val base = providerSetting.baseUrl.trimEnd('/')
            "$base/embeddings"
        }

        is ProviderSetting.Google -> {
            if (providerSetting.vertexAI) {
                "https://aiplatform.googleapis.com/v1/projects/${providerSetting.projectId}/locations/${providerSetting.location}/publishers/google/models/${model.modelId}:embedContent"
            } else {
                val base = providerSetting.baseUrl.trimEnd('/')
                "$base/models/${model.modelId}:embedContent"
            }
        }

        else -> ""
    }
}

private fun Throwable?.extractRawResponseText(): String {
    val visited = mutableSetOf<Throwable>()
    var current = this
    while (current != null && visited.add(current)) {
        if (current is RawResponseException) {
            return current.rawResponse
        }
        current = current.cause
    }
    return ""
}

private fun String.truncateTo(maxChars: Int): String {
    if (length <= maxChars) return this
    return take(maxChars) + "\n...(truncated)"
}

private fun String.truncateInline(maxChars: Int): String {
    if (length <= maxChars) return this
    return take(maxChars) + "...(truncated)"
}

private fun String.isSensitiveName(): Boolean {
    return trim().lowercase() in SENSITIVE_NAMES
}

private fun JsonElement.maskSensitiveValues(): JsonElement {
    return when (this) {
        is JsonObject -> {
            JsonObject(
                this.entries.associate { (key, value) ->
                    if (key.isSensitiveName()) {
                        key to JsonPrimitive(MASKED_VALUE)
                    } else {
                        key to value.maskSensitiveValues()
                    }
                }
            )
        }

        is JsonArray -> JsonArray(this.jsonArray.map { it.maskSensitiveValues() })

        else -> this
    }
}
