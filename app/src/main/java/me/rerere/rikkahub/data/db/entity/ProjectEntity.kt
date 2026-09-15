package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "projects",
    indices = [
        Index(value = ["assistant_id", "sort_index"]),
    ]
)
data class ProjectEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "assistant_id")
    val assistantId: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "description", defaultValue = "''")
    val description: String = "",
    @ColumnInfo(name = "system_prompt", defaultValue = "''")
    val systemPrompt: String = "",
    @ColumnInfo(name = "model_id")
    val modelId: String? = null,
    @ColumnInfo(name = "enable_memory_tools", defaultValue = "1")
    val enableMemoryTools: Boolean = true,
    @ColumnInfo(name = "enable_consolidation", defaultValue = "1")
    val enableConsolidation: Boolean = true,
    @ColumnInfo(name = "expose_to_external", defaultValue = "0")
    val exposeToExternal: Boolean = false,
    @ColumnInfo(name = "read_external_memory", defaultValue = "1")
    val readExternalMemory: Boolean = true,
    @ColumnInfo(name = "enable_memory_summary", defaultValue = "0")
    val enableMemorySummary: Boolean = false,
    @ColumnInfo(name = "sort_index", defaultValue = "0")
    val sortIndex: Int = 0,
    @ColumnInfo(name = "icon", defaultValue = "''")
    val icon: String = "",
    @ColumnInfo(name = "created_at", defaultValue = "0")
    val createdAt: Long = 0L,
    @ColumnInfo(name = "updated_at", defaultValue = "0")
    val updatedAt: Long = 0L,
)
