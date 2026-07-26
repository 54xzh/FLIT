package me.rerere.ai.ui

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
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
    fun `finalizeInterruptedGenerationMessages adds missing tool result on user cancel`() {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Search"))),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning("thinking", finishedAt = null),
                    UIMessagePart.ToolCall("call1", "search_web", """{"query":"LastChat"}"""),
                )
            ),
        )

        val result = messages.finalizeInterruptedGenerationMessages(
            reason = InterruptedGenerationReason.UserCancelled,
        )

        // user → assistant(工具调用) → tool(占位结果) → user(独立打断标记)
        assertEquals(4, result.size)
        val assistant = result[1]
        val reasoning = assistant.parts.filterIsInstance<UIMessagePart.Reasoning>().single()
        assertTrue(reasoning.finishedAt != null)
        assertEquals(listOf("call1"), assistant.getToolCalls().map { it.toolCallId })

        val toolResult = result[2].getToolResults().single()
        assertEquals("call1", toolResult.toolCallId)
        assertEquals("search_web", toolResult.toolName)
        assertEquals("interrupted", toolResult.content.jsonObject["status"]?.jsonPrimitive?.contentOrNull)
        assertEquals("user_cancelled", toolResult.content.jsonObject["reason"]?.jsonPrimitive?.contentOrNull)

        // 末尾是独立 user 打断标记消息
        val marker = result[3]
        assertEquals(MessageRole.USER, marker.role)
        val markerText = marker.parts.filterIsInstance<UIMessagePart.Text>().single().text
        assertTrue(markerText.contains("<app_context>The user stopped the output.</app_context>"))
        assertEquals("", markerText.stripInterruptedAppContextForDisplay())
    }

    @Test
    fun `finalizeInterruptedGenerationMessages only fills missing result in mixed tool batch`() {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Search twice"))),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.ToolCall("call1", "search_web", """{"query":"one"}"""),
                    UIMessagePart.ToolCall("call2", "search_web", """{"query":"two"}"""),
                )
            ),
            UIMessage(
                role = MessageRole.TOOL,
                parts = listOf(
                    UIMessagePart.ToolResult("call1", "search_web", JsonPrimitive("done"), JsonPrimitive("{}")),
                )
            ),
        )

        val result = messages.finalizeInterruptedGenerationMessages(
            reason = InterruptedGenerationReason.GenerationFailed,
            detail = "stream failed",
        )

        // user → assistant(两个工具调用) → tool(call1真结果 + call2占位) → user(独立打断标记)
        assertEquals(4, result.size)
        val results = result[2].getToolResults()
        assertEquals(listOf("call1", "call2"), results.map { it.toolCallId })
        assertEquals("generation_failed", results[1].content.jsonObject["reason"]?.jsonPrimitive?.contentOrNull)
        assertEquals("stream failed", results[1].content.jsonObject["message"]?.jsonPrimitive?.contentOrNull)
        assertEquals(MessageRole.USER, result[3].role)
    }

    @Test
    fun `finalizeInterruptedGenerationMessages repairs blank id and invalid arguments`() {
        val partialArguments = """{"query":"""
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Search"))),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.ToolCall("", "search_web", partialArguments),
                )
            ),
        )

        val result = messages.finalizeInterruptedGenerationMessages(
            reason = InterruptedGenerationReason.ReplacedByNewRequest,
        )

        val repairedCall = result[1].getToolCalls().single()
        assertTrue(repairedCall.toolCallId.startsWith("interrupted_"))
        assertEquals("{}", repairedCall.arguments)

        val toolResult = result[2].getToolResults().single()
        assertEquals(repairedCall.toolCallId, toolResult.toolCallId)
        assertEquals(partialArguments, toolResult.content.jsonObject["partialArguments"]?.jsonPrimitive?.contentOrNull)
        assertEquals("replaced_by_new_request", toolResult.content.jsonObject["reason"]?.jsonPrimitive?.contentOrNull)
        // 末尾追加独立 user 打断标记
        assertEquals(4, result.size)
        assertEquals(MessageRole.USER, result[3].role)
    }

    @Test
    fun `finalizeInterruptedGenerationMessages preserves server side tool calls`() {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Search"))),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.ToolCall("call1", "server_search", "{}"),
                )
            ),
        )

        val result = messages.finalizeInterruptedGenerationMessages(
            reason = InterruptedGenerationReason.UserCancelled,
        ) { false }

        assertEquals(messages, result)
    }

    @Test
    fun `finalizeInterruptedGenerationMessages leaves interrupted assistant text untouched and appends standalone user marker`() {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Search"))),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning("thinking", finishedAt = null),
                    UIMessagePart.Text("半截回复"),
                )
            ),
        )

        val result = messages.finalizeInterruptedGenerationMessages(
            reason = InterruptedGenerationReason.UserCancelled,
        )

        // 原 assistant 正文保持不变, 不再被追加隐藏标记
        val textPart = result[1].parts.filterIsInstance<UIMessagePart.Text>().single()
        assertEquals("半截回复", textPart.text)
        // 末尾追加一条独立 user 打断标记
        assertEquals(3, result.size)
        val marker = result[2]
        assertEquals(MessageRole.USER, marker.role)
        val markerText = marker.parts.filterIsInstance<UIMessagePart.Text>().single().text
        assertEquals(
            "<app_context>The user stopped the output.</app_context>",
            markerText,
        )
        assertEquals("", markerText.stripInterruptedAppContextForDisplay())
    }

    @Test
    fun `finalizeInterruptedGenerationMessages does not duplicate hidden context on repeated finalization`() {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Search"))),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("半截回复"))
            ),
        )

        val once = messages.finalizeInterruptedGenerationMessages(
            reason = InterruptedGenerationReason.UserCancelled,
        )
        val twice = once.finalizeInterruptedGenerationMessages(
            reason = InterruptedGenerationReason.UserCancelled,
        )

        assertEquals(once, twice)
        // 原 assistant 正文仍不含标记
        val assistantText = twice[1].parts.filterIsInstance<UIMessagePart.Text>().single().text
        assertFalse(assistantText.contains("<app_context>"))
        // 整个序列里只有一条 user marker, 且只含一个 <app_context> 标签
        val markerCount = twice.sumOf { msg ->
            msg.parts
                .filterIsInstance<UIMessagePart.Text>()
                .sumOf { Regex("<app_context>").findAll(it.text).count() }
        }
        assertEquals(1, markerCount)
    }

    @Test
    fun `stripInterruptedAppContextForDisplay hides interrupted context markers`() {
        val text = buildString {
            append("半截回复")
            append("\n\n<app_context>The user stopped the output.</app_context>")
            append("\n继续可见")
            append("\n\n<app_context>The message was interrupted.</app_context>")
        }

        assertEquals("半截回复\n继续可见", text.stripInterruptedAppContextForDisplay())
    }

    @Test
    fun `finalizeInterruptedGenerationMessages inserts standalone interrupted marker for bare tool call`() {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Search"))),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.ToolCall("call1", "search_web", """{"query":"LastChat"}"""),
                )
            ),
        )

        val result = messages.finalizeInterruptedGenerationMessages(
            reason = InterruptedGenerationReason.UserCancelled,
        )

        // user → assistant(工具调用) → tool(占位结果) → user(独立打断标记)
        assertEquals(4, result.size)
        // 原 assistant 不含任何标记文本, 保持只有工具调用
        val assistant = result[1]
        assertTrue(assistant.parts.filterIsInstance<UIMessagePart.Text>().isEmpty())
        assertEquals("call1", assistant.getToolCalls().single().toolCallId)
        // 占位 tool result
        assertEquals("call1", result[2].getToolResults().single().toolCallId)
        // 末尾追加独立 user 打断标记
        val marker = result[3]
        assertEquals(MessageRole.USER, marker.role)
        val markerText = marker.parts.filterIsInstance<UIMessagePart.Text>().single().text
        assertTrue(markerText.contains("<app_context>The user stopped the output.</app_context>"))
        assertEquals("", markerText.stripInterruptedAppContextForDisplay())
    }

    @Test
    fun `finalizeInterruptedGenerationMessages does not duplicate standalone marker on repeated finalization`() {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Search"))),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.ToolCall("call1", "search_web", """{"query":"LastChat"}"""),
                )
            ),
        )

        val once = messages.finalizeInterruptedGenerationMessages(
            reason = InterruptedGenerationReason.UserCancelled,
        )
        val twice = once.finalizeInterruptedGenerationMessages(
            reason = InterruptedGenerationReason.UserCancelled,
        )
        val markerCount = twice.sumOf { msg ->
            msg.parts
                .filterIsInstance<UIMessagePart.Text>()
                .sumOf { Regex("<app_context>").findAll(it.text).count() }
        }

        assertEquals(once, twice)
        assertEquals(1, markerCount)
    }

    @Test
    fun `finalizeInterruptedGenerationMessages is a no-op on user-only sequence`() {
        // 连发场景: 上一条尚停在前 user 阶段(还未真正生成 assistant), 不应崩溃也不应补打断标记.
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("第一条"))),
        )
        val result = messages.finalizeInterruptedGenerationMessages(
            reason = InterruptedGenerationReason.ReplacedByNewRequest,
        )
        assertEquals(messages, result)
        assertFalse(result.any { it.isStandaloneInterruptedAppContextMarker() })
    }

    @Test
    fun `finalizeInterruptedGenerationMessages is a no-op on empty sequence`() {
        val result = emptyList<UIMessage>().finalizeInterruptedGenerationMessages(
            reason = InterruptedGenerationReason.ReplacedByNewRequest,
        )
        assertTrue(result.isEmpty())
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

    private fun toolCallDeltaChunk(vararg toolCalls: UIMessagePart.ToolCall): MessageChunk {
        return MessageChunk(
            id = "tool-delta",
            model = "test-model",
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = toolCalls.toList(),
                    ),
                    message = null,
                    finishReason = null,
                )
            )
        )
    }

    @Test
    fun `handleMessageChunk merges indexed tool call fragments without id into one call`() {
        // OpenAI 兼容渠道不发 id 时，参数片段靠 index 归位，不应被当成新调用
        var messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("写文件"))),
        )
        messages = messages.handleMessageChunk(
            toolCallDeltaChunk(UIMessagePart.ToolCall("", "workspace_write_file", "", index = 0))
        )
        messages = messages.handleMessageChunk(
            toolCallDeltaChunk(UIMessagePart.ToolCall("", "", """{"path":"a.txt",""", index = 0))
        )
        messages = messages.handleMessageChunk(
            toolCallDeltaChunk(UIMessagePart.ToolCall("", "", """"content":"hi"}""", index = 0))
        )

        val toolCalls = messages.last().parts.filterIsInstance<UIMessagePart.ToolCall>()
        assertEquals(1, toolCalls.size)
        assertEquals("workspace_write_file", toolCalls.single().toolName)
        assertEquals("""{"path":"a.txt","content":"hi"}""", toolCalls.single().arguments)
    }

    @Test
    fun `handleMessageChunk keeps parallel indexed tool calls separate`() {
        var messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("并行调用"))),
        )
        messages = messages.handleMessageChunk(
            toolCallDeltaChunk(
                UIMessagePart.ToolCall("call_a", "tool_a", "", index = 0),
                UIMessagePart.ToolCall("call_b", "tool_b", "", index = 1),
            )
        )
        messages = messages.handleMessageChunk(
            toolCallDeltaChunk(
                UIMessagePart.ToolCall("", "", """{"a":1}""", index = 0),
                UIMessagePart.ToolCall("", "", """{"b":2}""", index = 1),
            )
        )

        val toolCalls = messages.last().parts.filterIsInstance<UIMessagePart.ToolCall>()
        assertEquals(2, toolCalls.size)
        assertEquals("""{"a":1}""", toolCalls.first { it.toolName == "tool_a" }.arguments)
        assertEquals("""{"b":2}""", toolCalls.first { it.toolName == "tool_b" }.arguments)
    }

    @Test
    fun `handleMessageChunk appends complete blank-id tool calls without index as new calls`() {
        // Google 渠道：id 恒为空且每个 chunk 都是一次完整调用，行为保持追加
        var messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("两次调用"))),
        )
        messages = messages.handleMessageChunk(
            toolCallDeltaChunk(UIMessagePart.ToolCall("", "tool_a", """{"a":1}"""))
        )
        messages = messages.handleMessageChunk(
            toolCallDeltaChunk(UIMessagePart.ToolCall("", "tool_b", """{"b":2}"""))
        )

        val toolCalls = messages.last().parts.filterIsInstance<UIMessagePart.ToolCall>()
        assertEquals(2, toolCalls.size)
        assertEquals(listOf("tool_a", "tool_b"), toolCalls.map { it.toolName })
    }

    @Test
    fun `handleMessageChunk merges blank-id fragment into last tool call with id`() {
        // Claude 渠道：首个块带 id，后续 input_json_delta 片段 id 为空，应拼进最近一次调用
        var messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("调用"))),
        )
        messages = messages.handleMessageChunk(
            toolCallDeltaChunk(UIMessagePart.ToolCall("toolu_1", "search_web", ""))
        )
        messages = messages.handleMessageChunk(
            toolCallDeltaChunk(UIMessagePart.ToolCall("", "", """{"query":"hi"}"""))
        )

        val toolCalls = messages.last().parts.filterIsInstance<UIMessagePart.ToolCall>()
        assertEquals(1, toolCalls.size)
        assertEquals("toolu_1", toolCalls.single().toolCallId)
        assertEquals("""{"query":"hi"}""", toolCalls.single().arguments)
    }

    @Test
    fun `handleMessageChunk treats complete call with repeated index as new call`() {
        // 个别网关每个 chunk 重发完整调用且 index 恒为 0：已有参数是完整 JSON 时应追加为新调用
        var messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("两次完整调用"))),
        )
        messages = messages.handleMessageChunk(
            toolCallDeltaChunk(UIMessagePart.ToolCall("", "tool_a", """{"a":1}""", index = 0))
        )
        messages = messages.handleMessageChunk(
            toolCallDeltaChunk(UIMessagePart.ToolCall("", "tool_a", """{"b":2}""", index = 0))
        )

        val toolCalls = messages.last().parts.filterIsInstance<UIMessagePart.ToolCall>()
        assertEquals(2, toolCalls.size)
        assertEquals(listOf("""{"a":1}""", """{"b":2}"""), toolCalls.map { it.arguments })
    }

    @Test
    fun `handleMessageChunk drops full resend of the same tool call`() {
        // SSE 重放/累积式网关把同一条完整调用重发一遍，不应追加成第二次执行
        var messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("重发"))),
        )
        val call = UIMessagePart.ToolCall("call_1", "tool_a", """{"a":1}""", index = 0)
        messages = messages.handleMessageChunk(toolCallDeltaChunk(call))
        messages = messages.handleMessageChunk(toolCallDeltaChunk(call.copy()))

        val toolCalls = messages.last().parts.filterIsInstance<UIMessagePart.ToolCall>()
        assertEquals(1, toolCalls.size)
        assertEquals("""{"a":1}""", toolCalls.single().arguments)

        // 空 id 的同内容重发同样只保留一条
        var blankIdMessages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("重发"))),
        )
        val blankIdCall = UIMessagePart.ToolCall("", "tool_a", """{"a":1}""", index = 0)
        blankIdMessages = blankIdMessages.handleMessageChunk(toolCallDeltaChunk(blankIdCall))
        blankIdMessages = blankIdMessages.handleMessageChunk(toolCallDeltaChunk(blankIdCall.copy()))
        assertEquals(1, blankIdMessages.last().parts.filterIsInstance<UIMessagePart.ToolCall>().size)
    }

    @Test
    fun `handleMessageChunk routes indexed nameless fragment to last call when index misses`() {
        // 混发网关：首块不带 index，后续参数片段带 index——无名片段应兜底拼进最近一次调用
        var messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("混发"))),
        )
        messages = messages.handleMessageChunk(
            toolCallDeltaChunk(UIMessagePart.ToolCall("call_1", "tool_a", ""))
        )
        messages = messages.handleMessageChunk(
            toolCallDeltaChunk(UIMessagePart.ToolCall("", "", """{"a":1}""", index = 0))
        )

        val toolCalls = messages.last().parts.filterIsInstance<UIMessagePart.ToolCall>()
        assertEquals(1, toolCalls.size)
        assertEquals("""{"a":1}""", toolCalls.single().arguments)
        assertEquals("call_1", toolCalls.single().toolCallId)
    }

    @Test
    fun `handleMessageChunk falls back to id match when index misses`() {
        // 混发网关：首块不带 index 但带 id，后续片段带 index 且带同一 id——按 id 兜底合并
        var messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("混发带id"))),
        )
        messages = messages.handleMessageChunk(
            toolCallDeltaChunk(UIMessagePart.ToolCall("call_1", "tool_a", ""))
        )
        messages = messages.handleMessageChunk(
            toolCallDeltaChunk(UIMessagePart.ToolCall("call_1", "", """{"a":1}""", index = 0))
        )

        val toolCalls = messages.last().parts.filterIsInstance<UIMessagePart.ToolCall>()
        assertEquals(1, toolCalls.size)
        assertEquals("""{"a":1}""", toolCalls.single().arguments)
    }

    @Test
    fun `handleMessageChunk starts new call when fragmented call reuses completed index`() {
        // 恒定 index=0 且不发 id 的网关：第二次调用以碎片形式到来，不应拼进已完成的第一次调用
        var messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("连续两次"))),
        )
        messages = messages.handleMessageChunk(
            toolCallDeltaChunk(UIMessagePart.ToolCall("", "tool_a", """{"a":1}""", index = 0))
        )
        messages = messages.handleMessageChunk(
            toolCallDeltaChunk(UIMessagePart.ToolCall("", "tool_b", "", index = 0))
        )
        messages = messages.handleMessageChunk(
            toolCallDeltaChunk(UIMessagePart.ToolCall("", "", """{"b":2}""", index = 0))
        )

        val toolCalls = messages.last().parts.filterIsInstance<UIMessagePart.ToolCall>()
        assertEquals(2, toolCalls.size)
        assertEquals("""{"a":1}""", toolCalls.first { it.toolName == "tool_a" }.arguments)
        assertEquals("""{"b":2}""", toolCalls.first { it.toolName == "tool_b" }.arguments)
    }

    @Test
    fun `handleMessageChunk routes indexed fragments by ordinal when first blocks lack index`() {
        // 混发网关并行两次调用：首块都不带 index，参数片段按"第 N 条调用"序号对号入座
        var messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("并行混发"))),
        )
        messages = messages.handleMessageChunk(
            toolCallDeltaChunk(
                UIMessagePart.ToolCall("call_a", "tool_a", ""),
                UIMessagePart.ToolCall("call_b", "tool_b", ""),
            )
        )
        messages = messages.handleMessageChunk(
            toolCallDeltaChunk(
                UIMessagePart.ToolCall("", "", """{"a":1}""", index = 0),
                UIMessagePart.ToolCall("", "", """{"b":2}""", index = 1),
            )
        )

        val toolCalls = messages.last().parts.filterIsInstance<UIMessagePart.ToolCall>()
        assertEquals(2, toolCalls.size)
        assertEquals("""{"a":1}""", toolCalls.first { it.toolName == "tool_a" }.arguments)
        assertEquals("""{"b":2}""", toolCalls.first { it.toolName == "tool_b" }.arguments)
    }

    @Test
    fun `handleMessageChunk drops trailing name echo after call completes`() {
        // 逐片段重复携带工具名的网关：参数发完后的收尾 chunk（同名+空参数）不应变成幻影新调用
        var messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("收尾回显"))),
        )
        messages = messages.handleMessageChunk(
            toolCallDeltaChunk(UIMessagePart.ToolCall("", "tool_a", """{"a":1}""", index = 0))
        )
        messages = messages.handleMessageChunk(
            toolCallDeltaChunk(UIMessagePart.ToolCall("", "tool_a", "", index = 0))
        )

        val toolCalls = messages.last().parts.filterIsInstance<UIMessagePart.ToolCall>()
        assertEquals(1, toolCalls.size)
        assertEquals("""{"a":1}""", toolCalls.single().arguments)
    }

    @Test
    fun `repairToolCallMessageSequence drops nameless tool call and its result`() {
        // 无名调用是流式合并救不回来的残片，回传服务端会因空 function.name 被拒，必须剔除
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("q"))),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.ToolCall("call_ok", "search_web", "{}"),
                    UIMessagePart.ToolCall("call_broken", "", "{}"),
                ),
            ),
            UIMessage(
                role = MessageRole.TOOL,
                parts = listOf(
                    UIMessagePart.ToolResult(toolCallId = "call_ok", toolName = "search_web", content = JsonPrimitive("ok"), arguments = buildJsonObject { }),
                    UIMessagePart.ToolResult(toolCallId = "call_broken", toolName = "", content = JsonPrimitive("err"), arguments = buildJsonObject { }),
                ),
            ),
        )

        val repaired = messages.repairToolCallMessageSequence()
        val assistant = repaired.first { it.role == MessageRole.ASSISTANT }
        assertEquals(listOf("call_ok"), assistant.getToolCalls().map { it.toolCallId })
        val toolResults = repaired.first { it.role == MessageRole.TOOL }.getToolResults()
        assertEquals(listOf("call_ok"), toolResults.map { it.toolCallId })
    }

    @Test
    fun `ToolCall merge dedupes repeated tool name and adopts id from delta`() {
        val base = UIMessagePart.ToolCall("", "tool_a", "{\"a\"", index = 0)
        val merged = base.merge(UIMessagePart.ToolCall("call_1", "tool_a", ":1}", index = 0))

        assertEquals("call_1", merged.toolCallId)
        assertEquals("tool_a", merged.toolName)
        assertEquals("""{"a":1}""", merged.arguments)

        val namedLater = UIMessagePart.ToolCall("", "", "", index = 1)
            .merge(UIMessagePart.ToolCall("", "tool_b", "{}", index = 1))
        assertEquals("tool_b", namedLater.toolName)
    }
}
