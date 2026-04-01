package me.rerere.ai.provider

import org.junit.Test

class BuiltInSearchTest {
    @Test
    fun testClaudeBuiltInSearchRequiresOfficialHost() {
        val model = Model(modelId = "claude-3.7-sonnet")
        val officialProvider = ProviderSetting.Claude(baseUrl = "https://api.anthropic.com/v1")
        val unofficialProvider = ProviderSetting.Claude(baseUrl = "https://openrouter.ai/api/v1")

        assert(model.supportsBuiltInSearch(officialProvider))
        assert(!model.supportsBuiltInSearch(unofficialProvider))
    }

    @Test
    fun testExplicitClaudeToolOverridesProviderHostRestriction() {
        val model = Model(
            modelId = "custom-claude",
            tools = setOf(BuiltInTools.ClaudeWebSearch)
        )
        val unofficialProvider = ProviderSetting.Claude(baseUrl = "https://example.com/v1")

        assert(model.supportsBuiltInSearch(unofficialProvider))
    }

    @Test
    fun testClaudeDisabledMarkerOverridesOfficialDefault() {
        val model = Model(
            modelId = "claude-3.7-sonnet",
            tools = setOf(BuiltInTools.ClaudeWebSearchDisabled),
        )
        val officialProvider = ProviderSetting.Claude(baseUrl = "https://api.anthropic.com/v1")

        assert(!model.supportsBuiltInSearch(officialProvider))
        assert(!model.isClaudeBuiltInSearchEnabled(officialProvider))
    }

    @Test
    fun testGeminiBuiltInSearchStillWorksByModelId() {
        val model = Model(modelId = "gemini-2.5-pro")

        assert(model.supportsBuiltInSearch())
    }

    @Test
    fun testExplicitGenericSearchToolAlwaysSupported() {
        val model = Model(
            modelId = "unknown-model",
            tools = setOf(BuiltInTools.Search)
        )

        assert(model.supportsBuiltInSearch())
    }

    @Test
    fun testGrokBuiltInSearchRequiresOfficialHost() {
        val model = Model(modelId = "grok-4")
        val officialProvider = ProviderSetting.OpenAI(baseUrl = "https://api.x.ai/v1")
        val unofficialProvider = ProviderSetting.OpenAI(baseUrl = "https://openrouter.ai/api/v1")

        assert(model.supportsBuiltInSearch(officialProvider))
        assert(!model.supportsBuiltInSearch(unofficialProvider))
    }

    @Test
    fun testGrokCustomModelDetectedByHost() {
        val model = Model(modelId = "custom-grok-model")
        val officialProvider = ProviderSetting.OpenAI(baseUrl = "https://api.x.ai/v1")
        val otherProvider = ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1")

        assert(model.supportsBuiltInSearch(officialProvider))
        assert(!model.supportsBuiltInSearch(otherProvider))
    }

    @Test
    fun testGrokXSearchNotClearedByWithoutBuiltInSearchTools() {
        val model = Model(
            modelId = "grok-4",
            tools = setOf(BuiltInTools.GrokWebSearch, BuiltInTools.GrokXSearch)
        )
        val stripped = model.withoutBuiltInSearchTools()

        assert(!stripped.tools.contains(BuiltInTools.GrokWebSearch))
        assert(stripped.tools.contains(BuiltInTools.GrokXSearch))
    }

    @Test
    fun testGrokEnsureBuiltInSearchToolAddsGrokWebSearch() {
        val model = Model(modelId = "grok-4")
        val officialProvider = ProviderSetting.OpenAI(baseUrl = "https://api.x.ai/v1")
        val result = model.ensureBuiltInSearchTool(officialProvider)

        assert(result.tools.contains(BuiltInTools.GrokWebSearch))
    }
}
