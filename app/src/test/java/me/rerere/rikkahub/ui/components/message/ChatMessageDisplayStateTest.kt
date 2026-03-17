package me.rerere.rikkahub.ui.components.message

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageDisplayStateTest {
    @Test
    fun `build display state merges prefixed process parts into visible copy text`() {
        val reasoning = UIMessagePart.Reasoning("先思考")
        val toolCall = UIMessagePart.ToolCall(
            toolCallId = "call_1",
            toolName = "search_web",
            arguments = """{"query":"LastChat"}""",
        )
        val toolResult = UIMessagePart.ToolResult(
            toolCallId = "call_1",
            toolName = "search_web",
            content = JsonPrimitive("ok"),
            arguments = JsonPrimitive("""{"query":"LastChat"}"""),
        )
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("这是最终答案")),
        )

        val state = buildChatMessageDisplayState(
            message = message,
            leadingDisplaySegments = listOf(listOf(reasoning, toolCall, toolResult)),
        )

        assertEquals(4, state.message.parts.size)
        assertEquals(listOf(reasoning, toolCall, toolResult), state.message.parts.take(3))
        assertTrue(state.copyText.contains("先思考"))
        assertTrue(state.copyText.contains("这是最终答案"))
        assertFalse(state.copyText.contains("search_web"))
        assertEquals(listOf("这是最终答案"), state.selectionCopyBlocks)
        assertEquals("这是最终答案", state.selectionCopyText)
    }

    @Test
    fun `build display state follows render order when reasoning arrives after text`() {
        val state = buildChatMessageDisplayState(
            message = UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Text("先回答"),
                    UIMessagePart.ToolCall(
                        toolCallId = "call_1",
                        toolName = "search_web",
                        arguments = """{"query":"新闻"}""",
                    ),
                    UIMessagePart.Reasoning("补上的思考"),
                ),
            ),
            leadingDisplaySegments = emptyList(),
        )

        assertEquals("补上的思考", state.copyBlocks[0])
        assertEquals("先回答", state.copyBlocks[1])
    }

    @Test
    fun `build display state keeps message segment order when previous assistant content is prefixed`() {
        val state = buildChatMessageDisplayState(
            message = UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning("第二段推理"),
                    UIMessagePart.Text("第二段正文"),
                ),
            ),
            leadingDisplaySegments = listOf(
                listOf(
                    UIMessagePart.Reasoning("第一段推理"),
                    UIMessagePart.Text("第一段正文"),
                    UIMessagePart.ToolCall(
                        toolCallId = "call_1",
                        toolName = "search_web",
                        arguments = """{"query":"LastChat"}""",
                    ),
                )
            ),
        )

        assertTrue(state.copyText.contains("第一段推理\n\n第一段正文\n\n第二段推理\n\n第二段正文"))
        assertEquals("第一段正文\n\n第二段正文", state.selectionCopyText)
        val textBlocks = state.renderBlocks.filterIsInstance<MessageRenderBlock.TextBlock>()
        assertEquals("第一段正文", textBlocks[0].part.text)
        assertEquals("第二段正文", textBlocks[1].part.text)
    }
}
