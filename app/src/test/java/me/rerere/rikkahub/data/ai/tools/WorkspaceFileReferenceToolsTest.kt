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
        assertTrue(WORKSPACE_DELIVERABLE_VERSIONING_PROMPT.contains("do not overwrite the existing file"))
        val writeTemplate = workspaceToolSystemPromptTemplate("workspace_write_file", includeCommonRules = false)
        assertTrue(writeTemplate.contains("Name the new file after the change the user asked for"))
        assertTrue(writeTemplate.contains("诗词-楷体版.docx"))
        assertTrue(
            workspaceToolSystemPromptTemplate(
                WORKSPACE_FILE_REFERENCE_TOOL_NAME,
                includeCommonRules = true,
            ).contains("new deliverable file"),
        )
    }
}
