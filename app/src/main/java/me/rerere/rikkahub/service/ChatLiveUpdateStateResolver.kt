package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.AskUserState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

internal object ChatLiveUpdateStateResolver {
    fun resolve(messages: List<UIMessage>): ChatLiveUpdateState {
        val lastMessage = messages.lastOrNull() ?: return ChatLiveUpdateState.WAITING
        if (lastMessage.role == MessageRole.TOOL) return ChatLiveUpdateState.TOOL_CALL

        val hasPendingAskUser = messages.asReversed().any { msg ->
            msg.parts.any { it is UIMessagePart.AskUser && it.state == AskUserState.Pending }
        }
        if (hasPendingAskUser) return ChatLiveUpdateState.WAITING_FOR_ANSWER

        val lastAssistant = messages.lastOrNull { it.role == MessageRole.ASSISTANT }
            ?: return ChatLiveUpdateState.WAITING

        val assistantText = lastAssistant.toContentText().trim()
        val hasToolCall = lastAssistant.getToolCalls().isNotEmpty()
        if (hasToolCall && assistantText.isBlank()) return ChatLiveUpdateState.TOOL_CALL

        if (assistantText.isNotBlank()) return ChatLiveUpdateState.OUTPUT

        val hasActiveReasoning = lastAssistant.parts
            .filterIsInstance<UIMessagePart.Reasoning>()
            .any { it.finishedAt == null }
        val hasActiveThinking = lastAssistant.parts
            .filterIsInstance<UIMessagePart.Thinking>()
            .any { it.finishedAt == null }
        if (hasActiveReasoning || hasActiveThinking) return ChatLiveUpdateState.INFERENCE

        return ChatLiveUpdateState.WAITING
    }
}

