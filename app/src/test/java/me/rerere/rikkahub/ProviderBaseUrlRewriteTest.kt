package me.rerere.rikkahub

import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.ui.pages.setting.components.convertTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderBaseUrlRewriteTest {
    @Test
    fun `convertTo should rewrite v1 to v1beta when switching to Google`() {
        val provider = ProviderSetting.OpenAI(
            name = "Test",
            baseUrl = "https://example.com/v1",
        )

        val converted = provider.convertTo(ProviderSetting.Google::class)

        assertTrue(converted is ProviderSetting.Google)
        assertEquals("https://example.com/v1beta", (converted as ProviderSetting.Google).baseUrl)
    }

    @Test
    fun `convertTo should remove trailing slash when rewriting v1 to v1beta`() {
        val provider = ProviderSetting.OpenAI(
            name = "Test",
            baseUrl = "https://example.com/v1/",
        )

        val converted = provider.convertTo(ProviderSetting.Google::class) as ProviderSetting.Google

        assertEquals("https://example.com/v1beta", converted.baseUrl)
    }

    @Test
    fun `convertTo should rewrite v1beta to v1 when switching to OpenAI`() {
        val provider = ProviderSetting.Google(
            name = "Test",
            baseUrl = "https://example.com/v1beta",
        )

        val converted = provider.convertTo(ProviderSetting.OpenAI::class)

        assertTrue(converted is ProviderSetting.OpenAI)
        assertEquals("https://example.com/v1", (converted as ProviderSetting.OpenAI).baseUrl)
    }

    @Test
    fun `convertTo should not append version segment when missing`() {
        val provider = ProviderSetting.OpenAI(
            name = "Test",
            baseUrl = "https://example.com/api",
        )

        val converted = provider.convertTo(ProviderSetting.Google::class) as ProviderSetting.Google

        assertEquals("https://example.com/api", converted.baseUrl)
    }

    @Test
    fun `convertTo should not rewrite when version segment is not at the end`() {
        val provider = ProviderSetting.OpenAI(
            name = "Test",
            baseUrl = "https://example.com/v1/extra",
        )

        val converted = provider.convertTo(ProviderSetting.Google::class) as ProviderSetting.Google

        assertEquals("https://example.com/v1/extra", converted.baseUrl)
    }
}
