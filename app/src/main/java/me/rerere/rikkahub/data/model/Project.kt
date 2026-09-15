package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.db.entity.ProjectEntity
import kotlin.uuid.Uuid

@Serializable
data class Project(
    val id: Uuid = Uuid.random(),
    val assistantId: Uuid,
    val name: String,
    val description: String = "",
    val systemPrompt: String = "",
    val modelId: Uuid? = null,
    val enableMemoryTools: Boolean = true,
    val enableConsolidation: Boolean = true,
    val exposeToExternal: Boolean = false,
    val readExternalMemory: Boolean = true,
    /** A private, project-only summary. It intentionally does not follow exposeToExternal. */
    val enableMemorySummary: Boolean = false,
    val sortIndex: Int = 0,
    val icon: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

fun ProjectEntity.toProject(): Project = Project(
    id = Uuid.parse(id),
    assistantId = Uuid.parse(assistantId),
    name = name,
    description = description,
    systemPrompt = systemPrompt,
    modelId = modelId?.let { runCatching { Uuid.parse(it) }.getOrNull() },
    enableMemoryTools = enableMemoryTools,
    enableConsolidation = enableConsolidation,
    exposeToExternal = exposeToExternal,
    readExternalMemory = readExternalMemory,
    enableMemorySummary = enableMemorySummary,
    sortIndex = sortIndex,
    icon = icon,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun Project.toEntity(): ProjectEntity = ProjectEntity(
    id = id.toString(),
    assistantId = assistantId.toString(),
    name = name,
    description = description,
    systemPrompt = systemPrompt,
    modelId = modelId?.toString(),
    enableMemoryTools = enableMemoryTools,
    enableConsolidation = enableConsolidation,
    exposeToExternal = exposeToExternal,
    readExternalMemory = readExternalMemory,
    enableMemorySummary = enableMemorySummary,
    sortIndex = sortIndex,
    icon = icon,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
