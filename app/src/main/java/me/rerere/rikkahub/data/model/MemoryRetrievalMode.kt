package me.rerere.rikkahub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How long-term memories are selected for automatic context injection. */
@Serializable
enum class MemoryRetrievalMode {
    @SerialName("off")
    OFF,

    @SerialName("vector")
    VECTOR,

    @SerialName("keyword")
    KEYWORD,
}

fun Assistant.effectiveMemoryRetrievalMode(): MemoryRetrievalMode =
    memoryRetrievalMode ?: if (useRagMemoryRetrieval) {
        MemoryRetrievalMode.VECTOR
    } else {
        MemoryRetrievalMode.OFF
    }
