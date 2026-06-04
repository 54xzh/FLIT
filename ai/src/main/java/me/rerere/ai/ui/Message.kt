package me.rerere.ai.ui

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
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
    val usedSessionMemories: List<UsedSessionMemory>? = null // Conversation-scoped memories used in this message
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
                        val existingReasoningIndex = acc.indexOfLast { it is UIMessagePart.Reasoning }
                        val existingReasoningPart = acc.getOrNull(existingReasoningIndex) as? UIMessagePart.Reasoning
                        if (existingReasoningPart == null || existingReasoningIndex < 0) {
                            acc + deltaPart
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
                                metadata = existingReasoningPart.metadata,
                            ).also {
                                if (deltaPart.metadata != null) {
                                    it.metadata = deltaPart.metadata // 更新metadata
                                    println("更新metadata: ${json.encodeToString(deltaPart)}")
                                }
                            }
                            acc.mapIndexed { index, part ->
                                if (index == existingReasoningIndex) {
                                    mergedReasoning
                                } else {
                                    part
                                }
                            }
                        }
                    }

                    is UIMessagePart.ToolCall -> {
                        if (deltaPart.toolCallId.isBlank()) {
                            val lastToolCall =
                                acc.lastOrNull { it is UIMessagePart.ToolCall } as? UIMessagePart.ToolCall
                            if (lastToolCall == null || lastToolCall.toolCallId.isBlank()) {
                                acc + deltaPart.copy()
                            } else {
                                acc.map { part ->
                                    if (part == lastToolCall && part is UIMessagePart.ToolCall) {
                                        part.merge(deltaPart)
                                    } else part
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
                        println("delta part append not supported: $deltaPart")
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
            val newAnnotations = delta.annotations.ifEmpty {
                annotations
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
        it !is UIMessagePart.Reasoning
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
            is UIMessagePart.Text -> message.text.isBlank()
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
                .mapNotNull { call -> call.toolCallId.takeIf { it.isNotBlank() && it in resultIds } }
                .toSet()

            val repairedParts = message.parts.mapNotNull { part ->
                if (
                    part is UIMessagePart.ToolCall &&
                    requiresToolResult(part) &&
                    part.toolCallId !in matchedRequiredIds
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
        override var metadata: JsonObject? = null
    ) : UIMessagePart() {
        fun merge(other: ToolCall): ToolCall {
            return ToolCall(
                toolCallId = toolCallId,
                toolName = toolName + other.toolName,
                arguments = arguments + other.arguments,
                metadata = if(other.metadata != null) other.metadata else metadata,
            )
        }

        override val priority: Int = 0
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
        override var metadata: JsonObject? = null
    ) : UIMessagePart() {
        override val priority: Int = 0
    }
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
