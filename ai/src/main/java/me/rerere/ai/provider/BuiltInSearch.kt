package me.rerere.ai.provider

import me.rerere.ai.registry.ModelRegistry
import java.net.URI
import java.util.Locale

private val OFFICIAL_CLAUDE_API_HOSTS = setOf("api.anthropic.com")
private val OFFICIAL_GROK_API_HOSTS = setOf("api.x.ai")

private fun ProviderSetting?.supportsClaudeBuiltInSearchByHost(): Boolean {
    if (this !is ProviderSetting.Claude) return false
    val host = baseUrl.extractHostFromBaseUrl() ?: return false
    return host in OFFICIAL_CLAUDE_API_HOSTS || host.endsWith(".anthropic.com")
}

private fun ProviderSetting?.supportsGrokBuiltInSearchByHost(): Boolean {
    if (this !is ProviderSetting.OpenAI) return false
    val host = baseUrl.extractHostFromBaseUrl() ?: return false
    return host in OFFICIAL_GROK_API_HOSTS || host.endsWith(".x.ai")
}

private fun String.extractHostFromBaseUrl(): String? {
    val normalized = trim()
    if (normalized.isBlank()) return null
    return runCatching { URI(normalized).host?.lowercase(Locale.US) }.getOrNull()
        ?: runCatching { URI("https://$normalized").host?.lowercase(Locale.US) }.getOrNull()
}

fun Model.supportsBuiltInSearch(providerSetting: ProviderSetting? = null): Boolean {
    if (tools.contains(BuiltInTools.Search)) {
        return true
    }
    if (tools.contains(BuiltInTools.ClaudeWebSearchDisabled)) {
        return false
    }
    if (tools.contains(BuiltInTools.ClaudeWebSearch)) {
        return true
    }
    if (tools.contains(BuiltInTools.GrokWebSearch)) {
        return true
    }

    return when {
        ModelRegistry.GEMINI_SERIES.match(modelId) -> true
        ModelRegistry.CLAUDE_SERIES.match(modelId) -> providerSetting.supportsClaudeBuiltInSearchByHost()
        ModelRegistry.GROK_4.match(modelId) -> providerSetting.supportsGrokBuiltInSearchByHost()
        else -> providerSetting.supportsGrokBuiltInSearchByHost()
    }
}

fun Model.isClaudeBuiltInSearchEnabled(providerSetting: ProviderSetting? = null): Boolean {
    if (tools.contains(BuiltInTools.ClaudeWebSearchDisabled)) {
        return false
    }
    if (tools.contains(BuiltInTools.ClaudeWebSearch)) {
        return true
    }
    return ModelRegistry.CLAUDE_SERIES.match(modelId) &&
        providerSetting.supportsClaudeBuiltInSearchByHost()
}

fun Model.preferredBuiltInSearchTool(providerSetting: ProviderSetting? = null): BuiltInTools? {
    return when {
        tools.contains(BuiltInTools.Search) -> BuiltInTools.Search
        tools.contains(BuiltInTools.ClaudeWebSearchDisabled) -> null
        tools.contains(BuiltInTools.ClaudeWebSearch) -> BuiltInTools.ClaudeWebSearch
        tools.contains(BuiltInTools.GrokWebSearch) -> BuiltInTools.GrokWebSearch
        ModelRegistry.CLAUDE_SERIES.match(modelId) -> BuiltInTools.ClaudeWebSearch
        ModelRegistry.GEMINI_SERIES.match(modelId) -> BuiltInTools.Search
        providerSetting.supportsGrokBuiltInSearchByHost() -> BuiltInTools.GrokWebSearch
        else -> null
    }
}

fun Model.ensureBuiltInSearchTool(providerSetting: ProviderSetting? = null): Model {
    val tool = preferredBuiltInSearchTool(providerSetting) ?: return this
    return if (tools.contains(tool)) this else copy(tools = tools + tool)
}

fun Model.withoutBuiltInSearchTools(): Model {
    val filtered = tools.filterNot { tool ->
        tool == BuiltInTools.Search || tool == BuiltInTools.ClaudeWebSearch ||
            tool == BuiltInTools.GrokWebSearch  // GrokXSearch intentionally omitted (model-level opt-in)
    }.toSet()
    return if (filtered == tools) this else copy(tools = filtered)
}
