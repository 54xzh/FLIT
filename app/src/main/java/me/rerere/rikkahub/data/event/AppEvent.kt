package me.rerere.rikkahub.data.event

/**
 * 全局事件总线事件。
 *
 * 当前仅承载 MCP OAuth 授权完成后的 deep link 回传结果；
 * 其它跨组件事件按需扩展，避免在多个 ViewModel 之间互相注入。
 */
sealed class AppEvent {
    /** MCP OAuth 授权完成后经 deep link 回传的结果。 */
    data class McpOAuthCallback(
        val state: String?,
        val code: String?,
        val error: String?,
    ) : AppEvent()
}