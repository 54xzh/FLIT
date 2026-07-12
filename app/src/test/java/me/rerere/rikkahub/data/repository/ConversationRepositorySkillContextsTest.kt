package me.rerere.rikkahub.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationRepositorySkillContextsTest {

    private val repo = ConversationRepository.Companion

    @Test
    fun `keeps valid skill names, drops invalid`() {
        val raw = """["translator","deleted-one","writer"]"""
        val out = repo.rewriteSkillContextsColumnKeepValid(raw, setOf("translator", "writer", "pdf-reader"))
        // 保留顺序：translator、writer；deleted-one 被移除。
        assertEquals("""["translator","writer"]""", out)
    }

    @Test
    fun `returns null when nothing changes`() {
        val raw = """["translator","writer"]"""
        assertNull(repo.rewriteSkillContextsColumnKeepValid(raw, setOf("translator", "writer")))
    }

    @Test
    fun `drops all when none valid`() {
        val raw = """["translator"]"""
        assertEquals("[]", repo.rewriteSkillContextsColumnKeepValid(raw, emptySet()))
    }

    @Test
    fun `returns null on parse failure`() {
        assertNull(repo.rewriteSkillContextsColumnKeepValid("not json", setOf("translator")))
    }

    @Test
    fun `returns null on empty array`() {
        assertNull(repo.rewriteSkillContextsColumnKeepValid("[]", setOf("translator")))
    }
}