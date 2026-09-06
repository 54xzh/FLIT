package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.hasMessagesForConsolidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryConsolidationPolicyTest {
    @Test
    fun `legacy assistant keeps automatic consolidation enabled`() {
        val assistant = Assistant()

        assertFalse(assistant.isMemoryConsolidationPaused)
        assertEquals(0L, assistant.memoryConsolidationResumeAt)
        assertTrue(assistant.canConsolidateConversation(conversationUpdateAt = 1L, isManual = false))
    }

    @Test
    fun `paused assistant only allows manual consolidation`() {
        val assistant = Assistant(isMemoryConsolidationPaused = true)

        assertFalse(assistant.canConsolidateConversation(conversationUpdateAt = Long.MAX_VALUE, isManual = false))
        assertTrue(assistant.canConsolidateConversation(conversationUpdateAt = 1L, isManual = true))
    }

    @Test
    fun `resuming skips conversations from the paused period`() {
        val resumedAt = 1_000L
        val assistant = Assistant(isMemoryConsolidationPaused = true)
            .withMemoryConsolidationPaused(paused = false, now = resumedAt)

        assertFalse(assistant.isMemoryConsolidationPaused)
        assertEquals(resumedAt, assistant.memoryConsolidationResumeAt)
        assertFalse(assistant.canConsolidateConversation(conversationUpdateAt = resumedAt, isManual = false))
        assertTrue(assistant.canConsolidateConversation(conversationUpdateAt = resumedAt + 1, isManual = false))
    }

    @Test
    fun `pausing does not erase the previous resume cutoff`() {
        val assistant = Assistant(memoryConsolidationResumeAt = 1_000L)
            .withMemoryConsolidationPaused(paused = true, now = 2_000L)

        assertTrue(assistant.isMemoryConsolidationPaused)
        assertEquals(1_000L, assistant.memoryConsolidationResumeAt)
    }

    @Test
    fun `conversation requires both user and assistant messages for consolidation`() {
        val emptyConversation = me.rerere.rikkahub.data.model.Conversation(
            id = kotlin.uuid.Uuid.random(),
            assistantId = kotlin.uuid.Uuid.random(),
            title = "Test",
            createAt = java.time.Instant.now(),
            updateAt = java.time.Instant.now(),
            messageNodes = emptyList(),
        )
        assertFalse(emptyConversation.hasMessagesForConsolidation())

        val userOnlyConversation = emptyConversation.copy(
            messageNodes = listOf(
                me.rerere.rikkahub.data.model.MessageNode.of(
                    me.rerere.ai.ui.UIMessage.user("Hello")
                )
            )
        )
        assertFalse(userOnlyConversation.hasMessagesForConsolidation())

        val bothConversation = emptyConversation.copy(
            messageNodes = listOf(
                me.rerere.rikkahub.data.model.MessageNode.of(
                    me.rerere.ai.ui.UIMessage.user("Hello")
                ),
                me.rerere.rikkahub.data.model.MessageNode.of(
                    me.rerere.ai.ui.UIMessage.assistant("Hi there!")
                )
            )
        )
        assertTrue(bothConversation.hasMessagesForConsolidation())
    }

    @Test
    fun `memory consolidation progress data class holds expected values`() {
        val idleProgress = me.rerere.rikkahub.data.repository.MemoryConsolidationProgress()
        assertFalse(idleProgress.isRunning)
        assertEquals(0, idleProgress.current)
        assertEquals(0, idleProgress.total)

        val runningProgress = me.rerere.rikkahub.data.repository.MemoryConsolidationProgress(
            isRunning = true,
            current = 3,
            total = 10,
        )
        assertTrue(runningProgress.isRunning)
        assertEquals(3, runningProgress.current)
        assertEquals(10, runningProgress.total)
    }
}
