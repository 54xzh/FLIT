package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceFileReferenceToolsTest {

    @Test
    fun `lightweight paths use workspace relative paths`() {
        assertEquals(
            "output/report.pdf",
            normalizeWorkspaceFileReferencePath("/output/report.pdf", sandbox = false),
        )
        assertEquals(
            "output/report.pdf",
            normalizeWorkspaceFileReferencePath("output\\report.pdf", sandbox = false),
        )
    }

    @Test
    fun `sandbox paths require workspace root`() {
        assertEquals(
            "output/report.pdf",
            normalizeWorkspaceFileReferencePath("/workspace/output/report.pdf", sandbox = true),
        )
        assertNull(normalizeWorkspaceFileReferencePath("output/report.pdf", sandbox = true))
        assertNull(normalizeWorkspaceFileReferencePath("/tmp/report.pdf", sandbox = true))
    }

    @Test
    fun `invalid reference paths are rejected`() {
        assertNull(normalizeWorkspaceFileReferencePath("../secret.txt", sandbox = false))
        assertNull(normalizeWorkspaceFileReferencePath("output/../secret.txt", sandbox = true))
        assertNull(normalizeWorkspaceFileReferencePath("/workspace/output//report.pdf", sandbox = true))
        assertNull(normalizeWorkspaceFileReferencePath("/workspace/", sandbox = true))
        assertNull(normalizeWorkspaceFileReferencePath("", sandbox = false))
    }

    @Test
    fun `workspace prompts preserve user-facing deliverable versions`() {
        assertTrue(WORKSPACE_DELIVERABLE_VERSIONING_PROMPT.contains("keep the existing file"))
        val writeTemplate = workspaceToolSystemPromptTemplate("workspace_write_file", includeCommonRules = false)
        assertTrue(writeTemplate.contains("Name the new file after the change the user asked for"))
        assertTrue(writeTemplate.contains("report-english.pdf"))
        assertTrue(
            workspaceToolSystemPromptTemplate(
                WORKSPACE_FILE_REFERENCE_TOOL_NAME,
                includeCommonRules = true,
            ).contains("new deliverable file"),
        )
    }

    @Test
    fun `workspace prompts keep new task files in task folders`() {
        val writeTemplate = workspaceToolSystemPromptTemplate("workspace_write_file", includeCommonRules = false)
        assertTrue(writeTemplate.contains("folder organization"))
        assertTrue(writeTemplate.contains("named after the task instead of the workspace root"))
        assertTrue(writeTemplate.contains("travel-plan/itinerary.md"))
        // 文件夹整理只约束新文件，不能和交付物版本化规则打架
        assertTrue(writeTemplate.contains("Revised versions of an existing file stay in its current directory"))
    }
}
