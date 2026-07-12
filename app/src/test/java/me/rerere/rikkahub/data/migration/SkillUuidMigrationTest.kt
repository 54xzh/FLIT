package me.rerere.rikkahub.data.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillUuidMigrationTest {
    private val migration = SkillUuidMigration.Companion

    // ---- allocateUniqueName ----

    @Test
    fun `allocateUniqueName uses baseName when free`() {
        assertEquals("translator", migration.allocateUniqueName("translator", mutableSetOf()))
    }

    @Test
    fun `allocateUniqueName appends digit on collision`() {
        val used = mutableSetOf("translator")
        assertEquals("translator1", migration.allocateUniqueName("translator", used))
        used.add("translator1")
        assertEquals("translator2", migration.allocateUniqueName("translator", used))
    }

    @Test
    fun `allocateUniqueName falls back to skill root when baseName invalid`() {
        assertEquals("skill1", migration.allocateUniqueName(null, mutableSetOf()))
        assertEquals("skill1", migration.allocateUniqueName("Bad Name", mutableSetOf()))
        val used = mutableSetOf("skill1")
        assertEquals("skill2", migration.allocateUniqueName(null, used))
    }

    @Test
    fun `allocateUniqueName never returns an already-used name`() {
        val used = mutableSetOf("skill", "skill1", "skill2")
        assertEquals("skill3", migration.allocateUniqueName(null, used))
    }

    // ---- rewriteSkillsJson ----

    @Test
    fun `rewriteSkillsJson removes id field`() {
        val raw = """[{"id":"123e4567-e89b-12d3-a456-426614174000","name":"translator","description":"t","folderId":null}]"""
        val out = migration.rewriteSkillsJson(raw)
        assertTrue("id should be removed: $out", !out.contains("\"id\""))
        assertTrue(out.contains("\"name\":\"translator\""))
        assertTrue(out.contains("\"description\":\"t\""))
    }

    @Test
    fun `rewriteSkillsJson preserves entries already without id`() {
        val raw = """[{"name":"pdf-reader","description":"p","folderId":null}]"""
        val out = migration.rewriteSkillsJson(raw)
        assertTrue(out.contains("\"name\":\"pdf-reader\""))
        assertTrue(!out.contains("\"id\""))
    }

    @Test
    fun `rewriteSkillsJson returns raw on parse failure`() {
        val raw = "not json"
        assertEquals(raw, migration.rewriteSkillsJson(raw))
    }

    // ---- rewriteAssistantsJson ----

    @Test
    fun `rewriteAssistantsJson maps enabledSkillIds uuids to names`() {
        val uuid1 = "11111111-1111-4111-8111-111111111111"
        val uuid2 = "22222222-2222-4222-8222-222222222222"
        val raw = """[{"id":"33333333-3333-4333-8333-333333333333","enabledSkillIds":["$uuid1","$uuid2"],"name":"a"}]"""
        val map = mapOf(uuid1 to "translator", uuid2 to "pdf-reader")
        val out = migration.rewriteAssistantsJson(raw, map)
        assertTrue("enabledSkillIds should be gone: $out", !out.contains("enabledSkillIds"))
        assertTrue(out.contains("\"enabledSkills\":[\"translator\",\"pdf-reader\"]"))
    }

    @Test
    fun `rewriteAssistantsJson drops unknown uuids`() {
        val uuid1 = "11111111-1111-4111-8111-111111111111"
        val raw = """[{"enabledSkillIds":["$uuid1","deadbeef-not-a-uuid"],"name":"a"}]"""
        val map = mapOf(uuid1 to "translator")
        val out = migration.rewriteAssistantsJson(raw, map)
        assertTrue(out.contains("\"enabledSkills\":[\"translator\"]"))
    }

    @Test
    fun `rewriteAssistantsJson preserves existing enabledSkills when no enabledSkillIds`() {
        val raw = """[{"enabledSkills":["writer"],"name":"a"}]"""
        val out = migration.rewriteAssistantsJson(raw, emptyMap())
        assertTrue(out.contains("\"enabledSkills\":[\"writer\"]"))
    }

    // ---- rewriteSkillContextsColumn ----

    @Test
    fun `rewriteSkillContextsColumn maps uuids to names`() {
        val uuid = "11111111-1111-4111-8111-111111111111"
        val raw = """["$uuid"]"""
        val out = migration.rewriteSkillContextsColumn(raw, mapOf(uuid to "translator"))
        assertEquals("""["translator"]""", out)
    }

    @Test
    fun `rewriteSkillContextsColumn keeps already-name entries`() {
        val uuid = "11111111-1111-4111-8111-111111111111"
        val raw = """["$uuid","writer"]"""
        val out = migration.rewriteSkillContextsColumn(raw, mapOf(uuid to "translator"))
        assertEquals("""["translator","writer"]""", out)
    }

    @Test
    fun `rewriteSkillContextsColumn keeps non-uuid strings and maps known uuids`() {
        val uuid = "11111111-1111-4111-8111-111111111111"
        // "writer" 不是 UUID，像技能名，应原样保留（不能丢，因为无法判断它是否有效引用）。
        val raw = """["$uuid","writer"]"""
        val out = migration.rewriteSkillContextsColumn(raw, mapOf(uuid to "translator"))
        assertEquals("""["translator","writer"]""", out)
    }

    @Test
    fun `rewriteSkillContextsColumn drops unmapped uuids`() {
        // 这是一个不在映射表里的 UUID 串：映射不到 → 丢弃（对应技能已不存在）。
        val raw = """["99999999-9999-4999-8999-999999999999"]"""
        val out = migration.rewriteSkillContextsColumn(raw, emptyMap())
        assertEquals("""[]""", out)
    }

    @Test
    fun `rewriteSkillContextsColumn returns null when nothing changes`() {
        val raw = """["writer","pdf-reader"]"""
        assertNull(migration.rewriteSkillContextsColumn(raw, emptyMap()))
    }

    @Test
    fun `rewriteSkillContextsColumn returns null on parse failure`() {
        assertNull(migration.rewriteSkillContextsColumn("not json", emptyMap()))
    }
}