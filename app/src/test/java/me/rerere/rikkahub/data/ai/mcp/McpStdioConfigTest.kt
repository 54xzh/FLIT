package me.rerere.rikkahub.data.ai.mcp

import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpStdioConfigTest {
    @Test
    fun `remote and stdio configs round trip together`() {
        val configs: List<McpServerConfig> = listOf(
            McpServerConfig.SseTransportServer(
                commonOptions = McpCommonOptions(name = "remote"),
                url = "https://example.com/sse",
            ),
            McpServerConfig.StdioServer(
                commonOptions = McpCommonOptions(name = "local"),
                workspaceId = "workspace-a",
                command = "node",
                args = listOf("server.js", "value with spaces"),
                environment = mapOf("TOKEN" to "secret"),
                workingDirectory = "/workspace/project",
                startupTimeoutSeconds = 90,
            ),
        )

        val encoded = JsonInstant.encodeToString(configs)
        val decoded = JsonInstant.decodeFromString<List<McpServerConfig>>(encoded)

        assertEquals(configs, decoded)
    }

    @Test
    fun `existing remote config remains decodable without stdio fields`() {
        val encodedBeforeStdio = """
            [
              {
                "type": "streamable_http",
                "id": "00000000-0000-0000-0000-000000000001",
                "commonOptions": { "name": "old" },
                "url": "https://example.com/mcp"
              }
            ]
        """.trimIndent()

        val decoded = JsonInstant.decodeFromString<List<McpServerConfig>>(encodedBeforeStdio).single()
        assertTrue(decoded is McpServerConfig.StreamableHTTPServer)
        assertEquals("old", decoded.commonOptions.name)
        assertEquals("https://example.com/mcp", decoded.serverUrl)
    }

    @Test
    fun `claude desktop import preserves argv and env and export omits workspace`() {
        val imported = McpClaudeDesktopCodec.importStdioServers(
            json = """
                {
                  "mcpServers": {
                    "demo": {
                      "command": "node",
                      "args": ["server.js", "value with spaces", ""],
                      "env": {"API_TOKEN": "secret value"}
                    }
                  }
                }
            """.trimIndent(),
            workspaceId = "workspace-a",
        ).single()

        assertEquals(listOf("server.js", "value with spaces", ""), imported.args)
        assertEquals(mapOf("API_TOKEN" to "secret value"), imported.environment)
        assertEquals("workspace-a", imported.workspaceId)

        val exported = McpClaudeDesktopCodec.exportStdioServers(listOf(imported))
        assertFalse(exported.contains("workspaceId"))
        assertFalse(exported.contains("workspace-a"))
        assertTrue(exported.contains("value with spaces"))
    }
}
