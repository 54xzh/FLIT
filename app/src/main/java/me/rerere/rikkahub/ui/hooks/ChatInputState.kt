package me.rerere.rikkahub.ui.hooks

import android.net.Uri
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

@Composable
fun rememberChatInputState(
    textContent: String = "",
    message: List<UIMessagePart> = emptyList(),
    loading: Boolean = false,
): ChatInputState {
    return rememberSaveable(textContent, message, loading, saver = ChatInputStateSaver) {
        ChatInputState().apply {
            this.textContent.setTextAndPlaceCursorAtEnd(textContent)
            this.messageContent = message
            this.loading = loading
        }
    }
}

class ChatInputState {
    val textContent = TextFieldState()
    var messageContent by mutableStateOf(listOf<UIMessagePart>())
    var editingMessage by mutableStateOf<Uuid?>(null)
    var loading by mutableStateOf(false)
    // fork 用户消息后进入的"编辑并发送"模式: 发送时覆盖目标用户消息并直接触发 AI 补全
    var forkEditMode by mutableStateOf(false)
    // 自增信号: 变化时驱动 ChatInput 主动请求焦点并弹出输入法
    var requestFocusSignal by mutableStateOf(0)
    // 追问引用: 用户长按选中助手消息文本后点"追问"时带上的引用片段（完整原文，不截断）。
    // 仅在此输入框会话中存活，发送后转为 UIMessagePart.QuotedFollowUp 存入消息；
    // 编辑既有消息时由 setContents 从已有 parts 中还原。
    // 显示层（胶囊/气泡引用行）才做 20 字省略号截断，原文完整保留给数据库与模型。
    var quotedFollowUp by mutableStateOf<String?>(null)

    fun clearInput() {
        textContent.setTextAndPlaceCursorAtEnd("")
        messageContent = emptyList()
        editingMessage = null
        forkEditMode = false
        quotedFollowUp = null
    }

    fun requestFocus() {
        requestFocusSignal += 1
    }

    fun isEditing() = editingMessage != null

    fun setMessageText(text: String) {
        textContent.setTextAndPlaceCursorAtEnd(text)
    }

    fun appendText(content: String) {
        textContent.setTextAndPlaceCursorAtEnd(textContent.text.toString() + content)
    }

    fun replaceText(start: Int, end: Int, replacement: String) {
        textContent.edit {
            val safeStart = start.coerceIn(0, length)
            val safeEnd = end.coerceIn(safeStart, length)
            replace(safeStart, safeEnd, replacement)
        }
    }

    fun insertTextAtCursor(content: String) {
        textContent.edit {
            val rangeStart = kotlin.math.min(selection.start, selection.end).coerceIn(0, length)
            val rangeEnd = kotlin.math.max(selection.start, selection.end).coerceIn(rangeStart, length)
            replace(rangeStart, rangeEnd, content)
        }
    }

    fun applyQuotedFollowUp(text: String) {
        // 存完整原文（仅做超长兜底截断），20 字省略号截断只发生在显示层，
        // 保证数据库与模型拿到足够完整的引用上下文
        quotedFollowUp = clipQuotedFollowUpRaw(text).takeIf { it.isNotBlank() }
    }

    fun clearQuotedFollowUp() {
        quotedFollowUp = null
    }

    fun setContents(contents: List<UIMessagePart>) {
        val text = contents.filterIsInstance<UIMessagePart.Text>().joinToString { it.text }
        textContent.setTextAndPlaceCursorAtEnd(text)
        messageContent = contents.filter { it !is UIMessagePart.Text && it !is UIMessagePart.QuotedFollowUp }
        // 从既有消息中还原引用状态（编辑/重新进入对话时保持一致），
        // 同样走 clipQuotedFollowUpRaw + 空白归一化，与 applyQuotedFollowUp 保持同一不变量
        quotedFollowUp = contents.filterIsInstance<UIMessagePart.QuotedFollowUp>()
            .firstOrNull()?.let { clipQuotedFollowUpRaw(it.text).takeIf { c -> c.isNotBlank() } }
    }

    fun getContents(): List<UIMessagePart> {
        val parts = mutableListOf<UIMessagePart>()
        quotedFollowUp?.takeIf { it.isNotBlank() }?.let {
            // 出口兜底再 clip 一次，保证进 part 的原文始终满足 ≤2000 字 + 非空白的不变量，
            // 即使将来新增写入 quotedFollowUp 的路径忘了走 clip 也不会污染数据库/模型
            parts.add(UIMessagePart.QuotedFollowUp(clipQuotedFollowUpRaw(it)))
        }
        parts.add(UIMessagePart.Text(textContent.text.toString()))
        parts.addAll(messageContent)
        return parts
    }

    fun isEmpty(): Boolean {
        return textContent.text.isEmpty() && messageContent.isEmpty()
    }

    fun addImages(uris: List<Uri>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach { uri ->
            val image = UIMessagePart.Image(uri.toString())
            if (newMessage.none { it is UIMessagePart.Image && it.url == image.url }) {
                newMessage.add(image)
            }
        }
        messageContent = newMessage
    }

    fun addImageReference(image: UIMessagePart.Image) {
        if (messageContent.none { it is UIMessagePart.Image && it.url == image.url }) {
            messageContent = messageContent + image
        }
    }

    fun addVideos(uris: List<Uri>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach { uri ->
            newMessage.add(UIMessagePart.Video(uri.toString()))
        }
        messageContent = newMessage
    }

    fun addAudios(uris: List<Uri>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach { uri ->
            newMessage.add(UIMessagePart.Audio(uri.toString()))
        }
        messageContent = newMessage
    }

    fun addFiles(uris: List<UIMessagePart.Document>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach {
            newMessage.add(it)
        }
        messageContent = newMessage
    }
}

object ChatInputStateSaver : Saver<ChatInputState, String> {
    override fun restore(value: String): ChatInputState? {
        val jsonObject = JsonInstant.parseToJsonElement(value).jsonObject
        val messageContent = jsonObject["messageContent"]?.let {
            JsonInstant.decodeFromJsonElement<List<UIMessagePart>>(it)
        }
        val editingMessage = jsonObject["editingMessage"]?.jsonPrimitive?.contentOrNull?.let {
            Uuid.parse(it)
        }
        val textContent = jsonObject["textContent"]?.jsonPrimitive?.contentOrNull ?: ""
        val forkEditMode = jsonObject["forkEditMode"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
        val quotedFollowUp = jsonObject["quotedFollowUp"]?.jsonPrimitive?.contentOrNull
            ?.let { clipQuotedFollowUpRaw(it).takeIf { c -> c.isNotBlank() } }
        val state = ChatInputState()
        state.messageContent = messageContent ?: emptyList()
        state.editingMessage = editingMessage
        state.forkEditMode = forkEditMode
        state.quotedFollowUp = quotedFollowUp
        state.setMessageText(textContent)
        return state
    }

    override fun SaverScope.save(value: ChatInputState): String? {
        return JsonInstant.encodeToString(buildJsonObject {
            put("textContent", value.textContent.text.toString())
            put("messageContent", JsonInstant.encodeToJsonElement(value.messageContent))
            put("editingMessage", JsonInstant.encodeToJsonElement(value.editingMessage))
            put("forkEditMode", value.forkEditMode.toString())
            put("quotedFollowUp", value.quotedFollowUp)
        })
    }
}

/**
 * 追问引用的显示上限：超过此长度截断并追加省略号，仅用于提示用户"正在引用"，不承载完整内容。
 */
const val QUOTED_FOLLOW_UP_MAX_CHARS = 20

/**
 * 追问引用原文的存储上限：超出此长度截断并追加省略号。
 * 用于状态/数据库/发送给模型的原文，避免极端长选中段落膨胀 Saver 体积；
 * 与请求日志脱敏上限（REQUEST_LOG_MAX_TEXT_PART_CHARS）量级一致，仍远超 20 字显示上限，足够给模型提供上下文。
 */
const val QUOTED_FOLLOW_UP_RAW_MAX_CHARS = 2000

fun truncateQuotedFollowUp(text: String): String {
    val cleaned = text.trim()
    if (cleaned.length <= QUOTED_FOLLOW_UP_MAX_CHARS) return cleaned
    return cleaned.take(QUOTED_FOLLOW_UP_MAX_CHARS) + "…"
}

/**
 * 截断追问引用原文到存储上限（含省略号）。显示层仍会用 [truncateQuotedFollowUp] 再做 20 字预览。
 */
fun clipQuotedFollowUpRaw(text: String): String {
    val cleaned = text.trim()
    if (cleaned.length <= QUOTED_FOLLOW_UP_RAW_MAX_CHARS) return cleaned
    return cleaned.take(QUOTED_FOLLOW_UP_RAW_MAX_CHARS) + "…"
}
