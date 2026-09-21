package me.rerere.ai.provider

import me.rerere.ai.util.KeyRoulette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderApiKeyTest {
    @Test
    fun `Agent Platform defaults to standard mode with global location`() {
        val provider = ProviderSetting.Google(platform = GooglePlatform.AGENT_PLATFORM)

        assertEquals(AgentPlatformMode.STANDARD, provider.agentPlatformMode)
        assertEquals("global", provider.location)
    }

    @Test
    fun `Agent Platform mode changes retain both configurations`() {
        val standard = ProviderSetting.Google(
            platform = GooglePlatform.AGENT_PLATFORM,
            projectId = "standard-project",
            location = "global",
            serviceAccountEmail = "service@example.iam.gserviceaccount.com",
            privateKey = "private-key",
        )
        val express = standard.copy(
            agentPlatformMode = AgentPlatformMode.EXPRESS,
            apiKey = "express-api-key",
        )
        val restoredStandard = express.copy(agentPlatformMode = AgentPlatformMode.STANDARD)

        assertEquals("express-api-key", restoredStandard.apiKey)
        assertEquals("standard-project", restoredStandard.projectId)
        assertEquals("global", restoredStandard.location)
        assertEquals("service@example.iam.gserviceaccount.com", restoredStandard.serviceAccountEmail)
        assertEquals("private-key", restoredStandard.privateKey)
    }

    @Test
    fun `splitProviderApiKeys supports comma spaces and line breaks`() {
        val keys = splitProviderApiKeys(" key-a, key-b\nkey-c  key-a ")

        assertEquals(listOf("key-a", "key-b", "key-c"), keys)
    }

    @Test
    fun `normalizeProviderApiKeys migrates legacy comma separated api key`() {
        val provider = ProviderSetting.OpenAI(apiKey = "key-a,key-b")
            .normalizeProviderApiKeys()

        assertTrue(provider is ProviderSetting.OpenAI)
        provider as ProviderSetting.OpenAI
        assertTrue(provider.multiKeyEnabled)
        assertEquals("key-a,key-b", provider.apiKey)
        assertEquals(listOf("key-a", "key-b"), provider.apiKeys.map { it.value })
        assertEquals("key-a,key-b", provider.legacyApiKeyBackup)
    }

    @Test
    fun `syncEnabledApiKeysToLegacyField writes enabled keys only`() {
        val provider = ProviderSetting.Claude(
            apiKey = "old",
            multiKeyEnabled = true,
            apiKeys = listOf(
                ProviderApiKey(value = "key-a", enabled = true),
                ProviderApiKey(value = "key-b", enabled = false),
                ProviderApiKey(value = "key-c", enabled = true),
            ),
        ).syncEnabledApiKeysToLegacyField()

        assertTrue(provider is ProviderSetting.Claude)
        provider as ProviderSetting.Claude
        assertEquals("key-a,key-c", provider.apiKey)
    }

    @Test
    fun `disabling multi key mode stays disabled during normalization`() {
        val provider = ProviderSetting.Google(
            apiKey = "key-a,key-b",
            multiKeyEnabled = false,
            apiKeys = listOf(
                ProviderApiKey(value = "key-a"),
                ProviderApiKey(value = "key-b"),
            ),
        ).normalizeProviderApiKeys()

        assertTrue(provider is ProviderSetting.Google)
        provider as ProviderSetting.Google
        assertFalse(provider.multiKeyEnabled)
    }

    @Test
    fun `round robin strategy uses enabled keys in order`() {
        val provider = ProviderSetting.OpenAI(
            id = kotlin.uuid.Uuid.parse("00000000-0000-0000-0000-000000000001"),
            apiKey = "fallback",
            multiKeyEnabled = true,
            keyStrategy = ProviderKeyStrategy.ROUND_ROBIN,
            apiKeys = listOf(
                ProviderApiKey(value = "key-a"),
                ProviderApiKey(value = "key-b"),
            ),
        )
        val roulette = KeyRoulette.default()

        assertEquals("key-a", roulette.next(provider))
        assertEquals("key-b", roulette.next(provider))
        assertEquals("key-a", roulette.next(provider))
    }
}
