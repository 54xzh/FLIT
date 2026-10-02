package me.rerere.rikkahub.data.interactive

import kotlinx.serialization.json.*
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull

internal fun interactivePath(base: String, path: String): String =
    if (path.startsWith('/')) path else "${base.trimEnd('/')}/$path"

internal fun interactiveValueAt(model: JsonElement, path: String): JsonElement? {
    var value: JsonElement? = model
    if (path == "/" || path.isEmpty()) return value
    path.removePrefix("/").split('/').forEach { token ->
        val key = token.replace("~1", "/").replace("~0", "~")
        value = when (val current = value) {
            is JsonObject -> current[key]
            is JsonArray -> key.toIntOrNull()?.let { current.getOrNull(it) }
            else -> null
        }
    }
    return value
}

/** 同时限制模板展开后的数量，防止少量组件引用巨大的列表或重复子树。 */
fun interactiveInstances(components: Map<String, JsonObject>, model: JsonElement): List<Pair<String, String>> {
    val result = mutableListOf<Pair<String, String>>()
    fun walk(id: String, base: String, depth: Int) {
        require(depth <= InteractiveDocument.MAX_DEPTH && result.size < InteractiveDocument.MAX_COMPONENTS) { "Interface expansion exceeds the limit" }
        val component = components[id] ?: return
        result += id to base
        when (component["component"]?.jsonPrimitiveOrNull?.contentOrNull) {
            "Card", "Button" -> component["child"]?.jsonPrimitiveOrNull?.contentOrNull?.let { walk(it, base, depth + 1) }
            "Row", "Column", "List" -> when (val children = component["children"]) {
                is JsonArray -> children.forEach { it.jsonPrimitiveOrNull?.contentOrNull?.let { child -> walk(child, base, depth + 1) } }
                is JsonObject -> {
                    val child = children["componentId"]?.jsonPrimitiveOrNull?.contentOrNull
                    val path = children["path"]?.jsonPrimitiveOrNull?.contentOrNull
                    if (child != null && path != null) {
                        val resolved = interactivePath(base, path)
                        (interactiveValueAt(model, resolved) as? JsonArray)?.indices?.forEach { index -> walk(child, "$resolved/$index", depth + 1) }
                    }
                }
                else -> Unit
            }
            "Tabs" -> (component["tabs"] as? JsonArray)?.forEach { tab ->
                (tab as? JsonObject)?.get("child")?.jsonPrimitiveOrNull?.contentOrNull?.let { walk(it, base, depth + 1) }
            }
            "Modal" -> listOf("trigger", "content").forEach { key ->
                component[key]?.jsonPrimitiveOrNull?.contentOrNull?.let { walk(it, base, depth + 1) }
            }
        }
    }
    walk("root", "/", 1)
    return result
}
