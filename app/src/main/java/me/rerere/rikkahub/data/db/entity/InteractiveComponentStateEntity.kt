package me.rerere.rikkahub.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "interactive_component_state",
    primaryKeys = ["conversationId", "messageId", "partIndex", "blockOffset"],
    foreignKeys = [ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"], childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("conversationId")],
)
data class InteractiveComponentStateEntity(
    val conversationId: String,
    val messageId: String,
    val partIndex: Int,
    val blockOffset: Int,
    val fingerprint: String,
    val surfaceId: String,
    val dataModel: String,
    val submissionId: String? = null,
    val submitted: Boolean = false,
    val submittedDataModel: String? = null,
)
