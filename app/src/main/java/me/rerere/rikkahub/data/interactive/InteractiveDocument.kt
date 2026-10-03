package me.rerere.rikkahub.data.interactive

import kotlinx.serialization.json.*
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import java.security.MessageDigest

const val INTERACTIVE_ACTION_METADATA = "interactive_component_action"

data class InteractiveFence(val offset: Int, val code: String, val closed: Boolean, val endOffset: Int = offset + code.length)

private val fenceCache = object : LinkedHashMap<String, List<InteractiveFence>>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<InteractiveFence>>) = size > 32
}

/** 与正文使用同一个 Markdown 解析器，避免把示例内嵌代码误判为可执行的界面。 */
fun interactiveFences(text: String): List<InteractiveFence> {
    if (!text.contains("a2ui", true)) return emptyList()
    synchronized(fenceCache) { fenceCache[text]?.let { return it } }
    val result = mutableListOf<InteractiveFence>()
    fun visit(node: ASTNode) {
        if (node.type == MarkdownElementTypes.CODE_FENCE) {
            val language = node.children.firstOrNull { it.type == MarkdownTokenTypes.FENCE_LANG }
                ?.let { text.substring(it.startOffset, it.endOffset).trim().lowercase() }
            if (language == "a2ui") {
                val contents = node.children.filter { it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT }
                val first = contents.firstOrNull()
                val last = contents.lastOrNull()
                if (first != null && last != null) {
                    val firstIndex = node.children.indexOf(first)
                    val start = node.children.take(firstIndex).lastOrNull { it.type == MarkdownTokenTypes.EOL }
                        ?.endOffset ?: first.startOffset
                    result += InteractiveFence(node.startOffset, text.substring(start, last.endOffset).trimIndent(),
                        node.children.any { it.type == MarkdownTokenTypes.CODE_FENCE_END }, node.endOffset)
                }
            }
            return
        }
        node.children.forEach(::visit)
    }
    visit(MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(text))
    if (text.length <= InteractiveDocument.MAX_CHARS) synchronized(fenceCache) { fenceCache[text] = result }
    return result
}

fun interactiveFingerprint(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

/** 完整行按协议消费，大数组内已闭合的组件提前显示；应用过的前缀变化时重建。 */
class InteractiveDocument(private val catalogId: String) {
    private var prefix = ""
    private var previewPrefix = ""
    private var componentStream: InteractiveComponentStream? = null
    private val components = linkedMapOf<String, JsonObject>()
    var surfaceId: String? = null
        private set
    var deleted: Boolean = false
        private set
    val componentSnapshot: Map<String, JsonObject> get() = components.toMap()

    fun hasChangedPrefix(code: String): Boolean = !code.startsWith(prefix) || !code.startsWith(previewPrefix)

    fun consume(code: String, complete: Boolean): List<String> {
        require(code.length <= MAX_CHARS) { "Interface exceeds the size limit" }
        require(!hasChangedPrefix(code)) { "Interface content changed; restart rendering" }
        val result = mutableListOf<String>()
        var cursor = prefix.length
        while (cursor < code.length) {
            val newline = code.indexOf('\n', cursor)
            if (newline < 0 && !complete) break
            val end = if (newline < 0) code.length else newline
            val line = code.substring(cursor, end).trim()
            if (line.isNotEmpty()) {
                validate(line)
                val preview = componentStream
                if (preview != null && preview.count > 0) preview.remaining(line)?.let(result::add)
                else result += line
            }
            componentStream = null
            previewPrefix = ""
            cursor = if (newline < 0) end else end + 1
            prefix = code.substring(0, cursor)
        }
        if (!complete && cursor < code.length) {
            val stream = componentStream ?: InteractiveComponentStream().also { componentStream = it }
            stream.drain(code.substring(cursor)).forEach { message ->
                validate(message)
                result += message
            }
            if (stream.appliedEnd > 0) previewPrefix = code.substring(0, cursor + stream.appliedEnd)
        }
        if (complete && !deleted) {
            require(surfaceId != null) { "Missing createSurface" }
            require("root" in components) { "Missing root component" }
            validateGraph(requireResolved = true)
        }
        return result
    }

    private fun validate(line: String) {
        validateJsonDepth(line)
        val envelope = JsonInstant.parseToJsonElement(line) as? JsonObject
            ?: error("Expected a JSON object")
        require(envelope["version"]?.jsonPrimitiveOrNull?.contentOrNull == "v0.9.1") { "Expected version v0.9.1" }
        val keys = listOf("createSurface", "updateComponents", "updateDataModel", "deleteSurface")
            .filter { it in envelope }
        require(keys.size == 1 && envelope.keys.all { it == "version" || it in keys }) { "Expected one protocol message per line" }
        val type = keys.single()
        val body = envelope[type] as? JsonObject ?: error("Expected message object")
        val id = body["surfaceId"]?.jsonPrimitiveOrNull?.contentOrNull
        require(!id.isNullOrBlank() && id.length <= 128) { "Invalid surfaceId" }
        if (type == "createSurface") {
            require(surfaceId == null) { "One surface per block; duplicate createSurface" }
            require(body["catalogId"]?.jsonPrimitiveOrNull?.contentOrNull == catalogId) { "Unsupported catalogId" }
            surfaceId = id
        } else {
            require(surfaceId == id && !deleted) { "Create this surface before updating it" }
        }
        if (type == "updateComponents") {
            val batch = body["components"] as? JsonArray ?: error("Expected components array")
            require(batch.size <= MAX_COMPONENTS) { "Too many components" }
            batch.forEach { item ->
                val component = item as? JsonObject ?: error("Expected component object")
                val componentId = component["id"]?.jsonPrimitiveOrNull?.contentOrNull
                require(!componentId.isNullOrBlank() && componentId.length <= 128) { "Invalid component id" }
                require(component["component"]?.jsonPrimitiveOrNull?.contentOrNull in COMPONENT_NAMES) { "Unknown component" }
                component["weight"]?.let {
                    val weight = it.jsonPrimitiveOrNull?.doubleOrNull
                    require(weight != null && weight.isFinite() && weight > 0 && weight <= Float.MAX_VALUE) { "Weight must be a positive finite number" }
                }
                val allowedUrlCall = (component["action"] as? JsonObject)?.get("functionCall") as? JsonObject
                fun checkLocalCalls(value: JsonElement) {
                    if (value is JsonObject) {
                        require(value["call"]?.jsonPrimitiveOrNull?.contentOrNull != "openUrl" || value === allowedUrlCall) {
                            "openUrl is only allowed as an explicit button action"
                        }
                        value.values.forEach(::checkLocalCalls)
                    } else if (value is JsonArray) value.forEach(::checkLocalCalls)
                }
                checkLocalCalls(component)
                components[componentId] = component
            }
            require(components.size <= MAX_COMPONENTS) { "Too many components" }
            validateGraph(requireResolved = false)
        }
        if (type == "updateDataModel") {
            val path = body["path"]?.jsonPrimitiveOrNull?.contentOrNull ?: "/"
            if (path == "/") require(body["value"] == null || body["value"] == JsonNull || body["value"] is JsonObject) {
                "Root data model must be an object"
            }
            fun bounded(value: JsonElement) {
                if (value is JsonArray) {
                    require(value.size <= MAX_COMPONENTS) { "Data collection is too large" }
                    value.forEach(::bounded)
                } else if (value is JsonObject) value.values.forEach(::bounded)
            }
            body["value"]?.let(::bounded)
        }
        if (type == "deleteSurface") deleted = true
    }

    private fun validateGraph(requireResolved: Boolean) {
        fun children(value: JsonElement): List<String> = when (value) {
            is JsonObject -> value.entries.flatMap { (key, item) ->
                if (key in CHILD_KEYS && item is JsonPrimitive && item.isString) listOf(item.content)
                else if (key == "children" && item is JsonArray) item.mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull }
                else if (item is JsonObject || item is JsonArray) children(item) else emptyList()
            }
            is JsonArray -> value.flatMap(::children)
            else -> emptyList()
        }
        val edges = components.mapValues { children(it.value) }
        val depths = mutableMapOf<String, Int>()
        val sizes = mutableMapOf<String, Int>()
        fun walk(id: String, ancestors: Set<String>): Pair<Int, Int> {
            require(id !in ancestors) { "Cyclic component reference" }
            require(ancestors.size < MAX_DEPTH) { "Interface nesting is too deep" }
            if (requireResolved) require(id in components) { "Missing child component: $id" }
            if (id in depths) return depths.getValue(id) to sizes.getValue(id)
            var depth = 1
            var size = 1
            edges[id].orEmpty().forEach {
                val (childDepth, childSize) = walk(it, ancestors + id)
                depth = maxOf(depth, childDepth + 1)
                size += childSize
                require(depth <= MAX_DEPTH && size <= MAX_COMPONENTS) { "Interface expansion exceeds the limit" }
            }
            depths[id] = depth; sizes[id] = size
            return depth to size
        }
        components.keys.forEach { walk(it, emptySet()) }
    }

    companion object {
        const val MAX_CHARS = 262_144
        const val MAX_COMPONENTS = 200
        const val MAX_DEPTH = 32
        private val CHILD_KEYS = setOf("child", "componentId", "entryPointChild", "contentChild", "trigger", "content")
        val COMPONENT_NAMES = setOf("Text", "Icon", "Image", "Video", "AudioPlayer", "Row", "Column", "List", "Card", "Tabs", "Modal", "Divider", "Button", "TextField", "CheckBox", "ChoicePicker", "Slider", "DateTimeInput")
        private fun validateJsonDepth(text: String) {
            var depth = 0
            var quoted = false
            var escaped = false
            text.forEach { char ->
                if (quoted) {
                    if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
                } else when (char) {
                    '"' -> quoted = true
                    '{', '[' -> { depth++; require(depth <= MAX_DEPTH) { "JSON nesting is too deep" } }
                    '}', ']' -> depth--
                }
            }
        }
    }
}

fun JsonElement.toInteractiveValue(): Any? = when (this) {
    is JsonObject -> mapValues { it.value.toInteractiveValue() }
    is JsonArray -> map { it.toInteractiveValue() }
    JsonNull -> null
    is JsonPrimitive -> if (isString) content else booleanOrNull ?: longOrNull ?: doubleOrNull ?: content
}

fun interactiveJson(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to interactiveJson(it.value) })
    is List<*> -> JsonArray(value.map(::interactiveJson))
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    else -> JsonPrimitive(value.toString())
}

/** 视觉正则仍应用于正文，但组件描述原样保留；位置由代码块序号映射回原消息。 */
fun transformAroundInteractiveFences(text: String, transform: (String) -> String): String {
    val fences = interactiveFences(text)
    if (fences.isEmpty()) return transform(text)
    return buildString {
        var cursor = 0
        fences.forEach { fence ->
            append(transform(text.substring(cursor, fence.offset)))
            append(text.substring(fence.offset, fence.endOffset))
            cursor = fence.endOffset
        }
        append(transform(text.substring(cursor)))
    }
}
