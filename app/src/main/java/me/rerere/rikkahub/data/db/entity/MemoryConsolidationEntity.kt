package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable processing history. It intentionally outlives its episodic memory so
 * retention never makes an old conversation eligible for consolidation again.
 */
@Entity(
    tableName = "memory_consolidation_records",
    primaryKeys = ["conversation_id", "assistant_id"],
    indices = [Index(value = ["assistant_id"])]
)
data class MemoryConsolidationRecordEntity(
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    @ColumnInfo(name = "assistant_id")
    val assistantId: String,
    @ColumnInfo(name = "completed_at")
    val completedAt: Long,
)

/**
 * A short-lived database claim prevents automatic and manual workers from
 * paying for the same conversation at the same time.
 */
@Entity(
    tableName = "memory_consolidation_claims",
    indices = [Index(value = ["claimed_at"])]
)
data class MemoryConsolidationClaimEntity(
    @PrimaryKey
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    @ColumnInfo(name = "claim_token")
    val claimToken: String,
    @ColumnInfo(name = "claimed_at")
    val claimedAt: Long,
)
