package me.rerere.rikkahub.data.ai.prompts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemorySummaryPromptsTest {
    @Test
    fun incrementalPromptIncludesPreviousSummaryAndNewMemories() {
        val prompt = buildMemorySummaryPrompt(
            promptTemplate = DEFAULT_MEMORY_SUMMARY_PROMPT,
            mode = MemorySummaryPromptMode.INCREMENTAL,
            currentDate = "2026-08-29",
            previousSummary = "previous summary marker",
            memories = "new memory marker",
        )

        assertTrue(prompt.contains("previous summary marker"))
        assertTrue(prompt.contains("new memory marker"))
        assertTrue(prompt.contains("New memories added"))
        assertFalse(prompt.contains("{previous_summary_section}"))
    }

    @Test
    fun fullPromptIncludesPreviousSummaryAndCompleteMemoryLibrary() {
        val prompt = buildMemorySummaryPrompt(
            promptTemplate = DEFAULT_MEMORY_SUMMARY_PROMPT,
            mode = MemorySummaryPromptMode.FULL,
            currentDate = "2026-08-29",
            previousSummary = "previous summary marker",
            memories = "complete memory marker",
        )

        assertTrue(prompt.contains("previous summary marker"))
        assertTrue(prompt.contains("complete memory marker"))
        assertTrue(prompt.contains("Current complete memory library"))
        assertTrue(prompt.contains("Do not retain information supported only by the previous summary"))
    }

    @Test
    fun rebuildPromptDoesNotIncludePreviousSummary() {
        val prompt = buildMemorySummaryPrompt(
            promptTemplate = DEFAULT_MEMORY_SUMMARY_PROMPT,
            mode = MemorySummaryPromptMode.REBUILD,
            currentDate = "2026-08-29",
            previousSummary = "previous summary marker",
            memories = "complete memory marker",
        )

        assertFalse(prompt.contains("previous summary marker"))
        assertTrue(prompt.contains("complete memory marker"))
        assertTrue(prompt.contains("Do not use or rely on any previous memory summary"))
    }
}
