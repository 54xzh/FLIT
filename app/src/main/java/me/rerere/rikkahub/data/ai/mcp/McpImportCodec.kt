package me.rerere.rikkahub.data.ai.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull

/** Failure categories surfaced to the UI so it can show a localized message. */
enum class McpImportError { NotAnObject, NoServers, SandboxRequired }

class McpImportException(val kind: McpImportError) : Exception(kind.name)

/**
 * @param configs successfully parsed servers
 * @param skippedStdio how many STDIO entries were skipped because no sandbox was selected
 */
data class McpImportResult(
    val configs: List<McpServerConfig>,
    val skippedStdio: Int,
)

/**
 * Parses Claude Code compatible MCP configuration JSON into [McpServerConfig] entries.
 *
 * Accepted shape (`.mcp.json` / `claude mcp add --json`):
 * ```json
 * {
 *   "mcpServers": {
 *     "my-stdio": { "command": "npx", "args": ["-y", "pkg"], "env": { "K": "V" }, "cwd": "/workspace" },
 *     "my-http":  { "type": "http", "url": "https://example.com/mcp", "headers": { "Authorization": "Bearer x" } },
 *     "my-sse":   { "type": "sse", "url": "https://example.com/sse" }
 *   }
 * }
 * ```
 * The outer `mcpServers` wrapper is optional; a bare `{ name: {...} }` object is accepted too.
 * Entries without `type` are treated as STDIO when they carry a `command`.
 * [workspaceId] is applied to STDIO entries only; remote transports ignore it. STDIO entries
 * are skipped (and counted in [McpImportResult.skippedStdio]) when [workspaceId] is blank,
 * so remote servers can still be imported without a sandbox selection.
 */
object McpImportCodec {
    fun parse(json: String, workspaceId: String): McpImportResult {
        val root = runCatching { JsonInstant.parseToJsonElement(json) }.getOrNull() as? JsonObject
            ?: throw McpImportException(McpImportError.NotAnObject)
        val servers = when {
            root.containsKey("mcpServers") ->
                root["mcpServers"] as? JsonObject ?: throw McpImportException(McpImportError.NoServers)
            else -> root.filterValues { it is JsonObject }
                .takeIf { it.isNotEmpty() }
                ?.let { JsonObject(it) }
                ?: throw McpImportException(McpImportError.NoServers)
        }

        val configs = mutableListOf<McpServerConfig>()
        var skippedStdio = 0
        for ((name, value) in servers) {
            val item = value as? JsonObject ?: continue
            when (val entry = parseEntry(name, item, workspaceId)) {
                is EntryResult.Parsed -> configs.add(entry.config)
                EntryResult.SkippedStdio -> skippedStdio++
                EntryResult.Invalid -> Unit
            }
        }
        if (configs.isEmpty()) {
            throw McpImportException(
                if (skippedStdio > 0) McpImportError.SandboxRequired else McpImportError.NoServers
            )
        }
        return McpImportResult(configs, skippedStdio)
    }

    private sealed interface EntryResult {
        data class Parsed(val config: McpServerConfig) : EntryResult
        data object SkippedStdio : EntryResult
        data object Invalid : EntryResult
    }

    private fun parseEntry(name: String, item: JsonObject, workspaceId: String): EntryResult {
        val type = item["type"]?.jsonPrimitiveOrNull?.contentOrNull?.lowercase()
        val command = item["command"]?.jsonPrimitiveOrNull?.contentOrNull?.takeIf { it.isNotBlank() }
        val url = item["url"]?.jsonPrimitiveOrNull?.contentOrNull?.takeIf { it.isNotBlank() }
        val headers = (item["headers"] as? JsonObject).orEmpty().mapNotNull { (key, headerValue) ->
            headerValue.jsonPrimitiveOrNull?.contentOrNull?.let { key to it }
        }
        return when {
            command != null || type == "stdio" -> {
                if (command == null) return EntryResult.Invalid
                if (workspaceId.isBlank()) return EntryResult.SkippedStdio
                EntryResult.Parsed(
                    McpServerConfig.StdioServer(
                        commonOptions = McpCommonOptions(name = name),
                        workspaceId = workspaceId,
                        command = command,
                        args = (item["args"] as? JsonArray).orEmpty().mapNotNull {
                            it.jsonPrimitiveOrNull?.contentOrNull
                        },
                        environment = (item["env"] as? JsonObject).orEmpty().mapNotNull { (key, envValue) ->
                            envValue.jsonPrimitiveOrNull?.contentOrNull?.let { key to it }
                        }.toMap(),
                        workingDirectory = item["cwd"]?.jsonPrimitiveOrNull?.contentOrNull
                            ?.takeIf { it.startsWith("/") } ?: "/workspace",
                    )
                )
            }
            type == "http" && url != null -> EntryResult.Parsed(
                McpServerConfig.StreamableHTTPServer(
                    commonOptions = McpCommonOptions(name = name, headers = headers),
                    url = url,
                )
            )
            type == "sse" && url != null -> EntryResult.Parsed(
                McpServerConfig.SseTransportServer(
                    commonOptions = McpCommonOptions(name = name, headers = headers),
                    url = url,
                )
            )
            else -> EntryResult.Invalid
        }
    }
}
