package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitSectionsTest {
    @Test
    fun splitSections_splitsOnH2Headers() {
        val prompt = """
            ## workspace tools (common rules)

            ### scope
            - Operates only within the current conversation workspace.

            ## tool: workspace_list

            ### examples
            - List workspace root: {"path":"","recursive":false}
        """.trimIndent()

        val sections = prompt.splitSections()
        assertEquals(2, sections.size)
        assertTrue(sections[0].startsWith("## workspace tools (common rules)"))
        assertTrue(sections[1].startsWith("## tool: workspace_list"))
    }

    @Test
    fun splitSections_keepsLeadingTextBeforeFirstHeader() {
        // SANDBOX_PROMPT starts with plain prose before any `## ` header.
        val prompt = """
            You are using a persistent Linux sandbox. Sandbox file tools accept absolute paths under /workspace only.

            ### deliverable versioning
            - workspace_send_file does not copy the file.

            ## a header

            body
        """.trimIndent()

        val sections = prompt.splitSections()
        assertEquals(2, sections.size)
        // First section is the leading prose (no `## ` prefix).
        assertTrue(sections[0].startsWith("You are using a persistent Linux sandbox"))
        assertTrue(sections[1].startsWith("## a header"))
    }

    @Test
    fun splitSections_dedupCollapsesSharedCommonRules() {
        // Two workspace tools share the identical common-rules block but carry distinct
        // `## tool:` blocks. Whole-text dedup would inject common rules twice; per-section dedup
        // must collapse it to one while keeping both private blocks.
        val commonRules = """
            ## workspace tools (common rules)

            ### scope
            - Operates only within the current conversation workspace.
        """.trimIndent()
        val listPrompt = "$commonRules\n\n## tool: workspace_list\n\n### examples\n- {\"path\":\"\"}"
        val sendFilePrompt = "$commonRules\n\n## tool: workspace_send_file\n\n### examples\n- {\"path\":\"output/report.pdf\"}"

        val seen = linkedSetOf<String>()
        val injected = mutableListOf<String>()
        for (prompt in listOf(listPrompt, sendFilePrompt)) {
            for (section in prompt.splitSections()) {
                if (seen.add(section.trim())) {
                    injected.add(section)
                }
            }
        }

        // 1 shared common-rules section + 2 distinct tool sections = 3
        assertEquals(3, injected.size)
        // The common-rules block must appear exactly once.
        assertEquals(1, injected.count { it.startsWith("## workspace tools (common rules)") })
    }

    @Test
    fun splitSections_dedupCollapsesRepeatedSandboxPrompt() {
        // The four sandbox tools all return the same SANDBOX_PROMPT verbatim; per-section dedup
        // must reduce it to a single copy.
        val prompt = """
            You are using a persistent Linux sandbox. Use this tool only when the user asks to receive a file.

            ## deliverable versioning
            - Keep the existing file.
        """.trimIndent()

        val seen = linkedSetOf<String>()
        var injectedCount = 0
        repeat(4) {
            for (section in prompt.splitSections()) {
                if (seen.add(section.trim())) injectedCount++
            }
        }

        assertEquals(prompt.splitSections().size, injectedCount)
    }

    @Test
    fun splitSections_blankInputReturnsEmpty() {
        assertTrue("".splitSections().isEmpty())
    }
}