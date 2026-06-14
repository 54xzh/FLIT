package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MermaidExportAssetsTest {
    @Test
    fun extractMermaidCodeBlocks_returnsClosedMermaidFences() {
        val markdown = """
            Before
            ```mermaid
            graph TD
                A --> B
            ```
            After
        """.trimIndent()

        val blocks = extractMermaidCodeBlocks(markdown)

        assertEquals(listOf("graph TD\n    A --> B"), blocks)
    }

    @Test
    fun extractMermaidCodeBlocks_ignoresUnclosedFences() {
        val markdown = """
            ```mermaid
            graph TD
                A --> B
        """.trimIndent()

        val blocks = extractMermaidCodeBlocks(markdown)

        assertTrue(blocks.isEmpty())
    }

    @Test
    fun mermaidExportKey_isStableForSameCode() {
        val code = "graph TD\n    A --> B"

        assertEquals(mermaidExportKey(code), mermaidExportKey(code))
    }
}
