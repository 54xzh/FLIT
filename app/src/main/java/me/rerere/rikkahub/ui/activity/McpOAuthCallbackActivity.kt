package me.rerere.rikkahub.ui.activity

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import org.koin.android.ext.android.inject

/**
 * 透明 Activity，用于接收 MCP OAuth 授权完成后的 deep link 回调
 * (lastchat://mcp-oauth-callback?code=...&state=...)，解析后经 [AppEventBus] 转发。
 *
 * 用 [AppScope] 发送事件，避免自身立即 finish() 取消协程导致事件丢失。
 * McpOAuthCoordinator 在打开浏览器前已订阅事件，这里 emit 即可被消费。
 *
 * launchMode=singleTask，Activity 复用时回调走 [onNewIntent]，故两处都需处理。
 */
class McpOAuthCallbackActivity : ComponentActivity() {
    private val eventBus by inject<AppEventBus>()
    private val appScope by inject<AppScope>()

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
        appScope.launch {
            eventBus.emit(
                AppEvent.McpOAuthCallback(
                    state = uri.getQueryParameter("state"),
                    code = uri.getQueryParameter("code"),
                    error = uri.getQueryParameter("error"),
                )
            )
        }
    }
}