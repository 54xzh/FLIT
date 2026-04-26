package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.ModelQuotaUsageEntity

@Dao
interface ModelQuotaUsageDAO {
    @Query("SELECT * FROM model_quota_usage WHERE model_id = :modelId")
    suspend fun getUsage(modelId: String): ModelQuotaUsageEntity?

    @Query("SELECT * FROM model_quota_usage WHERE model_id IN (:modelIds)")
    suspend fun getUsageForModels(modelIds: List<String>): List<ModelQuotaUsageEntity>

    @Query("SELECT * FROM model_quota_usage WHERE model_id IN (:modelIds)")
    fun getUsageForModelsFlow(modelIds: List<String>): Flow<List<ModelQuotaUsageEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(entity: ModelQuotaUsageEntity)

    @Query("""
        UPDATE model_quota_usage SET
            input_tokens = input_tokens + :inputTokens,
            output_tokens = output_tokens + :outputTokens,
            cached_tokens = cached_tokens + :cachedTokens,
            last_updated_at = :timestamp
        WHERE model_id = :modelId
    """)
    suspend fun addUsage(
        modelId: String,
        inputTokens: Long,
        outputTokens: Long,
        cachedTokens: Long,
        timestamp: Long,
    )

    @Query("""
        UPDATE model_quota_usage SET
            input_tokens = 0,
            output_tokens = 0,
            cached_tokens = 0,
            last_reset_at = :timestamp,
            last_updated_at = :timestamp
        WHERE model_id = :modelId
    """)
    suspend fun resetUsage(modelId: String, timestamp: Long)

    @Query("""
        UPDATE model_quota_usage SET
            input_tokens = 0,
            output_tokens = 0,
            cached_tokens = 0,
            last_reset_at = :timestamp,
            last_updated_at = :timestamp
        WHERE model_id IN (:modelIds)
    """)
    suspend fun resetUsageForModels(modelIds: List<String>, timestamp: Long)
}
