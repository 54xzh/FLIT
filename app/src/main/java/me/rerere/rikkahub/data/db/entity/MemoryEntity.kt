package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class MemoryEntity(
    @PrimaryKey(true)
    val id: Int = 0,
    @ColumnInfo("assistant_id")
    val assistantId: String,
    @ColumnInfo("content")
    val content: String = "",
    @ColumnInfo("embedding")
    val embedding: String? = null, // JSON string of float array
    @ColumnInfo(name = "embedding_model_id", defaultValue = "")
    val embeddingModelId: String? = null, // UUID of the embedding model used
    @ColumnInfo(name = "type", defaultValue = "0")
    val type: Int = 0, // 0: CORE, 1: EPISODIC
    @ColumnInfo(name = "pinned", defaultValue = "0")
    val pinned: Boolean = false,
    @ColumnInfo(name = "last_accessed_at", defaultValue = "0")
    val lastAccessedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "created_at", defaultValue = "0")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at", defaultValue = "NULL")
    val updatedAt: Long? = null,
)

object MemoryType {
    const val CORE = 0
    const val EPISODIC = 1
    const val TOOL_RESULT = 2
    const val TOOL_RESULT_CHUNK = 3
}
