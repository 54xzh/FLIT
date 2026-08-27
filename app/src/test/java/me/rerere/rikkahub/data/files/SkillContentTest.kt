package me.rerere.rikkahub.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SkillContentTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `preview removes front matter and keeps first three non blank body lines`() {
        val preview = SkillContent.previewFromText(
            """
            ---
            name: example
            description: Example skill
            ---

            # Title

            First line
            Second line
            Fourth line
            """.trimIndent(),
        )

        assertEquals("# Title\nFirst line\nSecond line", preview)
    }

    @Test
    fun `unclosed front matter is treated as regular content`() {
        val source = "---\nname: example\n# Still content"

        assertEquals(source, SkillContent.extractBody(source))
    }

    @Test
    fun `tree sorts directories first and skill markdown before other files`() {
        val skillDir = tempFolder.newFolder("example")
        File(skillDir, "z.txt").writeText("z")
        File(skillDir, "SKILL.md").writeText("skill")
        val tools = File(skillDir, "tools").apply { mkdirs() }
        File(tools, "run.py").writeText("print('ok')")

        val tree = SkillDirectoryTree.build(skillDir)

        assertEquals("tools", tree[0].name)
        assertEquals("SKILL.md", tree[1].name)
        assertEquals("z.txt", tree[2].name)
        val toolsNode = tree[0] as SkillDirectoryNode.DirectoryNode
        assertEquals("tools/run.py", toolsNode.children.single().relativePath)
    }

    @Test
    fun `tree refuses invalid skill name when loading from skills root`() {
        val skillsRoot = tempFolder.newFolder("skills")
        tempFolder.newFolder("outside")

        assertTrue(SkillDirectoryTree.load(skillsRoot, "../outside").isEmpty())
    }
}
