package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object MemorySummaryUpdateMode {
    const val INCREMENTAL = 0
    const val FULL = 1
}

object MemorySummaryChangeType {
    const val ADDED = 0
    const val UPDATED = 1
    const val DELETED = 2
}

/** A saved summary version. The newest version of an assistant is the active one. */
@Entity(
    tableName = "memory_summary_versions",
    indices = [Index(value = ["assistant_id", "generated_at"])],
)
data class MemorySummaryVersionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "assistant_id")
    val assistantId: String,
    @ColumnInfo(name = "content")
    val content: String,
    @ColumnInfo(name = "generated_at")
    val generatedAt: Long,
    @ColumnInfo(name = "update_mode")
    val updateMode: Int,
    @ColumnInfo(name = "source_change_count")
    val sourceChangeCount: Int,
)

/** One pending change per memory. changeToken prevents a running summary task from clearing newer changes. */
@Entity(
    tableName = "memory_summary_changes",
    primaryKeys = ["assistant_id", "memory_type", "memory_id"],
    indices = [Index(value = ["assistant_id", "changed_at"])],
)
data class MemorySummaryChangeEntity(
    @ColumnInfo(name = "assistant_id")
    val assistantId: String,
    @ColumnInfo(name = "memory_type")
    val memoryType: Int,
    @ColumnInfo(name = "memory_id")
    val memoryId: Int,
    @ColumnInfo(name = "change_type")
    val changeType: Int,
    @ColumnInfo(name = "changed_at")
    val changedAt: Long,
    @ColumnInfo(name = "change_token")
    val changeToken: String,
)
