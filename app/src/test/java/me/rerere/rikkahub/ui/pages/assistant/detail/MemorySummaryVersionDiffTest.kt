package me.rerere.rikkahub.ui.pages.assistant.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import me.rerere.rikkahub.ui.components.richtext.MarkdownVersionDiffMarkers

class MemorySummaryVersionDiffTest {
    @Test
    fun `only changed markdown heading blocks are returned`() {
        val diff = buildMemorySummaryVersionDiff(
            previous = """
                # User
                Likes concise answers.

                # Food
                I ate a piece of cake today.
            """.trimIndent(),
            current = """
                # User
                Likes concise answers.

                # Food
                I ate half a watermelon today.
            """.trimIndent(),
        )

        assertEquals(1, diff.size)
        assertEquals("Food", diff.single().title)
        assertTrue(diff.single().parts.any { it.operation == MemorySummaryTextDiffOperation.DELETE })
        assertTrue(diff.single().parts.any { it.operation == MemorySummaryTextDiffOperation.INSERT })
    }

    @Test
    fun `summary without headings is compared as one block`() {
        val diff = buildMemorySummaryVersionDiff(
            previous = "I ate a piece of cake today.",
            current = "I ate half a watermelon today.",
        )

        assertEquals(1, diff.size)
        assertEquals(null, diff.single().title)
        assertTrue(diff.single().parts.any { it.operation == MemorySummaryTextDiffOperation.DELETE })
        assertTrue(diff.single().parts.any { it.operation == MemorySummaryTextDiffOperation.INSERT })
    }

    @Test
    fun `added and deleted headings remain visible as changed blocks`() {
        val diff = buildMemorySummaryVersionDiff(
            previous = "# Old heading\nOld content",
            current = "# New heading\nNew content",
        )

        assertEquals(listOf("New heading", "Old heading"), diff.map { it.title })
        assertEquals(MemorySummaryTextDiffOperation.INSERT, diff.first().operation)
        assertEquals(MemorySummaryTextDiffOperation.DELETE, diff.last().operation)
    }

    @Test
    fun `diff markdown preserves headings and uses external deletion markers`() {
        val markdown = buildMemorySummaryVersionDiffMarkdown(
            previous = "# Food\nI ate a piece of cake today.",
            current = "# Food\nI ate half a watermelon today.",
        )

        assertTrue(markdown!!.startsWith("# Food"))
        assertTrue(markdown.contains(MarkdownVersionDiffMarkers.DELETE_START))
        assertTrue(markdown.contains(MarkdownVersionDiffMarkers.DELETE_END))
        assertTrue(markdown.contains(MarkdownVersionDiffMarkers.encodeDeletedText("a piece of cake")))
        assertTrue(markdown.contains("half a watermelon"))
    }

    @Test
    fun `identical versions have no diff markdown`() {
        assertEquals(
            null,
            buildMemorySummaryVersionDiffMarkdown(
                previous = "# Food\nI ate cake.",
                current = "# Food\nI ate cake.",
            ),
        )
    }
}
