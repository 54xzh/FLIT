package me.rerere.ai

import kotlinx.serialization.json.Json
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.registry.ModelsDevCapabilityParser
import me.rerere.ai.registry.RemoteCapabilityMatch
import me.rerere.ai.registry.RemoteModelCapability
import me.rerere.ai.registry.RemoteModelCapabilityResolver
import me.rerere.ai.registry.RemoteModelNameResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteModelCapabilityResolverTest {
    @Test
    fun exactCanonicalIdMatches() {
        val match = resolver().resolve("z-ai/glm-5")

        assertMatchedModel("z-ai/glm-5", match)
        assertTrue(match is RemoteCapabilityMatch.Exact)
    }

    @Test
    fun shortIdMatchesCanonicalIdWhenUnique() {
        val match = resolver().resolve("glm-5")

        assertMatchedModel("z-ai/glm-5", match)
    }

    @Test
    fun freeSuffixMatchesBaseCanonicalId() {
        val match = resolver().resolve("z-ai/glm-5:free")

        assertMatchedModel("z-ai/glm-5", match)
    }

    @Test
    fun proxySlashPrefixMatchesCanonicalId() {
        val match = resolver().resolve("openrouter/z-ai/glm-5")

        assertMatchedModel("z-ai/glm-5", match)
    }

    @Test
    fun proxyColonPrefixMatchesCanonicalId() {
        val match = resolver().resolve("provider:z-ai/glm-5")

        assertMatchedModel("z-ai/glm-5", match)
    }

    @Test
    fun rewrittenProviderPathMatchesByShortId() {
        val match = resolver().resolve("Pro/zai-org/GLM-5")

        assertMatchedModel("z-ai/glm-5", match)
    }

    @Test
    fun shortIdWithProviderSuffixMatchesCanonicalIdWhenUnique() {
        val match = resolver().resolve("glm-5-pro")

        assertMatchedModel("z-ai/glm-5", match)
    }

    @Test
    fun rewrittenProviderPathWithSuffixMatchesByShortIdWhenUnique() {
        val match = resolver().resolve("Pro/zai-org/GLM-5-pro")

        assertMatchedModel("z-ai/glm-5", match)
    }

    @Test
    fun ambiguousShortIdDoesNotMatch() {
        val match = resolver(
            RemoteModelCapability(modelId = "provider-a/shared-model"),
            RemoteModelCapability(modelId = "provider-b/shared-model"),
        ).resolve("shared-model")

        assertEquals(RemoteCapabilityMatch.None, match)
    }

    @Test
    fun ambiguousSuffixStrippedShortIdDoesNotMatch() {
        val match = resolver(
            RemoteModelCapability(modelId = "provider-a/qwen3-coder"),
            RemoteModelCapability(modelId = "provider-a/qwen3-coder:free"),
        ).resolve("qwen3-coder")

        assertTrue(match is RemoteCapabilityMatch.Exact || match is RemoteCapabilityMatch.Alias)

        val suffixMatch = resolver(
            RemoteModelCapability(modelId = "provider-a/qwen3-coder"),
            RemoteModelCapability(modelId = "provider-b/qwen3-coder"),
        ).resolve("proxy/qwen3-coder:free")

        assertEquals(RemoteCapabilityMatch.None, suffixMatch)
    }

    @Test
    fun ambiguousShortIdSuffixDoesNotMatch() {
        val match = resolver(
            RemoteModelCapability(modelId = "provider-a/glm-5"),
            RemoteModelCapability(modelId = "provider-a/glm-5-turbo"),
        ).resolve("proxy/glm-5-turbo-pro")

        assertEquals(RemoteCapabilityMatch.None, match)
    }

    @Test
    fun unknownIdDoesNotMatch() {
        val match = resolver().resolve("not-a-real-model")

        assertEquals(RemoteCapabilityMatch.None, match)
    }

    @Test
    fun strictNameResolverMatchesCanonicalIdIgnoringCase() {
        val name = nameResolver().resolveDisplayName("Z-AI/GLM-5")

        assertEquals("GLM 5", name)
    }

    @Test
    fun strictNameResolverMatchesUniqueShortIdIgnoringCase() {
        val name = nameResolver().resolveDisplayName("GLM-5")

        assertEquals("GLM 5", name)
    }

    @Test
    fun strictNameResolverDoesNotMatchPrefixedPaths() {
        val resolver = nameResolver()

        assertEquals(null, resolver.resolveDisplayName("Pro/zai-org/GLM-5"))
        assertEquals(null, resolver.resolveDisplayName("openrouter/z-ai/glm-5"))
        assertEquals(null, resolver.resolveDisplayName("provider:z-ai/glm-5"))
    }

    @Test
    fun strictNameResolverDoesNotStripSuffixes() {
        val resolver = nameResolver()

        assertEquals(null, resolver.resolveDisplayName("z-ai/glm-5:free"))
        assertEquals(null, resolver.resolveDisplayName("glm-5-pro"))
    }

    @Test
    fun strictNameResolverDoesNotMatchAmbiguousShortId() {
        val name = nameResolver(
            RemoteModelCapability(modelId = "provider-a/shared-model", displayName = "A Shared"),
            RemoteModelCapability(modelId = "provider-b/shared-model", displayName = "B Shared"),
        ).resolveDisplayName("shared-model")

        assertEquals(null, name)
    }

    @Test
    fun strictNameResolverIgnoresBlankDisplayName() {
        val resolver = nameResolver(
            RemoteModelCapability(modelId = "z-ai/glm-5", displayName = null),
        )

        assertEquals(null, resolver.resolveDisplayName("z-ai/glm-5"))
        assertEquals(null, resolver.resolveDisplayName("glm-5"))
    }

    @Test
    fun parsesOpenRouterCapabilitiesAndIgnoresCosts() {
        val json = Json.parseToJsonElement(
            """
            {
              "openrouter": {
                "models": {
                  "z-ai/glm-5": {
                    "id": "z-ai/glm-5",
                    "name": "GLM 5",
                    "reasoning": true,
                    "tool_call": true,
                    "modalities": {
                      "input": ["text", "image", "video"],
                      "output": ["text"]
                    },
                    "cost": {
                      "input": 1,
                      "output": 2
                    }
                  },
                  "broken": {
                    "name": "Missing ID"
                  }
                }
              },
              "openai": {
                "models": {
                  "gpt-4o": {
                    "id": "gpt-4o",
                    "tool_call": true
                  }
                }
              }
            }
            """.trimIndent()
        )

        val capabilities = ModelsDevCapabilityParser.parseOpenRouterCapabilities(json)

        assertEquals(1, capabilities.size)
        val capability = capabilities.first()
        assertEquals("z-ai/glm-5", capability.modelId)
        assertEquals("GLM 5", capability.displayName)
        assertEquals(listOf(Modality.TEXT, Modality.IMAGE), capability.inputModalities)
        assertEquals(listOf(Modality.TEXT), capability.outputModalities)
        assertEquals(listOf(ModelAbility.TOOL, ModelAbility.REASONING), capability.abilities)
    }

    @Test
    fun parserFallsBackToTextForMissingOrUnsupportedModalities() {
        val json = Json.parseToJsonElement(
            """
            {
              "openrouter": {
                "models": {
                  "audio/model": {
                    "id": "audio/model",
                    "modalities": {
                      "input": ["audio"],
                      "output": []
                    }
                  }
                }
              }
            }
            """.trimIndent()
        )

        val capability = ModelsDevCapabilityParser.parseOpenRouterCapabilities(json).first()

        assertEquals(listOf(Modality.TEXT), capability.inputModalities)
        assertEquals(listOf(Modality.TEXT), capability.outputModalities)
        assertEquals(emptyList<ModelAbility>(), capability.abilities)
    }

    private fun resolver(
        vararg extraCapabilities: RemoteModelCapability,
    ): RemoteModelCapabilityResolver {
        val capabilities = if (extraCapabilities.isNotEmpty()) {
            extraCapabilities.toList()
        } else {
            listOf(
                RemoteModelCapability(modelId = "z-ai/glm-5"),
                RemoteModelCapability(modelId = "z-ai/glm-5-turbo"),
                RemoteModelCapability(modelId = "anthropic/claude-sonnet-4.5"),
            )
        }
        return RemoteModelCapabilityResolver(capabilities)
    }

    private fun nameResolver(
        vararg extraCapabilities: RemoteModelCapability,
    ): RemoteModelNameResolver {
        val capabilities = if (extraCapabilities.isNotEmpty()) {
            extraCapabilities.toList()
        } else {
            listOf(
                RemoteModelCapability(modelId = "z-ai/glm-5", displayName = "GLM 5"),
                RemoteModelCapability(modelId = "z-ai/glm-5-turbo", displayName = "GLM 5 Turbo"),
                RemoteModelCapability(modelId = "anthropic/claude-sonnet-4.5", displayName = "Claude Sonnet 4.5"),
            )
        }
        return RemoteModelNameResolver(capabilities)
    }

    private fun assertMatchedModel(
        expectedModelId: String,
        match: RemoteCapabilityMatch,
    ) {
        assertEquals(expectedModelId, match.capabilityOrNull?.modelId)
    }
}
