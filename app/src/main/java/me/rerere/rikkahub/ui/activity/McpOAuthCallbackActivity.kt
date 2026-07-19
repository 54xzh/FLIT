package me.rerere.rikkahub.ui.activity

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.mcp.McpOAuthCallbackRecord
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import org.koin.android.ext.android.inject

/**
 * 透明 Activity，用于接收 MCP OAuth 授权完成后的 deep link 回调
 * (lastchat://mcp-oauth-callback?code=...&state=...)。
 *
 * 回调同时走两条路：
 * 1. 经 [AppEventBus] 转发，供进行中的授权协程（已订阅事件）立即消费；
 * 2. 落盘到 DataStore（按 state 索引），供进程重建后尚未启动的恢复路径消费。
 *
 * 第二条路是关键：进程被系统回收后，授权等待协程可能还没启动，事件总线又是
 * replay=0 不保留历史，仅靠事件会丢失回调。落盘 + DataStore flow 重放保证回调不丢。
 *
 * launchMode=singleTask，Activity 复用时回调走 [onNewIntent]，故两处都需处理。
 */
class McpOAuthCallbackActivity : ComponentActivity() {
    private val eventBus by inject<AppEventBus>()
    private val appScope by inject<AppScope>()
    private val settingsStore by inject<SettingsStore>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleCallback(intent?.data)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCallback(intent.data)
        finish()
    }

    private fun handleCallback(uri: Uri?) {
        if (uri == null) return
        val state = uri.getQueryParameter("state")
        val code = uri.getQueryParameter("code")
        val error = uri.getQueryParameter("error")
        // 没有 state 的回调无法与待授权记录匹配，直接丢弃，避免污染落盘的回调表。
        if (state.isNullOrBlank()) return
        appScope.launch {
            // 校验 state 是否属于某个待授权记录：Activity 对外暴露（exported），
            // 任意应用都能发 deep link；若不校验，恶意/误调用可写入大量无效回调且永不被清理。
            // 仅当 state 命中当前待授权记录时才落盘与发事件。
            val hasPending = settingsStore.readPendingMcpAuthorizations().values.any { it.state == state }
            if (!hasPending) return@launch
            // 落盘优先：即使 emit 因为没有订阅者被丢弃，落盘记录也能被恢复路径读取。
            val record = McpOAuthCallbackRecord(state = state, code = code, error = error)
            settingsStore.writePendingMcpOAuthCallback(state, record)
            // 同步发事件给进行中的协程，省一次磁盘往返。
            eventBus.emit(AppEvent.McpOAuthCallback(state = state, code = code, error = error))
        }
    }
}