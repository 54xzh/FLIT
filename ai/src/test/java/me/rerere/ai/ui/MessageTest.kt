package me.rerere.ai.ui

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

class MessageTest {

    @Test
    fun `limitContext with size 0 should return original list`() {
        val messages = createTestMessages(5)
        val result = messages.limitContext(0)
        assertEquals(messages, result)
    }

    @Test
    fun `limitContext with negative size should return original list`() {
        val messages = createTestMessages(5)
        val result = messages.limitContext(-1)
        assertEquals(messages, result)
    }

    @Test
    fun `limitContext with size greater than list size should return original list`() {
        val messages = createTestMessages(3)
        val result = messages.limitContext(5)
        assertEquals(messages, result)
    }

    @Test
    fun `limitContext with normal size should return last N messages`() {
        val messages = createTestMessages(5)
        val result = messages.limitContext(3)
        assertEquals(3, result.size)
        assertEquals(messages.subList(2, 5), result)
    }

    @Test
    fun `limitContext with tool result at start should include corresponding tool call`() {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("User message"))),
            UIMessage(
                role = MessageRole.ASSISTANT, parts = listOf(
                    UIMessagePart.ToolCall("call1", "test_tool", "{}")
                )
            ),
            UIMessage(
                role = MessageRole.USER, parts = listOf(
                    UIMessagePart.ToolResult("call1", "test_tool", JsonPrimitive("result"), JsonPrimitive("{}"))
                )
            ),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("Final response")))
        )

        val result = messages.limitContext(2)
        assertEquals(4, result.size)
        assertEquals(messages, result)
    }

    @Test
    fun `limitContext with tool call at start should include corresponding user message`() {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("User query"))),
            UIMessage(
                role = MessageRole.ASSISTANT, parts = listOf(
                    UIMessagePart.ToolCall("call1", "test_tool", "{}")
                )
            ),
            UIMessage(
                role = MessageRole.USER, parts = listOf(
                    UIMessagePart.ToolResult("call1", "test_tool", JsonPrimitive("result"), JsonPrimitive("{}"))
                )
            ),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("Final response")))
        )

        val result = messages.limitContext(2)
        assertEquals(4, result.size)
        assertEquals(messages, result)
    }

    @Test
    fun `limitContext with tool result that chains to tool call and user message`() {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Initial query"))),
            UIMessage(
                role = MessageRole.ASSISTANT, parts = listOf(
                    UIMessagePart.ToolCall("call1", "test_tool", "{}")
                )
            ),
            UIMessage(
                role = MessageRole.USER, parts = listOf(
                    UIMessagePart.ToolResult("call1", "test_tool", JsonPrimitive("result"), JsonPrimitive("{}"))
                )
            ),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("Response 1"))),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("Response 2")))
        )

        // Request only 1 message but tool result should chain back to include user message
        val result = messages.limitContext(1)
        assertEquals(1, result.size)
        assertEquals(messages.subList(4, 5), result)
    }

    @Test
    fun `limitContext with multiple tool calls should find earliest user message`() {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("User query"))),
            UIMessage(
                role = MessageRole.ASSISTANT, parts = listOf(
                    UIMessagePart.ToolCall("call1", "tool1", "{}")
                )
            ),
            UIMessage(
                role = MessageRole.ASSISTANT, parts = listOf(
                    UIMessagePart.ToolCall("call2", "tool2", "{}")
                )
            ),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("Final response")))
        )

        val result = messages.limitContext(2)
        assertEquals(4, result.size)
        assertEquals(messages, result)
    }

    @Test
    fun `limitContext with tool result but no corresponding tool call should not adjust`() {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("User 1"))),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("Assistant 1"))),
            UIMessage(
                role = MessageRole.USER, parts = listOf(
                    UIMessagePart.ToolResult("orphan", "test_tool", JsonPrimitive("result"), JsonPrimitive("{}"))
                )
            ),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("Assistant 2")))
        )

        val result = messages.limitContext(2)
        assertEquals(2, result.size)
        assertEquals(messages.subList(2, 4), result)
    }

    @Test
    fun `limitContext with tool call but no corresponding user message should not adjust further`() {
        val messages = listOf(
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("Assistant 1"))),
            UIMessage(
                role = MessageRole.ASSISTANT, parts = listOf(
                    UIMessagePart.ToolCall("call1", "test_tool", "{}")
                )
            ),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("Assistant 2")))
        )

        val result = messages.limitContext(2)
        assertEquals(2, result.size)
        assertEquals(messages.subList(1, 3), result)
    }

    @Test
    fun `limitContext with empty list should return empty list`() {
        val messages = emptyList<UIMessage>()
        val result = messages.limitContext(5)
        assertEquals(emptyList<UIMessage>(), result)
    }

    @Test
    fun `limitContext with single message should return that message`() {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Single message")))
        )
        val result = messages.limitContext(1)
        assertEquals(1, result.size)
        assertEquals(messages, result)
    }

    @Test
    fun `limitContext with complex chain of tool calls and results`() {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Initial query"))),
            UIMessage(
                role = MessageRole.ASSISTANT, parts = listOf(
                    UIMessagePart.ToolCall("call1", "tool1", "{}")
                )
            ),
            UIMessage(
                role = MessageRole.USER, parts = listOf(
                    UIMessagePart.ToolResult("call1", "tool1", JsonPrimitive("result1"), JsonPrimitive("{}"))
                )
            ),
            UIMessage(
                role = MessageRole.ASSISTANT, parts = listOf(
                    UIMessagePart.ToolCall("call2", "tool2", "{}")
                )
            ),
            UIMessage(
                role = MessageRole.USER, parts = listOf(
                    UIMessagePart.ToolResult("call2", "tool2", JsonPrimitive("result2"), JsonPrimitive("{}"))
                )
            ),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("Final response")))
        )

        // Request 3 messages starting from tool result, should include the whole chain
        val result = messages.limitContext(3)
        assertEquals(6, result.size)
        assertEquals(messages, result)
    }

    @Test
    fun `repairToolCallMessageSequence drops orphan tool result`() {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Previous context"))),
            UIMessage(
                role = MessageRole.TOOL,
                parts = listOf(UIMessagePart.ToolResult("call1", "search_web", JsonPrimitive("result"), JsonPrimitive("{}")))
            ),
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Latest question"))),
        )

        val result = messages.repairToolCallMessageSequence()

        assertEquals(2, result.size)
        assertFalse(result.any { it.role == MessageRole.TOOL })
        assertEquals(messages.first(), result.first())
        assertEquals(messages.last(), result.last())
    }

    @Test
    fun `repairToolCallMessageSequence keeps complete tool call and result pair`() {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Search"))),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.ToolCall("call1", "search_web", "{}"),
                    UIMessagePart.Text("")
                )
            ),
            UIMessage(
                role = MessageRole.TOOL,
                parts = listOf(UIMessagePart.ToolResult("call1", "search_web", JsonPrimitive("result"), JsonPrimitive("{}")))
            ),
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Follow up"))),
        )

        val result = messages.repairToolCallMessageSequence()

        assertEquals(messages, result)
    }

    @Test
    fun `repairToolCallMessageSequence removes unmatched tool calls and results from mixed tool batch`() {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Search twice"))),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.ToolCall("call1", "search_web", "{}"),
                    UIMessagePart.ToolCall("call2", "search_web", "{}"),
                    UIMessagePart.Text("")
                )
            ),
            UIMessage(
                role = MessageRole.TOOL,
                parts = listOf(
                    UIMessagePart.ToolResult("call2", "search_web", JsonPrimitive("result2"), JsonPrimitive("{}")),
                    UIMessagePart.ToolResult("orphan", "search_web", JsonPrimitive("result orphan"), JsonPrimitive("{}")),
                )
            ),
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Follow up"))),
        )

        val result = messages.repairToolCallMessageSequence()
        val assistant = result[1]
        val tool = result[2]

        assertEquals(listOf("call2"), assistant.getToolCalls().map { it.toolCallId })
        assertEquals(listOf("call2"), tool.getToolResults().map { it.toolCallId })
    }

    @Test
    fun `repairToolCallMessageSequence preserves tool calls that do not require local result`() {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Search"))),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.ToolCall("call1", "server_search", "{}"),
                    UIMessagePart.Text("")
                )
            ),
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Follow up"))),
        )

        val result = messages.repairToolCallMessageSequence { toolCall ->
            toolCall.toolName != "server_search"
        }

        assertEquals(messages, result)
    }

    @Test
    fun `handleMessageChunk should not include interruption duration when reasoning resumes`() {
        val now = Clock.System.now()
        val firstStart = now - 20.seconds
        val firstEnd = now - 10.seconds
        val accumulated = firstEnd - firstStart

        val previous = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(
                    reasoning = "first",
                    createdAt = firstStart,
                    finishedAt = firstEnd,
                )
            )
        )
        val chunk = MessageChunk(
            id = "resume-1",
            model = "test-model",
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(UIMessagePart.Reasoning(" second"))
                    ),
                    message = null,
                    finishReason = null,
                )
            )
        )

        val updated = listOf(previous).handleMessageChunk(chunk)
        val reasoning = updated.last().parts.filterIsInstance<UIMessagePart.Reasoning>().last()
        val resumedDuration = Clock.System.now() - reasoning.createdAt

        assertEquals("first second", reasoning.reasoning)
        assertEquals(null, reasoning.finishedAt)
        assertTrue(resumedDuration.inWholeSeconds >= accumulated.inWholeSeconds - 2)
        assertTrue(resumedDuration.inWholeSeconds <= accumulated.inWholeSeconds + 2)
    }

    @Test
    fun `handleMessageChunk image should replace with cumulative payload`() {
        var messages = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text("draw")),
            )
        )

        messages = messages.handleMessageChunk(imageDeltaChunk("QUJD"))
        messages = messages.handleMessageChunk(imageDeltaChunk("QUJDREVG"))

        val imageUrl = messages.last().parts.filterIsInstance<UIMessagePart.Image>().single().url
        assertEquals("data:image/png;base64,QUJDREVG", imageUrl)
    }

    @Test
    fun `handleMessageChunk image should not append after padded payload`() {
        var messages = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text("draw")),
            )
        )

        messages = messages.handleMessageChunk(imageDeltaChunk("QUJDRA=="))
        messages = messages.handleMessageChunk(imageDeltaChunk("/9j/"))

        val imageUrl = messages.last().parts.filterIsInstance<UIMessagePart.Image>().single().url
        assertEquals("data:image/png;base64,QUJDRA==", imageUrl)
    }

    @Test
    fun `handleMessageChunk text should keep latest metadata`() {
        var messages = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text("draw")),
            )
        )

        messages = messages.handleMessageChunk(
            textDeltaChunk(
                text = "first",
                thoughtSignature = "sig_1",
            )
        )
        messages = messages.handleMessageChunk(
            textDeltaChunk(
                text = " second",
                thoughtSignature = "sig_2",
            )
        )

        val textPart = messages.last().parts.filterIsInstance<UIMessagePart.Text>().single()
        assertEquals("first second", textPart.text)
        assertEquals("sig_2", textPart.metadata?.get("thoughtSignature")?.jsonPrimitive?.content)
    }

    @Test
    fun `handleMessageChunk image should keep latest metadata`() {
        var messages = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text("draw")),
            )
        )

        messages = messages.handleMessageChunk(
            imageDeltaChunk(
                payload = "QUJD",
                thoughtSignature = "sig_1",
            )
        )
        messages = messages.handleMessageChunk(
            imageDeltaChunk(
                payload = "QUJDREVG",
                thoughtSignature = "sig_2",
            )
        )

        val imagePart = messages.last().parts.filterIsInstance<UIMessagePart.Image>().single()
        assertEquals("data:image/png;base64,QUJDREVG", imagePart.url)
        assertEquals("sig_2", imagePart.metadata?.get("thoughtSignature")?.jsonPrimitive?.content)
    }

    private fun createTestMessages(count: Int): List<UIMessage> {
        return (0 until count).map { i ->
            UIMessage(
                role = if (i % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("Message $i"))
            )
        }
    }

    private fun imageDeltaChunk(payload: String, thoughtSignature: String? = null): MessageChunk {
        return MessageChunk(
            id = "img-$payload",
            model = "test-model",
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(
                            UIMessagePart.Image(
                                url = payload,
                                metadata = thoughtSignature?.let(::thoughtSignatureMetadata),
                            )
                        )
                    ),
                    message = null,
                    finishReason = null,
                )
            )
        )
    }

    private fun textDeltaChunk(text: String, thoughtSignature: String? = null): MessageChunk {
        return MessageChunk(
            id = "txt-$text",
            model = "test-model",
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(
                            UIMessagePart.Text(
                                text = text,
                                metadata = thoughtSignature?.let(::thoughtSignatureMetadata),
                            )
                        )
                    ),
                    message = null,
                    finishReason = null,
                )
            )
        )
    }

    private fun thoughtSignatureMetadata(signature: String) = buildJsonObject {
        put("thoughtSignature", signature)
    }
}
