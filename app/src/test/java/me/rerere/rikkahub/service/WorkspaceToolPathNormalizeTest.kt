package me.rerere.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkspaceToolPathNormalizeTest {

    @Test
    fun `normal relative path passes through`() {
        assertEquals("folder/file.txt", normalizeWorkspaceToolPath("folder/file.txt", allowBlank = false))
    }

    @Test
    fun `leading slash is tolerated as relative path`() {
        // 模型偶尔把工作区根目录理解成 "/"，宽容处理与 workspace_list 保持一致
        assertEquals("notes.txt", normalizeWorkspaceToolPath("/notes.txt", allowBlank = false))
        assertEquals("a/b.txt", normalizeWorkspaceToolPath("///a/b.txt", allowBlank = false))
    }

    @Test
    fun `single slash is still rejected`() {
        // 根目录只能用空字符串显式指定，"/" 不应命中根目录（尤其是 workspace_delete）
        assertNull(normalizeWorkspaceToolPath("/", allowBlank = false))
        assertNull(normalizeWorkspaceToolPath("/", allowBlank = true))
        assertNull(normalizeWorkspaceToolPath("  //  ", allowBlank = true))
    }

    @Test
    fun `blank path follows allowBlank`() {
        assertEquals("", normalizeWorkspaceToolPath("", allowBlank = true))
        assertNull(normalizeWorkspaceToolPath("", allowBlank = false))
        assertNull(normalizeWorkspaceToolPath(null, allowBlank = false))
    }

    @Test
    fun `windows style separators are tolerated`() {
        assertEquals("folder/file.txt", normalizeWorkspaceToolPath("\\folder\\file.txt", allowBlank = false))
        assertEquals("folder/file.txt", normalizeWorkspaceToolPath("folder\\file.txt", allowBlank = false))
        assertNull(normalizeWorkspaceToolPath("\\", allowBlank = true))
    }

    @Test
    fun `parent traversal is rejected`() {
        assertNull(normalizeWorkspaceToolPath("../secret.txt", allowBlank = false))
        assertNull(normalizeWorkspaceToolPath("/a/../b.txt", allowBlank = false))
    }

    @Test
    fun `list path maps root aliases to empty string`() {
        assertEquals("", normalizeWorkspaceListToolPath(null))
        assertEquals("", normalizeWorkspaceListToolPath("/"))
        assertEquals("", normalizeWorkspaceListToolPath("."))
        assertEquals("", normalizeWorkspaceListToolPath("\\"))
        assertEquals("docs", normalizeWorkspaceListToolPath("/docs"))
        assertEquals("docs", normalizeWorkspaceListToolPath("\\docs"))
    }
}
