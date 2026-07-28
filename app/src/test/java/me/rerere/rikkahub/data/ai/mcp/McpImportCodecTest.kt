package me.rerere.rikkahub.data.ai.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class McpImportCodecTest {
    @Test
    fun `parses mixed transports from a claude code mcp json`() {
        val json = """
            {
              "mcpServers": {
                "local-tools": {
                  "command": "npx",
                  "args": ["-y", "@example/server"],
                  "env": { "TOKEN": "secret" },
                  "cwd": "/workspace/project"
                },
                "remote-http": {
                  "type": "http",
                  "url": "https://example.com/mcp",
                  "headers": { "Authorization": "Bearer abc" }
                },
                "remote-sse": {
                  "type": "sse",
                  "url": "https://example.com/sse"
                }
              }
            }
        """.trimIndent()

        val result = McpImportCodec.parse(json, workspaceId = "workspace-a")
        assertEquals(0, result.skippedStdio)
        assertEquals(3, result.configs.size)

        val stdio = result.configs.filterIsInstance<McpServerConfig.StdioServer>().single()
        assertEquals("local-tools", stdio.commonOptions.name)
        assertEquals("workspace-a", stdio.workspaceId)
        assertEquals("npx", stdio.command)
        assertEquals(listOf("-y", "@example/server"), stdio.args)
        assertEquals(mapOf("TOKEN" to "secret"), stdio.environment)
        assertEquals("/workspace/project", stdio.workingDirectory)

        val http = result.configs.filterIsInstance<McpServerConfig.StreamableHTTPServer>().single()
        assertEquals("remote-http", http.commonOptions.name)
        assertEquals("https://example.com/mcp", http.url)
        assertEquals(listOf("Authorization" to "Bearer abc"), http.commonOptions.headers)

        val sse = result.configs.filterIsInstance<McpServerConfig.SseTransportServer>().single()
        assertEquals("remote-sse", sse.commonOptions.name)
        assertEquals("https://example.com/sse", sse.url)
        assertTrue(sse.commonOptions.headers.isEmpty())
    }

    @Test
    fun `entry without type defaults to stdio and cwd falls back to workspace`() {
        val json = """
            {
              "mcpServers": {
                "plain": { "command": "node", "args": ["server.js"] }
              }
            }
        """.trimIndent()

        val stdio = McpImportCodec.parse(json, workspaceId = "workspace-b").configs.single()
            as McpServerConfig.StdioServer
        assertEquals("node", stdio.command)
        assertEquals("/workspace", stdio.workingDirectory)
    }

    @Test
    fun `relative cwd falls back to workspace`() {
        val json = """
            { "mcpServers": { "plain": { "command": "node", "cwd": "relative/dir" } } }
        """.trimIndent()

        val stdio = McpImportCodec.parse(json, workspaceId = "workspace-b").configs.single()
            as McpServerConfig.StdioServer
        assertEquals("/workspace", stdio.workingDirectory)
    }

    @Test
    fun `bare server object without mcpServers wrapper is accepted`() {
        val json = """
            { "my-server": { "type": "http", "url": "https://example.com/mcp" } }
        """.trimIndent()

        val config = McpImportCodec.parse(json, workspaceId = "").configs.single()
        assertTrue(config is McpServerConfig.StreamableHTTPServer)
        assertEquals("my-server", config.commonOptions.name)
    }

    @Test
    fun `invalid entries are skipped while valid ones are kept`() {
        val json = """
            {
              "mcpServers": {
                "no-url": { "type": "http" },
                "unknown-type": { "type": "carrier-pigeon", "url": "https://example.com" },
                "stdio-without-command": { "type": "stdio" },
                "good": { "type": "sse", "url": "https://example.com/sse" }
              }
            }
        """.trimIndent()

        val result = McpImportCodec.parse(json, workspaceId = "workspace-a")
        assertEquals(1, result.configs.size)
        assertEquals("good", result.configs.single().commonOptions.name)
        assertEquals(0, result.skippedStdio)
    }

    @Test
    fun `stdio entries are skipped without a sandbox while remote ones import`() {
        val json = """
            {
              "mcpServers": {
                "local": { "command": "node" },
                "remote": { "type": "http", "url": "https://example.com/mcp" }
              }
            }
        """.trimIndent()

        val result = McpImportCodec.parse(json, workspaceId = "")
        assertEquals(1, result.configs.size)
        assertTrue(result.configs.single() is McpServerConfig.StreamableHTTPServer)
        assertEquals(1, result.skippedStdio)
    }

    @Test
    fun `only skipped stdio entries fails with sandbox required`() {
        val json = """
            { "mcpServers": { "local": { "command": "node" } } }
        """.trimIndent()

        val error = assertThrows(McpImportException::class.java) {
            McpImportCodec.parse(json, workspaceId = "")
        }
        assertEquals(McpImportError.SandboxRequired, error.kind)
    }

    @Test
    fun `invalid json or empty server list fails`() {
        val notJson = assertThrows(McpImportException::class.java) {
            McpImportCodec.parse("not json", workspaceId = "")
        }
        assertEquals(McpImportError.NotAnObject, notJson.kind)

        val empty = assertThrows(McpImportException::class.java) {
            McpImportCodec.parse("""{ "mcpServers": {} }""", workspaceId = "")
        }
        assertEquals(McpImportError.NoServers, empty.kind)

        val array = assertThrows(McpImportException::class.java) {
            McpImportCodec.parse("""[]""", workspaceId = "")
        }
        assertEquals(McpImportError.NotAnObject, array.kind)
    }

    @Test
    fun `explicit stdio type with command parses as stdio`() {
        val json = """
            { "mcpServers": { "typed": { "type": "stdio", "command": "node" } } }
        """.trimIndent()

        val stdio = McpImportCodec.parse(json, workspaceId = "workspace-a").configs.single()
        assertTrue(stdio is McpServerConfig.StdioServer)
        assertEquals("node", (stdio as McpServerConfig.StdioServer).command)
    }

    @Test
    fun `non-string env and args values are coerced or dropped`() {
        val json = """
            {
              "mcpServers": {
                "local": {
                  "command": "node",
                  "args": ["server.js", 42, ["nested"]],
                  "env": { "PORT": 8080, "NESTED": { "a": 1 } }
                }
              }
            }
        """.trimIndent()

        val stdio = McpImportCodec.parse(json, workspaceId = "workspace-a").configs.single()
            as McpServerConfig.StdioServer
        assertEquals(listOf("server.js", "42"), stdio.args)
        assertEquals(mapOf("PORT" to "8080"), stdio.environment)
    }

    @Test
    fun `mcpServers present but not an object fails instead of misparsing siblings`() {
        val json = """
            { "mcpServers": "foo", "meta": { "type": "http", "url": "https://example.com" } }
        """.trimIndent()

        val error = assertThrows(McpImportException::class.java) {
            McpImportCodec.parse(json, workspaceId = "")
        }
        assertEquals(McpImportError.NoServers, error.kind)
    }
}
