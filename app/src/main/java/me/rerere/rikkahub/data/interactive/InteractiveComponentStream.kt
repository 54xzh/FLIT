package me.rerere.rikkahub.data.interactive

import kotlinx.serialization.json.*
import me.rerere.rikkahub.utils.JsonInstant

/** 只预览 components 数组里已经闭合的对象；残缺的字段从不交给渲染引擎。 */
internal class InteractiveComponentStream {
    private var header: JsonObject? = null
    private var cursor = 0
    var count = 0
        private set
    var appliedEnd = 0
        private set

    fun drain(line: String): Sequence<String> = sequence {
        if (header == null) {
            val match = COMPONENTS_ARRAY.find(line) ?: return@sequence
            val parsed = runCatching {
                JsonInstant.parseToJsonElement(line.substring(0, match.range.last) + "[]}}") as? JsonObject
            }.getOrNull() ?: return@sequence
            val body = parsed["updateComponents"] as? JsonObject ?: return@sequence
            // 只识别普通协议头，避免在标签文字或其他嵌套数据里误认数组。
            if (parsed.keys != setOf("version", "updateComponents") ||
                body.keys != setOf("surfaceId", "components")) return@sequence
            header = parsed
            cursor = match.range.last + 1
        }
        while (cursor < line.length) {
            var start = cursor
            while (start < line.length && line[start].isWhitespace()) start++
            if (count > 0) {
                if (start >= line.length || line[start] != ',') break
                start++
                while (start < line.length && line[start].isWhitespace()) start++
            }
            if (start >= line.length || line[start] != '{') break
            val end = objectEnd(line, start) ?: break
            val component = JsonInstant.parseToJsonElement(line.substring(start, end)) as? JsonObject
                ?: error("Expected component object")
            cursor = end
            appliedEnd = end
            count++
            require(count <= InteractiveDocument.MAX_COMPONENTS) { "Too many components" }
            yield(message(listOf(component)))
        }
    }

    fun remaining(line: String, previewed: Map<String, JsonObject>): String? {
        val envelope = JsonInstant.parseToJsonElement(line) as? JsonObject ?: error("Expected message object")
        val body = envelope["updateComponents"] as? JsonObject ?: error("Expected updateComponents")
        val components = body["components"] as? JsonArray ?: error("Expected components array")
        require(components.size >= count) { "Interface content changed; restart rendering" }
        val remaining = components.filter { item ->
            val component = item as? JsonObject
            val id = (component?.get("id") as? kotlinx.serialization.json.JsonPrimitive)?.content
            component == null || previewed[id] != component
        }
        if (remaining.isEmpty()) return null
        return JsonObject(envelope + ("updateComponents" to JsonObject(body + ("components" to JsonArray(remaining))))).toString()
    }

    private fun message(components: List<JsonObject>): String {
        val envelope = requireNotNull(header)
        val body = envelope["updateComponents"] as? JsonObject ?: error("Expected updateComponents")
        return JsonObject(envelope + ("updateComponents" to JsonObject(body + ("components" to JsonArray(components))))).toString()
    }

    private fun objectEnd(line: String, start: Int): Int? {
        var depth = 0
        var quoted = false
        var escaped = false
        for (index in start until line.length) {
            val char = line[index]
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= InteractiveDocument.MAX_DEPTH) { "JSON nesting is too deep" } }
                '}', ']' -> { depth--; if (depth == 0) return index + 1 }
            }
        }
        return null
    }

    companion object {
        private val COMPONENTS_ARRAY = Regex("\"components\"\\s*:\\s*\\[")
    }
}
