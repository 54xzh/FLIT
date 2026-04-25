package me.rerere.rikkahub.data.repository

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSearchTextTest {
    @Test
    fun `search text includes only selected visible text parts`() {
        val searchText = buildConversationVisibleSearchText(
            listOf(
                MessageNode(
                    messages = listOf(
                        UIMessage(
                            role = MessageRole.USER,
                            parts = listOf(UIMessagePart.Text("old branch hidden text")),
                        ),
                        UIMessage(
                            role = MessageRole.USER,
                            parts = listOf(UIMessagePart.Text("visible user question")),
                        ),
                    ),
                    selectIndex = 1,
                ),
                MessageNode(
                    messages = listOf(
                        UIMessage(
                            role = MessageRole.ASSISTANT,
                            parts = listOf(
                                UIMessagePart.Reasoning("hidden reasoning topic"),
                                UIMessagePart.ToolCall("call_1", "search_web", "{\"query\":\"hidden tool args\"}"),
                                UIMessagePart.ToolResult(
                                    toolCallId = "call_1",
                                    toolName = "search_web",
                                    content = JsonPrimitive("hidden tool result"),
                                    arguments = JsonPrimitive("{}"),
                                ),
                                UIMessagePart.Text("visible assistant answer"),
                            ),
                        )
                    ),
                ),
            )
        )

        assertTrue(searchText.contains("visible user question"))
        assertTrue(searchText.contains("visible assistant answer"))
        assertFalse(searchText.contains("old branch hidden text"))
        assertFalse(searchText.contains("hidden reasoning topic"))
        assertFalse(searchText.contains("hidden tool args"))
        assertFalse(searchText.contains("hidden tool result"))
    }
}
