package me.rerere.rikkahub.ui.components.interactive

import androidx.compose.runtime.compositionLocalOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.Uuid

data class InteractiveContentContext(
    val conversationId: Uuid,
    val messageId: Uuid,
    val partIndex: Int,
    val speakerAssistantId: Uuid?,
    val speakerSeatId: Uuid?,
    val generating: Boolean,
    val temporary: Boolean,
    val fences: List<me.rerere.rikkahub.data.interactive.InteractiveFence>,
    val isReadOnly: (surfaceId: String, offset: Int, fingerprint: String) -> Boolean,
    val onSubmit: (InteractiveSubmission) -> Boolean,
    val generationActive: () -> Boolean = { generating },
    val isSuperseded: (surfaceId: String) -> Boolean = { false },
    val hasSubmitted: (offset: Int, fingerprint: String) -> Boolean = { _, _ -> false },
)

data class InteractiveSubmission(
    val origin: InteractiveContentContext,
    val offset: Int,
    val fingerprint: String,
    val surfaceId: String,
    val componentId: String,
    val eventName: String,
    val timestamp: Long,
    val buttonLabel: String,
    val values: JsonObject,
    val dataModel: JsonElement,
)

val LocalInteractiveContentContext = compositionLocalOf<InteractiveContentContext?> { null }

val LocalInteractiveExport = compositionLocalOf { false }
