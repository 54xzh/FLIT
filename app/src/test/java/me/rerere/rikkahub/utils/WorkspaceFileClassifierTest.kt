package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceFileClassifierTest {

    @Test
    fun `markdown extensions classify as MARKDOWN`() {
        for (name in listOf("readme.md", "README.MD", "notes.markdown", "a/b/c.MARKDOWN")) {
            val c = WorkspaceFileClassifier.classify(name)
            assertEquals("$name -> MARKDOWN", WorkspaceFileClassifier.Category.MARKDOWN, c.category)
        }
    }

    @Test
    fun `SKILL_MD file name classifies as MARKDOWN`() {
        // SKILL.md 本身是技能定义文件，应作为 markdown 预览，而不是技能包。
        val c = WorkspaceFileClassifier.classify("SKILL.md")
        assertEquals(WorkspaceFileClassifier.Category.MARKDOWN, c.category)
    }

    @Test
    fun `skill package extension classifies as SKILL_PACKAGE`() {
        for (name in listOf("my-tool.skill", "My.Tool.SKILL", "pkg/a.skill")) {
            val c = WorkspaceFileClassifier.classify(name)
            assertEquals("$name -> SKILL_PACKAGE", WorkspaceFileClassifier.Category.SKILL_PACKAGE, c.category)
        }
    }

    @Test
    fun `code extensions classify as CODE with prism language`() {
        val cases = mapOf(
            "script.sh" to "bash",
            "app.kt" to "kotlin",
            "App.kts" to "kotlin",
            "config.json" to "json",
            "data.yml" to "yaml",
            "page.html" to "html",
            "style.css" to "css",
            "query.sql" to "sql",
            "main.py" to "python",
            "main.js" to "javascript",
            "app.tsx" to "tsx",
            "Dockerfile.dockerfile" to "docker",
        )
        cases.forEach { (name, lang) ->
            val c = WorkspaceFileClassifier.classify(name)
            assertEquals("$name -> CODE", WorkspaceFileClassifier.Category.CODE, c.category)
            assertEquals("$name prism lang", lang, c.prismLanguage)
        }
    }

    @Test
    fun `text extensions classify as TEXT`() {
        for (name in listOf("notes.txt", "app.log", "data.csv", "notes.text", "sub/a.LOG")) {
            val c = WorkspaceFileClassifier.classify(name)
            assertEquals("$name -> TEXT", WorkspaceFileClassifier.Category.TEXT, c.category)
            assertNull("$name should have no prism language", c.prismLanguage)
        }
    }

    @Test
    fun `special file names without extension classify as TEXT`() {
        for (name in listOf("Makefile", "MAKEFILE", "Dockerfile", ".gitignore", ".env", "build.gradle")) {
            val c = WorkspaceFileClassifier.classify(name)
            assertEquals("$name -> TEXT", WorkspaceFileClassifier.Category.TEXT, c.category)
        }
    }

    @Test
    fun `unknown extensions classify as OTHER`() {
        for (name in listOf("photo.jpg", "video.mp4", "archive.zip", "binary.bin", "data.xlsx", "noext_file")) {
            val c = WorkspaceFileClassifier.classify(name)
            assertEquals("$name -> OTHER", WorkspaceFileClassifier.Category.OTHER, c.category)
        }
    }

    @Test
    fun `empty and blank names classify as OTHER`() {
        assertEquals(WorkspaceFileClassifier.Category.OTHER, WorkspaceFileClassifier.classify("").category)
        assertEquals(WorkspaceFileClassifier.Category.OTHER, WorkspaceFileClassifier.classify("   ").category)
    }

    @Test
    fun `path separators in name are handled`() {
        // 带路径的文件名应取最后一段判断扩展名。
        val c = WorkspaceFileClassifier.classify("dir/sub/config.json")
        assertEquals(WorkspaceFileClassifier.Category.CODE, c.category)
        assertEquals("json", c.prismLanguage)
    }

    @Test
    fun `shouldUseBuiltInViewer flags text and skill files`() {
        assertTrue(WorkspaceFileClassifier.shouldUseBuiltInViewer("readme.md"))
        assertTrue(WorkspaceFileClassifier.shouldUseBuiltInViewer("script.sh"))
        assertTrue(WorkspaceFileClassifier.shouldUseBuiltInViewer("notes.txt"))
        assertTrue(WorkspaceFileClassifier.shouldUseBuiltInViewer("my-tool.skill"))
        assertTrue(WorkspaceFileClassifier.shouldUseBuiltInViewer("Dockerfile"))
        assertFalse(WorkspaceFileClassifier.shouldUseBuiltInViewer("photo.jpg"))
        assertFalse(WorkspaceFileClassifier.shouldUseBuiltInViewer("archive.zip"))
        assertFalse(WorkspaceFileClassifier.shouldUseBuiltInViewer("binary.bin"))
    }

    @Test
    fun `skill package extension constant is lowercase`() {
        // 确保常量值与 classify 内部比较一致（小写）。
        assertEquals("skill", WorkspaceFileClassifier.SKILL_PACKAGE_EXTENSION)
    }
}