package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.ai.core.ReasoningLevel
import kotlin.uuid.Uuid

@Serializable
data class GroupChatTemplate(
    val id: Uuid = Uuid.random(),
    val name: String = "",
    val intro: String = "",
    val hostModelId: Uuid? = null,
    val hostSystemPrompt: String = "",
    val integrationModelId: Uuid? = null,
    val consolidationDelayMinutes: Int = 30,
    val seats: List<GroupChatSeat> = emptyList(),
)

@Serializable
data class GroupChatSeat(
    val id: Uuid = Uuid.random(),
    val assistantId: Uuid,
    val instanceNumber: Int = 1,
    val overrides: GroupChatSeatOverrides = GroupChatSeatOverrides(),
    val defaultEnabled: Boolean = true,
)

@Serializable
data class GroupChatSeatOverrides(
    val chatModelId: Uuid? = null,
    val systemPrompt: String? = null,
    val reasoningLevel: ReasoningLevel? = null,
    val maxTokens: Int? = null,
    val searchEnabled: Boolean = false,
    val memoryEnabled: Boolean = false,
    val searchMode: AssistantSearchMode = AssistantSearchMode.Off,
    val preferBuiltInSearch: Boolean = false,
    val mcpServerIds: Set<Uuid> = emptySet(),
)
