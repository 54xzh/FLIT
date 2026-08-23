package me.rerere.rikkahub

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.awaitCancellation
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.codex.CodexCredential
import me.rerere.ai.provider.providers.codex.CodexDeviceCode
import me.rerere.ai.provider.providers.codex.CodexProtocolException
import me.rerere.rikkahub.data.ai.codex.CodexLoginService
import me.rerere.rikkahub.ui.pages.setting.CodexLoginDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class CodexLoginDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun waitingStateShowsCodeOpensBrowserAndCanCancel() {
        val browserLaunches = AtomicInteger()
        val dismissed = AtomicBoolean()
        val loginService = FakeCodexLoginService(waitForAuthorization = true)

        composeRule.setContent {
            MaterialTheme {
                CodexLoginDialog(
                    provider = ProviderSetting.OpenAICodex(),
                    onDismiss = { dismissed.set(true) },
                    onSuccess = {},
                    authService = loginService,
                    browserLauncher = { _, _ -> browserLaunches.incrementAndGet() },
                )
            }
        }

        composeRule.onNodeWithText("TEST-CODE").assertIsDisplayed()
        composeRule.waitUntil { browserLaunches.get() == 1 }
        composeRule.onNodeWithText(context.getString(R.string.cancel)).performClick()
        assertTrue(dismissed.get())
    }

    @Test
    fun disabledDeviceLoginShowsRetry() {
        val loginService = FakeCodexLoginService(
            startError = CodexProtocolException(404, "disabled"),
        )

        composeRule.setContent {
            MaterialTheme {
                CodexLoginDialog(
                    provider = ProviderSetting.OpenAICodex(),
                    onDismiss = {},
                    onSuccess = {},
                    authService = loginService,
                    browserLauncher = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.codex_device_login_not_enabled))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.codex_retry)).assertIsDisplayed()
    }

    @Test
    fun successfulAuthorizationCallsSuccessOnce() {
        val successCalls = AtomicInteger()

        composeRule.setContent {
            MaterialTheme {
                CodexLoginDialog(
                    provider = ProviderSetting.OpenAICodex(),
                    onDismiss = {},
                    onSuccess = { successCalls.incrementAndGet() },
                    authService = FakeCodexLoginService(),
                    browserLauncher = { _, _ -> },
                )
            }
        }

        composeRule.waitUntil { successCalls.get() == 1 }
        assertEquals(1, successCalls.get())
    }
}

private class FakeCodexLoginService(
    private val waitForAuthorization: Boolean = false,
    private val startError: Throwable? = null,
) : CodexLoginService {
    override fun cancelDeviceLogin(providerId: kotlin.uuid.Uuid) = Unit

    override suspend fun startDeviceLogin(provider: ProviderSetting.OpenAICodex): CodexDeviceCode {
        startError?.let { throw it }
        return CodexDeviceCode(
            deviceAuthId = "device-auth-id",
            userCode = "TEST-CODE",
            expiresAtEpochMillis = System.currentTimeMillis() + 60_000L,
            intervalMillis = 1_000L,
        )
    }

    override suspend fun completeDeviceLogin(
        provider: ProviderSetting.OpenAICodex,
        deviceCode: CodexDeviceCode,
        commitAssociatedState: suspend () -> Unit,
    ): CodexCredential {
        if (waitForAuthorization) awaitCancellation()
        commitAssociatedState()
        return CodexCredential(
            accessToken = "access",
            refreshToken = "refresh",
            expiresAtEpochMillis = Long.MAX_VALUE,
            accountId = "account",
        )
    }
}
