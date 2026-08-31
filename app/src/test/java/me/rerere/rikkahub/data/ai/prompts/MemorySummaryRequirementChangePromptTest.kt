package me.rerere.rikkahub.data.ai.prompts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemorySummaryRequirementChangePromptTest {
    @Test
    fun keepsSummaryAndRequirementInSeparateTaggedSections() {
        val currentSummary = "# Profile\n- Enjoys tea"
        val requirement = "Replace tea with coffee"

        val prompt = buildMemorySummaryRequirementChangePrompt(
            currentSummary = currentSummary,
            requirement = requirement,
        )

        assertTrue(prompt.contains("<current_memory_summary>\n$currentSummary\n</current_memory_summary>"))
        assertTrue(prompt.contains("<change_request>\n$requirement\n</change_request>"))
    }

    @Test
    fun systemInstructionsTreatCurrentSummaryAsReferenceData() {
        assertTrue(MEMORY_SUMMARY_REQUIREMENT_CHANGE_SYSTEM_PROMPT.contains("reference data"))
        assertFalse(MEMORY_SUMMARY_REQUIREMENT_CHANGE_SYSTEM_PROMPT.contains("<current_memory_summary>"))
    }
}
