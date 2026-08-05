package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkspaceMarkdownLinkTest {
    @Test
    fun `extracts valid workspace links in source order`() {
        val content = """
            [one](/workspace/output/one.txt)
            [with space](/workspace/output/report final.pdf)
            [encoded space](/workspace/output/data%20table.csv)
            ![image](/workspace/output/image.png)
            [web](https://example.com/report.pdf)
            `[inline](/workspace/output/code.txt)`
            ```text
            [code](/workspace/output/code-block.txt)
            ```
            [invalid parent](/workspace/../secret.txt)
            [invalid dot](/workspace/output/./secret.txt)
            [invalid empty](/workspace/output//secret.txt)
        """.trimIndent() + "\n[invalid null](/workspace/output/bad" + "\u0000" + ".txt)"

        assertEquals(
            listOf("output/one.txt", "output/report final.pdf", "output/data table.csv"),
            extractWorkspaceFileReferencePaths(content),
        )
    }

    @Test
    fun `only workspace prefix is treated as a file link`() {
        assertEquals("output/report.pdf", workspaceMarkdownLinkPath("/workspace/output/report.pdf"))
        assertEquals(
            "output/report final.pdf",
            workspaceMarkdownLinkPath("/workspace/output/report%20final.pdf"),
        )
        assertEquals(
            "output/report+final.pdf",
            workspaceMarkdownLinkPath("/workspace/output/report+final.pdf"),
        )
        assertEquals(
            "output/report+final.pdf",
            workspaceMarkdownLinkPath("/workspace/output/report%2Bfinal.pdf"),
        )
        assertNull(workspaceMarkdownLinkPath("/workspace/output/%2E%2E/secret.txt"))
        assertNull(workspaceMarkdownLinkPath("/workspace/output/bad%00.txt"))
        assertNull(workspaceMarkdownLinkPath("https://example.com/report.pdf"))
        assertNull(workspaceMarkdownLinkPath("/workspace/../secret.txt"))
        assertNull(workspaceMarkdownLinkPath("/workspace/"))
    }
}
