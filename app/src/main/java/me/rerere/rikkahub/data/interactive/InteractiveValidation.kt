package me.rerere.rikkahub.data.interactive

import androidx.a2ui.engine.model.A2uiCoreSurfaceModel
import androidx.a2ui.model.protocol.A2uiDataPath
import kotlinx.serialization.json.*
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull

/** 提交时重新计算字段校验，不能只依赖按钮自身是否声明 checks。 */
fun interactiveValidationError(surface: A2uiCoreSurfaceModel, components: Map<String, JsonObject>): String? {
    fun evaluate(id: String, basePath: String, payload: JsonElement) = surface.evaluatePayload(
        componentId = id, valueResolver = { surface.dataModel[it] },
        dataPath = A2uiDataPath(basePath), payload = payload.toInteractiveValue(),
    )
    val model = interactiveJson(surface.dataModel[A2uiDataPath("/")])
    interactiveInstances(components, model).forEach { (id, base) ->
        val component = components[id] ?: return@forEach
        (component["checks"] as? JsonArray)?.forEach { item ->
            val check = item as? JsonObject ?: return "Invalid validation rule"
            if (check["condition"]?.let { evaluate(id, base, it) } != true) {
                return check["message"]?.let { evaluate(id, base, it) as? String } ?: "Validation failed"
            }
        }
        if (component["component"]?.jsonPrimitiveOrNull?.contentOrNull == "TextField") {
            val pattern = component["validationRegexp"]?.jsonPrimitiveOrNull?.contentOrNull
            if (pattern != null) {
                val valid = evaluate(id, base, buildJsonObject {
                    put("call", "regex")
                    put("args", buildJsonObject { put("value", component["value"] ?: JsonPrimitive("")); put("pattern", pattern) })
                })
                if (valid != true) return component["label"]?.let { evaluate(id, base, it) as? String } ?: "Validation failed"
            }
        }
    }
    return null
}
