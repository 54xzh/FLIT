package me.rerere.rikkahub.data.ai.tools

import me.rerere.rikkahub.data.repository.normalizeWorkspaceFileReferencePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceFileReferenceToolsTest {
    @Test
    fun `workspace links use normalized relative paths`() {
        assertEquals(
            "output/report.pdf",
            normalizeWorkspaceFileReferencePath("/output/report.pdf"),
        )
        assertEquals(
            "output/report final.pdf",
            normalizeWorkspaceFileReferencePath("output\\report final.pdf"),
        )
    }

    @Test
    fun `invalid workspace link paths are rejected`() {
        assertNull(normalizeWorkspaceFileReferencePath(""))
        assertNull(normalizeWorkspaceFileReferencePath("../secret.txt"))
        assertNull(normalizeWorkspaceFileReferencePath("output/../secret.txt"))
        assertNull(normalizeWorkspaceFileReferencePath("output//report.pdf"))
        assertNull(normalizeWorkspaceFileReferencePath("output/./report.pdf"))
        assertNull(normalizeWorkspaceFileReferencePath("output/secret\u0000.txt"))
    }

    @Test
    fun `workspace prompts describe markdown file references`() {
        assertTrue(WORKSPACE_COMMON_RULES_PROMPT.contains("[file name](/workspace/relative/path)"))
        assertTrue(WORKSPACE_COMMON_RULES_PROMPT.contains("logical prefix"))
        assertTrue(!WORKSPACE_COMMON_RULES_PROMPT.contains("workspace_" + "send_file"))
        assertTrue(WORKSPACE_DELIVERABLE_VERSIONING_PROMPT.contains("Markdown workspace link"))
    }

    @Test
    fun `workspace prompts keep new task files in task folders`() {
        val writeTemplate = workspaceToolSystemPromptTemplate("workspace_write_file", includeCommonRules = false)
        assertTrue(writeTemplate.contains("folder organization"))
        assertTrue(writeTemplate.contains("named after the task instead of the workspace root"))
        assertTrue(writeTemplate.contains("travel-plan/itinerary.md"))
        assertTrue(writeTemplate.contains("Revised versions of an existing file stay in its current directory"))
    }
}
