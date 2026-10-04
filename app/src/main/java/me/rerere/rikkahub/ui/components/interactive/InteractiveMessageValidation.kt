package me.rerere.rikkahub.ui.components.interactive

import androidx.a2ui.compose.ui.A2uiCatalog
import androidx.a2ui.engine.catalog.A2uiCoreCatalog
import androidx.a2ui.engine.schema.A2uiCoreSchemaValidator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkahub.data.interactive.toInteractiveValue
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull

/** 在消息入队前检查原生组件属性，避免目录校验错误中断后续消息。 */
internal fun interactiveMessageValidator(catalog: A2uiCatalog): (String) -> Unit {
    val core = catalog as? A2uiCoreCatalog ?: error("Missing native catalog")
    val validator = A2uiCoreSchemaValidator(core)
    return { line ->
        val envelope = JsonInstant.parseToJsonElement(line) as? JsonObject ?: error("Expected message object")
        val body = envelope["updateComponents"] as? JsonObject
        if (body != null) {
            val components = body["components"] as? JsonArray ?: error("Expected components array")
            components.forEach { item ->
                val component = item as? JsonObject ?: error("Expected component object")
                val type = component["component"]?.jsonPrimitiveOrNull?.contentOrNull ?: error("Missing component type")
                val definition = core.componentDefinitions[type] ?: error("Unknown component")
                val properties = component.filterKeys { it != "id" && it != "component" }
                    .mapValues { it.value.toInteractiveValue() }
                validator.validateSchema(properties, definition.propertySchema)
            }
        }
    }
}
