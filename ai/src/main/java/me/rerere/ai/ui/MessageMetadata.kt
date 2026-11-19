package me.rerere.ai.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.util.json
import me.rerere.common.http.jsonPrimitiveOrNull

/**
 * [UIMessagePart.metadata] 的类型安全 schema
 *
 * metadata 在序列化层仍然是 [JsonObject], 这里只是为读写提供编译期类型:
 * - 读: `part.metadataAs<ClaudeReasoningMetadata>()?.signature`
 * - 写: `part.metadata = ClaudeReasoningMetadata(signature = ...).toMetadata()`
 *
 * 所有字段必须可空且 key 与历史数据保持一致(必要时用 SerialName),
 * 否则旧会话中持久化的 metadata 将无法解析
 */
sealed interface PartMetadata

/**
 * Claude thinking block 的元数据, 回传时需要携带 signature
 */
@Serializable
data class ClaudeReasoningMetadata(
    val signature: String? = null,
) : PartMetadata

/**
 * Google Gemini 的 thought signature，以及 Interactions API 中需要原样回传的步骤。
 *
 * generateContent 把 signature 附在内容部件上；Interactions API 则把它放在独立的
 * thought / 内置工具步骤中。后者在无状态续聊时必须保留原始结构。
 */
@Serializable
data class GoogleThoughtMetadata(
    val thoughtSignature: String? = null,
    val interactionStep: JsonObject? = null,
    val interactionSteps: List<GoogleInteractionStep>? = null,
    /** 手动清除过该片段的 Gemini 思考签名，用于发送层按协议做安全兜底（不计入“仍有可清除签名”）。 */
    val thoughtSignatureCleared: Boolean? = null,
) : PartMetadata

@Serializable
data class GoogleInteractionStep(
    val turnId: String,
    val index: Int,
    val step: JsonObject,
    val revision: Int = 0,
)

/** 合并展示时保留每个原始步骤，后到的完整快照更新同一序号。 */
internal fun mergeGooglePartMetadata(existing: JsonObject?, incoming: JsonObject?): JsonObject? {
    if (incoming == null) return existing
    if (existing == null) return incoming
    val oldSteps = (existing["interactionSteps"] as? JsonArray).orEmpty()
    val newSteps = (incoming["interactionSteps"] as? JsonArray).orEmpty()
    return buildJsonObject {
        existing.forEach { (key, value) -> put(key, value) }
        incoming.forEach { (key, value) -> put(key, value) }
        if (oldSteps.isNotEmpty() || newSteps.isNotEmpty()) {
            val steps = linkedMapOf<Pair<kotlinx.serialization.json.JsonElement?, kotlinx.serialization.json.JsonElement?>, kotlinx.serialization.json.JsonElement>()
            (oldSteps + newSteps).forEach { element ->
                val step = element as? JsonObject ?: return@forEach
                val key = step["turnId"] to step["index"]
                val previous = steps[key] as? JsonObject
                if ((step["revision"]?.jsonPrimitiveOrNull?.intOrNull ?: 0) >=
                    (previous?.get("revision")?.jsonPrimitiveOrNull?.intOrNull ?: 0)) steps[key] = step
            }
            put("interactionSteps", JsonArray(steps.values.toList()))
        }
    }
}

internal fun UIMessagePart.sharesGoogleInteractionStep(other: UIMessagePart): Boolean {
    val own = metadataAs<GoogleThoughtMetadata>()?.interactionSteps.orEmpty()
    val incoming = other.metadataAs<GoogleThoughtMetadata>()?.interactionSteps.orEmpty()
    return own.any { step -> incoming.any { it.turnId == step.turnId && it.index == step.index } }
}

/**
 * 将 metadata 解析为类型化的 [PartMetadata], 解析失败或 metadata 为 null 时返回 null
 *
 * 由于 json 配置了 ignoreUnknownKeys, 不同 provider 的 metadata 互不干扰
 * (例如切换 provider 后, Google 写入的 thoughtSignature 不会影响 Claude 的解析)
 */
inline fun <reified T : PartMetadata> UIMessagePart.metadataAs(): T? = metadata?.let {
    runCatching { json.decodeFromJsonElement<T>(it) }.getOrNull()
}

/**
 * 将类型化的 [PartMetadata] 编码为 metadata [JsonObject]
 *
 * 由于 json 配置了 explicitNulls = false, 值为 null 的字段不会写入
 */
inline fun <reified T : PartMetadata> T.toMetadata(): JsonObject =
    json.encodeToJsonElement(this).jsonObject

/**
 * Google 官方文档规定的历史/跨渠道 functionCall 跳过思考签名校验占位值。
 * 仅用于已被用户手动清除思考签名的历史工具调用，避免重试或续聊时触发 400。
 */
const val GOOGLE_SKIP_THOUGHT_SIGNATURE_VALIDATOR = "skip_thought_signature_validator"

private const val THOUGHT_SIGNATURE_CLEARED_KEY = "thoughtSignatureCleared"
private val GEMINI_TOP_LEVEL_SIGNATURE_KEYS = setOf("thoughtSignature", "thought_signature")
private val GEMINI_STEP_SIGNATURE_KEYS = setOf("signature", "thought_signature", "thoughtSignature")
internal val GEMINI_BUILTIN_SIGNED_INTERACTION_STEP_TYPES = setOf(
    "google_search_call",
    "google_search_result",
    "url_context_call",
    "url_context_result",
    "code_execution_call",
    "code_execution_result",
)

/**
 * 首版仅适配 Gemini 系列模型：通过模型 ID 是否包含 gemini（不区分大小写）进行匹配。
 */
fun isGeminiThoughtSignatureModelId(modelId: String?): Boolean {
    return !modelId.isNullOrBlank() && modelId.contains("gemini", ignoreCase = true)
}

/**
 * 判断单个消息片段是否包含可清除的 Gemini 思考签名（涵盖 generateContent 与 Interactions API）。
 */
fun UIMessagePart.hasGeminiThoughtSignature(): Boolean {
    val meta = metadata ?: return false
    if (GEMINI_TOP_LEVEL_SIGNATURE_KEYS.any { key ->
            !meta[key]?.jsonPrimitiveOrNull?.contentOrNull.isNullOrBlank()
        }
    ) {
        return true
    }
    val singleStep = meta["interactionStep"] as? JsonObject
    if (singleStep != null && singleStep.hasNonBlankGeminiStepSignature()) {
        return true
    }
    val steps = meta["interactionSteps"] as? JsonArray
    if (steps != null) {
        for (element in steps) {
            val wrapper = element as? JsonObject ?: continue
            val step = wrapper["step"] as? JsonObject ?: continue
            if (step.hasNonBlankGeminiStepSignature()) {
                return true
            }
        }
    }
    return false
}

fun UIMessage.hasGeminiThoughtSignature(): Boolean = parts.any { it.hasGeminiThoughtSignature() }

/**
 * 清除单个消息片段中的 Gemini 思考签名，保留正文、思考文本及其他无关元数据。
 *
 * @return Pair(清理后的片段, 是否有实际的签名被清除)
 */
fun UIMessagePart.clearGeminiThoughtSignature(): Pair<UIMessagePart, Boolean> {
    val meta = metadata ?: return this to false
    val hadSignature = hasGeminiThoughtSignature()
    var metadataChanged = false

    val nextEntries = linkedMapOf<String, kotlinx.serialization.json.JsonElement>()
    for ((key, value) in meta) {
        when (key) {
            in GEMINI_TOP_LEVEL_SIGNATURE_KEYS -> {
                metadataChanged = true
            }

            "interactionStep" -> {
                val step = value as? JsonObject
                if (step != null) {
                    val (cleanedStep, stepChanged) = step.stripGeminiStepSignatures()
                    if (stepChanged) metadataChanged = true
                    nextEntries[key] = cleanedStep
                } else {
                    nextEntries[key] = value
                }
            }

            "interactionSteps" -> {
                val steps = value as? JsonArray
                if (steps != null) {
                    var anyStepChanged = false
                    val cleanedSteps = steps.map { element ->
                        val wrapper = element as? JsonObject ?: return@map element
                        val step = wrapper["step"] as? JsonObject ?: return@map element
                        val (cleanedStep, stepChanged) = step.stripGeminiStepSignatures()
                        if (stepChanged) {
                            anyStepChanged = true
                            JsonObject(wrapper + ("step" to cleanedStep))
                        } else {
                            wrapper
                        }
                    }
                    if (anyStepChanged) {
                        metadataChanged = true
                        nextEntries[key] = JsonArray(cleanedSteps)
                    } else {
                        nextEntries[key] = value
                    }
                } else {
                    nextEntries[key] = value
                }
            }

            else -> {
                nextEntries[key] = value
            }
        }
    }

    if (!metadataChanged) return this to false
    val shouldMarkCleared = hadSignature && (
        this is UIMessagePart.ToolCall ||
            nextEntries.containsKey("interactionStep") ||
            nextEntries.containsKey("interactionSteps")
        )
    if (shouldMarkCleared) {
        nextEntries[THOUGHT_SIGNATURE_CLEARED_KEY] = JsonPrimitive(true)
    }
    val nextMetadata = nextEntries.takeIf { it.isNotEmpty() }?.let(::JsonObject)
    return withMetadata(nextMetadata) to hadSignature
}

/**
 * 清除整条消息中的 Gemini 思考签名。
 * 对于仅用于承载签名的空文本占位片段，在清理后若消息仍有其他片段则一并移除，避免出现空白文本块。
 * 若该消息存在有效签名被清除且包含工具调用，会确保首个工具调用带有清除标记，
 * 以便后续重试或跨渠道续聊时由发送层附带官方跳过校验占位值。
 *
 * @return Pair(清理后的消息, 是否清除了至少一个有效签名)
 */
fun UIMessage.clearGeminiThoughtSignatures(): Pair<UIMessage, Boolean> {
    var clearedAny = false
    var changedAnyPart = false
    val cleanedParts = mutableListOf<UIMessagePart>()
    var droppedSignatureOnlyTextCount = 0

    for (part in parts) {
        val (cleanedPart, hadSignature) = part.clearGeminiThoughtSignature()
        if (hadSignature) clearedAny = true
        if (cleanedPart !== part) changedAnyPart = true

        val wasSignatureOnlyEmptyText =
            part is UIMessagePart.Text &&
                part.text.isEmpty() &&
                cleanedPart is UIMessagePart.Text &&
                cleanedPart.metadata == null &&
                part.metadata != null &&
                part.metadata?.keys?.all { it in GEMINI_TOP_LEVEL_SIGNATURE_KEYS } == true

        if (wasSignatureOnlyEmptyText) {
            droppedSignatureOnlyTextCount++
            changedAnyPart = true
        } else {
            cleanedParts += cleanedPart
        }
    }

    if (!changedAnyPart) return this to false

    // 若本条消息的签名原本附在同消息的思考/文本片段上，补标首个工具调用，
    // 防止切换到标准 Gemini 接口后重试“工具执行后的最终回答”触发 400。
    if (clearedAny) {
        val hasMarkedToolCall = cleanedParts.any { part ->
            part is UIMessagePart.ToolCall &&
                part.metadataAs<GoogleThoughtMetadata>()?.thoughtSignatureCleared == true
        }
        if (!hasMarkedToolCall) {
            val firstToolCallIndex = cleanedParts.indexOfFirst { it is UIMessagePart.ToolCall }
            if (firstToolCallIndex >= 0) {
                cleanedParts[firstToolCallIndex] = cleanedParts[firstToolCallIndex].withThoughtSignatureClearedFlag()
            }
        }
    }

    // 若整条消息原本仅包含空签名占位文本，保留一个空文本避免产生空 parts 列表
    if (cleanedParts.isEmpty() && droppedSignatureOnlyTextCount > 0) {
        cleanedParts += UIMessagePart.Text(text = "", metadata = null)
    }

    return copy(parts = cleanedParts) to clearedAny
}

private fun UIMessagePart.withThoughtSignatureClearedFlag(): UIMessagePart {
    val entries = linkedMapOf<String, kotlinx.serialization.json.JsonElement>()
    metadata?.forEach { (key, value) -> entries[key] = value }
    entries[THOUGHT_SIGNATURE_CLEARED_KEY] = JsonPrimitive(true)
    return withMetadata(JsonObject(entries))
}

private fun JsonObject.hasNonBlankGeminiStepSignature(): Boolean {
    return GEMINI_STEP_SIGNATURE_KEYS.any { key ->
        !this[key]?.jsonPrimitiveOrNull?.contentOrNull.isNullOrBlank()
    }
}

private fun JsonObject.stripGeminiStepSignatures(): Pair<JsonObject, Boolean> {
    if (GEMINI_STEP_SIGNATURE_KEYS.none { containsKey(it) }) return this to false
    return JsonObject(filterKeys { it !in GEMINI_STEP_SIGNATURE_KEYS }) to true
}

@Suppress("DEPRECATION")
internal fun UIMessagePart.withMetadata(newMetadata: JsonObject?): UIMessagePart = when (this) {
    is UIMessagePart.Text -> copy(metadata = newMetadata)
    is UIMessagePart.Image -> copy(metadata = newMetadata)
    is UIMessagePart.Video -> copy(metadata = newMetadata)
    is UIMessagePart.Audio -> copy(metadata = newMetadata)
    is UIMessagePart.Document -> copy(metadata = newMetadata)
    is UIMessagePart.Reasoning -> copy(metadata = newMetadata)
    is UIMessagePart.Thinking -> copy(metadata = newMetadata)
    is UIMessagePart.WebSearch -> copy(metadata = newMetadata)
    is UIMessagePart.Search -> this
    is UIMessagePart.ToolCall -> copy(metadata = newMetadata)
    is UIMessagePart.ToolApproval -> copy(metadata = newMetadata)
    is UIMessagePart.AskUser -> copy(metadata = newMetadata)
    is UIMessagePart.ToolResult -> copy(metadata = newMetadata)
    is UIMessagePart.QuotedFollowUp -> copy(metadata = newMetadata)
}
