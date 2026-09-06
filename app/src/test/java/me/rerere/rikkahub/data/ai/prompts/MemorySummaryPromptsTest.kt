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

    @Test
    fun requirementsAreAppendedInChronologicalOrder() {
        val prompt = buildMemorySummaryPrompt(
            promptTemplate = "Custom prompt {memory_section}",
            mode = MemorySummaryPromptMode.FULL,
            currentDate = "2026-08-29",
            previousSummary = "previous",
            memories = "memories",
            recentRequirements = listOf("older requirement", "newer requirement"),
        )

        assertTrue(prompt.contains("<recent_user_summary_requirements>"))
        assertTrue(prompt.indexOf("older requirement") < prompt.indexOf("newer requirement"))
        assertTrue(prompt.contains("the later requirement takes precedence"))
    }

    @Test
    fun promptOmitsRequirementsSectionWhenNoneAreSelected() {
        val prompt = buildMemorySummaryPrompt(
            promptTemplate = DEFAULT_MEMORY_SUMMARY_PROMPT,
            mode = MemorySummaryPromptMode.INCREMENTAL,
            currentDate = "2026-08-29",
            previousSummary = "previous",
            memories = "memories",
            recentRequirements = emptyList(),
        )

        assertFalse(prompt.contains("recent_user_summary_requirements"))
    }

    @Test
    fun rebuildCanCarryRequirementsWithoutUsingPreviousSummary() {
        val prompt = buildMemorySummaryPrompt(
            promptTemplate = DEFAULT_MEMORY_SUMMARY_PROMPT,
            mode = MemorySummaryPromptMode.REBUILD,
            currentDate = "2026-08-29",
            previousSummary = "previous summary marker",
            memories = "complete memory marker",
            recentRequirements = listOf("keep the profile concise"),
        )

        assertFalse(prompt.contains("previous summary marker"))
        assertTrue(prompt.contains("keep the profile concise"))
    }
}
