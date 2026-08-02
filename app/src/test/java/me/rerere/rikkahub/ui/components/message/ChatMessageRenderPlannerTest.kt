package me.rerere.rikkahub.ui.components.message

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.WORKSPACE_FILE_REFERENCE_WORKSPACE_ID_METADATA_KEY
import me.rerere.rikkahub.data.model.WorkspaceFileReferenceContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageRenderPlannerTest {
    @Test
    fun `puts deduplicated workspace links after message content`() {
        val reasoning = UIMessagePart.Reasoning("先想")
        val text = UIMessagePart.Text(
            "说明 [报告](/workspace/output/report.pdf) " +
                "[报告副本](/workspace/output/report.pdf) [数据](/workspace/output/data.csv)",
        )
        val trailingTool = UIMessagePart.ToolCall(
            toolCallId = "call_search",
            toolName = "search_web",
            arguments = "{}",
        )

        val blocks = buildMessageRenderBlocks(
            leadingProcessParts = emptyList(),
            parts = listOf(reasoning, text, trailingTool),
            workspaceFileReferenceContext = WorkspaceFileReferenceContext("workspace-1"),
            workspaceFileReferenceEntryScope = "message-1",
        )

        assertEquals(MessageRenderBlock.ProcessGroup(listOf(reasoning)), blocks[0])
        assertEquals(MessageRenderBlock.TextBlock(text, textIndex = 0), blocks[1])
        assertEquals(MessageRenderBlock.ProcessGroup(listOf(trailingTool)), blocks[2])
        assertEquals(
            MessageRenderBlock.WorkspaceFileReferenceGroup(
                listOf(
                    WorkspaceFileReferenceCandidate("workspace-1", "output/report.pdf"),
                    WorkspaceFileReferenceCandidate("workspace-1", "output/data.csv"),
                ),
                entryScope = "message-1",
            ),
            blocks[3],
        )
    }

    @Test
    fun `metadata workspace wins over current workspace fallback`() {
        val text = UIMessagePart.Text(
            text = "[报告](/workspace/output/report.pdf)",
            metadata = buildJsonObject {
                put(WORKSPACE_FILE_REFERENCE_WORKSPACE_ID_METADATA_KEY, "workspace-original")
            },
        )

        val blocks = buildMessageRenderBlocks(
            leadingProcessParts = emptyList(),
            parts = listOf(text),
            workspaceFileReferenceContext = WorkspaceFileReferenceContext("workspace-current"),
        )

        val group = blocks.filterIsInstance<MessageRenderBlock.WorkspaceFileReferenceGroup>().single()
        assertEquals("workspace-original", group.items.single().workspaceId)
    }

    @Test
    fun `does not create file cards without a workspace context`() {
        val blocks = buildMessageRenderBlocks(
            leadingProcessParts = emptyList(),
            parts = listOf(UIMessagePart.Text("[报告](/workspace/output/report.pdf)")),
        )

        assertTrue(blocks.none { it is MessageRenderBlock.WorkspaceFileReferenceGroup })
    }

    @Test
    fun `keeps ordinary tool results in the process timeline`() {
        val toolCall = UIMessagePart.ToolCall(
            toolCallId = "call_search",
            toolName = "search_web",
            arguments = "{}",
        )
        val toolResult = UIMessagePart.ToolResult(
            toolCallId = "call_search",
            toolName = "search_web",
            content = buildJsonObject { put("ok", true) },
            arguments = buildJsonObject {},
        )

        assertEquals(
            listOf(MessageRenderBlock.ProcessGroup(listOf(toolCall, toolResult))),
            buildMessageRenderBlocks(emptyList(), listOf(toolCall, toolResult)),
        )
    }

    @Test
    fun `keeps reasoning and tool parts in order around text`() {
        val reasoning = UIMessagePart.Reasoning("补上的思考")
        val text = UIMessagePart.Text("先回答")
        val toolCall = UIMessagePart.ToolCall(
            toolCallId = "call_search",
            toolName = "search_web",
            arguments = "{}",
        )

        assertEquals(
            listOf(
                MessageRenderBlock.ProcessGroup(listOf(reasoning)),
                MessageRenderBlock.TextBlock(text, textIndex = 0),
                MessageRenderBlock.ProcessGroup(listOf(toolCall)),
            ),
            buildMessageRenderBlocks(
                leadingProcessParts = emptyList(),
                parts = listOf(text, toolCall, reasoning),
            ),
        )
    }

    @Test
    fun `groups consecutive media of the same kind`() {
        val firstImage = UIMessagePart.Image("file:///image-1.png")
        val secondImage = UIMessagePart.Image("file:///image-2.png")
        val text = UIMessagePart.Text("中间插一句")
        val thirdImage = UIMessagePart.Image("file:///image-3.png")
        val document = UIMessagePart.Document(
            url = "file:///report.pdf",
            fileName = "report.pdf",
            mime = "application/pdf",
        )

        val blocks = buildMessageRenderBlocks(
            leadingProcessParts = emptyList(),
            parts = listOf(firstImage, secondImage, text, thirdImage, document),
        )

        assertEquals(MessageRenderBlock.ImageGroup(listOf(firstImage, secondImage)), blocks[0])
        assertEquals(MessageRenderBlock.TextBlock(text, textIndex = 0), blocks[1])
        assertEquals(MessageRenderBlock.ImageGroup(listOf(thirdImage)), blocks[2])
        assertTrue(blocks[3] is MessageRenderBlock.DocumentGroup)
    }
}
