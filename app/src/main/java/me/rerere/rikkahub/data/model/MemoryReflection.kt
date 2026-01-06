package me.rerere.rikkahub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class MemoryReflectionActionType {
    @SerialName("create")
    CREATE,
    @SerialName("update")
    UPDATE,
    @SerialName("merge")
    MERGE,
    @SerialName("skip")
    SKIP,
}

@Serializable
enum class MemoryReflectionStability {
    @SerialName("LONG_TERM")
    LONG_TERM,
    @SerialName("MID_TERM")
    MID_TERM,
}

@Serializable
enum class MemoryReflectionSensitivity {
    @SerialName("LOW")
    LOW,
    @SerialName("MEDIUM")
    MEDIUM,
    @SerialName("HIGH")
    HIGH,
}

@Serializable
data class MemoryReflectionAction(
    val type: MemoryReflectionActionType = MemoryReflectionActionType.SKIP,
    val stability: MemoryReflectionStability = MemoryReflectionStability.LONG_TERM,
    val content: String? = null,
    val reason: String = "",
    val confidence: Double? = null,
    val sensitivity: MemoryReflectionSensitivity = MemoryReflectionSensitivity.LOW,
    val evidence_episode_ids: List<String> = emptyList(),
    val review_in_days: Int? = null,
)

@Serializable
data class MemoryReflectionResponse(
    val version: String = "v1",
    val mode: String = "extract_and_act",
    val actions: List<MemoryReflectionAction> = emptyList(),
    val notes: String? = null,
)

