package me.rerere.rikkahub.ui.pages.chat

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.ui.components.message.workspaceFileReferenceEntryKey
import me.rerere.rikkahub.ui.components.message.workspaceFileReferenceRenderItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceFileReferenceHistoryTest {
    @Test
    fun `collects existing file entries only after conversation initialization`() {
        val result = UIMessagePart.ToolResult(
            toolCallId = "call_history",
            toolName = "workspace_send_file",
            content = buildJsonObject {
                put("ok", true)
                put("type", "workspace_file_reference")
                put("workspace_id", "workspace-1")
                put("path", "output/report.pdf")
                put("name", "report.pdf")
                put("mime", "application/pdf")
                put("size_bytes", 1024)
            },
            arguments = buildJsonObject { put("path", "output/report.pdf") },
        )
        val nodes = listOf(
            UIMessage(
                role = MessageRole.TOOL,
                parts = listOf(result),
            ).toMessageNode(),
        )

        assertTrue(
            initialWorkspaceFileReferenceEntryKeys(
                messageNodes = nodes,
                conversationInitialized = false,
            ).isEmpty()
        )
        assertEquals(
            setOf(requireNotNull(result.workspaceFileReferenceRenderItem()?.workspaceFileReferenceEntryKey())),
            initialWorkspaceFileReferenceEntryKeys(
                messageNodes = nodes,
                conversationInitialized = true,
            ),
        )
    }
}
