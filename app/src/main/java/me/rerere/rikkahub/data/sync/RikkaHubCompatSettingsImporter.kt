package me.rerere.rikkahub.data.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import java.net.URI

/**
 * Converts the overlapping part of a RikkaHub settings export to FLIT's shape.
 *
 * This runs before decoding [me.rerere.rikkahub.data.datastore.Settings]. Sealed
 * class discriminators must be normalized at this level because kotlinx.serialization
 * cannot ignore an unknown discriminator inside a sealed class.
 */
internal object RikkaHubCompatSettingsImporter {
    internal data class ConversionResult(
        val json: String,
        val skippedItems: Int,
        val skippedTypes: Set<String>,
    )

    private class SkipCounter {
        var count: Int = 0
        val types = linkedSetOf<String>()

        fun add(type: String, amount: Int = 1) {
            count += amount
            types += type
        }
    }

    private val supportedModelTools = setOf(
        "search",
        "url_context",
    )

    private val supportedSearchTypes = setOf(
        "bing_local",
        "zhipu",
        "doubao",
        "doubao_search",
        "tavily",
        "exa",
        "searxng",
        "linkup",
        "brave",
        "metaso",
        "ollama",
        "perplexity",
        "firecrawl",
        "jina",
        "bocha",
        "nanogpt",
        "grok",
        "serper",
    )

    private val supportedTtsTypes = setOf(
        "openai",
        "mimo",
        "gemini",
        "system",
        "minimax",
        "elevenlabs",
    )

    private val supportedLocalTools = setOf(
        "javascript_engine",
        "ask_user",
        "get_current_time",
    )

    /**
     * Converts a RikkaHub settings JSON string. Invalid JSON is deliberately
     * allowed to throw so the caller can keep the existing restore rollback.
     */
    internal fun convert(raw: String): ConversionResult {
        val root = JsonInstant.parseToJsonElement(raw) as? JsonObject
            ?: error("RikkaHub settings root is not an object")
        val skipped = SkipCounter()
        val converted = root.toMutableMap()

        converted["providers"] = convertProviders(root["providers"], skipped)

        val originalSearchServices = root["searchServices"]?.let {
            it as? JsonArray ?: error("RikkaHub settings 'searchServices' must be an array")
        } ?: JsonArray(emptyList())
        val originalSelectedSearchIndex = root["searchServiceSelected"]
            ?.jsonPrimitiveOrNull
            ?.intOrNull
            ?: 0
        val selectedSearchId = originalSearchServices
            .getOrNull(originalSelectedSearchIndex)
            ?.asObject()
            ?.string("id")
        val convertedSearchServices = convertSearchServices(originalSearchServices, skipped)
        converted["searchServices"] = convertedSearchServices
        converted["searchServiceSelected"] = JsonPrimitive(
            selectedIndex(
                candidates = convertedSearchServices,
                selectedId = selectedSearchId,
                fallback = 0,
            )
        )

        val selectedSearchIndex = converted["searchServiceSelected"]
            ?.jsonPrimitiveOrNull
            ?.intOrNull
            ?: 0
        converted["assistants"] = convertAssistants(
            element = root["assistants"],
            searchIndex = selectedSearchIndex,
            hasSearchService = convertedSearchServices.isNotEmpty(),
            originalSearchServices = originalSearchServices,
            convertedSearchServices = convertedSearchServices,
            skipped = skipped,
        )

        if ("mcpServers" in root) {
            converted["mcpServers"] = convertMcpServers(root["mcpServers"], skipped)
        }

        val originalTtsProviders = root["ttsProviders"]?.let {
            it as? JsonArray ?: error("RikkaHub settings 'ttsProviders' must be an array")
        } ?: JsonArray(emptyList())
        val originalSelectedTtsId = root["selectedTTSProviderId"]
            ?.jsonPrimitiveOrNull
            ?.contentOrNull
        val convertedTtsProviders = convertTtsProviders(originalTtsProviders, skipped)
        converted["ttsProviders"] = convertedTtsProviders
        selectedTtsId(
            converted = converted,
            candidates = convertedTtsProviders,
            originalSelectedId = originalSelectedTtsId,
        )

        convertObjectStorage(root["s3Config"], converted, skipped)
        skipUnsupportedTopLevelCollections(root, converted, skipped)

        return ConversionResult(
            json = JsonInstant.encodeToString(JsonObject(converted)),
            skippedItems = skipped.count,
            skippedTypes = skipped.types,
        )
    }

    private fun convertProviders(element: JsonElement?, skipped: SkipCounter): JsonElement {
        val providers = element?.let {
            it as? JsonArray ?: error("RikkaHub settings 'providers' must be an array")
        } ?: JsonArray(emptyList())
        val convertedProviders = providers.mapNotNull { providerElement ->
            val provider = providerElement.asObject() ?: run {
                skipped.add("provider_invalid")
                return@mapNotNull null
            }
            JsonObject(provider.toMutableMap().apply {
                this["models"] = convertModels(provider["models"], skipped)
            })
        }
        check(providers.isEmpty() || convertedProviders.isNotEmpty()) {
            "RikkaHub settings contain no valid providers"
        }
        return JsonArray(convertedProviders)
    }

    private fun convertModels(element: JsonElement?, skipped: SkipCounter): JsonElement {
        val models = element?.let {
            it as? JsonArray ?: error("RikkaHub settings 'models' must be an array")
        } ?: JsonArray(emptyList())
        val convertedModels = models.mapNotNull { modelElement ->
            val model = modelElement.asObject() ?: run {
                skipped.add("model_invalid")
                return@mapNotNull null
            }
            JsonObject(model.toMutableMap().apply {
                val tools = this["tools"]?.let {
                    it as? JsonArray ?: error("RikkaHub model 'tools' must be an array")
                }
                if (tools != null) {
                    this["tools"] = JsonArray(tools.mapNotNull { toolElement ->
                        val tool = toolElement.asObject()
                        val type = tool?.string("type")
                        if (type in supportedModelTools) {
                            toolElement
                        } else {
                            skipped.add(type ?: "model_tool_invalid")
                            null
                        }
                    })
                }
                val convertedOverwrite = convertProviderOverwrite(
                    element = this["providerOverwrite"],
                    skipped = skipped,
                )
                if (convertedOverwrite == null) {
                    remove("providerOverwrite")
                } else {
                    this["providerOverwrite"] = convertedOverwrite
                }
            })
        }
        check(models.isEmpty() || convertedModels.isNotEmpty()) {
            "RikkaHub provider contains no valid models"
        }
        return JsonArray(convertedModels)
    }

    private fun convertProviderOverwrite(
        element: JsonElement?,
        skipped: SkipCounter,
    ): JsonElement? {
        if (element == null || element is JsonNull) return null
        val provider = element.asObject() ?: run {
            skipped.add("provider_overwrite_invalid")
            return null
        }
        return JsonObject(provider.toMutableMap().apply {
            this["models"] = convertModels(provider["models"], skipped)
        })
    }

    private fun convertAssistants(
        element: JsonElement?,
        searchIndex: Int,
        hasSearchService: Boolean,
        originalSearchServices: JsonArray,
        convertedSearchServices: JsonArray,
        skipped: SkipCounter,
    ): JsonElement {
        val assistants = element?.let {
            it as? JsonArray ?: error("RikkaHub settings 'assistants' must be an array")
        } ?: JsonArray(emptyList())
        val convertedAssistants = assistants.mapNotNull { assistantElement ->
            val assistant = assistantElement.asObject() ?: run {
                skipped.add("assistant_invalid")
                return@mapNotNull null
            }
            JsonObject(assistant.toMutableMap().apply {
                this["localTools"] = convertLocalTools(assistant["localTools"], skipped)
                val presetMessages = this["presetMessages"] as? JsonArray
                if (presetMessages != null && presetMessages.isNotEmpty()) {
                    skipped.add("preset_messages", presetMessages.size)
                    remove("presetMessages")
                }

                val existingSearchMode = assistant["searchMode"]
                if (existingSearchMode != null) {
                    this["searchMode"] = remapSearchMode(
                        element = existingSearchMode,
                        originalSearchServices = originalSearchServices,
                        convertedSearchServices = convertedSearchServices,
                        skipped = skipped,
                    )
                }

                val enableWebSearch = assistant["enableWebSearch"]
                    ?.jsonPrimitiveOrNull
                    ?.contentOrNull
                    ?.toBooleanStrictOrNull()
                if (enableWebSearch == true && "searchMode" !in this) {
                    this["searchMode"] = if (hasSearchService) {
                        buildJsonObject {
                            put("type", JsonPrimitive("provider"))
                            put("index", JsonPrimitive(searchIndex))
                        }
                    } else {
                        buildJsonObject { put("type", JsonPrimitive("off")) }
                    }
                }
            })
        }
        check(assistants.isEmpty() || convertedAssistants.isNotEmpty()) {
            "RikkaHub settings contain no valid assistants"
        }
        return JsonArray(convertedAssistants)
    }

    private fun remapSearchMode(
        element: JsonElement,
        originalSearchServices: JsonArray,
        convertedSearchServices: JsonArray,
        skipped: SkipCounter,
    ): JsonElement {
        val mode = element.asObject() ?: run {
            skipped.add("search_mode_invalid")
            return offSearchMode()
        }
        return when (mode.string("type")) {
            "provider" -> {
                val oldIndex = mode["index"]?.jsonPrimitiveOrNull?.intOrNull
                val newIndex = oldIndex?.let {
                    remapSearchIndex(it, originalSearchServices, convertedSearchServices)
                }
                if (newIndex == null) {
                    skipped.add("search_mode")
                    offSearchMode()
                } else {
                    JsonObject(mode.toMutableMap().apply {
                        this["index"] = JsonPrimitive(newIndex)
                    })
                }
            }

            "multi_provider" -> {
                val indices = mode["indices"] as? JsonArray
                val remapped = indices?.mapNotNull { indexElement ->
                    val oldIndex = indexElement.jsonPrimitiveOrNull?.intOrNull
                    oldIndex?.let {
                        remapSearchIndex(it, originalSearchServices, convertedSearchServices)
                    }
                }?.distinct()?.sorted().orEmpty()
                if (indices == null || remapped.size != indices.size) {
                    skipped.add("search_mode")
                }
                when (remapped.size) {
                    0 -> offSearchMode()
                    1 -> buildJsonObject {
                        put("type", JsonPrimitive("provider"))
                        put("index", JsonPrimitive(remapped.first()))
                    }
                    else -> buildJsonObject {
                        put("type", JsonPrimitive("multi_provider"))
                        put("indices", JsonArray(remapped.map(::JsonPrimitive)))
                    }
                }
            }

            "off", "builtin" -> element
            else -> {
                skipped.add("search_mode_invalid")
                offSearchMode()
            }
        }
    }

    private fun remapSearchIndex(
        oldIndex: Int,
        originalSearchServices: JsonArray,
        convertedSearchServices: JsonArray,
    ): Int? {
        val selectedId = originalSearchServices
            .getOrNull(oldIndex)
            ?.asObject()
            ?.string("id")
            ?: return null
        return convertedSearchServices.indexOfFirst {
            it.asObject()?.string("id") == selectedId
        }.takeIf { it >= 0 }
    }

    private fun offSearchMode(): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("off"))
    }

    private fun convertMcpServers(element: JsonElement?, skipped: SkipCounter): JsonElement {
        val servers = element?.let {
            it as? JsonArray ?: error("RikkaHub settings 'mcpServers' must be an array")
        } ?: JsonArray(emptyList())
        return JsonArray(servers.mapNotNull { serverElement ->
            val server = serverElement.asObject() ?: run {
                skipped.add("mcp_server_invalid")
                return@mapNotNull null
            }
            JsonObject(server.toMutableMap().apply {
                this["commonOptions"] = convertMcpCommonOptions(
                    element = this["commonOptions"],
                    skipped = skipped,
                )
            })
        })
    }

    private fun convertMcpCommonOptions(
        element: JsonElement?,
        skipped: SkipCounter,
    ): JsonElement {
        val common = element?.asObject() ?: error("RikkaHub MCP commonOptions must be an object")
        val tools = common["tools"]?.let {
            it as? JsonArray ?: error("RikkaHub MCP tools must be an array")
        } ?: JsonArray(emptyList())
        return JsonObject(common.toMutableMap().apply {
            this["tools"] = JsonArray(tools.mapNotNull { toolElement ->
                val tool = toolElement.asObject() ?: run {
                    skipped.add("mcp_tool_invalid")
                    return@mapNotNull null
                }
                JsonObject(tool.toMutableMap().apply {
                    tool["needsApproval"]?.let { needsApproval ->
                        if (needsApproval !is JsonNull) {
                            this["requireApproval"] = needsApproval
                        }
                        remove("needsApproval")
                    }
                })
            })
        })
    }

    private fun convertLocalTools(element: JsonElement?, skipped: SkipCounter): JsonElement {
        val tools = element?.let {
            it as? JsonArray ?: error("RikkaHub assistant 'localTools' must be an array")
        } ?: JsonArray(emptyList())
        return JsonArray(tools.mapNotNull { toolElement ->
            val tool = toolElement.asObject()
            val originalType = tool?.string("type")
            when (originalType) {
                null -> {
                    skipped.add("local_tool_invalid")
                    null
                }
                "time_info" -> JsonObject(
                    tool.toMutableMap().apply { this["type"] = JsonPrimitive("get_current_time") }
                )
                in supportedLocalTools -> toolElement
                else -> {
                    skipped.add(originalType)
                    null
                }
            }
        })
    }

    private fun convertSearchServices(
        services: JsonArray,
        skipped: SkipCounter,
    ): JsonArray {
        return JsonArray(services.mapNotNull { serviceElement ->
            val service = serviceElement.asObject()
            val originalType = service?.string("type")
            if (service == null || originalType == null) {
                skipped.add("search_service_invalid")
                return@mapNotNull null
            }
            if (originalType !in supportedSearchTypes) {
                skipped.add(originalType)
                return@mapNotNull null
            }

            JsonObject(service.toMutableMap().apply {
                if (originalType == "doubao") {
                    this["type"] = JsonPrimitive("doubao_search")
                    service.string("mode")?.takeUnless { it.equals("global", ignoreCase = true) }?.let {
                        skipped.add("doubao_mode")
                    }
                    remove("mode")
                }
                if (originalType == "grok") {
                    convertGrokFields(service, this)
                }
            })
        })
    }

    private fun convertGrokFields(source: JsonObject, target: MutableMap<String, JsonElement>) {
        val customUrl = source.string("customUrl") ?: return
        val uri = runCatching { URI(customUrl) }.getOrNull() ?: return
        val scheme = uri.scheme ?: return
        val authority = uri.rawAuthority ?: return
        val rawPath = uri.rawPath.orEmpty().trimEnd('/')
        val (path, apiType) = when {
            rawPath.endsWith("/chat/completions", ignoreCase = true) ->
                "/chat/completions" to "chat_completions"

            rawPath.endsWith("/responses", ignoreCase = true) ->
                "/responses" to "responses"

            else -> return
        }
        val basePath = rawPath.dropLast(path.length).trimEnd('/')
        val baseUrl = "$scheme://$authority$basePath"
        target["customBaseUrl"] = JsonPrimitive(baseUrl)
        target["apiType"] = JsonPrimitive(apiType)
        target["customPath"] = JsonPrimitive(path)
        target["enableCustom"] = JsonPrimitive(
            baseUrl != "https://api.x.ai" && baseUrl != "https://api.x.ai/v1"
        )
        source.string("systemPrompt")?.let { target["customSystemPrompt"] = JsonPrimitive(it) }
    }

    private fun convertTtsProviders(
        providers: JsonArray,
        skipped: SkipCounter,
    ): JsonArray {
        return JsonArray(providers.mapNotNull { providerElement ->
            val provider = providerElement.asObject()
            val type = provider?.string("type")
            if (provider == null || type == null) {
                skipped.add("tts_provider_invalid")
                return@mapNotNull null
            }
            if (type !in supportedTtsTypes) {
                skipped.add(type)
                return@mapNotNull null
            }

            JsonObject(provider.toMutableMap().apply {
                if (type == "elevenlabs") {
                    provider["model"]?.let { this["modelId"] = it }
                }
            })
        })
    }

    private fun convertObjectStorage(
        element: JsonElement?,
        converted: MutableMap<String, JsonElement>,
        skipped: SkipCounter,
    ) {
        val source = element?.asObject() ?: run {
            if (element != null && element !is JsonNull) {
                error("RikkaHub settings 's3Config' must be an object")
            }
            return
        }
        val pathStyleElement = source["pathStyle"]
        val pathStylePrimitive = pathStyleElement?.jsonPrimitiveOrNull
        val pathStyle = when {
            pathStyleElement == null -> true
            pathStylePrimitive != null ->
                pathStylePrimitive.contentOrNull?.toBooleanStrictOrNull()
                    ?: error("RikkaHub settings 's3Config.pathStyle' must be a boolean")

            else -> error("RikkaHub settings 's3Config.pathStyle' must be a boolean")
        }
        if (!pathStyle) {
            skipped.add("s3_path_style")
            converted.remove("objectStorageConfig")
            return
        }

        converted["objectStorageConfig"] = buildJsonObject {
            listOf("endpoint", "accessKeyId", "secretAccessKey", "bucket", "region", "items")
                .forEach { key -> source[key]?.let { put(key, it) } }
        }
    }

    private fun skipUnsupportedTopLevelCollections(
        root: JsonObject,
        converted: MutableMap<String, JsonElement>,
        skipped: SkipCounter,
    ) {
        listOf(
            "modeInjections" to "mode_injections",
            "quickMessages" to "quick_messages",
            "asrProviders" to "asr_providers",
        ).forEach { (key, type) ->
            val items = root[key] as? JsonArray ?: return@forEach
            if (items.isNotEmpty()) skipped.add(type, items.size)
            converted.remove(key)
        }

        val lorebooks = root["lorebooks"] as? JsonArray
        if (lorebooks != null && lorebooks.isNotEmpty()) {
            skipped.add("lorebooks", lorebooks.size)
            converted.remove("lorebooks")
        }
    }

    private fun selectedTtsId(
        converted: MutableMap<String, JsonElement>,
        candidates: JsonArray,
        originalSelectedId: String?,
    ) {
        val selected = candidates.firstOrNull { it.asObject()?.string("id") == originalSelectedId }
            ?: candidates.firstOrNull()
        val id = selected?.asObject()?.string("id")
        if (id == null) {
            converted.remove("selectedTTSProviderId")
        } else {
            converted["selectedTTSProviderId"] = JsonPrimitive(id)
        }
    }

    private fun selectedIndex(
        candidates: JsonArray,
        selectedId: String?,
        fallback: Int,
    ): Int {
        if (selectedId == null) return fallback.coerceIn(0, (candidates.size - 1).coerceAtLeast(0))
        return candidates.indexOfFirst { it.asObject()?.string("id") == selectedId }
            .takeIf { it >= 0 }
            ?: fallback.coerceIn(0, (candidates.size - 1).coerceAtLeast(0))
    }

    private fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

    private fun JsonObject.string(key: String): String? = this[key]
        ?.jsonPrimitiveOrNull
        ?.contentOrNull
}
