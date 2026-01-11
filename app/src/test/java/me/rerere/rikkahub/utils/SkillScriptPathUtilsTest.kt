package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class SkillScriptPathUtilsTest {
    @Test
    fun `normalizeAndValidateScriptPath accepts scripts py`() {
        assertEquals("scripts/save_result.py", SkillScriptPathUtils.normalizeAndValidateScriptPath("scripts/save_result.py"))
        assertEquals("scripts/save_result.py", SkillScriptPathUtils.normalizeAndValidateScriptPath("scripts\\save_result.py"))
        assertEquals("scripts/save_result.py", SkillScriptPathUtils.normalizeAndValidateScriptPath("./scripts/save_result.py"))
    }

    @Test
    fun `normalizeAndValidateScriptPath rejects unsafe or non py`() {
        assertNull(SkillScriptPathUtils.normalizeAndValidateScriptPath(""))
        assertNull(SkillScriptPathUtils.normalizeAndValidateScriptPath("save_result.py"))
        assertNull(SkillScriptPathUtils.normalizeAndValidateScriptPath("/scripts/save_result.py"))
        assertNull(SkillScriptPathUtils.normalizeAndValidateScriptPath("scripts/save_result.txt"))
        assertNull(SkillScriptPathUtils.normalizeAndValidateScriptPath("scripts/../save_result.py"))
        assertNull(SkillScriptPathUtils.normalizeAndValidateScriptPath("scripts/./save_result.py"))
    }

    @Test
    fun `normalizeAndValidateWorkDirRelPath normalizes separators`() {
        assertEquals("ProjectA/task-1", SkillScriptPathUtils.normalizeAndValidateWorkDirRelPath("ProjectA/task-1"))
        assertEquals("ProjectA/task-1", SkillScriptPathUtils.normalizeAndValidateWorkDirRelPath("ProjectA\\task-1"))
    }

    @Test
    fun `normalizeAndValidateWorkDirRelPath rejects unsafe`() {
        assertNull(SkillScriptPathUtils.normalizeAndValidateWorkDirRelPath(""))
        assertNull(SkillScriptPathUtils.normalizeAndValidateWorkDirRelPath("/ProjectA"))
        assertNull(SkillScriptPathUtils.normalizeAndValidateWorkDirRelPath("../ProjectA"))
        assertNull(SkillScriptPathUtils.normalizeAndValidateWorkDirRelPath("ProjectA/../task"))
    }

    @Test
    fun `normalizeAndValidateWorkspaceFileRelPath normalizes separators`() {
        assertEquals("folder/file.txt", SkillScriptPathUtils.normalizeAndValidateWorkspaceFileRelPath("folder/file.txt"))
        assertEquals("folder/file.txt", SkillScriptPathUtils.normalizeAndValidateWorkspaceFileRelPath("folder\\file.txt"))
        assertEquals("folder/file.txt", SkillScriptPathUtils.normalizeAndValidateWorkspaceFileRelPath("./folder/file.txt"))
    }

    @Test
    fun `normalizeAndValidateWorkspaceFileRelPath rejects unsafe`() {
        assertNull(SkillScriptPathUtils.normalizeAndValidateWorkspaceFileRelPath(""))
        assertNull(SkillScriptPathUtils.normalizeAndValidateWorkspaceFileRelPath("/file.txt"))
        assertNull(SkillScriptPathUtils.normalizeAndValidateWorkspaceFileRelPath("../file.txt"))
        assertNull(SkillScriptPathUtils.normalizeAndValidateWorkspaceFileRelPath("folder/../file.txt"))
        assertNull(SkillScriptPathUtils.normalizeAndValidateWorkspaceFileRelPath("folder/./file.txt"))
    }

    @Test
    fun `sanitizeWorkDirBaseName produces safe folder name`() {
        assertEquals("Chat", SkillScriptPathUtils.sanitizeWorkDirBaseName("   "))
        assertEquals("Test____", SkillScriptPathUtils.sanitizeWorkDirBaseName("Test:<>|"))
        assertTrue(SkillScriptPathUtils.sanitizeWorkDirBaseName("a".repeat(200)).length <= 64)
    }

    @Test
    fun `pickUniqueName appends index`() {
        val existing = setOf("Chat", "Chat (2)")
        assertEquals("Chat (3)", SkillScriptPathUtils.pickUniqueName(existing, "Chat"))
        assertEquals("New", SkillScriptPathUtils.pickUniqueName(existing, "New"))
    }

    @Test
    fun `datePlaceholderWorkDirBaseName formats by zone`() {
        val instant = Instant.parse("2026-01-11T01:02:03Z")
        assertEquals(
            "2026-01-11",
            SkillScriptPathUtils.datePlaceholderWorkDirBaseName(instant, zoneId = ZoneId.of("UTC"))
        )
    }

    @Test
    fun `isDatePlaceholderWorkDirBaseName detects placeholder`() {
        assertTrue(SkillScriptPathUtils.isDatePlaceholderWorkDirBaseName("2026-01-11"))
        assertTrue(SkillScriptPathUtils.isDatePlaceholderWorkDirBaseName("2026-01-11 (2)"))
    }

    @Test
    fun `isDatePlaceholderWorkDirBaseName rejects invalid`() {
        assertTrue(!SkillScriptPathUtils.isDatePlaceholderWorkDirBaseName("Chat"))
        assertTrue(!SkillScriptPathUtils.isDatePlaceholderWorkDirBaseName("2026-01-32"))
        assertTrue(!SkillScriptPathUtils.isDatePlaceholderWorkDirBaseName("2026-01-11 (x)"))
        assertTrue(!SkillScriptPathUtils.isDatePlaceholderWorkDirBaseName("2026-01-11 (2) extra"))
    }
}
