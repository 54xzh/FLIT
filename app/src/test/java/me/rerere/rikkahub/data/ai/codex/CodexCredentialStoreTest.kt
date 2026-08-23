package me.rerere.rikkahub.data.ai.codex

import me.rerere.ai.provider.providers.codex.CodexCredential
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class CodexCredentialStoreTest {
    @Test
    fun `credential file is plaintext and round trips multiple accounts`() {
        val firstId = Uuid.random()
        val secondId = Uuid.random()
        val credentials = mapOf(
            firstId to credential("access-one", "refresh-one", "account-one"),
            secondId to credential("access-two", "refresh-two", "account-two"),
        )

        val encoded = encodeCodexCredentials(credentials)

        assertTrue(encoded.contains("access-one"))
        assertTrue(encoded.contains("refresh-two"))
        assertTrue(encoded.contains("account-one"))
        assertEquals(credentials, decodeCodexCredentials(encoded))
    }

    @Test
    fun `decode ignores credential records with invalid provider ids`() {
        val validId = Uuid.random()
        val encoded = encodeCodexCredentials(mapOf(validId to credential("access", "refresh", "account")))
            .replace(validId.toString(), "not-a-provider-id", ignoreCase = false)

        val decoded = decodeCodexCredentials(encoded)

        assertTrue(decoded.isEmpty())
        assertFalse(decoded.keys.any { it.toString() == "not-a-provider-id" })
    }

    @Test
    fun `decode rejects unsupported file version`() {
        val encoded = encodeCodexCredentials(emptyMap()).replace("\"version\":1", "\"version\":2")

        assertThrows(IllegalArgumentException::class.java) {
            decodeCodexCredentials(encoded)
        }
    }

    private fun credential(access: String, refresh: String, account: String) = CodexCredential(
        accessToken = access,
        refreshToken = refresh,
        expiresAtEpochMillis = 123456L,
        accountId = account,
        email = "$account@example.com",
        planType = "plus",
    )
}
