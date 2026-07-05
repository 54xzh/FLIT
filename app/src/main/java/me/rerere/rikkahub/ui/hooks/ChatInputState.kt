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

    fun clearInput() {
        textContent.setTextAndPlaceCursorAtEnd("")
        messageContent = emptyList()
        editingMessage = null
        forkEditMode = false
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

    fun setContents(contents: List<UIMessagePart>) {
        val text = contents.filterIsInstance<UIMessagePart.Text>().joinToString { it.text }
        textContent.setTextAndPlaceCursorAtEnd(text)
        messageContent = contents.filter { it !is UIMessagePart.Text }
    }

    fun getContents(): List<UIMessagePart> {
        return listOf(UIMessagePart.Text(textContent.text.toString())) + messageContent
    }

    fun isEmpty(): Boolean {
        return textContent.text.isEmpty() && messageContent.isEmpty()
    }

    fun addImages(uris: List<Uri>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach { uri ->
            newMessage.add(UIMessagePart.Image(uri.toString()))
        }
        messageContent = newMessage
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
        val state = ChatInputState()
        state.messageContent = messageContent ?: emptyList()
        state.editingMessage = editingMessage
        state.forkEditMode = forkEditMode
        state.setMessageText(textContent)
        return state
    }

    override fun SaverScope.save(value: ChatInputState): String? {
        return JsonInstant.encodeToString(buildJsonObject {
            put("textContent", value.textContent.text.toString())
            put("messageContent", JsonInstant.encodeToJsonElement(value.messageContent))
            put("editingMessage", JsonInstant.encodeToJsonElement(value.editingMessage))
            put("forkEditMode", value.forkEditMode.toString())
        })
    }
}
