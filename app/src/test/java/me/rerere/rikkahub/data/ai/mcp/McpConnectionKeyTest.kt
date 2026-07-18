package me.rerere.rikkahub.data.ai.mcp

import kotlin.io.encoding.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpConnectionKeyTest {
    private val base = McpServerConfig.StreamableHTTPServer(
        commonOptions = McpCommonOptions(name = "demo"),
        url = "https://example.com/mcp",
    )

    @Test
    fun `tool metadata does not affect connection key`() {
        val withTools = base.copy(
            commonOptions = base.commonOptions.copy(
                tools = listOf(McpTool(name = "search", enable = false))
            )
        )

        assertEquals(base.connectionKey(), withTools.connectionKey())
    }

    @Test
    fun `url transport and headers affect connection key`() {
        assertNotEquals(base.connectionKey(), base.copy(url = "https://example.com/other").connectionKey())
        assertNotEquals(
            base.connectionKey(),
            McpServerConfig.SseTransportServer(
                id = base.id,
                commonOptions = base.commonOptions,
                url = base.url,
            ).connectionKey()
        )
        assertNotEquals(
            base.connectionKey(),
            base.copy(
                commonOptions = base.commonOptions.copy(headers = listOf("X-API-Key" to "secret"))
            ).connectionKey()
        )
    }

    @Test
    fun `oauth token is used only for its bound resource`() {
        val oauth = McpOAuthState(
            enabled = true,
            resource = McpOAuthClient.canonicalResource(base.url),
            accessToken = "oauth-token",
        )
        val withOAuth = base.copy(commonOptions = base.commonOptions.copy(oauth = oauth))

        assertNotEquals(base.connectionKey(), withOAuth.connectionKey())
        assertTrue(withOAuth.resolvedHeaders().contains("Authorization" to "Bearer oauth-token"))

        val changedUrl = withOAuth.copy(url = "https://other.example.com/mcp")
        assertFalse(changedUrl.resolvedHeaders().any { it.first.equals("Authorization", ignoreCase = true) })

        val legacyUnbound = base.copy(
            commonOptions = base.commonOptions.copy(
                oauth = McpOAuthState(enabled = true, accessToken = "legacy-token")
            )
        )
        assertFalse(legacyUnbound.resolvedHeaders().any { it.first.equals("Authorization", ignoreCase = true) })
    }

    @Test
    fun `manual authorization header wins over bound oauth token`() {
        val oauth = McpOAuthState(
            enabled = true,
            resource = McpOAuthClient.canonicalResource(base.url),
            accessToken = "oauth-token",
        )
        val manualAuth = base.copy(
            commonOptions = base.commonOptions.copy(
                headers = listOf("Authorization" to "Bearer manual"),
                oauth = oauth,
            )
        )

        assertEquals(listOf("Authorization" to "Bearer manual"), manualAuth.resolvedHeaders())
    }

    @Test
    fun `token endpoint auth method follows registration and server metadata`() {
        assertEquals(
            McpOAuthClient.TOKEN_ENDPOINT_AUTH_NONE,
            McpOAuthClient.selectTokenEndpointAuthMethod(
                clientSecret = null,
                registeredMethod = null,
                supportedMethods = null,
            )
        )
        assertEquals(
            McpOAuthClient.TOKEN_ENDPOINT_AUTH_BASIC,
            McpOAuthClient.selectTokenEndpointAuthMethod(
                clientSecret = "secret",
                registeredMethod = null,
                supportedMethods = null,
            )
        )
        assertEquals(
            McpOAuthClient.TOKEN_ENDPOINT_AUTH_POST,
            McpOAuthClient.selectTokenEndpointAuthMethod(
                clientSecret = "secret",
                registeredMethod = null,
                supportedMethods = listOf(McpOAuthClient.TOKEN_ENDPOINT_AUTH_POST),
            )
        )
        assertEquals(
            McpOAuthClient.TOKEN_ENDPOINT_AUTH_NONE,
            McpOAuthClient.selectTokenEndpointAuthMethod(
                clientSecret = "server-returned-secret",
                registeredMethod = McpOAuthClient.TOKEN_ENDPOINT_AUTH_NONE,
                supportedMethods = listOf(McpOAuthClient.TOKEN_ENDPOINT_AUTH_NONE),
            )
        )
        assertEquals(
            McpOAuthClient.TOKEN_ENDPOINT_AUTH_NONE,
            McpOAuthClient.selectDynamicRegistrationAuthMethod(null)
        )
        assertEquals(
            McpOAuthClient.TOKEN_ENDPOINT_AUTH_BASIC,
            McpOAuthClient.selectDynamicRegistrationAuthMethod(
                listOf(McpOAuthClient.TOKEN_ENDPOINT_AUTH_BASIC)
            )
        )
        assertEquals(
            McpOAuthClient.TOKEN_ENDPOINT_AUTH_POST,
            McpOAuthClient.selectDynamicRegistrationAuthMethod(
                listOf(McpOAuthClient.TOKEN_ENDPOINT_AUTH_POST)
            )
        )
    }

    @Test
    fun `basic client credentials are form encoded before base64`() {
        val header = McpOAuthClient.buildClientSecretBasicAuthorization(
            clientId = "client:id",
            clientSecret = "p+ass word",
        )

        val decoded = Base64.Default.decode(header.removePrefix("Basic ")).toString(Charsets.UTF_8)
        assertEquals("client%3Aid:p%2Bass+word", decoded)
    }
}
