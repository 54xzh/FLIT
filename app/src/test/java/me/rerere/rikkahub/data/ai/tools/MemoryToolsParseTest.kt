package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryToolsParseTest {

    @Test
    fun tokenize_splitsBySpace() {
        assertEquals(listOf("a", "b", "c"), MemoryTools.tokenize("a b c"))
    }

    @Test
    fun tokenize_collapsesRunsOfWhitespace() {
        assertEquals(listOf("a", "b"), MemoryTools.tokenize("  a   b  "))
    }

    @Test
    fun tokenize_keepsQuotedPhraseTogether() {
        assertEquals(listOf("hello world", "foo"), MemoryTools.tokenize("\"hello world\" foo"))
    }

    @Test
    fun tokenize_unclosedQuoteSwallowsRest() {
        // Quote opens but never closes: everything to the end is one token
        assertEquals(listOf("unclosed phrase"), MemoryTools.tokenize("\"unclosed phrase"))
    }

    @Test
    fun tokenize_emptyClauseGivesEmptyList() {
        assertEquals(emptyList<String>(), MemoryTools.tokenize(""))
        assertEquals(emptyList<String>(), MemoryTools.tokenize("   "))
    }

    @Test
    fun matches_andSemantics_singleClause() {
        // "a b" requires both "a" AND "b" present
        assertTrue(MemoryTools.matches("x a y b z", listOf("a b")))
        assertFalse(MemoryTools.matches("a only", listOf("a b")))
    }

    @Test
    fun matches_orSemantics_multipleClauses() {
        // ["foo","bar"] = foo OR bar — content with only bar should still match
        assertTrue(MemoryTools.matches("just bar here", listOf("foo", "bar")))
        assertFalse(MemoryTools.matches("nothing relevant", listOf("foo", "bar")))
    }

    @Test
    fun matches_isCaseInsensitive() {
        assertTrue(MemoryTools.matches("Project Alpha Deadline", listOf("alpha deadline")))
    }

    @Test
    fun matches_quotedPhrasePreservesSpace() {
        // "周一例会" as one phrase must appear contiguously
        assertTrue(MemoryTools.matches("今天的周一例会纪要", listOf("\"周一例会\" 纪要")))
        assertFalse(MemoryTools.matches("周一开了 例会，再纪要", listOf("\"周一例会\" 纪要")))
    }

    @Test
    fun matches_emptyClauseListReturnsFalse() {
        assertFalse(MemoryTools.matches("anything", emptyList()))
    }

    @Test
    fun matches_blankClauseIsIgnored() {
        // A clause with no terms should not flip the result to true
        assertFalse(MemoryTools.matches("nothing", listOf("   ")))
    }
}
