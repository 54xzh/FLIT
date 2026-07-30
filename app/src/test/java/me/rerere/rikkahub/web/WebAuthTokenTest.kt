package me.rerere.rikkahub.web

import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class WebAuthTokenTest {
    @Test
    fun `issued token is accepted with current password`() {
        val password = "test-password"
        val response = issueWebAuthToken(
            settings = Settings(
                webServerJwtEnabled = true,
                webServerAccessPassword = password,
            ),
            request = WebAuthTokenRequest(password),
        )

        assertTrue(verifyWebJwt(response.token, password))
    }

    @Test
    fun `token is rejected after password changes`() {
        val (token) = createWebJwt("old-password")

        assertFalse(verifyWebJwt(token, "new-password"))
    }

    @Test
    fun `malformed token is rejected`() {
        assertFalse(verifyWebJwt("not-a-token", "test-password"))
    }

    @Test
    fun `wrong password cannot issue token`() {
        val settings = Settings(
            webServerJwtEnabled = true,
            webServerAccessPassword = "correct-password",
        )

        assertThrows(UnauthorizedException::class.java) {
            issueWebAuthToken(settings, WebAuthTokenRequest("wrong-password"))
        }
    }
}
