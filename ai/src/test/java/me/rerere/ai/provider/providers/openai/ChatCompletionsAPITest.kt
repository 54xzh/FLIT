package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ChatCompletionsAPITest {
    private fun buildMessagesJson(
        api: ChatCompletionsAPI,
        messages: List<UIMessage>,
        modelId: String,
    ): JsonArray {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildMessages",
            List::class.java,
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(api, messages, modelId) as JsonArray
    }

    @Test
    fun `deepseek tool call should include reasoning_content`() {
        val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hi"))),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning(reasoning = "thinking..."),
                    UIMessagePart.ToolCall(toolCallId = "call_1", toolName = "search", arguments = "{}"),
                    UIMessagePart.Text("")
                )
            ),
        )

        val json = buildMessagesJson(api, messages, modelId = "deepseek-v3.2")
        val assistant = json[1].jsonObject
        assertEquals("thinking...", assistant["reasoning_content"]?.jsonPrimitive?.content)
        assertNotNull(assistant["tool_calls"])
    }

    @Test
    fun `deepseek tool call should include empty reasoning_content when missing`() {
        val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hi"))),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.ToolCall(toolCallId = "call_1", toolName = "search", arguments = "{}"),
                    UIMessagePart.Text("")
                )
            ),
        )

        val json = buildMessagesJson(api, messages, modelId = "deepseek-v3.2")
        val assistant = json[1].jsonObject
        assertEquals("", assistant["reasoning_content"]?.jsonPrimitive?.content)
        assertNotNull(assistant["tool_calls"])
    }

    @Test
    fun `gpt tool call should not include reasoning_content`() {
        val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hi"))),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.ToolCall(toolCallId = "call_1", toolName = "search", arguments = "{}"),
                    UIMessagePart.Text("")
                )
            ),
        )

        val json = buildMessagesJson(api, messages, modelId = "gpt-4o-mini")
        val assistant = json[1].jsonObject
        assertNull(assistant["reasoning_content"])
        assertNotNull(assistant["tool_calls"])
    }

    @Test
    fun `deepseek should pass back reasoning_content from previous tool-call turns`() {
        val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
        val messages = toolCallConversation(
            toolReasoning = "reasoning_A",
            finalReasoning = "reasoning_B"
        )

        val json = buildMessagesJson(api, messages, modelId = "deepseek-v3.2")
        assertEquals("reasoning_A", json[1].jsonObject["reasoning_content"]?.jsonPrimitive?.content)
        assertEquals("reasoning_B", json[3].jsonObject["reasoning_content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `deepseek should pass back empty reasoning_content from previous tool-call turns when missing`() {
        val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("搜天气"))),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.ToolCall(toolCallId = "call_1", toolName = "weather", arguments = "{}"),
                    UIMessagePart.Text("")
                )
            ),
            UIMessage(
                role = MessageRole.TOOL,
                parts = listOf(toolResult())
            ),
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("那明天呢？"))),
        )

        val json = buildMessagesJson(api, messages, modelId = "deepseek-v3.2")
        assertEquals("", json[1].jsonObject["reasoning_content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `mimo should pass back reasoning_content from previous tool-call turns`() {
        val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
        val messages = toolCallConversation(
            toolReasoning = "mimo_reasoning_A",
            finalReasoning = "mimo_reasoning_B"
        )

        val json = buildMessagesJson(api, messages, modelId = "MiMo-V2.5-Pro")
        assertEquals("mimo_reasoning_A", json[1].jsonObject["reasoning_content"]?.jsonPrimitive?.content)
        assertEquals("mimo_reasoning_B", json[3].jsonObject["reasoning_content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `mimo tool call should include empty reasoning_content when missing`() {
        val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hi"))),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.ToolCall(toolCallId = "call_1", toolName = "search", arguments = "{}"),
                    UIMessagePart.Text("")
                )
            ),
        )

        val json = buildMessagesJson(api, messages, modelId = "mimo-v2-flash")
        val assistant = json[1].jsonObject
        assertEquals("", assistant["reasoning_content"]?.jsonPrimitive?.content)
        assertNotNull(assistant["tool_calls"])
    }

    @Test
    fun `previous turn reasoning should not be uploaded`() {
        val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("turn1"))),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning(reasoning = "thinking..."),
                    UIMessagePart.Text("answer")
                )
            ),
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("turn2"))),
        )

        val json = buildMessagesJson(api, messages, modelId = "deepseek-v3.2")
        val assistant = json[1].jsonObject
        assertNull(assistant["reasoning_content"])
    }

    private fun toolCallConversation(
        toolReasoning: String,
        finalReasoning: String,
    ): List<UIMessage> = listOf(
        UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("搜天气"))),
        UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = toolReasoning),
                UIMessagePart.ToolCall(toolCallId = "call_1", toolName = "weather", arguments = "{}"),
                UIMessagePart.Text("")
            )
        ),
        UIMessage(
            role = MessageRole.TOOL,
            parts = listOf(toolResult())
        ),
        UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = finalReasoning),
                UIMessagePart.Text("晴天")
            )
        ),
        UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("那明天呢？"))),
    )

    private fun toolResult() = UIMessagePart.ToolResult(
        toolCallId = "call_1",
        toolName = "weather",
        content = JsonPrimitive("晴天"),
        arguments = JsonObject(emptyMap())
    )
}
