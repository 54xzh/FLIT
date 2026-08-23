package me.rerere.rikkahub.data.sync

import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.codex.CodexCredential
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class CodexCredentialRestoreTest {
    @Test
    fun `restore keeps only credentials belonging to restored Codex providers`() {
        val restoredCodex = ProviderSetting.OpenAICodex(id = Uuid.random(), name = "Restored")
        val unrelatedCodex = Uuid.random()
        val ordinaryProvider = ProviderSetting.OpenAI(id = Uuid.random(), name = "API")
        val expected = credential("restored")

        val filtered = filterRestoredCodexCredentials(
            providers = listOf(restoredCodex, ordinaryProvider),
            credentials = mapOf(
                restoredCodex.id to expected,
                unrelatedCodex to credential("unrelated"),
                ordinaryProvider.id to credential("wrong-type"),
            ),
        )

        assertEquals(mapOf(restoredCodex.id to expected), filtered)
    }

    @Test
    fun `old backup without credential entry restores Codex providers logged out`() {
        val provider = ProviderSetting.OpenAICodex(id = Uuid.random())

        assertTrue(filterRestoredCodexCredentials(listOf(provider), emptyMap()).isEmpty())
    }

    private fun credential(token: String) = CodexCredential(
        accessToken = token,
        refreshToken = "refresh-$token",
        expiresAtEpochMillis = Long.MAX_VALUE,
        accountId = "account-$token",
    )
}
