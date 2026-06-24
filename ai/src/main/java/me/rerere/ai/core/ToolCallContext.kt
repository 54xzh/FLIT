package me.rerere.ai.core

import kotlin.coroutines.CoroutineContext

/**
 * 隐式携带「当前工具调用的 toolCallId」的协程上下文元素。
 *
 * [Tool.execute] 的签名固定为 `suspend (JsonElement) -> JsonElement`，
 * 无法直接拿到 handler 在调用时已知的 toolCallId。这里通过协程上下文
 * 在 [GenerationHandler] 调用 `tool.execute(args)` 外层注入，工具内部
 * 读 `coroutineContext[ToolCallContext]?.toolCallId` 即可取用，无需改
 * Tool 接口、不影响其它工具。
 */
class ToolCallContext(
    val toolCallId: String,
) : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> = Key

    companion object Key : CoroutineContext.Key<ToolCallContext>
}