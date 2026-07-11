package me.rerere.rikkahub.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UniqueWorkspaceNameTest {

    @Test
    fun returnsBaseWhenUnused() {
        assertEquals("Sandbox", uniqueWorkspaceName("Sandbox", emptyList()))
        assertEquals("Sandbox", uniqueWorkspaceName("Sandbox", listOf("Other")))
    }

    @Test
    fun appendsNumericSuffixWhenTaken() {
        assertEquals("Sandbox 2", uniqueWorkspaceName("Sandbox", listOf("Sandbox")))
        assertEquals(
            "Sandbox 3",
            uniqueWorkspaceName("Sandbox", listOf("Sandbox", "Sandbox 2")),
        )
    }

    @Test
    fun trimsAndUsesBlankFallback() {
        assertEquals("Sandbox", uniqueWorkspaceName("  ", emptyList()))
        assertEquals("Workspace", uniqueWorkspaceName("", emptyList(), blankFallback = "Workspace"))
        assertEquals(
            "Workspace 2",
            uniqueWorkspaceName("   ", listOf("Workspace"), blankFallback = "Workspace"),
        )
    }

    @Test
    fun ignoresExistingWhitespaceWhenComparing() {
        assertEquals(
            "Sandbox 2",
            uniqueWorkspaceName("Sandbox", listOf("  Sandbox  ")),
        )
    }

    @Test
    fun respectsMaxLengthWhenAppendingSuffix() {
        val longBase = "A".repeat(200)
        val result = uniqueWorkspaceName(longBase, listOf(longBase))
        assertEquals(200, result.length)
        assertEquals(" 2", result.takeLast(2))
    }

    @Test
    fun rejectsEmptyFallback() {
        assertThrows(IllegalStateException::class.java) {
            uniqueWorkspaceName("  ", emptyList(), blankFallback = "  ")
        }
    }
}
