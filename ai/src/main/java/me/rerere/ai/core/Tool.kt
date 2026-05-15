package me.rerere.ai.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage

@Serializable
data class Tool(
    val name: String,
    val description: String,
    val parameters: () -> InputSchema? = { null },
    val systemPrompt: (model: Model, messages: List<UIMessage>) -> String = { _, _ -> "" },
    val systemPromptVariables: (model: Model, messages: List<UIMessage>) -> Map<String, String> = { _, _ -> emptyMap() },
    val requiresUserApproval: Boolean = false,
    val execute: suspend (JsonElement) -> JsonElement
)

fun Tool.parametersOrEmptyObject(): InputSchema {
    return parameters() ?: InputSchema.Obj(properties = JsonObject(emptyMap()))
}

@Serializable
sealed class InputSchema {
    @Serializable
    @SerialName("object")
    data class Obj(
        val properties: JsonObject,
        val required: List<String>? = null,
    ) : InputSchema()
}
