package me.rerere.rikkahub.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.ChatEpisodeDAO
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.dao.ProjectDAO
import me.rerere.rikkahub.data.model.Project
import me.rerere.rikkahub.data.model.toEntity
import me.rerere.rikkahub.data.model.toProject
import kotlin.uuid.Uuid

class ProjectRepository(
    private val database: AppDatabase,
    private val projectDAO: ProjectDAO,
    private val conversationDAO: ConversationDAO,
    private val memoryDAO: MemoryDAO,
    private val chatEpisodeDAO: ChatEpisodeDAO,
    private val memorySummaryRepoProvider: () -> MemorySummaryRepository? = { null },
) {
    fun getProjectsOfAssistant(assistantId: Uuid): Flow<List<Project>> {
        return projectDAO.getProjectsOfAssistant(assistantId.toString()).map { list ->
            list.map { it.toProject() }
        }
    }

    suspend fun getProjectsOfAssistantSync(assistantId: Uuid): List<Project> = withContext(Dispatchers.IO) {
        projectDAO.getProjectsOfAssistantSync(assistantId.toString()).map { it.toProject() }
    }

    suspend fun getProjectById(id: Uuid): Project? = withContext(Dispatchers.IO) {
        projectDAO.getProjectById(id.toString())?.toProject()
    }

    fun getProjectByIdFlow(id: Uuid): Flow<Project?> {
        return projectDAO.getProjectByIdFlow(id.toString()).map { it?.toProject() }
    }

    suspend fun createProject(
        assistantId: Uuid,
        name: String,
        description: String = "",
        systemPrompt: String = "",
        modelId: Uuid? = null,
        enableMemoryTools: Boolean = true,
        enableConsolidation: Boolean = true,
        exposeToExternal: Boolean = false,
        readExternalMemory: Boolean = true,
        icon: String = "",
    ): Project = withContext(Dispatchers.IO) {
        val count = projectDAO.countByAssistantId(assistantId.toString())
        val project = Project(
            id = Uuid.random(),
            assistantId = assistantId,
            name = name.trim(),
            description = description.trim(),
            systemPrompt = systemPrompt.trim(),
            modelId = modelId,
            enableMemoryTools = enableMemoryTools,
            enableConsolidation = enableConsolidation,
            exposeToExternal = exposeToExternal,
            readExternalMemory = readExternalMemory,
            sortIndex = count,
            icon = icon.trim(),
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        projectDAO.insertProject(project.toEntity())
        project
    }

    suspend fun updateProject(project: Project) = withContext(Dispatchers.IO) {
        val oldProject = projectDAO.getProjectById(project.id.toString())
        val updated = project.copy(updatedAt = System.currentTimeMillis())
        projectDAO.updateProject(updated.toEntity())
        if (oldProject != null && oldProject.exposeToExternal != project.exposeToExternal) {
            memorySummaryRepoProvider()?.markRequiresFullUpdate(project.assistantId.toString())
        }
    }

    suspend fun updateProjectIcon(id: Uuid, icon: String) = withContext(Dispatchers.IO) {
        projectDAO.updateIcon(id.toString(), icon.trim(), System.currentTimeMillis())
    }

    suspend fun renameProject(id: Uuid, newName: String) = withContext(Dispatchers.IO) {
        projectDAO.updateName(id.toString(), newName.trim(), System.currentTimeMillis())
    }

    /**
     * 删除项目时安全迁移：绝不删除项目内的会话和记忆，而是将它们的 projectId 清空，移到项目外。
     */
    suspend fun deleteProject(id: Uuid) = withContext(Dispatchers.IO) {
        val idStr = id.toString()
        val project = projectDAO.getProjectById(idStr)
        database.withTransaction {
            conversationDAO.clearProjectId(idStr)
            memoryDAO.clearProjectId(idStr)
            chatEpisodeDAO.clearProjectId(idStr)
            projectDAO.deleteById(idStr)
        }
        if (project != null) {
            memorySummaryRepoProvider()?.markRequiresFullUpdate(project.assistantId)
        }
    }
}

