package me.rerere.rikkahub.ui.pages.chat

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.WORKSPACE_FILE_REFERENCE_WORKSPACE_ID_METADATA_KEY
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.ui.components.message.WorkspaceFileReferenceCandidate
import me.rerere.rikkahub.ui.components.message.workspaceFileReferenceEntryKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceFileReferenceHistoryTest {
    @Test
    fun `collects historical links only after conversation initialization`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text(
                    text = "[报告](/workspace/output/report.pdf)",
                    metadata = buildJsonObject {
                        put(WORKSPACE_FILE_REFERENCE_WORKSPACE_ID_METADATA_KEY, "workspace-original")
                    },
                ),
            ),
        )
        val nodes = listOf(message.toMessageNode())
        val expectedKey = WorkspaceFileReferenceCandidate(
            workspaceId = "workspace-original",
            path = "output/report.pdf",
        ).workspaceFileReferenceEntryKey(message.id.toString())

        assertTrue(
            initialWorkspaceFileReferenceEntryKeys(
                messageNodes = nodes,
                conversationInitialized = false,
            ).isEmpty(),
        )
        assertEquals(
            setOf(expectedKey),
            initialWorkspaceFileReferenceEntryKeys(
                messageNodes = nodes,
                conversationInitialized = true,
            ),
        )
    }

    @Test
    fun `uses current workspace when historical metadata is absent`() {
        val nodes = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("[报告](/workspace/output/report.pdf)")),
            ).toMessageNode(),
        )
        val expectedKey = WorkspaceFileReferenceCandidate(
            workspaceId = "workspace-current",
            path = "output/report.pdf",
        ).workspaceFileReferenceEntryKey(nodes.single().currentMessage.id.toString())

        assertEquals(
            setOf(expectedKey),
            initialWorkspaceFileReferenceEntryKeys(
                messageNodes = nodes,
                conversationInitialized = true,
                workspaceIdForMessage = { "workspace-current" },
            ),
        )
    }
}
