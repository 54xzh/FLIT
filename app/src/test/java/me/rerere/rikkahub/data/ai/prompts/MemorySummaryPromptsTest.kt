package me.rerere.rikkahub.data.ai.prompts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemorySummaryPromptsTest {
    @Test
    fun rebuildPromptDoesNotAcceptPreviousSummary() {
        assertFalse(DEFAULT_REBUILD_MEMORY_SUMMARY_PROMPT.contains("previous_summary"))
        assertTrue(DEFAULT_REBUILD_MEMORY_SUMMARY_PROMPT.contains("{all_memories}"))
    }
}
