package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.ProjectEntity

@Dao
interface ProjectDAO {
    @Query("SELECT * FROM projects WHERE assistant_id = :assistantId ORDER BY sort_index ASC, created_at ASC")
    fun getProjectsOfAssistant(assistantId: String): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE assistant_id = :assistantId ORDER BY sort_index ASC, created_at ASC")
    suspend fun getProjectsOfAssistantSync(assistantId: String): List<ProjectEntity>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getProjectById(id: String): ProjectEntity?

    @Query("SELECT * FROM projects WHERE id = :id")
    fun getProjectByIdFlow(id: String): Flow<ProjectEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: ProjectEntity)

    @Update
    suspend fun updateProject(project: ProjectEntity)

    @Query("UPDATE projects SET name = :name, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateName(id: String, name: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE projects SET icon = :icon, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateIcon(id: String, icon: String, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM projects WHERE assistant_id = :assistantId")
    suspend fun deleteByAssistantId(assistantId: String)

    @Query("SELECT COUNT(*) FROM projects WHERE assistant_id = :assistantId")
    suspend fun countByAssistantId(assistantId: String): Int
}

