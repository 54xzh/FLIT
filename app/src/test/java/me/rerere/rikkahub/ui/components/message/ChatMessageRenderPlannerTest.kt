package me.rerere.rikkahub.ui.components.message

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageRenderPlannerTest {
    @Test
    fun `places workspace file reference at its tool result position`() {
        val reasoningBeforeTool = UIMessagePart.Reasoning("先想")
        val toolCall = UIMessagePart.ToolCall(
            toolCallId = "call_file",
            toolName = "workspace_send_file",
            arguments = """{"path":"output/report.pdf"}""",
        )
        val fileResult = UIMessagePart.ToolResult(
            toolCallId = "call_file",
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
        val reasoningAfterTool = UIMessagePart.Reasoning("再整理")
        val text = UIMessagePart.Text("这是结果")

        val blocks = buildMessageRenderBlocks(
            leadingProcessParts = emptyList(),
            parts = listOf(reasoningBeforeTool, toolCall, fileResult, reasoningAfterTool, text),
        )

        assertEquals(4, blocks.size)
        assertEquals(
            MessageRenderBlock.ProcessGroup(parts = listOf(reasoningBeforeTool, toolCall)),
            blocks[0],
        )
        assertEquals(
            MessageRenderBlock.WorkspaceFileReferenceGroup(
                contents = listOf(fileResult.content as JsonObject),
            ),
            blocks[1],
        )
        assertEquals(
            MessageRenderBlock.ProcessGroup(parts = listOf(reasoningAfterTool)),
            blocks[2],
        )
        assertEquals(MessageRenderBlock.TextBlock(part = text, textIndex = 0), blocks[3])
    }

    @Test
    fun `keeps non-reference tool results in the process group`() {
        val toolCall = UIMessagePart.ToolCall(
            toolCallId = "call_search",
            toolName = "search_web",
            arguments = """{"query":"LastChat"}""",
        )
        val toolResult = UIMessagePart.ToolResult(
            toolCallId = "call_search",
            toolName = "search_web",
            content = buildJsonObject { put("ok", true) },
            arguments = buildJsonObject { put("query", "LastChat") },
        )

        val blocks = buildMessageRenderBlocks(
            leadingProcessParts = emptyList(),
            parts = listOf(toolCall, toolResult),
        )

        assertEquals(
            listOf(MessageRenderBlock.ProcessGroup(parts = listOf(toolCall, toolResult))),
            blocks,
        )
    }

    @Test
    fun `keeps process parts on both sides of text in original order`() {
        val reasoning = UIMessagePart.Reasoning("先想")
        val text = UIMessagePart.Text("先说一句")
        val toolCall = UIMessagePart.ToolCall(
            toolCallId = "call_1",
            toolName = "search_web",
            arguments = """{"query":"今天新闻"}""",
        )

        val blocks = buildMessageRenderBlocks(
            leadingProcessParts = emptyList(),
            parts = listOf(reasoning, text, toolCall),
        )

        assertEquals(3, blocks.size)
        assertEquals(MessageRenderBlock.ProcessGroup(parts = listOf(reasoning)), blocks[0])
        assertEquals(MessageRenderBlock.TextBlock(part = text, textIndex = 0), blocks[1])
        assertEquals(MessageRenderBlock.ProcessGroup(parts = listOf(toolCall)), blocks[2])
    }

    @Test
    fun `moves reasoning that arrives after text back in front of content`() {
        val text = UIMessagePart.Text("先回一句")
        val toolCall = UIMessagePart.ToolCall(
            toolCallId = "call_late",
            toolName = "search_web",
            arguments = """{"query":"今天新闻"}""",
        )
        val reasoning = UIMessagePart.Reasoning("补上的思考")

        val blocks = buildMessageRenderBlocks(
            leadingProcessParts = emptyList(),
            parts = listOf(text, toolCall, reasoning),
        )

        assertEquals(3, blocks.size)
        assertEquals(MessageRenderBlock.ProcessGroup(parts = listOf(reasoning)), blocks[0])
        assertEquals(MessageRenderBlock.TextBlock(part = text, textIndex = 0), blocks[1])
        assertEquals(MessageRenderBlock.ProcessGroup(parts = listOf(toolCall)), blocks[2])
    }

    @Test
    fun `keeps reasoning search reasoning sequence together before answer`() {
        val firstReasoning = UIMessagePart.Reasoning("先想")
        val toolCall = UIMessagePart.ToolCall(
            toolCallId = "call_3",
            toolName = "search_web",
            arguments = """{"query":"热点"}""",
        )
        val secondReasoning = UIMessagePart.Reasoning("搜完继续想")
        val text = UIMessagePart.Text("最终答案")

        val blocks = buildMessageRenderBlocks(
            leadingProcessParts = emptyList(),
            parts = listOf(firstReasoning, toolCall, secondReasoning, text),
        )

        assertEquals(2, blocks.size)
        assertEquals(
            MessageRenderBlock.ProcessGroup(parts = listOf(firstReasoning, toolCall, secondReasoning)),
            blocks[0]
        )
        assertEquals(MessageRenderBlock.TextBlock(part = text, textIndex = 0), blocks[1])
    }

    @Test
    fun `puts prefixed process parts before current message content`() {
        val prefixedReasoning = UIMessagePart.Reasoning("上一条过程")
        val text = UIMessagePart.Text("这是正文")
        val toolCall = UIMessagePart.ToolCall(
            toolCallId = "call_2",
            toolName = "search_web",
            arguments = """{"query":"热点新闻"}""",
        )

        val blocks = buildMessageRenderBlocks(
            leadingProcessParts = listOf(prefixedReasoning),
            parts = listOf(text, toolCall),
        )

        assertEquals(3, blocks.size)
        assertEquals(MessageRenderBlock.ProcessGroup(parts = listOf(prefixedReasoning)), blocks[0])
        assertEquals(MessageRenderBlock.TextBlock(part = text, textIndex = 0), blocks[1])
        assertEquals(MessageRenderBlock.ProcessGroup(parts = listOf(toolCall)), blocks[2])
    }

    @Test
    fun `only groups consecutive media of the same kind`() {
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

        assertEquals(4, blocks.size)
        assertEquals(MessageRenderBlock.ImageGroup(parts = listOf(firstImage, secondImage)), blocks[0])
        assertEquals(MessageRenderBlock.TextBlock(part = text, textIndex = 0), blocks[1])
        assertEquals(MessageRenderBlock.ImageGroup(parts = listOf(thirdImage)), blocks[2])
        assertTrue(blocks[3] is MessageRenderBlock.DocumentGroup)
    }

    @Test
    fun `groups files across their hidden send lifecycle`() {
        val firstCall = workspaceFileCall("call_file_1", "output/summary.pdf")
        val firstResult = workspaceFileResult("call_file_1", "output/summary.pdf")
        val secondCall = workspaceFileCall("call_file_2", "output/data.csv")
        val secondResult = workspaceFileResult("call_file_2", "output/data.csv")

        val blocks = buildMessageRenderBlocks(
            leadingProcessParts = emptyList(),
            parts = listOf(firstCall, firstResult, secondCall, secondResult),
        )

        val fileGroups = blocks.filterIsInstance<MessageRenderBlock.WorkspaceFileReferenceGroup>()
        assertEquals(1, fileGroups.size)
        assertEquals(
            listOf(firstResult.content as JsonObject, secondResult.content as JsonObject),
            fileGroups.single().contents,
        )
    }

    @Test
    fun `text and other tool results split file groups`() {
        val firstCall = workspaceFileCall("call_file_1", "output/summary.pdf")
        val firstResult = workspaceFileResult("call_file_1", "output/summary.pdf")
        val secondCall = workspaceFileCall("call_file_2", "output/data.csv")
        val secondResult = workspaceFileResult("call_file_2", "output/data.csv")
        val searchCall = UIMessagePart.ToolCall(
            toolCallId = "call_search",
            toolName = "search_web",
            arguments = "{}",
        )
        val searchResult = UIMessagePart.ToolResult(
            toolCallId = "call_search",
            toolName = "search_web",
            content = buildJsonObject { put("ok", true) },
            arguments = buildJsonObject { },
        )

        val textBlocks = buildMessageRenderBlocks(
            leadingProcessParts = emptyList(),
            parts = listOf(firstCall, firstResult, UIMessagePart.Text("说明"), secondCall, secondResult),
        )
        val toolBlocks = buildMessageRenderBlocks(
            leadingProcessParts = emptyList(),
            parts = listOf(firstCall, firstResult, searchCall, searchResult, secondCall, secondResult),
        )

        assertEquals(2, textBlocks.filterIsInstance<MessageRenderBlock.WorkspaceFileReferenceGroup>().size)
        assertEquals(2, toolBlocks.filterIsInstance<MessageRenderBlock.WorkspaceFileReferenceGroup>().size)
    }

    @Test
    fun `does not group files across display segments`() {
        val firstCall = workspaceFileCall("call_file_1", "output/summary.pdf")
        val firstResult = workspaceFileResult("call_file_1", "output/summary.pdf")
        val secondCall = workspaceFileCall("call_file_2", "output/data.csv")
        val secondResult = workspaceFileResult("call_file_2", "output/data.csv")

        val blocks = buildMessageRenderBlocksFromSegments(
            segments = listOf(
                listOf(firstCall, firstResult),
                listOf(secondCall, secondResult),
            ),
        )

        assertEquals(2, blocks.filterIsInstance<MessageRenderBlock.WorkspaceFileReferenceGroup>().size)
    }

    @Test
    fun `failed file result splits file groups`() {
        val firstCall = workspaceFileCall("call_file_1", "output/summary.pdf")
        val firstResult = workspaceFileResult("call_file_1", "output/summary.pdf")
        val failedResult = UIMessagePart.ToolResult(
            toolCallId = "call_file_2",
            toolName = "workspace_send_file",
            content = buildJsonObject {
                put("ok", false)
                put("type", "workspace_file_reference")
            },
            arguments = buildJsonObject { put("path", "output/missing.pdf") },
        )
        val thirdCall = workspaceFileCall("call_file_3", "output/data.csv")
        val thirdResult = workspaceFileResult("call_file_3", "output/data.csv")

        val blocks = buildMessageRenderBlocks(
            leadingProcessParts = emptyList(),
            parts = listOf(firstCall, firstResult, failedResult, thirdCall, thirdResult),
        )

        assertEquals(2, blocks.filterIsInstance<MessageRenderBlock.WorkspaceFileReferenceGroup>().size)
    }
}

private fun workspaceFileCall(toolCallId: String, path: String) = UIMessagePart.ToolCall(
    toolCallId = toolCallId,
    toolName = "workspace_send_file",
    arguments = """{"path":"$path"}""",
)

private fun workspaceFileResult(toolCallId: String, path: String): UIMessagePart.ToolResult {
    val name = path.substringAfterLast('/')
    return UIMessagePart.ToolResult(
        toolCallId = toolCallId,
        toolName = "workspace_send_file",
        content = buildJsonObject {
            put("ok", true)
            put("type", "workspace_file_reference")
            put("workspace_id", "workspace-1")
            put("path", path)
            put("name", name)
            put("mime", "application/octet-stream")
            put("size_bytes", 1024)
        },
        arguments = buildJsonObject { put("path", path) },
    )
}
