package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import me.rerere.rikkahub.data.db.entity.InteractiveComponentStateEntity

@Dao
interface InteractiveComponentStateDao {
    @Query("SELECT * FROM interactive_component_state WHERE conversationId = :conversationId AND messageId = :messageId AND partIndex = :partIndex AND blockOffset = :blockOffset")
    suspend fun get(conversationId: String, messageId: String, partIndex: Int, blockOffset: Int): InteractiveComponentStateEntity?

    @Query("SELECT * FROM interactive_component_state WHERE conversationId = :conversationId")
    suspend fun list(conversationId: String): List<InteractiveComponentStateEntity>

    @Upsert
    suspend fun put(state: InteractiveComponentStateEntity)
}
