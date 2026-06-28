package me.rerere.rikkahub.ui.components.message

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ChatProcessDisplayPlannerTest {
    @Test
    fun `attachs pending process blocks to next assistant message with content`() {
        val nodes = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text("帮我搜一下")),
            ).toMessageNode(),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning("先想一想"),
                    UIMessagePart.ToolCall("call_1", "search_web", """{"query":"LastChat"}"""),
                ),
            ).toMessageNode(),
            UIMessage(
                role = MessageRole.TOOL,
                parts = listOf(
                    UIMessagePart.ToolResult(
                        toolCallId = "call_1",
                        toolName = "search_web",
                        content = JsonPrimitive("ok"),
                        arguments = JsonPrimitive("""{"query":"LastChat"}"""),
                    )
                ),
            ).toMessageNode(),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning("继续整理"),
                    UIMessagePart.Text("这是结果"),
                ),
            ).toMessageNode(),
        )

        val plan = planChatProcessDisplay(nodes)

        assertEquals(setOf(1, 2), plan.hiddenNodeIndexes)
        assertEquals(3, plan.prefixedProcessPartsByIndex[3]?.size)
        assertTrue(plan.standaloneProcessPartsByIndex.isEmpty())
    }

    @Test
    fun `keeps trailing process blocks as standalone group when answer has not arrived`() {
        val nodes = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text("读一下文件")),
            ).toMessageNode(),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning("准备读文件"),
                    UIMessagePart.ToolCall("call_1", "workspace_read_file", """{"path":"README.md"}"""),
                ),
            ).toMessageNode(),
        )

        val plan = planChatProcessDisplay(nodes)

        assertEquals(setOf(1), plan.hiddenNodeIndexes)
        assertEquals(2, plan.standaloneProcessPartsByIndex[1]?.size)
        assertEquals(1, plan.standaloneAssistantOwnerIndexByIndex[1])
        assertTrue(plan.prefixedProcessPartsByIndex.isEmpty())
    }

    @Test
    fun `keeps trailing process only assistant visible after generation stops`() {
        val nodes = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text("读一下文件")),
            ).toMessageNode(),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning("准备读文件"),
                    UIMessagePart.ToolCall("call_1", "workspace_read_file", """{"path":"README.md"}"""),
                ),
            ).toMessageNode(),
        )

        val plan = planChatProcessDisplay(
            nodes = nodes,
            keepTrailingProcessOwnerVisible = true,
        )

        assertTrue(plan.hiddenNodeIndexes.isEmpty())
        assertEquals(setOf(1), plan.visibleTrailingProcessOwnerIndexes)
        assertTrue(plan.standaloneProcessPartsByIndex.isEmpty())
    }

    @Test
    fun `keeps assistant as owner for finalized trailing tool result`() {
        val nodes = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text("搜一下")),
            ).toMessageNode(),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning("先思考"),
                    UIMessagePart.ToolCall("call_1", "search_web", """{"query":"LastChat"}"""),
                ),
            ).toMessageNode(),
            UIMessage(
                role = MessageRole.TOOL,
                parts = listOf(
                    UIMessagePart.ToolResult(
                        toolCallId = "call_1",
                        toolName = "search_web",
                        content = JsonPrimitive("interrupted"),
                        arguments = JsonPrimitive("""{"query":"LastChat"}"""),
                    )
                ),
            ).toMessageNode(),
        )

        val plan = planChatProcessDisplay(
            nodes = nodes,
            keepTrailingProcessOwnerVisible = true,
        )

        assertEquals(setOf(2), plan.hiddenNodeIndexes)
        assertEquals(setOf(1), plan.visibleTrailingProcessOwnerIndexes)
        assertTrue(plan.standaloneProcessPartsByIndex.isEmpty())
    }

    @Test
    fun `standalone process block keeps assistant owner when anchored on tool result`() {
        val nodes = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text("搜一下")),
            ).toMessageNode(),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning("先思考"),
                    UIMessagePart.ToolCall("call_1", "search_web", """{"query":"LastChat"}"""),
                ),
            ).toMessageNode(),
            UIMessage(
                role = MessageRole.TOOL,
                parts = listOf(
                    UIMessagePart.ToolResult(
                        toolCallId = "call_1",
                        toolName = "search_web",
                        content = JsonPrimitive("ok"),
                        arguments = JsonPrimitive("""{"query":"LastChat"}"""),
                    )
                ),
            ).toMessageNode(),
        )

        val plan = planChatProcessDisplay(nodes)

        assertEquals(setOf(1, 2), plan.hiddenNodeIndexes)
        assertEquals(3, plan.standaloneProcessPartsByIndex[2]?.size)
        assertEquals(1, plan.standaloneAssistantOwnerIndexByIndex[2])
    }

    @Test
    fun `flushes pending process blocks before non assistant barrier`() {
        val nodes = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text("开始")),
            ).toMessageNode(),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Reasoning("处理中")),
            ).toMessageNode(),
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text("下一句")),
            ).toMessageNode(),
        )

        val plan = planChatProcessDisplay(nodes)

        assertEquals(setOf(1), plan.hiddenNodeIndexes)
        assertEquals(1, plan.standaloneProcessPartsByIndex[1]?.size)
        assertTrue(plan.prefixedProcessPartsByIndex.isEmpty())
    }

    @Test
    fun `assistant message with text is not treated as process only`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning("思考"),
                UIMessagePart.Text("答案"),
            ),
        )

        assertFalse(message.isProcessOnlyDisplayMessage())
    }

    @Test
    fun `does not attach pending process blocks to a different assistant speaker`() {
        val firstAssistantId = Uuid.random()
        val secondAssistantId = Uuid.random()
        val nodes = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text("开始")),
            ).toMessageNode(),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Reasoning("助手 A 在思考")),
                speakerAssistantId = firstAssistantId,
            ).toMessageNode(),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("助手 B 回复了")),
                speakerAssistantId = secondAssistantId,
            ).toMessageNode(),
        )

        val plan = planChatProcessDisplay(nodes)

        assertEquals(setOf(1), plan.hiddenNodeIndexes)
        assertEquals(1, plan.standaloneProcessPartsByIndex[1]?.size)
        assertTrue(plan.prefixedProcessPartsByIndex.isEmpty())
    }

    @Test
    fun `merges consecutive assistant messages from same speaker into later message`() {
        val assistantId = Uuid.random()
        val nodes = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text("开始")),
            ).toMessageNode(),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("第一段")),
                speakerAssistantId = assistantId,
            ).toMessageNode(),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("第二段")),
                speakerAssistantId = assistantId,
            ).toMessageNode(),
        )

        val plan = planChatProcessDisplay(nodes)

        assertEquals(setOf(1), plan.hiddenNodeIndexes)
        assertEquals(listOf(listOf(UIMessagePart.Text("第一段"))), plan.prefixedDisplaySegmentsByIndex[2])
    }

    @Test
    fun `hides standalone interrupted user marker as a hidden node`() {
        val markerText = "\n\n<app_context>The user stopped the output.</app_context>"
        val nodes = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text("帮我搜一下")),
            ).toMessageNode(),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning("先想一想"),
                    UIMessagePart.ToolCall("call_1", "search_web", """{"query":"LastChat"}"""),
                ),
            ).toMessageNode(),
            UIMessage(
                role = MessageRole.TOOL,
                parts = listOf(
                    UIMessagePart.ToolResult(
                        toolCallId = "call_1",
                        toolName = "search_web",
                        content = JsonPrimitive("interrupted"),
                        arguments = JsonPrimitive("""{"query":"LastChat"}"""),
                    )
                ),
            ).toMessageNode(),
            // 打断后追加的独立 user marker: UI 应整条隐藏, 不渲染成空气泡
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text(markerText)),
            ).toMessageNode(),
        )

        val plan = planChatProcessDisplay(nodes)

        assertTrue(plan.hiddenNodeIndexes.contains(3))
        // marker 不应被当成 process-only 消息产生独立 timeline
        assertFalse(plan.standaloneProcessPartsByIndex.containsKey(3))
    }
}
