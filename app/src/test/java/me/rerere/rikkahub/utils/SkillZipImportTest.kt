package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class SkillZipImportTest {
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
}
