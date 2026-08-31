package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.MemoryConsolidationClaimEntity
import me.rerere.rikkahub.data.db.entity.MemoryConsolidationRecordEntity

@Dao
interface MemoryConsolidationDao {
    @Query(
        "SELECT EXISTS(SELECT 1 FROM memory_consolidation_records " +
            "WHERE conversation_id = :conversationId AND assistant_id = :assistantId)"
    )
    suspend fun hasRecord(conversationId: String, assistantId: String): Boolean

    @Query("SELECT * FROM memory_consolidation_records WHERE conversation_id = :conversationId")
    suspend fun getRecordsOfConversation(conversationId: String): List<MemoryConsolidationRecordEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM memory_consolidation_records WHERE conversation_id = :conversationId)")
    suspend fun hasRecordsOfConversation(conversationId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecord(record: MemoryConsolidationRecordEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecords(records: List<MemoryConsolidationRecordEntity>)

    @Query("DELETE FROM memory_consolidation_records WHERE conversation_id = :conversationId")
    suspend fun deleteRecordsOfConversation(conversationId: String)

    @Query("SELECT * FROM memory_consolidation_claims WHERE conversation_id = :conversationId LIMIT 1")
    suspend fun getClaim(conversationId: String): MemoryConsolidationClaimEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertClaim(claim: MemoryConsolidationClaimEntity): Long

    @Query("DELETE FROM memory_consolidation_claims WHERE conversation_id = :conversationId")
    suspend fun deleteClaim(conversationId: String)

    @Query(
        "DELETE FROM memory_consolidation_claims " +
            "WHERE conversation_id = :conversationId AND claim_token = :claimToken"
    )
    suspend fun deleteClaimIfTokenMatches(conversationId: String, claimToken: String): Int
}
