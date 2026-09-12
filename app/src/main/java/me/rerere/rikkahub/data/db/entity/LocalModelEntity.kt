package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Device-local model metadata. Paths are relative to noBackupFilesDir/local-ai. */
@Entity(tableName = "local_models")
data class LocalModelEntity(
    @PrimaryKey
    @ColumnInfo("model_id")
    val modelId: String,
    @ColumnInfo("display_name")
    val displayName: String,
    @ColumnInfo("format")
    val format: String,
    @ColumnInfo("relative_path")
    val relativePath: String,
    @ColumnInfo("source")
    val source: String,
    @ColumnInfo("sha256")
    val sha256: String? = null,
    @ColumnInfo("size_bytes")
    val sizeBytes: Long,
    @ColumnInfo("state")
    val state: String,
    @ColumnInfo("runtime_version")
    val runtimeVersion: String? = null,
    @ColumnInfo("supports_tools")
    val supportsTools: Boolean = false,
    @ColumnInfo("catalog_id")
    val catalogId: String? = null,
    @ColumnInfo("created_at")
    val createdAt: Long = System.currentTimeMillis(),
)

