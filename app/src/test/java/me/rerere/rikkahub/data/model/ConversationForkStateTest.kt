package me.rerere.rikkahub.data.model

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationForkStateTest {
    @Test
    fun `fork inherits source conversation state including project`() {
        val sourceProjectId = Uuid.random()
        val modeId = Uuid.random()
        val source = Conversation.ofId(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            projectId = sourceProjectId,
        ).copy(
            workspaceOverrideId = "workspace",
            enabledModeIds = setOf(modeId),
            explicitSkillContexts = setOf("skill"),
            sessionMemories = listOf(SessionMemory(id = 1, content = "memory")),
        )
        val fork = Conversation.ofId(
            id = Uuid.random(),
            assistantId = source.assistantId,
        ).inheritForkStateFrom(source)

        assertEquals(sourceProjectId, fork.projectId)
        assertEquals(source.workspaceOverrideId, fork.workspaceOverrideId)
        assertEquals(source.enabledModeIds, fork.enabledModeIds)
        assertEquals(source.explicitSkillContexts, fork.explicitSkillContexts)
        assertEquals(source.sessionMemories, fork.sessionMemories)
    }
}
