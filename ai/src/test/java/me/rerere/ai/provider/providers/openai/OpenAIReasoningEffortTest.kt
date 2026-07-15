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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class OpenAIReasoningEffortTest {
    @Test
    fun `xhigh budget maps to xhigh reasoning level`() {
        val level = ReasoningLevel.fromBudgetTokens(ReasoningLevel.XHIGH.budgetTokens)

        assertEquals(ReasoningLevel.XHIGH, level)
        assertEquals("xhigh", level.effort)
    }

    @Test
    fun `chat completions sends xhigh reasoning effort`() {
        val body = buildChatCompletionRequest(
            params = reasoningParams(ReasoningLevel.XHIGH),
            providerSetting = ProviderSetting.OpenAI(),
        )

        assertEquals("xhigh", body["reasoning_effort"]?.jsonPrimitive?.content)
    }

    @Test
    fun `chat completions off sends none effort for openai compatible hosts`() {
        val body = buildChatCompletionRequest(
            params = reasoningParams(ReasoningLevel.OFF),
            providerSetting = ProviderSetting.OpenAI(),
        )

        assertEquals("none", body["reasoning_effort"]?.jsonPrimitive?.content)
    }

    @Test
    fun `chat completions auto omits reasoning effort for openai compatible hosts`() {
        val body = buildChatCompletionRequest(
            params = reasoningParams(ReasoningLevel.AUTO),
            providerSetting = ProviderSetting.OpenAI(),
        )

        assertFalse(body.containsKey("reasoning_effort"))
    }

    @Test
    fun `unknown host falls back to openai style none`() {
        val body = buildChatCompletionRequest(
            params = reasoningParams(ReasoningLevel.OFF),
            providerSetting = ProviderSetting.OpenAI(
                baseUrl = "https://proxy.example.com/v1",
            ),
        )

        assertEquals("none", body["reasoning_effort"]?.jsonPrimitive?.content)
    }

    @Test
    fun `deepseek off uses thinking disabled without effort`() {
        val body = buildChatCompletionRequest(
            params = reasoningParams(ReasoningLevel.OFF),
            providerSetting = ProviderSetting.OpenAI(
                baseUrl = "https://api.deepseek.com",
            ),
        )

        assertEquals("disabled", body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertNull(body["reasoning_effort"])
    }

    @Test
    fun `volcengine auto uses thinking auto`() {
        val body = buildChatCompletionRequest(
            params = reasoningParams(ReasoningLevel.AUTO),
            providerSetting = ProviderSetting.OpenAI(
                baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            ),
        )

        assertEquals("auto", body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
    }

    @Test
    fun `minimax off uses thinking disabled`() {
        val body = buildChatCompletionRequest(
            params = reasoningParams(ReasoningLevel.OFF),
            providerSetting = ProviderSetting.OpenAI(
                baseUrl = "https://api.minimaxi.com/v1",
            ),
        )

        assertEquals("disabled", body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertNull(body["reasoning_effort"])
    }

    @Test
    fun `responses sends xhigh reasoning effort`() {
        val api = ResponseAPI(OkHttpClient(), KeyRoulette.default())
        val body = buildResponseRequest(api, reasoningParams(ReasoningLevel.XHIGH))

        assertEquals("xhigh", body["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
    }

    private fun reasoningParams(level: ReasoningLevel) = TextGenerationParams(
        model = Model(
            modelId = "gpt-5-codex",
            abilities = listOf(ModelAbility.REASONING)
        ),
        reasoningLevel = level,
    )

    private fun userMessages() = listOf(
        UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hi")))
    )

    private fun buildChatCompletionRequest(
        params: TextGenerationParams,
        providerSetting: ProviderSetting.OpenAI,
    ): JsonObject {
        val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
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
