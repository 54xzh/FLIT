package me.rerere.rikkahub.utils

import me.rerere.rikkahub.data.model.Skill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class SkillZipImportTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `parseFrontMatter reads folded multiline description`() {
        val frontMatter = SkillZipImport.parseFrontMatter(
            """
            ---
            name: caveman
            description: >
              Ultra-compressed communication mode. Cuts token usage ~75% by speaking like caveman
              while keeping full technical accuracy. Supports intensity levels: lite, full (default), ultra,
              wenyan-lite, wenyan-full, wenyan-ultra.
              Use when user says "caveman mode", "talk like caveman", "use caveman", "less tokens",
              "be brief", or invokes /caveman. Also auto-triggers when token efficiency is requested.
            ---
            Body
            """.trimIndent()
        )

        assertEquals("caveman", frontMatter.name)
        assertEquals(
            "Ultra-compressed communication mode. Cuts token usage ~75% by speaking like caveman " +
                "while keeping full technical accuracy. Supports intensity levels: lite, full (default), ultra, " +
                "wenyan-lite, wenyan-full, wenyan-ultra. " +
                "Use when user says \"caveman mode\", \"talk like caveman\", \"use caveman\", \"less tokens\", " +
                "\"be brief\", or invokes /caveman. Also auto-triggers when token efficiency is requested.",
            frontMatter.description
        )
    }

    @Test
    fun `parseFrontMatter reads literal multiline description`() {
        val frontMatter = SkillZipImport.parseFrontMatter(
            """
            ---
            name: writer
            description: |
              First line.
              Second line.
            ---
            """.trimIndent()
        )

        assertEquals("writer", frontMatter.name)
        assertEquals("First line.\nSecond line.", frontMatter.description)
    }

    @Test
    fun `parseFrontMatter still reads single line values`() {
        val frontMatter = SkillZipImport.parseFrontMatter(
            """
            ---
            name: "brief"
            description: 'Keep answers short'
            ---
            """.trimIndent()
        )

        assertEquals("brief", frontMatter.name)
        assertEquals("Keep answers short", frontMatter.description)
    }

    @Test
    fun `ensureFrontMatterName adds front matter when missing`() {
        val file = tempFolder.newFile("SKILL.md")
        file.writeText("Hello body without front matter")
        assertTrue(SkillZipImport.ensureFrontMatterName(file, "translator"))
        val frontMatter = SkillZipImport.parseFrontMatter(file.readText())
        assertEquals("translator", frontMatter.name)
    }

    @Test
    fun `ensureFrontMatterName replaces existing name`() {
        val file = tempFolder.newFile("SKILL.md")
        file.writeText("---\nname: old-name\ndescription: hi\n---\nbody")
        assertTrue(SkillZipImport.ensureFrontMatterName(file, "new-name"))
        val frontMatter = SkillZipImport.parseFrontMatter(file.readText())
        assertEquals("new-name", frontMatter.name)
        assertEquals("hi", frontMatter.description)
    }

    @Test
    fun `ensureFrontMatterName inserts name into front matter that lacks it`() {
        val file = tempFolder.newFile("SKILL.md")
        file.writeText("---\ndescription: hi\n---\nbody")
        assertTrue(SkillZipImport.ensureFrontMatterName(file, "translator"))
        val frontMatter = SkillZipImport.parseFrontMatter(file.readText())
        assertEquals("translator", frontMatter.name)
        assertEquals("hi", frontMatter.description)
    }

    @Test
    fun `valid single skill imports atomically`() {
        val (skillsRoot, unzipped) = newLayout()
        unzipInto(unzipped, "translator" to skillMd("translator", "A translator skill"))

        val result = SkillZipImport.importExtracted(unzipped, skillsRoot, emptySet(), archiveName = "pkg")

        assertTrue(result is SkillZipImport.ImportResult.Success)
        val skills = (result as SkillZipImport.ImportResult.Success).skills
        assertEquals(1, skills.size)
        assertEquals("translator", skills[0].name)
        val skillDir = File(skillsRoot, "translator")
        assertTrue("skill dir should exist", skillDir.isDirectory)
        val fm = SkillZipImport.parseFrontMatter(File(skillDir, "SKILL.md").readText())
        assertEquals("translator", fm.name)
    }

    @Test
    fun `multiple valid skills import atomically`() {
        val (skillsRoot, unzipped) = newLayout()
        unzipInto(
            unzipped,
            "translator" to skillMd("translator", "t"),
            "pdf-reader" to skillMd("pdf-reader", "p"),
        )

        val result = SkillZipImport.importExtracted(unzipped, skillsRoot, emptySet(), archiveName = "pkg")

        assertTrue(result is SkillZipImport.ImportResult.Success)
        val skills = (result as SkillZipImport.ImportResult.Success).skills
        assertEquals(2, skills.size)
        assertTrue(File(skillsRoot, "translator").isDirectory)
        assertTrue(File(skillsRoot, "pdf-reader").isDirectory)
    }

    @Test
    fun `invalid name is rejected`() {
        val (skillsRoot, unzipped) = newLayout()
        unzipInto(unzipped, "PDF Reader" to skillMd("PDF Reader", "bad name"))

        val result = SkillZipImport.importExtracted(unzipped, skillsRoot, emptySet(), archiveName = "pkg")

        assertTrue("expected Error, got $result", result is SkillZipImport.ImportResult.Error)
        // skillsRoot 本身就是 skills 目录；失败时不应留下任何技能子目录。
        assertFalse(skillsRoot.exists() && skillsRoot.listFiles().orEmpty().isNotEmpty())
    }

    @Test
    fun `name with path separator is rejected`() {
        val (skillsRoot, unzipped) = newLayout()
        unzipInto(unzipped, "foo" to skillMd("../x", "traversal"))

        val result = SkillZipImport.importExtracted(unzipped, skillsRoot, emptySet(), archiveName = "pkg")
        assertTrue(result is SkillZipImport.ImportResult.Error)
    }

    @Test
    fun `leading or trailing hyphen name is rejected`() {
        for (bad in listOf("-translator", "translator-")) {
            val (skillsRoot, unzipped) = newLayout()
            unzipInto(unzipped, "dir" to skillMd(bad, "bad"))
            val result = SkillZipImport.importExtracted(unzipped, skillsRoot, emptySet(), archiveName = "pkg")
            assertTrue("$bad should be rejected, got $result", result is SkillZipImport.ImportResult.Error)
        }
    }

    @Test
    fun `duplicate names inside zip are rejected`() {
        val (skillsRoot, unzipped) = newLayout()
        unzipInto(
            unzipped,
            "a" to skillMd("translator", "first"),
            "b" to skillMd("translator", "second"),
        )

        val result = SkillZipImport.importExtracted(unzipped, skillsRoot, emptySet(), archiveName = "pkg")
        assertTrue(result is SkillZipImport.ImportResult.Error)
    }

    @Test
    fun `name colliding with installed skill is rejected`() {
        val (skillsRoot, unzipped) = newLayout()
        val existing = File(skillsRoot, "translator").apply { mkdirs() }
        File(existing, "SKILL.md").writeText(skillMd("translator", "existing"))

        unzipInto(unzipped, "pkg" to skillMd("translator", "new"))
        val result = SkillZipImport.importExtracted(
            unzipped, skillsRoot, existingSkillNames = setOf("translator"), archiveName = "pkg"
        )
        assertTrue(result is SkillZipImport.ImportResult.Error)
    }

    @Test
    fun `missing name field is rejected`() {
        val (skillsRoot, unzipped) = newLayout()
        unzipInto(unzipped, "translator" to "---\ndescription: no name here\n---\nbody")

        val result = SkillZipImport.importExtracted(unzipped, skillsRoot, emptySet(), archiveName = "pkg")
        assertTrue(result is SkillZipImport.ImportResult.Error)
        assertFalse(File(skillsRoot, "translator").exists())
    }

    @Test
    fun `failed import leaves no half-installed directories`() {
        val (skillsRoot, unzipped) = newLayout()
        // Two skills, second one has an invalid name -> whole import must abort, no dir for the first.
        unzipInto(
            unzipped,
            "good" to skillMd("good-skill", "ok"),
            "bad" to skillMd("Bad Skill", "bad"),
        )

        val result = SkillZipImport.importExtracted(unzipped, skillsRoot, emptySet(), archiveName = "pkg")
        assertTrue(result is SkillZipImport.ImportResult.Error)
        assertFalse(
            "good-skill dir should not be left behind on failure",
            File(skillsRoot, "good-skill").exists(),
        )
        assertFalse(
            "Bad Skill dir should not exist either",
            File(skillsRoot, "Bad Skill").exists(),
        )
    }

    @Test
    fun `Skill NAME_REGEX validates expected names`() {
        assertTrue(Skill.isValidName("translator"))
        assertTrue(Skill.isValidName("pdf-reader"))
        assertTrue(Skill.isValidName("android-code-review"))
        assertTrue(Skill.isValidName("a1"))
        assertFalse(Skill.isValidName("PDF Reader"))
        assertFalse(Skill.isValidName("../x"))
        assertFalse(Skill.isValidName("foo/bar"))
        assertFalse(Skill.isValidName("-translator"))
        assertFalse(Skill.isValidName("translator-"))
        assertFalse(Skill.isValidName("translator--double"))
        assertFalse(Skill.isValidName(""))
    }

    // ---- helpers ----

    private fun newLayout(): Pair<File, File> {
        // skillsRoot 直接作为 skills 目录（与正式代码 File(filesDir, "skills") 对齐）；
        // unzipped 放在独立临时目录，避免被 walkTopDown 当成技能目录。
        val skillsRoot = tempFolder.newFolder()
        val unzipped = tempFolder.newFolder()
        return skillsRoot to unzipped
    }

    private fun skillMd(name: String, description: String): String =
        "---\nname: $name\ndescription: $description\n---\nbody"

    private fun unzipInto(target: File, vararg entries: Pair<String, String>) {
        val zip = tempFolder.newFile()
        ZipOutputStream(zip.outputStream()).use { zos ->
            entries.forEach { (dir, content) ->
                zos.putNextEntry(ZipEntry("$dir/SKILL.md"))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        ZipInputStream(zip.inputStream()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val out = File(target, entry.name)
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
            }
        }
    }
}