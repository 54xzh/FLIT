package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "model_quota_usage")
data class ModelQuotaUsageEntity(
    @PrimaryKey
    @ColumnInfo(name = "model_id")
    val modelId: String,

    @ColumnInfo(name = "input_tokens")
    val inputTokens: Long = 0L,

    @ColumnInfo(name = "output_tokens")
    val outputTokens: Long = 0L,

    @ColumnInfo(name = "cached_tokens")
    val cachedTokens: Long = 0L,

    @ColumnInfo(name = "last_reset_at")
    val lastResetAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "last_updated_at")
    val lastUpdatedAt: Long = System.currentTimeMillis(),
)
