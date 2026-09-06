package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object MemorySummaryUpdateMode {
    const val INCREMENTAL = 0
    const val FULL = 1
    const val REBUILD = 2
    const val MANUAL = 3
    const val REQUIREMENT_CHANGE = 4
}

object MemorySummaryChangeType {
    const val ADDED = 0
    const val UPDATED = 1
    const val DELETED = 2
}

/** An immutable saved summary version. Its active state is stored separately. */
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

/** The active summary selection for one assistant. No row exists until a version is available. */
@Entity(
    tableName = "memory_summary_state",
    foreignKeys = [
        ForeignKey(
            entity = MemorySummaryVersionEntity::class,
            parentColumns = ["id"],
            childColumns = ["active_version_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index(value = ["active_version_id"], unique = true)],
)
data class MemorySummaryStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "assistant_id")
    val assistantId: String,
    @ColumnInfo(name = "active_version_id")
    val activeVersionId: Long,
    @ColumnInfo(name = "requires_full_update")
    val requiresFullUpdate: Boolean = false,
    @ColumnInfo(name = "revision")
    val revision: Long = 0,
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

/**
 * A recently accepted user request for maintaining a memory summary.
 *
 * This history is deliberately independent from summary versions: old summary
 * versions are pruned, while the user's latest corrections must continue to
 * protect subsequent updates.
 */
@Entity(
    tableName = "memory_summary_requirements",
    indices = [Index(value = ["assistant_id", "created_at"])],
)
data class MemorySummaryRequirementEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "assistant_id")
    val assistantId: String,
    @ColumnInfo(name = "requirement")
    val requirement: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)
