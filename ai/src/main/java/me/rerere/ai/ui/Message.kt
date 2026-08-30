package me.rerere.ai.ui

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.BuildConfig
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Modality
import me.rerere.ai.util.json
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

// 公共消息抽象, 具体的Provider实现会转换为API接口需要的DTO
@Serializable
data class UIMessage(
    val id: Uuid = Uuid.random(),
    val role: MessageRole,
    val parts: List<UIMessagePart>,
    val annotations: List<UIMessageAnnotation> = emptyList(),
    val createdAt: LocalDateTime = Clock.System.now()
        .toLocalDateTime(TimeZone.currentSystemDefault()),
    val modelId: Uuid? = null,
    val usage: TokenUsage? = null,
    val translation: String? = null,
    val generationDurationMs: Long? = null, // Duration of AI generation in milliseconds
    val speakerAssistantId: Uuid? = null,
    val speakerSeatId: Uuid? = null,
    val usedLorebookEntries: List<UsedLorebookEntry>? = null, // Lorebook entries used in this message
    val usedModes: List<UsedMode>? = null, // Modes used in this message
    val usedMemories: List<UsedMemory>? = null, // Memories used in this message
    val usedSessionMemories: List<UsedSessionMemory>? = null, // Conversation-scoped memories used in this message
    val usedMemorySummary: UsedMemorySummary? = null // Assistant memory summary used in this message
) {
    private fun appendChunk(chunk: MessageChunk): UIMessage {
        val choice = chunk.choices.getOrNull(0)
        return choice?.delta?.let { delta ->
            // Handle Parts
            var newParts = delta.parts.fold(parts) { acc, deltaPart ->
                when (deltaPart) {
                    is UIMessagePart.Text -> {
                        val existingTextPart =
                            acc.find { it is UIMessagePart.Text } as? UIMessagePart.Text
                        if (existingTextPart != null) {
                            acc.map { part ->
                                if (part is UIMessagePart.Text) {
                                    part.copy(
                                        text = existingTextPart.text + deltaPart.text,
                                        metadata = deltaPart.metadata ?: part.metadata,
                                    )
                                } else part
                            }
                        } else {
                            acc + deltaPart
                        }
                    }

                    is UIMessagePart.Image -> {
                        val existingImagePart =
                            acc.find { it is UIMessagePart.Image } as? UIMessagePart.Image
                        if (existingImagePart != null) {
                            acc.map { part ->
                                if (part is UIMessagePart.Image) {
                                    part.copy(
                                        url = mergeStreamedImageUrl(
                                            existingUrl = existingImagePart.url,
                                            incomingUrl = deltaPart.url,
                                        ),
                                        metadata = deltaPart.metadata ?: part.metadata,
                                    )
                                } else part
                            }
                        } else {
                            acc + UIMessagePart.Image(
                                url = normalizeImageDataUri(deltaPart.url),
                                metadata = deltaPart.metadata,
                            )
                        }
                    }

                    is UIMessagePart.Reasoning -> {
                        val incomingReasoningId = deltaPart.metadata
                            ?.get("openai_reasoning_id")
                            ?.jsonPrimitive
                            ?.contentOrNull
                            ?.takeIf { it.isNotBlank() }
                        val existingReasoningIndex = if (incomingReasoningId == null) {
                            acc.indexOfLast { it is UIMessagePart.Reasoning }
                        } else {
                            acc.indexOfLast { part ->
                                part is UIMessagePart.Reasoning &&
                                    part.metadata
                                        ?.get("openai_reasoning_id")
                                        ?.jsonPrimitive
                                        ?.contentOrNull == incomingReasoningId
                            }
                        }
                        val existingReasoningPart = acc.getOrNull(existingReasoningIndex) as? UIMessagePart.Reasoning
                        if (existingReasoningPart == null || existingReasoningIndex < 0) {
                            acc + deltaPart
                        } else if (
                            incomingReasoningId != null &&
                            deltaPart.reasoning.isBlank() &&
                            existingReasoningPart.finishedAt != null
                        ) {
                            // Responses 的 output_item.done 在摘要增量之后只带元数据
                            // （尤其是 encrypted_content）。若搜索节点已收束这个思考块，
                            // 这里不能把它重新标记为进行中。
                            acc.mapIndexed { index, part ->
                                if (index == existingReasoningIndex) {
                                    existingReasoningPart.copy(
                                        metadata = mergePartMetadata(
                                            existing = existingReasoningPart.metadata,
                                            incoming = deltaPart.metadata,
                                        ),
                                    )
                                } else {
                                    part
                                }
                            }
                        } else {
                            val resumedCreatedAt = if (existingReasoningPart.finishedAt != null) {
                                val accumulated = existingReasoningPart.finishedAt - existingReasoningPart.createdAt
                                Clock.System.now() - accumulated
                            } else {
                                existingReasoningPart.createdAt
                            }
                            val mergedReasoning = UIMessagePart.Reasoning(
                                reasoning = existingReasoningPart.reasoning + deltaPart.reasoning,
                                createdAt = resumedCreatedAt,
                                finishedAt = null,
                                metadata = mergePartMetadata(
                                    existing = existingReasoningPart.metadata,
                                    incoming = deltaPart.metadata,
                                ),
                            )
                            acc.mapIndexed { index, part ->
                                if (index == existingReasoningIndex) {
                                    mergedReasoning
                                } else {
                                    part
                                }
                            }
                        }
                    }

                    is UIMessagePart.WebSearch -> {
                        val existingIndex = acc.indexOfLast { part ->
                            part is UIMessagePart.WebSearch && part.callId == deltaPart.callId
                        }
                        val existing = acc.getOrNull(existingIndex) as? UIMessagePart.WebSearch
                        if (existing == null || existingIndex < 0) {
                            // 网页搜索是 Responses 输出中的独立过程项。到达时先收束前一个
                            // 思考块，后续带有新 OpenAI reasoning id 的摘要自然会另起一块，
                            // 才能按“思考 → 搜索 → 思考”的真实顺序展示。
                            acc.map { part ->
                                if (part is UIMessagePart.Reasoning && part.finishedAt == null) {
                                    part.copy(finishedAt = deltaPart.createdAt)
                                } else {
                                    part
                                }
                            } + deltaPart
                        } else {
                            acc.mapIndexed { index, part ->
                                if (index == existingIndex) existing.merge(deltaPart) else part
                            }
                        }
                    }

                    is UIMessagePart.ToolCall -> {
                        val deltaIndex = deltaPart.index
                        if (deltaIndex != null) {
                            // OpenAI 兼容渠道的流式增量携带 index：按 index 对号入座，
                            // 避免不发 id 的渠道把参数片段误判成一次全新调用（导致参数丢失、
                            // 界面刷出大量无名 "Calling tool" 条目）
                            var pos = acc.indexOfLast { part ->
                                part is UIMessagePart.ToolCall && part.index == deltaIndex &&
                                    (deltaPart.toolCallId.isBlank() || part.toolCallId.isBlank() ||
                                        part.toolCallId == deltaPart.toolCallId)
                            }
                            if (pos < 0 && deltaPart.toolCallId.isNotBlank()) {
                                // 首块不带 index、后续片段带 index 且带 id 的混发网关：
                                // 回退到按 id 匹配，保持修复前"按 id 合并"的保证
                                pos = acc.indexOfLast {
                                    it is UIMessagePart.ToolCall && it.toolCallId == deltaPart.toolCallId
                                }
                            }
                            if (pos < 0 && deltaPart.toolCallId.isBlank() && deltaPart.toolName.isBlank()) {
                                // 无 id 无名的增量只可能是参数片段，单独存在毫无意义；
                                // 首块不带 index、后续片段带 index 的混发网关会走到这里。
                                // index 在 OpenAI 协议里是调用在本条消息中的序号，优先按
                                // "第 N 条调用"对号，找不到再拼进最近一次调用
                                val toolCallPositions = acc.withIndex()
                                    .filter { it.value is UIMessagePart.ToolCall }
                                    .map { it.index }
                                pos = toolCallPositions.getOrNull(deltaIndex)
                                    ?: toolCallPositions.lastOrNull()
                                    ?: -1
                            }
                            val existing = acc.getOrNull(pos) as? UIMessagePart.ToolCall
                            // 个别网关不发 id 且每次调用都把 index 重置为 0：若已有调用的参数
                            // 已是完整 JSON（合法 JSON 对象不存在同为合法 JSON 的真前缀），
                            // 再来一个带工具名的增量只能是同一调用的整体重发（丢弃，防止重复
                            // 执行）或下一次新调用的首块（追加，无论其参数是否为空）。仅当增量
                            // 带非空工具名时才做该检查，规范的参数片段（名字为空）不受影响；
                            // endsWith("}") 前置短路避免每个片段都对累积参数做完整 JSON 解析。
                            // 已知取舍：这类网关下"故意连续两次完全相同参数的调用"与整体重发
                            // 无法区分，会被当作重发丢弃
                            val existingComplete = existing != null &&
                                deltaPart.toolName.isNotBlank() &&
                                existing.toolName.isNotBlank() &&
                                existing.arguments.isNotBlank() &&
                                existing.arguments.trimEnd().endsWith("}") &&
                                runCatching { json.parseToJsonElement(existing.arguments) }.isSuccess
                            when {
                                existing == null -> acc + deltaPart.copy()
                                !existingComplete -> acc.toMutableList().apply {
                                    this[pos] = existing.merge(deltaPart)
                                }
                                // 同一条调用被整体重发（SSE 重放/累积式网关），或参数发完后
                                // 收尾 chunk 回显了工具名（空参数）：都忽略增量。
                                // 已知取舍：这类网关上"紧接着再调一次同名工具且首块无参数"
                                // 会被误判为回显，但收尾回显远比该场景常见
                                (deltaPart.toolCallId.isNotBlank() && deltaPart.toolCallId == existing.toolCallId) ||
                                    (deltaPart.toolName == existing.toolName &&
                                        (deltaPart.arguments.isBlank() ||
                                            deltaPart.arguments == existing.arguments)) -> acc

                                else -> acc + deltaPart.copy()
                            }
                        } else if (deltaPart.toolCallId.isBlank()) {
                            val pos = acc.indexOfLast { it is UIMessagePart.ToolCall }
                            val lastToolCall = acc.getOrNull(pos) as? UIMessagePart.ToolCall
                            if (lastToolCall == null || lastToolCall.toolCallId.isBlank()) {
                                // Google 等渠道：id 恒为空且每次发完整调用，视为新调用追加
                                acc + deltaPart.copy()
                            } else {
                                // Claude 等渠道：空 id 片段拼接到最近一次调用（按位置替换，
                                // 避免值相等的多个调用被同时改写）
                                acc.toMutableList().apply {
                                    this[pos] = lastToolCall.merge(deltaPart)
                                }
                            }
                        } else {
                            // insert or update
                            val existsPart = acc.find {
                                it is UIMessagePart.ToolCall && it.toolCallId == deltaPart.toolCallId
                            } as? UIMessagePart.ToolCall
                            if (existsPart == null) {
                                // insert
                                acc + deltaPart.copy()
                            } else {
                                // update
                                acc.map { part ->
                                    if (part is UIMessagePart.ToolCall && part.toolCallId == deltaPart.toolCallId) {
                                        part.merge(deltaPart)
                                    } else part
                                }
                            }
                        }
                    }

                    is UIMessagePart.ToolResult -> {
                        val existingIndex = acc.indexOfFirst { part ->
                            part is UIMessagePart.ToolResult &&
                                part.toolCallId == deltaPart.toolCallId &&
                                part.toolName == deltaPart.toolName
                        }
                        if (existingIndex < 0) {
                            acc + deltaPart.copy()
                        } else {
                            acc.mapIndexed { index, part ->
                                if (index == existingIndex && part is UIMessagePart.ToolResult) {
                                    deltaPart.copy(
                                        metadata = deltaPart.metadata ?: part.metadata
                                    )
                                } else {
                                    part
                                }
                            }
                        }
                    }

                    else -> {
                        if (BuildConfig.DEBUG) println("delta part append not supported: $deltaPart")
                        acc
                    }
                }
            }
            // Handle Reasoning End
            if (parts.filterIsInstance<UIMessagePart.Reasoning>()
                    .isNotEmpty() && delta.parts.filterIsInstance<UIMessagePart.Reasoning>()
                    .isEmpty()
            ) {
                newParts = newParts.map { part ->
                    if (part is UIMessagePart.Reasoning && part.finishedAt == null) {
                        part.copy(finishedAt = Clock.System.now())
                    } else part
                }
            }
            // Handle annotations
            val newAnnotations = if (delta.annotations.isEmpty()) {
                annotations
            } else {
                (annotations + delta.annotations).distinct()
            }
            copy(
                parts = newParts,
                annotations = newAnnotations,
            )
        } ?: this
    }

    private fun normalizeImageDataUri(url: String): String {
        return if (url.startsWith("data:image")) {
            url
        } else {
            "data:image/png;base64,$url"
        }
    }

    private fun dataUriPrefix(url: String): String {
        if (!url.startsWith("data:image")) {
            return "data:image/png;base64,"
        }
        val prefix = url.substringBefore("base64,", missingDelimiterValue = "")
        return if (prefix.isNotEmpty()) "$prefix" + "base64," else "data:image/png;base64,"
    }

    private fun mergePartMetadata(
        existing: JsonObject?,
        incoming: JsonObject?,
    ): JsonObject? {
        if (incoming == null) return existing
        if (existing == null) return incoming
        return buildJsonObject {
            existing.forEach { (key, value) -> put(key, value) }
            incoming.forEach { (key, value) -> put(key, value) }
        }
    }

    private fun extractBase64Payload(url: String): String {
        return if (url.startsWith("data:image")) {
            url.substringAfter("base64,", missingDelimiterValue = "")
        } else {
            url
        }
    }

    private fun hasEarlyPad(base64: String): Boolean {
        val firstPadIndex = base64.indexOf('=')
        if (firstPadIndex < 0) {
            return false
        }
        val tail = base64.substring(firstPadIndex)
        return tail.any { ch -> ch != '=' }
    }

    private fun mergeStreamedImageUrl(existingUrl: String, incomingUrl: String): String {
        val normalizedExisting = normalizeImageDataUri(existingUrl)
        val normalizedIncoming = normalizeImageDataUri(incomingUrl)

        val existingPayload = extractBase64Payload(normalizedExisting)
        val incomingPayload = extractBase64Payload(normalizedIncoming)

        if (incomingPayload.isBlank()) {
            return normalizedExisting
        }
        if (existingPayload.isBlank()) {
            return normalizedIncoming
        }

        if (hasEarlyPad(existingPayload)) {
            return normalizedIncoming
        }
        if (hasEarlyPad(incomingPayload)) {
            return normalizedExisting
        }

        if (incomingPayload == existingPayload) {
            return normalizedExisting
        }

        // Some providers resend full image snapshots during stream updates.
        if (incomingPayload.startsWith(existingPayload)) {
            return normalizedIncoming
        }
        if (existingPayload.startsWith(incomingPayload)) {
            return normalizedExisting
        }

        // If current payload already ended, appending would create invalid base64.
        if (existingPayload.endsWith("=")) {
            return if (incomingPayload.length > existingPayload.length) {
                normalizedIncoming
            } else {
                normalizedExisting
            }
        }

        return dataUriPrefix(normalizedExisting) + existingPayload + incomingPayload
    }

    fun summaryAsText(): String {
        return "[${role.name}]: " + parts.joinToString(separator = "\n") { part ->
            when (part) {
                is UIMessagePart.Text -> part.text
                is UIMessagePart.Thinking -> part.thinking
                is UIMessagePart.Reasoning -> part.reasoning
                else -> ""
            }
        }
    }

    fun toText() = parts.joinToString(separator = "\n") { part ->
        when (part) {
            is UIMessagePart.Text -> part.text
            is UIMessagePart.Thinking -> part.thinking
            is UIMessagePart.Reasoning -> part.reasoning
            else -> ""
        }
    }

    /**
     * Extract only text content, excluding reasoning/thinking parts.
     * Use this for background tasks where reasoning output should not be included.
     */
    fun toContentText() = parts.filterIsInstance<UIMessagePart.Text>()
        .joinToString(separator = "\n") { it.text }
        .trim()

    fun getToolCalls() = parts.filterIsInstance<UIMessagePart.ToolCall>()

    fun getToolResults() = parts.filterIsInstance<UIMessagePart.ToolResult>()

    fun isValidToUpload() = parts.any {
        it !is UIMessagePart.Reasoning && it !is UIMessagePart.WebSearch
    }

    inline fun <reified P : UIMessagePart> hasPart(): Boolean {
        return parts.any {
            it is P
        }
    }

    operator fun plus(chunk: MessageChunk): UIMessage {
        return this.appendChunk(chunk)
    }



    companion object {
        fun system(prompt: String) = UIMessage(
            role = MessageRole.SYSTEM,
            parts = listOf(UIMessagePart.Text(prompt))
        )

        fun user(prompt: String) = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text(prompt))
        )

        fun assistant(prompt: String) = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text(prompt))
        )
    }
}

/**
 * Represents a lorebook entry that was used when generating a message.
 * Stores enough info to display the entry and allow editing.
 */
@Serializable
data class UsedLorebookEntry(
    val lorebookId: String,  // UUID as string for serialization compatibility
    val lorebookName: String,
    val lorebookCover: String? = null,  // Avatar serialized as string or null
    val entryId: String,  // UUID as string
    val entryName: String,
    val entryIndex: Int,  // Position in the lorebook's entry list
    val priority: Int = 0,  // Higher = more priority (for sorting display)
    val activationReason: String? = null // e.g. "Always Active", "Keywords: foo, bar", "RAG (0.85)"
)

/**
 * Represents a mode that was used when generating a message.
 */
@Serializable
data class UsedMode(
    val modeId: String,  // UUID as string for serialization compatibility
    val modeName: String,
    val modeIcon: String? = null,  // Material icon name
    val priority: Int = 0,  // Position in mode list (higher = more priority)
    val activationReason: String? = null  // "Activated by user" or "Default enabled"
)

/**
 * Represents a memory that was used when generating a message.
 */
@Serializable
data class UsedMemory(
    val memoryId: Int,  // Negative IDs for episodic memories
    val memoryContent: String,  // First line/truncated content for display
    val memoryType: Int,  // 0 = CORE, 1 = EPISODIC
    val priority: Int = 0,
    val activationReason: String? = null  // "Contextually relevant", "Always included", "Recent episode boost"
)

@Serializable
data class UsedSessionMemory(
    val memoryId: Int,
    val memoryContent: String,
    val priority: Int = 0,
    val activationReason: String? = null,
)

@Serializable
data class UsedMemorySummary(
    val content: String,
    val versionId: Long? = null,
)


/**
 * 处理MessageChunk合并
 *
 * @receiver 已有消息列表
 * @param chunk 消息chunk
 * @param model 模型, 可以不传，如果传了，会把模型id写入到消息，标记是哪个模型输出的消息
 * @return 新消息列表
 */
fun List<UIMessage>.handleMessageChunk(chunk: MessageChunk, model: Model? = null): List<UIMessage> {
    require(this.isNotEmpty()) {
        "messages must not be empty"
    }
    val choice = chunk.choices.getOrNull(0) ?: return this
    val message = choice.delta ?: choice.message ?: throw Exception("delta/message is null")
    if (this.last().role != message.role) {
        return this + message.copy(modelId = model?.id)
    } else {
        val last = this.last() + chunk
        return this.dropLast(1) + last
    }
}

/**
 * 判断这个消息是否有有任何用户**可输入内容**
 *
 * 例如: 文本，图片, 文档
 */
fun List<UIMessagePart>.isEmptyInputMessage(): Boolean {
    if (this.isEmpty()) return true
    return this.all { message ->
        when (message) {
            is UIMessagePart.Text -> message.text.isBlank()
            is UIMessagePart.Image -> message.url.isBlank()
            is UIMessagePart.Document -> message.url.isBlank()
            is UIMessagePart.Video -> message.url.isBlank()
            is UIMessagePart.Audio -> message.url.isBlank()
            else -> true
        }
    }
}

/**
 * 判断这个消息在UI上是否显示任何内容
 */
fun List<UIMessagePart>.isEmptyUIMessage(): Boolean {
    if (this.isEmpty()) return true
    return this.all { message ->
        when (message) {
            is UIMessagePart.Text -> message.text.stripInterruptedAppContextForDisplay().isBlank()
            is UIMessagePart.Image -> message.url.isBlank()
            is UIMessagePart.Document -> message.url.isBlank()
            is UIMessagePart.Reasoning -> message.reasoning.isBlank()
            is UIMessagePart.Video -> message.url.isBlank()
            is UIMessagePart.Audio -> message.url.isBlank()
            else -> true
        }
    }
}

fun List<UIMessage>.truncate(index: Int): List<UIMessage> {
    if (index < 0 || index > this.lastIndex) return this
    return this.subList(index, this.size)
}

fun List<UIMessage>.limitContext(size: Int): List<UIMessage> {
    if (size <= 0 || this.size <= size) return this

    val startIndex = this.size - size
    var adjustedStartIndex = startIndex

    // 循环往前查找，直到满足所有依赖条件
    var needsAdjustment = true
    val visitedIndices = mutableSetOf<Int>()

    while (needsAdjustment && adjustedStartIndex > 0) {
        needsAdjustment = false

        // 防止无限循环
        if (adjustedStartIndex in visitedIndices) break
        visitedIndices.add(adjustedStartIndex)

        val currentMessage = this[adjustedStartIndex]

        // 如果当前消息包含tool result，往前查找对应的tool call
        if (currentMessage.getToolResults().isNotEmpty()) {
            for (i in adjustedStartIndex - 1 downTo 0) {
                if (this[i].getToolCalls().isNotEmpty()) {
                    adjustedStartIndex = i
                    needsAdjustment = true
                    break
                }
            }
        }

        // 如果当前消息包含tool call，往前查找对应的用户消息
        if (currentMessage.getToolCalls().isNotEmpty()) {
            for (i in adjustedStartIndex - 1 downTo 0) {
                if (this[i].role == MessageRole.USER) {
                    adjustedStartIndex = i
                    needsAdjustment = true
                    break
                }
            }
        }
    }

    return this.subList(adjustedStartIndex, this.size)
}

enum class InterruptedGenerationReason {
    UserCancelled,
    GenerationFailed,
    ReplacedByNewRequest,
}

const val USER_STOPPED_OUTPUT_APP_CONTEXT = "The user stopped the output."
const val MESSAGE_INTERRUPTED_APP_CONTEXT = "The message was interrupted."
private const val INTERRUPTED_APP_CONTEXT_START_TAG = "<app_context>"
private const val INTERRUPTED_APP_CONTEXT_END_TAG = "</app_context>"
private val INTERRUPTED_APP_CONTEXT_TEXTS = listOf(
    USER_STOPPED_OUTPUT_APP_CONTEXT,
    MESSAGE_INTERRUPTED_APP_CONTEXT,
)

fun List<UIMessage>.finalizeInterruptedGenerationMessages(
    reason: InterruptedGenerationReason,
    detail: String? = null,
    requiresToolResult: (UIMessagePart.ToolCall) -> Boolean = { true },
): List<UIMessage> {
    if (isEmpty()) return this

    val messages = map { it.finishReasoning() }.toMutableList()

    // 1) 占位工具结果: 给没有结果的工具调用补占位 result, 保证工具调用序列合法.
    //    与下方打断标记互不影响, 仅看 "是否已有结果".
    val assistantWithToolCallsIndex = messages.indexOfLast { message ->
        message.role == MessageRole.ASSISTANT &&
            message.getToolCalls().any(requiresToolResult)
    }
    if (assistantWithToolCallsIndex >= 0) {
        appendPlaceholderToolResults(
            messages = messages,
            assistantIndex = assistantWithToolCallsIndex,
            reason = reason,
            detail = detail,
            requiresToolResult = requiresToolResult,
        )
    }

    // 2) 打断标记: 在序列末尾追加一条独立的 user 消息(只含隐藏标记).
    //    用 user 角色注入, 而不是塞进 assistant 正文 —— 否则模型会把这段标记当成
    //    自己生成过的内容, 可能在后续回复里自己再吐一遍中断标记.
    //    仅在该轮确实调过 "需要本地结果" 的工具时才补, 服务端工具调用不打断标记.
    appendInterruptedUserMarker(messages, reason, requiresToolResult)

    return if (messages == this) this else messages
}

/**
 * 给"没有结果"的工具调用补占位的 tool result, 保证工具调用序列合法.
 * 已有真结果的保留, 不影响.
 */
private fun appendPlaceholderToolResults(
    messages: MutableList<UIMessage>,
    assistantIndex: Int,
    reason: InterruptedGenerationReason,
    detail: String?,
    requiresToolResult: (UIMessagePart.ToolCall) -> Boolean,
) {
    var nextIndex = assistantIndex + 1
    while (nextIndex < messages.size && messages[nextIndex].role == MessageRole.TOOL) {
        nextIndex++
    }
    if (nextIndex < messages.size) return // 后面还有非 tool 消息, 序列已是完成态, 不补

    val assistantMessage = messages[assistantIndex]
    val followingToolMessageIndexes = ((assistantIndex + 1) until nextIndex).toList()
    val existingResultIds = (assistantMessage.getToolResults() + followingToolMessageIndexes
        .flatMap { messages[it].getToolResults() })
        .mapNotNull { it.toolCallId.takeIf(String::isNotBlank) }
        .toSet()

    val callStates = mutableListOf<InterruptedToolCallState>()
    var toolCallIndex = 0
    val normalizedParts = assistantMessage.parts.map { part ->
        if (part is UIMessagePart.ToolCall) {
            val resolvedId = part.toolCallId.ifBlank {
                "interrupted_${assistantMessage.id}_${toolCallIndex}"
            }
            toolCallIndex++
            val sanitizedArguments = sanitizeToolCallArguments(part.arguments)
            val normalized = part.copy(
                toolCallId = resolvedId,
                arguments = sanitizedArguments,
            )
            if (requiresToolResult(part)) {
                callStates += InterruptedToolCallState(
                    original = part,
                    normalized = normalized,
                    originalArguments = part.arguments,
                    sanitizedArguments = sanitizedArguments,
                )
            }
            normalized
        } else {
            part
        }
    }
    messages[assistantIndex] = assistantMessage.copy(parts = normalizedParts)

    val missingResults = callStates
        .filter { it.normalized.toolCallId !in existingResultIds }
        .map { state ->
            UIMessagePart.ToolResult(
                toolCallId = state.normalized.toolCallId,
                toolName = state.normalized.toolName,
                content = buildInterruptedToolResultContent(
                    reason = reason,
                    detail = detail,
                    originalArguments = state.originalArguments,
                    sanitizedArguments = state.sanitizedArguments,
                ),
                arguments = parseToolArgumentsOrEmpty(state.sanitizedArguments),
                metadata = state.normalized.metadata ?: state.original.metadata,
            )
        }

    if (missingResults.isNotEmpty()) {
        val targetToolMessageIndex = followingToolMessageIndexes.firstOrNull()
        if (targetToolMessageIndex != null) {
            val toolMessage = messages[targetToolMessageIndex]
            messages[targetToolMessageIndex] = toolMessage.copy(
                parts = toolMessage.parts + missingResults,
            )
        } else {
            messages += UIMessage(
                role = MessageRole.TOOL,
                parts = missingResults,
            )
        }
    }
}

/**
 * 在序列末尾追加一条独立的 user 打断标记消息(只含隐藏标记).
 * 用 user 角色, 而非 assistant 正文 —— 避免模型把标记当成自己生成的内容.
 *
 * 门控(与 appendInterruptedAssistantMarker 原逻辑一致):
 * - 末尾是 assistant: 只要有正文就追加(纯文本半截回复也要告知被打断).
 * - 末尾是 tool: 仅在该轮调过 "需要本地结果" 的工具时才补, 服务端工具调用不打断标记.
 */
private fun appendInterruptedUserMarker(
    messages: MutableList<UIMessage>,
    reason: InterruptedGenerationReason,
    requiresToolResult: (UIMessagePart.ToolCall) -> Boolean,
) {
    if (messages.any { it.isStandaloneInterruptedAppContextMarker() }) return

    val trailingAssistantIndex = messages.indexOfLast { it.role == MessageRole.ASSISTANT }
    val trailingIndex = messages.lastIndex

    // 没有任何 assistant 消息(例如连发时上一条还停在 user 阶段, 未真正开始生成):
    // 不存在可打断的上下文, 直接返回, 避免访问 messages[-1] 导致 IndexOutOfBoundsException.
    if (trailingAssistantIndex < 0) return

    if (trailingAssistantIndex == trailingIndex) {
        // 末尾就是 assistant: 有正文才追加(纯标记/空 assistant 不补, 该场景外层也不会有).
        val hasText = messages[trailingAssistantIndex].parts
            .filterIsInstance<UIMessagePart.Text>()
            .any { it.text.isNotBlank() }
        if (!hasText) return
        messages += UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text(buildInterruptedAppContextSuffix(reason.appContextText))),
        )
        return
    }

    // 末尾不是 assistant(应是 tool): 仅在该轮确实调过 "需要本地结果" 的工具时才补,
    // 服务端工具调用不打断标记.
    val hasLocalToolCall = messages[trailingAssistantIndex]?.getToolCalls()
        ?.any(requiresToolResult) == true
    if (!hasLocalToolCall) return

    messages += UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(buildInterruptedAppContextSuffix(reason.appContextText))),
    )
}

fun UIMessage.isStandaloneInterruptedAppContextMarker(): Boolean {
    if (role != MessageRole.USER) return false
    val texts = parts.filterIsInstance<UIMessagePart.Text>()
    if (texts.size != 1) return false
    val markerText = texts.single().text
    // 单条 Text 且去掉打断标记后为空, 即认定是独立 marker 消息.
    // 不直接比较含 "\n\n" 的前后缀, 以免疫过 trim/stripping 导致误判(如重复 finalize 去重失败).
    return markerText.stripInterruptedAppContextForDisplay().isBlank() &&
        INTERRUPTED_APP_CONTEXT_TEXTS.any { markerText.contains(it) }
}

private data class InterruptedToolCallState(
    val original: UIMessagePart.ToolCall,
    val normalized: UIMessagePart.ToolCall,
    val originalArguments: String,
    val sanitizedArguments: String,
)

private fun sanitizeToolCallArguments(arguments: String): String {
    val normalized = arguments.ifBlank { "{}" }
    return if (runCatching { json.parseToJsonElement(normalized) }.isSuccess) {
        normalized
    } else {
        "{}"
    }
}

private fun parseToolArgumentsOrEmpty(arguments: String): JsonElement {
    return runCatching {
        json.parseToJsonElement(arguments.ifBlank { "{}" })
    }.getOrElse {
        JsonObject(emptyMap())
    }
}

private fun buildInterruptedToolResultContent(
    reason: InterruptedGenerationReason,
    detail: String?,
    originalArguments: String,
    sanitizedArguments: String,
): JsonObject {
    return buildJsonObject {
        put("status", "interrupted")
        put("reason", reason.wireValue)
        put("message", detail?.takeIf { it.isNotBlank() } ?: reason.defaultMessage)
        if (originalArguments.isNotBlank() && originalArguments != sanitizedArguments) {
            put("partialArguments", JsonPrimitive(originalArguments))
        }
    }
}

private val InterruptedGenerationReason.wireValue: String
    get() = when (this) {
        InterruptedGenerationReason.UserCancelled -> "user_cancelled"
        InterruptedGenerationReason.GenerationFailed -> "generation_failed"
        InterruptedGenerationReason.ReplacedByNewRequest -> "replaced_by_new_request"
    }

private val InterruptedGenerationReason.defaultMessage: String
    get() = when (this) {
        InterruptedGenerationReason.UserCancelled -> "Generation was stopped by the user before this tool call finished."
        InterruptedGenerationReason.GenerationFailed -> "Generation failed before this tool call finished."
        InterruptedGenerationReason.ReplacedByNewRequest -> "Generation was replaced by a new request before this tool call finished."
    }

private val InterruptedGenerationReason.appContextText: String
    get() = when (this) {
        InterruptedGenerationReason.UserCancelled,
        InterruptedGenerationReason.ReplacedByNewRequest,
        -> USER_STOPPED_OUTPUT_APP_CONTEXT
        InterruptedGenerationReason.GenerationFailed -> MESSAGE_INTERRUPTED_APP_CONTEXT
    }

private fun buildInterruptedAppContextSuffix(appContext: String): String {
    // 标记现在是独立的 user 消息, 不再贴在 assistant 正文后面, 不需要前置换行与正文隔开.
    return "$INTERRUPTED_APP_CONTEXT_START_TAG$appContext$INTERRUPTED_APP_CONTEXT_END_TAG"
}

// 标记集合固定，正则只编译一次；流式渲染路径上本函数调用极其频繁。
// 兼容新旧两种写法: 旧版标记可能贴在 assistant 正文后(带前导换行), 新版为裸标记.
// \\n* 同时吃掉可能的 0 个或多个前导换行, 避免剥离后残留空行.
private val interruptedAppContextStripRegexes: List<Regex> by lazy {
    INTERRUPTED_APP_CONTEXT_TEXTS.map { appContext ->
        Regex("\\n*" + Regex.escape(buildInterruptedAppContextSuffix(appContext)))
    }
}

fun String.stripInterruptedAppContextForDisplay(): String {
    if (!contains(INTERRUPTED_APP_CONTEXT_START_TAG)) return this
    return interruptedAppContextStripRegexes.fold(this) { text, regex ->
        text.replace(regex, "")
    }
}

fun List<UIMessage>.repairToolCallMessageSequence(
    requiresToolResult: (UIMessagePart.ToolCall) -> Boolean = { true },
): List<UIMessage> {
    if (none { it.role == MessageRole.TOOL || it.getToolCalls().isNotEmpty() }) return this

    val repaired = mutableListOf<UIMessage>()
    var index = 0
    var changed = false

    while (index < size) {
        val message = this[index]
        if (message.role == MessageRole.ASSISTANT && message.getToolCalls().any(requiresToolResult)) {
            val followingToolMessages = mutableListOf<UIMessage>()
            var nextIndex = index + 1
            while (nextIndex < size && this[nextIndex].role == MessageRole.TOOL) {
                followingToolMessages += this[nextIndex]
                nextIndex++
            }

            val resultIds = followingToolMessages
                .flatMap { it.getToolResults() }
                .mapNotNull { it.toolCallId.takeIf(String::isNotBlank) }
                .toSet()
            val matchedRequiredIds = message.getToolCalls()
                .filter(requiresToolResult)
                .filter { it.toolName.isNotBlank() }
                .mapNotNull { call -> call.toolCallId.takeIf { it.isNotBlank() && it in resultIds } }
                .toSet()

            val repairedParts = message.parts.mapNotNull { part ->
                if (
                    part is UIMessagePart.ToolCall &&
                    // 无名调用是流式合并救不回来的残片，序列化回服务端会因
                    // 空 function.name 被严格网关拒绝（400），必须连同其结果一起剔除
                    (part.toolName.isBlank() ||
                        (requiresToolResult(part) && part.toolCallId !in matchedRequiredIds))
                ) {
                    changed = true
                    null
                } else {
                    part
                }
            }
            val repairedMessage = message.copy(parts = repairedParts)
            if (repairedMessage.hasProviderUploadableParts()) {
                repaired += repairedMessage
            } else {
                changed = true
            }

            followingToolMessages.forEach { toolMessage ->
                val repairedToolResults = toolMessage.getToolResults()
                    .filter { it.toolCallId in matchedRequiredIds }
                if (repairedToolResults.isEmpty()) {
                    changed = true
                } else {
                    val repairedToolMessage = toolMessage.copy(parts = repairedToolResults)
                    if (repairedToolMessage != toolMessage) changed = true
                    repaired += repairedToolMessage
                }
            }

            index = nextIndex
            continue
        }

        if (message.role == MessageRole.TOOL) {
            changed = true
            index++
            continue
        }

        repaired += message
        index++
    }

    return if (changed) repaired else this
}

private fun UIMessage.hasProviderUploadableParts(): Boolean {
    return parts.any { part ->
        when (part) {
            is UIMessagePart.Text -> part.text.isNotBlank()
            is UIMessagePart.Image -> part.url.isNotBlank()
            is UIMessagePart.Video -> part.url.isNotBlank()
            is UIMessagePart.Audio -> part.url.isNotBlank()
            is UIMessagePart.Document -> part.url.isNotBlank()
            is UIMessagePart.ToolCall -> true
            is UIMessagePart.ToolResult -> true
            else -> false
        }
    }
}

@Serializable
enum class ToolApprovalState {
    Pending,
    Approved,
    Rejected,
}

@Serializable
enum class AskUserState {
    Pending,
    Answered,
    Dismissed,
}

@Serializable
sealed class UIMessagePart {
    abstract val priority: Int
    abstract val metadata: JsonObject?

    @Serializable
    data class Text(
        val text: String,
        override var metadata: JsonObject? = null
    ) : UIMessagePart() {
        override val priority: Int = 0
    }

    @Serializable
    data class Image(
        val url: String,
        override var metadata: JsonObject? = null
    ) : UIMessagePart() {
        override val priority: Int = 1
    }

    @Serializable
    data class Video(
        val url: String,
        override var metadata: JsonObject? = null
    ) : UIMessagePart() {
        override val priority: Int = 1
    }

    @Serializable
    data class Audio(
        val url: String,
        override var metadata: JsonObject? = null
    ) : UIMessagePart() {
        override val priority: Int = 1
    }

    @Serializable
    data class Document(
        val url: String,
        val fileName: String,
        val mime: String = "text/*",
        override var metadata: JsonObject? = null
    ) : UIMessagePart() {
        override val priority: Int = 1
    }

    @Serializable
    data class Reasoning(
        val reasoning: String,
        val createdAt: Instant = Clock.System.now(),
        val finishedAt: Instant? = Clock.System.now(),
        override var metadata: JsonObject? = null
    ) : UIMessagePart() {
        override val priority: Int = -1
    }

    @Serializable
    data class Thinking(
        val thinking: String,
        val createdAt: Instant = Clock.System.now(),
        val finishedAt: Instant? = Clock.System.now(),
        override var metadata: JsonObject? = null
    ) : UIMessagePart() {
        override val priority: Int = -1
    }

    @Serializable
    data class WebSearch(
        val callId: String,
        val status: WebSearchStatus = WebSearchStatus.InProgress,
        val action: WebSearchAction = WebSearchAction.Unknown,
        val queries: List<String> = emptyList(),
        val url: String? = null,
        val pattern: String? = null,
        val sources: List<UIMessageAnnotation.UrlCitation> = emptyList(),
        val createdAt: Instant = Clock.System.now(),
        val finishedAt: Instant? = null,
        override var metadata: JsonObject? = null,
    ) : UIMessagePart() {
        fun merge(other: WebSearch): WebSearch {
            return copy(
                status = other.status,
                action = if (other.action == WebSearchAction.Unknown) action else other.action,
                queries = other.queries.takeIf { it.isNotEmpty() } ?: queries,
                url = other.url?.takeIf { it.isNotBlank() } ?: url,
                pattern = other.pattern?.takeIf { it.isNotBlank() } ?: pattern,
                sources = (sources + other.sources).distinctBy { it.url },
                finishedAt = other.finishedAt ?: finishedAt ?: when (other.status) {
                    WebSearchStatus.Completed,
                    WebSearchStatus.Failed,
                        -> Clock.System.now()

                    WebSearchStatus.InProgress,
                    WebSearchStatus.Searching,
                        -> null
                },
                metadata = mergeMetadata(metadata, other.metadata),
            )
        }

        override val priority: Int = 0

        private fun mergeMetadata(
            existing: JsonObject?,
            incoming: JsonObject?,
        ): JsonObject? {
            if (incoming == null) return existing
            if (existing == null) return incoming
            return buildJsonObject {
                existing.forEach { (key, value) -> put(key, value) }
                incoming.forEach { (key, value) -> put(key, value) }
            }
        }
    }

    @Deprecated("Deprecated")
    @Serializable
    data object Search : UIMessagePart() {
        override val priority: Int = 0
        override var metadata: JsonObject? = null
    }

    @Serializable
    data class ToolCall(
        val toolCallId: String,
        val toolName: String,
        val arguments: String,
        val index: Int? = null,
        override var metadata: JsonObject? = null
    ) : UIMessagePart() {
        fun merge(other: ToolCall): ToolCall {
            return ToolCall(
                toolCallId = toolCallId.ifBlank { other.toolCallId },
                toolName = mergeStreamedToolName(toolName, other.toolName),
                arguments = arguments + other.arguments,
                index = index ?: other.index,
                metadata = if(other.metadata != null) other.metadata else metadata,
            )
        }

        override val priority: Int = 0

        companion object {
            // 部分 OpenAI 兼容网关在每个增量里重复携带完整工具名，直接拼接会得到
            // "namename..."，因此相同或空白的增量不再拼接
            private fun mergeStreamedToolName(current: String, delta: String): String = when {
                delta.isBlank() -> current
                current.isBlank() -> delta
                current == delta -> current
                else -> current + delta
            }
        }
    }

    @Serializable
    data class ToolApproval(
        val toolCallId: String,
        val toolName: String,
        val state: ToolApprovalState = ToolApprovalState.Pending,
        override var metadata: JsonObject? = null,
    ) : UIMessagePart() {
        override val priority: Int = 0
    }

    @Serializable
    data class AskUserQuestion(
        val question: String,
        val options: List<String>,
    )

    @Serializable
    data class AskUser(
        val toolCallId: String,
        val question: String,
        val options: List<String>,
        val questions: List<AskUserQuestion>? = null,
        val state: AskUserState = AskUserState.Pending,
        val answer: String? = null,
        val answers: List<String>? = null,
        override var metadata: JsonObject? = null,
    ) : UIMessagePart() {
        override val priority: Int = 0
    }

    @Serializable
    data class ToolResult(
        val toolCallId: String,
        val toolName: String,
        val content: JsonElement,
        val arguments: JsonElement,
        val images: List<ToolResultImage> = emptyList(),
        override var metadata: JsonObject? = null
    ) : UIMessagePart() {
        override val priority: Int = 0
    }

    /**
     * 追问引用：用户长按选中助手消息中的文本片段后"追问"时携带的引用内容。
     * 仅用于 UI 展示与发送给 provider 时的提示词拼接，本身不参与模型多模态输入。
     * [text] 为被引用的原文片段（完整保留，不截断）；20 字省略号截断只发生在显示层。
     */
    @Serializable
    @SerialName("quoted_follow_up")
    data class QuotedFollowUp(
        val text: String,
        override var metadata: JsonObject? = null
    ) : UIMessagePart() {
        override val priority: Int = -2
    }
}

/**
 * 图片工具结果的本地副本。
 *
 * 工具结果本身只保存文件地址和元信息，不把 base64 二进制塞进会话数据库；各 provider
 * 在组装请求时才按自身协议读取并编码。旧会话反序列化时 [UIMessagePart.ToolResult.images]
 * 会使用默认空列表。
 */
@Serializable
data class ToolResultImage(
    val url: String,
    val mimeType: String,
    val fileName: String,
    /** false 表示仅供界面预览（例如本次调用时模型不支持图片输入）。 */
    val includeInModel: Boolean = true,
)

/**
 * 在模型切换为纯文本输入时，保留原始会话数据和界面缩略图，但把发送给模型的结果改成
 * 可识别的能力错误。这样再次切换回支持图片的模型时，仍可继续发送原图片。
 */
fun UIMessagePart.ToolResult.contentForModelInput(model: Model): JsonElement {
    if (images.isEmpty() || Modality.IMAGE in model.inputModalities) return content
    val objectContent = content as? JsonObject ?: JsonObject(emptyMap())
    if (objectContent["error_code"]?.jsonPrimitive?.contentOrNull == "model_image_input_unsupported") {
        return objectContent
    }
    return JsonObject(
        objectContent + mapOf(
            "ok" to JsonPrimitive(false),
            "error_code" to JsonPrimitive("model_image_input_unsupported"),
            "error" to JsonPrimitive(
                "The current model does not support image input. Switch to an image-capable model to inspect this image.",
            ),
        ),
    )
}

@Serializable
enum class WebSearchStatus {
    InProgress,
    Searching,
    Completed,
    Failed,
}

@Serializable
enum class WebSearchAction {
    Search,
    OpenPage,
    FindInPage,
    Unknown,
}

const val PERSISTENT_CONTEXT_IMAGE_METADATA_KEY = "persistent_context_image"

fun UIMessagePart.Image.asPersistentContextImage(): UIMessagePart.Image {
    return copy(
        metadata = buildJsonObject {
            metadata?.forEach { (key, value) -> put(key, value) }
            put(PERSISTENT_CONTEXT_IMAGE_METADATA_KEY, true)
        },
    )
}

fun UIMessagePart.Image.isPersistentContextImage(): Boolean {
    return metadata?.get(PERSISTENT_CONTEXT_IMAGE_METADATA_KEY)
        ?.jsonPrimitive
        ?.contentOrNull
        ?.toBoolean() == true
}

fun List<UIMessagePart>.toSortedMessageParts(): List<UIMessagePart> {
    return sortedBy { it.priority }
}

fun UIMessage.finishReasoning(): UIMessage {
    return copy(
        parts = parts.map { part ->
            when (part) {
                is UIMessagePart.Reasoning -> {
                    if (part.finishedAt == null) {
                        part.copy(
                            finishedAt = Clock.System.now()
                        )
                    } else {
                        part
                    }
                }

                else -> part
            }
        }
    )
}

@Serializable
sealed class UIMessageAnnotation {
    @Serializable
    @SerialName("url_citation")
    data class UrlCitation(
        val title: String,
        val url: String
    ) : UIMessageAnnotation()
}

@Serializable
data class MessageChunk(
    val id: String,
    val model: String,
    val choices: List<UIMessageChoice>,
    val usage: TokenUsage? = null,
    val finishReasons: Set<String> = emptySet(),
    val rawResponse: String? = null,
)

@Serializable
data class UIMessageChoice(
    val index: Int,
    val delta: UIMessage?,
    val message: UIMessage?,
    val finishReason: String?
)
