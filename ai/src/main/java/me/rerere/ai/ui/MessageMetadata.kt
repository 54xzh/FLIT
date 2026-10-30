package me.rerere.ai.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.intOrNull
import me.rerere.common.http.jsonPrimitiveOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.util.json

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
