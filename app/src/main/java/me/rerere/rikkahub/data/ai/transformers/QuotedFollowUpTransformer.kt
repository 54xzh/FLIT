package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * 追问引用转换器：把用户消息中携带的 [UIMessagePart.QuotedFollowUp] 转换为
 * 发送给 provider 的英文提示词前缀，并从消息中移除该自定义 part（模型不识别它）。
 *
 * 仅作用于 [MessageRole.USER] 消息，且只影响发给 provider 的 internalMessages，
 * 不改动数据库中存储的原始消息，因此 UI 上仍能展示引用标记。
 *
 * 提示词文案不进入 UI / 数据库：UI 上的引用行来自持久化的 QuotedFollowUp part，
 * 而这里的提示词是 transformer 运行期临时拼接、只发给 provider 的。
 *
 * QuotedFollowUp.text 存的是完整原文（非 20 字截断），因此拼出的提示词包含完整引用上下文；
 * 20 字省略号截断只发生在显示层。
 */
object QuotedFollowUpTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        if (messages.none { it.hasQuotedFollowUp() }) return messages
        return messages.map { message ->
            if (message.role != MessageRole.USER || !message.hasQuotedFollowUp()) {
                message
            } else {
                rewriteUserMessage(message)
            }
        }
    }

    private fun UIMessage.hasQuotedFollowUp(): Boolean =
        parts.any { it is UIMessagePart.QuotedFollowUp }

    private fun rewriteUserMessage(message: UIMessage): UIMessage {
        val quoted = message.parts.filterIsInstance<UIMessagePart.QuotedFollowUp>()
            .joinToString("\n") { it.text }
            .trim()
        if (quoted.isBlank()) {
            return message.copy(parts = message.parts.filter { it !is UIMessagePart.QuotedFollowUp })
        }
        val prefix = buildPromptPrefix(quoted)
        val rebuiltParts = buildList {
            add(UIMessagePart.Text(prefix))
            message.parts.forEach { part ->
                when (part) {
                    is UIMessagePart.QuotedFollowUp -> Unit // 已转为提示词前缀，移除
                    is UIMessagePart.Text -> {
                        if (part.text.isNotBlank()) add(part)
                    }
                    else -> add(part)
                }
            }
        }
        return message.copy(parts = rebuiltParts)
    }

    private fun buildPromptPrefix(quoted: String): String = buildString {
        appendLine("The user is following up on the following quoted text:")
        appendLine("> ${quoted.replace("\n", "\n> ")}")
        appendLine()
    }
}