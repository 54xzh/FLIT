package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.db.entity.SANDBOX_WORKSPACE_TOOL_NAMES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSystemPromptRegistryTest {
    @Test
    fun `sandbox tools share one configurable prompt definition`() {
        val definition = ToolSystemPromptRegistry.get(SANDBOX_WORKSPACE_PROMPT_NAME)

        assertEquals(ToolSystemPromptGroup.Workspace, definition?.group)
        assertEquals(SANDBOX_WORKSPACE_TOOL_NAMES, definition?.affectedToolNames)
        SANDBOX_WORKSPACE_TOOL_NAMES.forEach { toolName ->
            assertEquals(definition, ToolSystemPromptRegistry.getInjectedDefinition(toolName))
        }
    }

    @Test
    fun `sandbox custom prompt replaces the shared default for every sandbox tool`() {
        val customPrompt = "Custom sandbox guidance"
        val settings = Settings(
            customToolSystemPrompts = mapOf(SANDBOX_WORKSPACE_PROMPT_NAME to customPrompt),
        )

        val renderedPrompts = SANDBOX_WORKSPACE_TOOL_NAMES.map { toolName ->
            sandboxTool(toolName).renderConfiguredSystemPrompt(
                settings = settings,
                model = Model(),
                messages = emptyList(),
            )
        }

        assertEquals(setOf(customPrompt), renderedPrompts.toSet())
    }

    @Test
    fun `sandbox tools keep the existing default when no customization is saved`() {
        val renderedPrompts = SANDBOX_WORKSPACE_TOOL_NAMES.map { toolName ->
            sandboxTool(toolName).renderConfiguredSystemPrompt(
                settings = Settings(),
                model = Model(),
                messages = emptyList(),
            )
        }

        assertEquals(setOf(SANDBOX_WORKSPACE_SYSTEM_PROMPT_TEMPLATE.trim()), renderedPrompts.toSet())
    }

    @Test
    fun `new memory defaults only contain tool instructions`() {
        assertTrue(MEMORY_MANAGEMENT_SYSTEM_PROMPT_TEMPLATE.startsWith("## Memory Tool"))
        assertFalse(MEMORY_MANAGEMENT_SYSTEM_PROMPT_TEMPLATE.contains("{{memory_context}}"))
        assertFalse(MEMORY_MANAGEMENT_SYSTEM_PROMPT_TEMPLATE.contains("## Memories"))
        assertTrue(SESSION_MEMORY_MANAGEMENT_SYSTEM_PROMPT_TEMPLATE.startsWith("## Session Memory Tool"))
        assertFalse(SESSION_MEMORY_MANAGEMENT_SYSTEM_PROMPT_TEMPLATE.contains("{{session_memory_context}}"))
        assertFalse(SESSION_MEMORY_MANAGEMENT_SYSTEM_PROMPT_TEMPLATE.contains("## Session Memories"))
        assertTrue(ToolSystemPromptRegistry.get(MEMORY_MANAGEMENT_TOOL_NAME)?.variables?.isEmpty() == true)
        assertTrue(ToolSystemPromptRegistry.get(SESSION_MEMORY_MANAGEMENT_TOOL_NAME)?.variables?.isEmpty() == true)
    }

    @Test
    fun `saved memory custom prompts keep legacy variable replacement`() {
        val settings = Settings(
            customToolSystemPrompts = mapOf(
                MEMORY_MANAGEMENT_TOOL_NAME to "Memory: {{memory_context}}",
                SESSION_MEMORY_MANAGEMENT_TOOL_NAME to "Session: {{session_memory_context}}",
            ),
        )

        assertEquals(
            "Memory: legacy memory placement",
            renderConfiguredToolSystemPrompt(
                settings = settings,
                key = MEMORY_MANAGEMENT_TOOL_NAME,
                defaultTemplate = MEMORY_MANAGEMENT_SYSTEM_PROMPT_TEMPLATE,
                variables = mapOf(MEMORY_CONTEXT_VARIABLE to "legacy memory placement"),
            ),
        )
        assertEquals(
            "Session: legacy session placement",
            renderConfiguredToolSystemPrompt(
                settings = settings,
                key = SESSION_MEMORY_MANAGEMENT_TOOL_NAME,
                defaultTemplate = SESSION_MEMORY_MANAGEMENT_SYSTEM_PROMPT_TEMPLATE,
                variables = mapOf(SESSION_MEMORY_CONTEXT_VARIABLE to "legacy session placement"),
            ),
        )
    }

    private fun sandboxTool(name: String): Tool = Tool(
        name = name,
        description = "",
        systemPrompt = { _, _ -> SANDBOX_WORKSPACE_SYSTEM_PROMPT_TEMPLATE },
        execute = { JsonObject(emptyMap()) },
    )
}
