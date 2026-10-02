package me.rerere.rikkahub.data.interactive

import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull

/** 从当前分支原始消息推导版本，不让列表可见性决定旧卡片是否可提交。 */
fun latestInteractiveMessage(messages: List<UIMessage>, source: UIMessage, surfaceId: String): UIMessage? =
    messages.asReversed().firstOrNull { candidate ->
        candidate.role == MessageRole.ASSISTANT &&
            candidate.speakerAssistantId == source.speakerAssistantId && candidate.speakerSeatId == source.speakerSeatId &&
            candidate.parts.filterIsInstance<UIMessagePart.Text>().any { part ->
                if (!part.text.contains("a2ui", ignoreCase = true)) false else interactiveFences(part.text).any { fence ->
                    fence.code.lineSequence().any { line ->
                        val obj = runCatching { me.rerere.rikkahub.utils.JsonInstant.parseToJsonElement(line) as? JsonObject }.getOrNull()
                        val create = obj?.get("createSurface") as? JsonObject
                        create?.get("surfaceId")?.jsonPrimitiveOrNull?.contentOrNull == surfaceId
                    }
                }
            }
    }

fun interactiveSubmissionExists(messages: List<UIMessage>, messageId: String, partIndex: Int, offset: Int, fingerprint: String): Boolean =
    messages.any { message -> message.parts.any { part ->
        val action = part.metadata?.get(INTERACTIVE_ACTION_METADATA) as? JsonObject ?: return@any false
        action.get("sourceMessageId")?.jsonPrimitiveOrNull?.contentOrNull == messageId &&
            action["partIndex"]?.jsonPrimitiveOrNull?.intOrNull == partIndex &&
            action["blockOffset"]?.jsonPrimitiveOrNull?.intOrNull == offset &&
            action["fingerprint"]?.jsonPrimitiveOrNull?.contentOrNull == fingerprint
    } }

fun List<UIMessage>.withInteractiveActionsForModel(): List<UIMessage> = map { message ->
    if (message.role != MessageRole.USER) message else message.copy(parts = message.parts.map { part ->
        val event = part.metadata?.get(INTERACTIVE_ACTION_METADATA)
        if (part is UIMessagePart.Text && event is JsonObject) {
            part.copy(text = part.text + "\n\n<a2ui_action>\n" + event.toString() + "\n</a2ui_action>")
        } else part
    })
}

data class InteractiveRequestRoute(val assistantId: kotlin.uuid.Uuid?, val seatId: kotlin.uuid.Uuid?)

fun interactiveRequestRoute(messages: List<UIMessage>): InteractiveRequestRoute? {
    val user = messages.lastOrNull { it.role == MessageRole.USER } ?: return null
    val event = user.parts.filterIsInstance<UIMessagePart.Text>().firstNotNullOfOrNull {
        it.metadata?.get(INTERACTIVE_ACTION_METADATA) as? JsonObject
    } ?: return null
    fun id(key: String): kotlin.uuid.Uuid? = event[key]?.jsonPrimitiveOrNull?.contentOrNull?.let {
        runCatching { kotlin.uuid.Uuid.parse(it) }.getOrNull()
    }
    return InteractiveRequestRoute(id("sourceAssistantId"), id("sourceSeatId"))
}

/** 仅为本轮新生成的卡片记录原助手，不改变既有普通消息的展示身份。 */
fun List<UIMessage>.withInteractiveOwners(existingIds: Set<kotlin.uuid.Uuid>, assistantId: kotlin.uuid.Uuid): List<UIMessage> = map { message ->
    if (message.role == MessageRole.ASSISTANT && message.speakerAssistantId == null && message.id !in existingIds &&
        message.parts.filterIsInstance<UIMessagePart.Text>().any { part ->
            part.text.contains("a2ui", true) && interactiveFences(part.text).isNotEmpty()
        }) message.copy(speakerAssistantId = assistantId) else message
}
