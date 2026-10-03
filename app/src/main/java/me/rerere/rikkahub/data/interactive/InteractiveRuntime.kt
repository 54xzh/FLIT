package me.rerere.rikkahub.data.interactive

import kotlinx.serialization.json.*
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull

const val FLIT_INTERACTIVE_CATALOG = "flit:interactive/v1"

data class InteractiveWatch(val paths: List<String>, val handler: String)
data class InteractiveRuntime(val code: String, val watch: List<InteractiveWatch>) {
    companion object {
        const val MAX_CODE_BYTES = 32 * 1024
        const val MAX_DATA_BYTES = 128 * 1024
        const val TIMEOUT_MS = 500L

        fun parse(value: JsonElement): InteractiveRuntime {
            val obj = value as? JsonObject ?: error("Expected flitRuntime object")
            require(obj.keys.all { it in setOf("version", "code", "watch") }) { "Unknown runtime option" }
            require(obj["version"]?.jsonPrimitiveOrNull?.intOrNull == 1) { "Unsupported runtime version" }
            val code = obj["code"]?.jsonPrimitiveOrNull?.takeIf { it.isString }?.content
                ?: error("Missing script code")
            require(code.isNotBlank() && code.toByteArray().size <= MAX_CODE_BYTES) { "Script exceeds the size limit" }
            val watches = obj["watch"]?.let { it as? JsonArray ?: error("Expected watch array") } ?: JsonArray(emptyList())
            require(watches.size <= 16) { "Too many watch rules" }
            return InteractiveRuntime(code, watches.map { item ->
                val rule = item as? JsonObject ?: error("Expected watch rule")
                require(rule.keys == setOf("paths", "handler")) { "Invalid watch rule" }
                val paths = (rule["paths"] as? JsonArray)?.map {
                    it.jsonPrimitiveOrNull?.takeIf { p -> p.isString }?.content ?: error("Invalid watch path")
                } ?: error("Missing watch paths")
                require(paths.isNotEmpty() && paths.size <= 32) { "Invalid watch paths" }
                paths.forEach(::interactivePointerSegments)
                InteractiveWatch(paths.distinct(), interactiveHandler(rule["handler"]))
            })
        }
    }
}

fun interactiveHandler(value: JsonElement?): String {
    val name = value?.jsonPrimitiveOrNull?.takeIf { it.isString }?.content ?: error("Missing handler")
    require(name.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]{0,63}")) && name !in setOf("__proto__", "prototype", "constructor")) {
        "Invalid handler"
    }
    return name
}

/** 严格 JSON Pointer；根替换及原型属性不可作为脚本输出。 */
fun interactivePointerSegments(path: String): List<String> {
    require(path.startsWith('/') && path != "/" && path.length <= 512) { "Invalid data path" }
    return path.substring(1).split('/').map { segment ->
        require(!Regex("~(?![01])").containsMatchIn(segment)) { "Invalid pointer escape" }
        segment.replace("~1", "/").replace("~0", "~").also {
            require(it !in setOf("__proto__", "prototype", "constructor")) { "Forbidden data path" }
        }
    }.also { require(it.size <= InteractiveDocument.MAX_DEPTH) { "Data path is too deep" } }
}

fun interactivePathValue(model: JsonElement, path: String): JsonElement? =
    interactivePointerSegments(path).fold<String, JsonElement?>(model) { value, segment ->
        when (value) {
            is JsonObject -> value[segment]
            is JsonArray -> segment.toIntOrNull()?.takeIf { it >= 0 && it.toString() == segment }?.let { value.getOrNull(it) }
            else -> null
        }
    }

fun validateInteractiveRuntimeData(value: JsonElement) {
    require(value is JsonObject) { "Root data model must be an object" }
    require(value.toString().toByteArray().size <= InteractiveRuntime.MAX_DATA_BYTES) { "Data exceeds the size limit" }
    fun walk(item: JsonElement, depth: Int) {
        require(depth <= InteractiveDocument.MAX_DEPTH) { "Data nesting is too deep" }
        when (item) {
            is JsonArray -> {
                require(item.size <= InteractiveDocument.MAX_COMPONENTS) { "Data collection is too large" }
                item.forEach { walk(it, depth + 1) }
            }
            is JsonObject -> {
                require(item.size <= InteractiveDocument.MAX_COMPONENTS) { "Data object is too large" }
                item.values.forEach { walk(it, depth + 1) }
            }
            is JsonPrimitive -> if (!item.isString && item != JsonNull && item.booleanOrNull == null) {
                require(item.doubleOrNull?.isFinite() == true) { "Invalid number" }
            }
        }
    }
    walk(value, 0)
}

/** 所有修改在不可变副本上完成，任何一项非法都不应用。 */
fun applyInteractiveScriptResult(model: JsonObject, result: String, runtime: InteractiveRuntime, automatic: Boolean): JsonObject {
    require(result.toByteArray().size <= InteractiveRuntime.MAX_DATA_BYTES) { "Result exceeds the size limit" }
    // 解析前限制结构深度，避免深层 JSON 先耗尽解析器的栈。
    InteractiveDocument.validateJsonDepth(result)
    val patches = JsonInstant.parseToJsonElement(result) as? JsonArray ?: error("Expected a modification list")
    require(patches.size <= InteractiveDocument.MAX_COMPONENTS) { "Too many modifications" }
    fun replace(value: JsonElement, segments: List<String>, index: Int, replacement: JsonElement): JsonElement {
        val segment = segments[index]
        val old = when (value) {
            is JsonObject -> value[segment]
            is JsonArray -> segment.toIntOrNull()?.takeIf { it >= 0 && it.toString() == segment }?.let { value.getOrNull(it) }
            else -> null
        } ?: error("Script can only update existing data paths")
        val next = if (index == segments.lastIndex) replacement else replace(old, segments, index + 1, replacement)
        return when (value) {
            is JsonObject -> JsonObject(value + (segment to next))
            is JsonArray -> JsonArray(value.mapIndexed { i, item -> if (i.toString() == segment) next else item })
            else -> error("Invalid data path")
        }
    }
    var updated: JsonElement = model
    patches.forEach { item ->
        val patch = item as? JsonObject ?: error("Expected modification object")
        require(patch.keys == setOf("path", "value")) { "Expected path and value" }
        val path = patch["path"]?.jsonPrimitiveOrNull?.takeIf { it.isString }?.content ?: error("Invalid data path")
        val segments = interactivePointerSegments(path)
        if (automatic) runtime.watch.flatMap { it.paths }.forEach { watched ->
            val input = interactivePointerSegments(watched)
            require(!(segments.take(input.size) == input || input.take(segments.size) == segments)) {
                "Automatic scripts cannot modify watched inputs"
            }
        }
        updated = replace(updated, segments, 0, patch.getValue("value"))
    }
    validateInteractiveRuntimeData(updated)
    return updated as JsonObject
}

/** 删除应用扩展后，交给官方协议解析器。 */
fun interactiveRendererMessage(line: String): String {
    val envelope = JsonInstant.parseToJsonElement(line) as? JsonObject ?: return line
    val create = envelope["createSurface"] as? JsonObject ?: return line
    return JsonObject(envelope + ("createSurface" to JsonObject(create - "flitRuntime"))).toString()
}
