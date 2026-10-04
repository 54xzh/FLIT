package me.rerere.rikkahub.data.interactive

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import me.rerere.rikkahub.utils.JsonInstant

/** 只补容器的结束括号；字符串、字段和值残缺时仍交给原有校验拒绝。 */
internal fun completeInteractiveJson(text: String): String? {
    val closing = mutableListOf<Char>()
    var quoted = false
    var escaped = false
    for (char in text) {
        if (quoted) {
            if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
        } else when (char) {
            '"' -> quoted = true
            '{', '[' -> {
                closing += if (char == '{') '}' else ']'
                if (closing.size > InteractiveDocument.MAX_DEPTH) return null
            }
            '}', ']' -> {
                if (closing.lastOrNull() != char) return null
                closing.removeAt(closing.lastIndex)
            }
        }
    }
    if (quoted || closing.isEmpty()) return null
    val completed = text + closing.asReversed().joinToString("")
    return try {
        // JSON 树解析会容忍 tru 等未加引号的字面量，不能把这些残缺值当作成功补齐。
        if (hasCompleteValues(JsonInstant.parseToJsonElement(completed))) completed else null
    } catch (_: SerializationException) {
        null
    }
}

private val jsonNumber = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

private fun hasCompleteValues(value: JsonElement): Boolean = when (value) {
    is JsonObject -> value.values.all(::hasCompleteValues)
    is JsonArray -> value.all(::hasCompleteValues)
    JsonNull -> true
    is JsonPrimitive -> value.isString || value.content == "true" || value.content == "false" || jsonNumber.matches(value.content)
}
