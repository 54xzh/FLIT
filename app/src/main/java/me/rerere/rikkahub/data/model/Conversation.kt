package me.rerere.rikkahub.data.model

import android.net.Uri
import androidx.core.net.toUri
import kotlinx.serialization.Serializable
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.InstantSerializer
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import java.time.Instant
import kotlin.uuid.Uuid

private const val CHAT_UPLOAD_SEGMENT = "/upload/"

internal fun collectChatUploadFileUrls(messageNodes: List<MessageNode>): List<String> {
    return messageNodes
        .asSequence()
        .flatMap { node -> node.messages.asSequence() }
        .flatMap { message -> message.parts.asSequence() }
        .mapNotNull { part ->
            when (part) {
                is UIMessagePart.Image -> part.url
                is UIMessagePart.Document -> part.url
                is UIMessagePart.Video -> part.url
                is UIMessagePart.Audio -> part.url
                else -> null
            }
        }
        .filter { url -> url.startsWith("file://") && url.contains(CHAT_UPLOAD_SEGMENT) }
        .toList()
}

/**
 * 精简版的会话信息，用于列表显示，不包含消息内容以避免 OOM
 */
data class ConversationSummary(
    val id: Uuid,
    val assistantId: Uuid,
    val title: String,
    val isPinned: Boolean = false,
    val createAt: Instant,
    val updateAt: Instant,
    val isConsolidated: Boolean = false,
)

@Serializable
enum class SessionMemoryPlacement {
    SYSTEM_PROMPT_AFTER,
    BEFORE_LATEST_MESSAGE;

    companion object {
        fun fromToolValue(value: String?): SessionMemoryPlacement {
            return when (value?.trim()?.lowercase()) {
                "system_prompt_after", "after_system_prompt", "system-prompt-after",
                "after-system-prompt", "system", "stable" -> SYSTEM_PROMPT_AFTER
                "before_latest_message", "before_latest_user_message", "before-latest-message",
                "before-latest-user-message", "latest", "dynamic" -> BEFORE_LATEST_MESSAGE
                else -> BEFORE_LATEST_MESSAGE
            }
        }
    }
}

@Serializable
data class SessionMemory(
    val id: Int,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val placement: SessionMemoryPlacement = SessionMemoryPlacement.BEFORE_LATEST_MESSAGE,
)

@Serializable
data class Conversation(
    val id: Uuid = Uuid.Companion.random(),
    val assistantId: Uuid,
    val title: String = "",
    val messageNodes: List<MessageNode>,
    val truncateIndex: Int = -1,
    val chatSuggestions: List<String> = emptyList(),
    val isPinned: Boolean = false,
    val enabledModeIds: Set<Uuid> = emptySet(), // Per-chat enabled modes
    val explicitSkillContextIds: Set<Uuid> = emptySet(), // Per-chat Skill.md files injected directly
    @Serializable(with = InstantSerializer::class)
    val createAt: Instant = Instant.now(),
    @Serializable(with = InstantSerializer::class)
    val updateAt: Instant = Instant.now(),
    val isConsolidated: Boolean = false,
    val contextSummary: String? = null, // Summary of pruned messages
    val contextSummaryUpToIndex: Int = -1, // Messages 0..N were summarized into contextSummary
    val lastPruneTime: Long = 0L, // Timestamp of last auto-prune
    val lastPruneMessageCount: Int = 0, // Messages pruned in last auto-prune
    val lastRefreshTime: Long = 0L, // Timestamp of last manual refresh
    val contextSummaryBoundaries: List<Int> = emptyList(), // History of summary boundary indices
    val contextSummaryPendingBoundaryIndex: Int = -1, // In-memory marker for active context compression divider
    val sessionMemories: List<SessionMemory> = emptyList(), // Memories that only apply to this conversation
    val loadedNodeStartIndex: Int = 0, // Absolute start index of currently loaded node window
    val totalMessageNodeCount: Int = 0, // Total node count stored in DB for this conversation
) {
    val hasOlderHistoryNodes: Boolean
        get() = loadedNodeStartIndex > 0

    val files: List<Uri>
        get() {
            return collectChatUploadFileUrls(messageNodes)
                .map { url -> url.toUri() }
        }

    /**
     *  当前选中的 message
     */
    val currentMessages
        get(): List<UIMessage> {
            return messageNodes.map { node -> node.messages[node.selectIndex] }
        }

    fun getMessageNodeByMessage(message: UIMessage): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.contains(message) }
    }

    fun getMessageNodeByMessageId(messageId: Uuid): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.any { it.id == messageId } }
    }

    fun updateCurrentMessages(messages: List<UIMessage>): Conversation {
        val newNodes = this.messageNodes.toMutableList()

        messages.forEachIndexed { index, message ->
            val node = newNodes
                .getOrElse(index) { message.toMessageNode() }

            val newMessages = node.messages.toMutableList()
            var newMessageIndex = node.selectIndex
            if (newMessages.any { it.id == message.id }) {
                newMessages[newMessages.indexOfFirst { it.id == message.id }] = message
            } else {
                newMessages.add(message)
                newMessageIndex = newMessages.lastIndex
            }

            val newNode = node.copy(
                messages = newMessages,
                selectIndex = newMessageIndex
            )

            // 更新newNodes
            if (index > newNodes.lastIndex) {
                newNodes.add(newNode)
            } else {
                newNodes[index] = newNode
            }
        }

        return this.copy(
            messageNodes = newNodes
        )
    }

    companion object {
        fun ofId(
            id: Uuid,
            assistantId: Uuid = DEFAULT_ASSISTANT_ID,
            messages: List<MessageNode> = emptyList(),
        ) = Conversation(
            id = id,
            assistantId = assistantId,
            messageNodes = messages
        )
    }
}

@Serializable
data class MessageNode(
    val id: Uuid = Uuid.random(),
    val messages: List<UIMessage>,
    val selectIndex: Int = 0,
) {
    val currentMessage get() = if (messages.isEmpty() || selectIndex !in messages.indices) {
        throw IllegalStateException("MessageNode has no valid current message: messages.size=${messages.size}, selectIndex=$selectIndex")
    } else {
        messages[selectIndex]
    }

    val role get() = messages.firstOrNull()?.role ?: MessageRole.USER

    companion object {
        fun of(message: UIMessage) = MessageNode(
            messages = listOf(message),
            selectIndex = 0
        )
    }
}

fun UIMessage.toMessageNode(): MessageNode {
    return MessageNode(
        messages = listOf(this),
        selectIndex = 0
    )
}
