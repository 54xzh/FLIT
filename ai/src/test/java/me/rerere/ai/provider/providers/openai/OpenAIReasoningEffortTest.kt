package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenAIReasoningEffortTest {
    @Test
    fun `xhigh budget maps to xhigh reasoning level`() {
        val level = ReasoningLevel.fromBudgetTokens(64_000)

        assertEquals(ReasoningLevel.XHIGH, level)
        assertEquals("xhigh", level.effort)
    }

    @Test
    fun `chat completions sends xhigh reasoning effort`() {
        val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
        val body = buildChatCompletionRequest(
            api = api,
            params = reasoningParams(),
            providerSetting = ProviderSetting.OpenAI()
        )

        assertEquals("xhigh", body["reasoning_effort"]?.jsonPrimitive?.content)
    }

    @Test
    fun `responses sends xhigh reasoning effort`() {
        val api = ResponseAPI(OkHttpClient(), KeyRoulette.default())
        val body = buildResponseRequest(api, reasoningParams())

        assertEquals("xhigh", body["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
    }

    private fun reasoningParams() = TextGenerationParams(
        model = Model(
            modelId = "gpt-5-codex",
            abilities = listOf(ModelAbility.REASONING)
        ),
        thinkingBudget = 64_000,
    )

    private fun userMessages() = listOf(
        UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hi")))
    )

    private fun buildChatCompletionRequest(
        api: ChatCompletionsAPI,
        params: TextGenerationParams,
        providerSetting: ProviderSetting.OpenAI,
    ): JsonObject {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildChatCompletionRequest",
            List::class.java,
            TextGenerationParams::class.java,
            ProviderSetting.OpenAI::class.java,
            java.lang.Boolean.TYPE,
        )
        method.isAccessible = true
        return method.invoke(api, userMessages(), params, providerSetting, false) as JsonObject
    }

    private fun buildResponseRequest(
        api: ResponseAPI,
        params: TextGenerationParams,
    ): JsonObject {
        val method = ResponseAPI::class.java.getDeclaredMethod(
            "buildRequestBody",
            List::class.java,
            TextGenerationParams::class.java,
            java.lang.Boolean.TYPE,
        )
        method.isAccessible = true
        return method.invoke(api, userMessages(), params, false) as JsonObject
    }
}
