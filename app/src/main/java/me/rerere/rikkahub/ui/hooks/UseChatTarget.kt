package me.rerere.rikkahub.ui.hooks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.ChatTarget
import me.rerere.rikkahub.data.model.GroupChatTemplate

@Composable
fun rememberChatTargetState(
    settings: Settings,
    onSelectTarget: (ChatTarget) -> Unit,
): ChatTargetState {
    return remember(settings, onSelectTarget) {
        ChatTargetState(settings, onSelectTarget)
    }
}

class ChatTargetState(
    private val settings: Settings,
    // 切换目标的专用写入通道（走 SettingsStore.updateChatTarget 的锁内读改写路径）。
    // 不提供「整份 settings 快照覆盖写」的回退：组合期捕获的快照可能落后于最新设置，
    // 用快照覆盖写正是「切换助手后归属错乱」竞态的来源之一。
    private val onSelectTarget: (ChatTarget) -> Unit,
) {
    val currentTarget: ChatTarget = settings.chatTarget

    val currentAssistant: Assistant?
        get() = when (val target = currentTarget) {
            is ChatTarget.Assistant -> settings.assistants.find { it.id == target.assistantId }
            is ChatTarget.GroupChat -> null
        }

    val currentGroupChat: GroupChatTemplate?
        get() = when (val target = currentTarget) {
            is ChatTarget.Assistant -> null
            is ChatTarget.GroupChat -> settings.groupChatTemplates.find { it.id == target.templateId }
        }

    fun selectAssistant(assistant: Assistant) {
        onSelectTarget(ChatTarget.Assistant(assistant.id))
    }

    fun selectGroupChat(template: GroupChatTemplate) {
        onSelectTarget(ChatTarget.GroupChat(template.id))
    }
}
