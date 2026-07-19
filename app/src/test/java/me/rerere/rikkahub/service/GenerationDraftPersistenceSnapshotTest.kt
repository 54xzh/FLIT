package me.rerere.rikkahub.service

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.AskUserState
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 验证 [toolPartPersistenceKey] 只随「稳定身份/状态字段」变化，不随流式输出期间高频增长的
 * `arguments` / `content` 变化。
 *
 * 这是 1.5.0「逐字符写文件、卡顿」问题的回归防线：若 key 又把 `arguments.hashCode()`
 * 放进去，模型每输出一段 `workspace_write_file` 的 content 都会触发立即全量落库，
 * 阻塞流式 collect 导致 JSON 截断、模型逐字符退避。
 */
class GenerationDraftPersistenceSnapshotTest {

    @Test
    fun `ToolCall key 不随 arguments 流式增长变化`() {
        // 模拟流式输出：同一次工具调用，content 从短到长拼接增长
        val a = UIMessagePart.ToolCall("call_1", "workspace_write_file", """{"path":"a.txt","content":"x"}""")
        val b = UIMessagePart.ToolCall("call_1", "workspace_write_file", """{"path":"a.txt","content":"x" + "更长更长的内容……"}""")
        assertEquals(toolPartPersistenceKey(0, a), toolPartPersistenceKey(0, b))
    }

    @Test
    fun `ToolCall key 随 toolCallId 变化`() {
        val a = UIMessagePart.ToolCall("call_1", "workspace_write_file", "{}")
        val b = UIMessagePart.ToolCall("call_2", "workspace_write_file", "{}")
        assertNotEquals(toolPartPersistenceKey(0, a), toolPartPersistenceKey(0, b))
    }

    @Test
    fun `ToolCall key 不随 toolName 变化（流式可能分片拼接 toolName）`() {
        val a = UIMessagePart.ToolCall("call_1", "workspace", "{}")
        val b = UIMessagePart.ToolCall("call_1", "workspace_write_file", "{}")
        assertEquals(toolPartPersistenceKey(0, a), toolPartPersistenceKey(0, b))
    }

    @Test
    fun `ToolCall key 随 messageIndex 变化`() {
        val part = UIMessagePart.ToolCall("call_1", "workspace_write_file", "{}")
        assertNotEquals(toolPartPersistenceKey(0, part), toolPartPersistenceKey(1, part))
    }

    @Test
    fun `ToolCall key 随 metadata 变化`() {
        val a = UIMessagePart.ToolCall("call_1", "workspace_write_file", "{}", metadata = null)
        val b = UIMessagePart.ToolCall("call_1", "workspace_write_file", "{}", metadata = jsonObjectOf("k" to "v"))
        assertNotEquals(toolPartPersistenceKey(0, a), toolPartPersistenceKey(0, b))
    }

    @Test
    fun `ToolResult key 不随 content 变化`() {
        val a = UIMessagePart.ToolResult("call_1", "workspace_write_file", JsonPrimitive("短结果"), JsonPrimitive("{}"))
        val b = UIMessagePart.ToolResult("call_1", "workspace_write_file", JsonPrimitive("超长的结果内容…………"), JsonPrimitive("""{"x":1}"""))
        assertEquals(toolPartPersistenceKey(0, a), toolPartPersistenceKey(0, b))
    }

    @Test
    fun `ToolResult key 不随 arguments 变化`() {
        val a = UIMessagePart.ToolResult("call_1", "workspace_write_file", JsonPrimitive("ok"), JsonPrimitive("{}"))
        val b = UIMessagePart.ToolResult("call_1", "workspace_write_file", JsonPrimitive("ok"), JsonPrimitive("""{"path":"a.txt"}"""))
        assertEquals(toolPartPersistenceKey(0, a), toolPartPersistenceKey(0, b))
    }

    @Test
    fun `ToolResult key 随 toolCallId 变化`() {
        val a = UIMessagePart.ToolResult("call_1", "workspace_write_file", JsonPrimitive("ok"), JsonPrimitive("{}"))
        val b = UIMessagePart.ToolResult("call_2", "workspace_write_file", JsonPrimitive("ok"), JsonPrimitive("{}"))
        assertNotEquals(toolPartPersistenceKey(0, a), toolPartPersistenceKey(0, b))
    }

    @Test
    fun `ToolApproval key 随 state 翻转变化（仍为关键节点，立即落盘）`() {
        val pending = UIMessagePart.ToolApproval("call_1", "workspace_write_file", state = ToolApprovalState.Pending)
        val approved = UIMessagePart.ToolApproval("call_1", "workspace_write_file", state = ToolApprovalState.Approved)
        assertNotEquals(toolPartPersistenceKey(0, pending), toolPartPersistenceKey(0, approved))
    }

    @Test
    fun `AskUser key 随 state 与 answer 变化（仍为关键节点，立即落盘）`() {
        val pending = UIMessagePart.AskUser("call_1", "选哪个?", listOf("A", "B"), state = AskUserState.Pending)
        val answered = UIMessagePart.AskUser("call_1", "选哪个?", listOf("A", "B"), state = AskUserState.Answered, answer = "A")
        assertNotEquals(toolPartPersistenceKey(0, pending), toolPartPersistenceKey(0, answered))
    }

    @Test
    fun `非过程 part（Text）返回 null 不进入快照`() {
        assertEquals(null, toolPartPersistenceKey(0, UIMessagePart.Text("hello")))
    }

    @Test
    fun `新增 ToolCall 与已有 ToolResult 在不同 messageIndex 上 key 不同`() {
        // 模拟 part 列表新增一项（结构变化），messageIndex 不同即可区分
        val call = UIMessagePart.ToolCall("call_1", "workspace_write_file", "{}")
        val result = UIMessagePart.ToolResult("call_1", "workspace_write_file", JsonPrimitive("ok"), JsonPrimitive("{}"))
        assertNotEquals(toolPartPersistenceKey(0, call), toolPartPersistenceKey(1, result))
    }

    private fun jsonObjectOf(vararg pairs: Pair<String, String>) =
        buildJsonObject {
            pairs.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
        }
}