package me.rerere.rikkahub.data.ai.mcp

import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/** OAuth 授权回调的 redirect_uri，需与 AndroidManifest 中 McpOAuthCallbackActivity 的 intent-filter 保持一致。 */
const val MCP_OAUTH_REDIRECT_URI = "lastchat://mcp-oauth-callback"

/** 使用 Chrome Custom Tabs 打开授权 URL。 */
fun launchOAuthAuthorization(context: Context, authorizationUrl: String) {
    val intent = CustomTabsIntent.Builder()
        .setShowTitle(true)
        .build()
    intent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    intent.launchUrl(context, authorizationUrl.toUri())
}

/**
 * 进行中的 OAuth 授权记录，持久化后用于进程重建（被系统回收）后恢复等待回调。
 *
 * 正常授权时 state 与 PKCE verifier 只存在协程内存里；进程被杀后浏览器返回的 deep link
 * 会因无订阅者而丢失。把这份最小记录写入 DataStore，进程重启时若记录仍有效，重新订阅事件
 * 并完成令牌交换。
 *
 * 只保留单条进行中授权（移动端实际场景）。
 *
 * 脱敏 toString：避免 state/verifier/secret 进入日志。
 */
@Serializable
data class McpPendingAuthorization(
    val configId: Uuid,
    val serverUrl: String,
    val state: String,
    val pkceVerifier: String,
    /** 授权发起时存入的 pending OAuth（含 client_id/secret、端点等），回调成功后在其上补 token。 */
    val pendingOAuth: McpOAuthState,
    val startedAt: Long,
) {
    override fun toString(): String =
        "McpPendingAuthorization(configId=$configId, serverUrl=$serverUrl, " +
            "state=***(${state.length}), pkceVerifier=***(${pkceVerifier.length}), " +
            "pendingOAuth=$pendingOAuth, startedAt=$startedAt)"
}

/**
 * OAuth 回调 deep link 结果的持久化记录。回调 Activity 落盘后，授权等待协程
 * （正常路径或进程重建后的恢复路径）通过 DataStore flow 读取并消费。
 *
 * 进程被杀场景下，回调可能在等待协程启动前到达；落盘 + DataStore flow 重放
 * 保证回调不丢失。消费后按 state 删除。
 *
 * 脱敏 toString：避免 code/error 内容进入日志。
 */
@Serializable
data class McpOAuthCallbackRecord(
    val state: String?,
    val code: String?,
    val error: String?,
) {
    override fun toString(): String =
        "McpOAuthCallbackRecord(state=***(${state?.length ?: 0}), " +
            "code=${if (code.isNullOrEmpty()) "null" else "***(${code.length})"}, " +
            "error=$error)"
}