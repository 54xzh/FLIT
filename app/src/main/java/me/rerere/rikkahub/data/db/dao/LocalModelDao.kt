package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.LocalModelEntity

@Dao
interface LocalModelDao {
    @Query("SELECT * FROM local_models ORDER BY created_at DESC")
    fun observeAll(): Flow<List<LocalModelEntity>>

    @Query("SELECT * FROM local_models ORDER BY created_at DESC")
    suspend fun getAll(): List<LocalModelEntity>

    @Query("SELECT * FROM local_models WHERE model_id = :modelId LIMIT 1")
    suspend fun get(modelId: String): LocalModelEntity?

    @Query("SELECT * FROM local_models WHERE catalog_id = :catalogId LIMIT 1")
    suspend fun getByCatalogId(catalogId: String): LocalModelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(model: LocalModelEntity)

    @Query("DELETE FROM local_models WHERE model_id = :modelId")
    suspend fun delete(modelId: String)
}
