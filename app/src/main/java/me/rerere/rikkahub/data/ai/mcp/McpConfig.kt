package me.rerere.rikkahub.data.ai.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.ai.core.InputSchema
import kotlin.uuid.Uuid

@Serializable
data class McpCommonOptions(
    val enable: Boolean = true,
    val name: String = "",
    val headers: List<Pair<String, String>> = emptyList(),
    val tools: List<McpTool> = emptyList(),
    val oauth: McpOAuthState? = null,
)

/**
 * OAuth 2.1 授权状态，遵循 MCP 授权规范 (2025-11-25)。
 *
 * 持久化动态客户端注册结果、授权服务器端点以及令牌，用于对需要
 * OAuth 授权的 MCP Server 注入 `Authorization: Bearer` 请求头并支持刷新。
 */
@Serializable
data class McpOAuthState(
    val enabled: Boolean = false,
    val resource: String? = null,
    val issuer: String? = null,
    val clientId: String? = null,
    val clientSecret: String? = null,
    val authorizationEndpoint: String? = null,
    val tokenEndpoint: String? = null,
    val tokenEndpointAuthMethod: String? = null,
    val registrationEndpoint: String? = null,
    val scope: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresAt: Long = 0L, // epoch millis, 0 表示未知/不过期
) {
    val isAuthorized: Boolean get() = !accessToken.isNullOrBlank()

    // 脱敏 toString，避免 client_secret / token 随 config 打印到日志
    override fun toString(): String =
        "McpOAuthState(enabled=$enabled, resource=$resource, issuer=$issuer, " +
            "clientId=$clientId, clientSecret=${clientSecret.masked()}, " +
            "authorizationEndpoint=$authorizationEndpoint, tokenEndpoint=$tokenEndpoint, " +
            "tokenEndpointAuthMethod=$tokenEndpointAuthMethod, " +
            "registrationEndpoint=$registrationEndpoint, scope=$scope, " +
            "accessToken=${accessToken.masked()}, refreshToken=${refreshToken.masked()}, expiresAt=$expiresAt)"

    private fun String?.masked(): String = when {
        this == null -> "null"
        isBlank() -> "***"
        else -> "***(${length})"
    }
}

@Serializable
data class McpTool(
    val enable: Boolean = true,
    val name: String = "",
    val description: String? = null,
    val inputSchema: InputSchema? = null,
    val requireApproval: Boolean = false,
)

@Serializable
sealed class McpServerConfig {
    abstract val id: Uuid
    abstract val commonOptions: McpCommonOptions

    abstract fun clone(
        id: Uuid = this.id,
        commonOptions: McpCommonOptions = this.commonOptions
    ): McpServerConfig

    @Serializable
    @SerialName("sse")
    data class SseTransportServer(
        override val id: Uuid = Uuid.random(),
        override val commonOptions: McpCommonOptions = McpCommonOptions(),
        val url: String = "",
    ) : McpServerConfig() {
        override fun clone(id: Uuid, commonOptions: McpCommonOptions): McpServerConfig {
            return copy(id = id, commonOptions = commonOptions)
        }
    }

    @Serializable
    @SerialName("streamable_http")
    data class StreamableHTTPServer(
        override val id: Uuid = Uuid.random(),
        override val commonOptions: McpCommonOptions,
        val url: String = "",
    ) : McpServerConfig() {
        override fun clone(id: Uuid, commonOptions: McpCommonOptions): McpServerConfig {
            return copy(id = id, commonOptions = commonOptions)
        }
    }

    @Serializable
    @SerialName("stdio")
    data class StdioServer(
        override val id: Uuid = Uuid.random(),
        override val commonOptions: McpCommonOptions = McpCommonOptions(),
        val workspaceId: String = "",
        val command: String = "",
        val args: List<String> = emptyList(),
        val environment: Map<String, String> = emptyMap(),
        val workingDirectory: String = "/workspace",
        val startupTimeoutSeconds: Int = 60,
    ) : McpServerConfig() {
        init {
            require(startupTimeoutSeconds in 1..900) {
                "startupTimeoutSeconds must be between 1 and 900"
            }
        }

        override fun clone(id: Uuid, commonOptions: McpCommonOptions): McpServerConfig {
            return copy(id = id, commonOptions = commonOptions)
        }

        override fun toString(): String =
            "StdioServer(id=$id, enabled=${commonOptions.enable}, name=${commonOptions.name}, " +
                "tools=${commonOptions.tools.size}, workspaceId=$workspaceId, " +
                "command=***, args=***(${args.size}), environment=***(${environment.size}), " +
                "workingDirectory=$workingDirectory, startupTimeoutSeconds=$startupTimeoutSeconds)"
    }
}

/** MCP Server 的连接地址（作为 OAuth 的 canonical resource 标识）。 */
val McpServerConfig.serverUrl: String
    get() = when (this) {
        is McpServerConfig.SseTransportServer -> url
        is McpServerConfig.StreamableHTTPServer -> url
        is McpServerConfig.StdioServer -> ""
    }

val McpServerConfig.isRemote: Boolean
    get() = this !is McpServerConfig.StdioServer
