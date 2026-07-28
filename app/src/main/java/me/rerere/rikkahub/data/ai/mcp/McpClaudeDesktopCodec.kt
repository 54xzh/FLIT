package me.rerere.rikkahub.data.ai.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkahub.utils.JsonInstantPretty

object McpClaudeDesktopCodec {
    fun importStdioServers(json: String, workspaceId: String): List<McpServerConfig.StdioServer> {
        require(workspaceId.isNotBlank()) { "A sandbox workspace must be selected before importing" }
        val root = JsonInstantPretty.parseToJsonElement(json) as? JsonObject
            ?: error("Claude Desktop configuration must be a JSON object")
        val servers = root["mcpServers"] as? JsonObject
            ?: error("Claude Desktop configuration does not contain mcpServers")
        return servers.mapNotNull { (name, value) ->
            val item = value as? JsonObject ?: return@mapNotNull null
            val command = (item["command"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val args = (item["args"] as? JsonArray).orEmpty().mapNotNull {
                (it as? JsonPrimitive)?.contentOrNull
            }
            val environment = (item["env"] as? JsonObject).orEmpty().mapNotNull { (key, envValue) ->
                (envValue as? JsonPrimitive)?.contentOrNull?.let { key to it }
            }.toMap()
            McpServerConfig.StdioServer(
                commonOptions = McpCommonOptions(name = name),
                workspaceId = workspaceId,
                command = command,
                args = args,
                environment = environment,
            )
        }.also { require(it.isNotEmpty()) { "No STDIO MCP servers were found" } }
    }

    fun exportStdioServers(configs: List<McpServerConfig>): String {
        val servers = linkedMapOf<String, JsonObject>()
        configs.filterIsInstance<McpServerConfig.StdioServer>().forEach { config ->
            val baseName = config.commonOptions.name.ifBlank { "stdio" }
            var name = baseName
            var suffix = 2
            while (name in servers) {
                name = "$baseName $suffix"
                suffix++
            }
            servers[name] = JsonObject(
                buildMap {
                    put("command", JsonPrimitive(config.command))
                    if (config.args.isNotEmpty()) {
                        put("args", JsonArray(config.args.map(::JsonPrimitive)))
                    }
                    if (config.environment.isNotEmpty()) {
                        put("env", JsonObject(config.environment.mapValues { JsonPrimitive(it.value) }))
                    }
                }
            )
        }
        return JsonInstantPretty.encodeToString(
            JsonObject.serializer(),
            JsonObject(mapOf("mcpServers" to JsonObject(servers))),
        )
    }
}
