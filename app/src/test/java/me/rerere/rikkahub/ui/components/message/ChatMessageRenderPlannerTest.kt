package me.rerere.rikkahub.ui.components.message

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageRenderPlannerTest {
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
}
