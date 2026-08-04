package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonNull
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceToolFactoryTest {

    @Test
    fun `workspace tools require assistant opt in and a bound workspace`() {
        assertFalse(shouldAttachWorkspaceTools(Assistant(workspaceId = "workspace")))
        assertFalse(
            shouldAttachWorkspaceTools(
                Assistant(
                    localTools = listOf(LocalToolOption.WorkspaceFiles),
                    workspaceId = "",
                )
            )
        )
        assertTrue(
            shouldAttachWorkspaceTools(
                Assistant(
                    localTools = listOf(LocalToolOption.WorkspaceFiles),
                    workspaceId = "workspace",
                )
            )
        )
    }

    @Test
    fun `scheduled mode excludes tools requiring interactive approval`() {
        val tools = listOf(
            testTool(name = "workspace_read_file", requiresApproval = false),
            testTool(name = "workspace_write_file", requiresApproval = true),
        )

        assertEquals(
            listOf("workspace_read_file"),
            filterToolsForExecutionMode(tools, WorkspaceToolExecutionMode.SCHEDULED)
                .map(Tool::name),
        )
        assertEquals(
            listOf("workspace_read_file", "workspace_write_file"),
            filterToolsForExecutionMode(tools, WorkspaceToolExecutionMode.INTERACTIVE)
                .map(Tool::name),
        )
    }

    private fun testTool(name: String, requiresApproval: Boolean): Tool = Tool(
        name = name,
        description = name,
        requiresUserApproval = requiresApproval,
        execute = { JsonNull },
    )
}
