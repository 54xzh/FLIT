package me.rerere.rikkahub.ui.pages.setting.components

import android.content.Context
import androidx.annotation.StringRes
import me.rerere.ai.provider.BalanceOption
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.R
import kotlin.reflect.KClass

/**
 * Data class representing a provider preset for quick setup
 */
data class ProviderPreset(
    val name: String,
    @param:StringRes val descriptionRes: Int,
    val type: KClass<out ProviderSetting>,
    val baseUrl: String,
    val balanceOption: BalanceOption = BalanceOption(),
    val useResponseApi: Boolean = false,
    val chatCompletionsPath: String = "/chat/completions",
    val requiresCodexLogin: Boolean = false,
)

/**
 * List of provider presets ordered by popularity
 */
val PROVIDER_PRESETS = listOf(
    // Tier 1: Major providers
    ProviderPreset(
        name = "OpenAI",
        descriptionRes = R.string.provider_preset_openai_description,
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.openai.com/v1",
        useResponseApi = false // Response API available but default off for compatibility
    ),
    ProviderPreset(
        name = "OpenAI Codex",
        descriptionRes = R.string.codex_provider_preset_description,
        type = ProviderSetting.OpenAICodex::class,
        baseUrl = "https://chatgpt.com/backend-api/codex",
        requiresCodexLogin = true,
    ),
    ProviderPreset(
        name = "Google Gemini",
        descriptionRes = R.string.provider_preset_google_gemini_description,
        type = ProviderSetting.Google::class,
        baseUrl = "https://generativelanguage.googleapis.com/v1beta"
    ),
    ProviderPreset(
        name = "Anthropic Claude",
        descriptionRes = R.string.provider_preset_anthropic_claude_description,
        type = ProviderSetting.Claude::class,
        baseUrl = "https://api.anthropic.com/v1"
    ),
    ProviderPreset(
        name = "OpenRouter",
        descriptionRes = R.string.provider_preset_openrouter_description,
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://openrouter.ai/api/v1",
        balanceOption = BalanceOption(
            enabled = true,
            apiPath = "/credits",
            resultPath = "data.total_credits - data.total_usage"
        )
    ),
    ProviderPreset(
        name = "Ollama",
        descriptionRes = R.string.provider_preset_ollama_description,
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://ollama.com/v1"
    ),
    ProviderPreset(
        name = "Vercel",
        descriptionRes = R.string.provider_preset_vercel_description,
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://ai-gateway.vercel.sh/v1"
    ),
    ProviderPreset(
        name = "Groq",
        descriptionRes = R.string.provider_preset_groq_description,
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.groq.com/openai/v1",
        useResponseApi = true // Groq supports OpenAI Responses API
    ),
    ProviderPreset(
        name = "DeepSeek",
        descriptionRes = R.string.provider_preset_deepseek_description,
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.deepseek.com",
        balanceOption = BalanceOption(
            enabled = true,
            apiPath = "/user/balance",
            resultPath = "balance_infos[0].total_balance"
        )
    ),
    ProviderPreset(
        name = "Together AI",
        descriptionRes = R.string.provider_preset_together_ai_description,
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.together.xyz/v1"
    ),
    ProviderPreset(
        name = "Mistral",
        descriptionRes = R.string.provider_preset_mistral_description,
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.mistral.ai/v1"
    ),
    ProviderPreset(
        name = "Perplexity",
        descriptionRes = R.string.provider_preset_perplexity_description,
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.perplexity.ai"
    ),
    ProviderPreset(
        name = "Fireworks AI",
        descriptionRes = R.string.provider_preset_fireworks_ai_description,
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.fireworks.ai/inference/v1"
    ),
    ProviderPreset(
        name = "Cohere",
        descriptionRes = R.string.provider_preset_cohere_description,
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.cohere.ai/compatibility/v1"
    ),
    ProviderPreset(
        name = "xAI Grok",
        descriptionRes = R.string.provider_preset_xai_grok_description,
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.x.ai/v1"
    ),
    ProviderPreset(
        name = "Cerebras",
        descriptionRes = R.string.provider_preset_cerebras_description,
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.cerebras.ai/v1"
    ),
    ProviderPreset(
        name = "Novita",
        descriptionRes = R.string.provider_preset_novita_description,
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.novita.ai/v3/openai",
        balanceOption = BalanceOption(
            enabled = true,
            apiPath = "/v3/user/balance",
            resultPath = "balance"
        )
    ),
    ProviderPreset(
        name = "NanoGPT",
        descriptionRes = R.string.provider_preset_nanogpt_description,
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://nano-gpt.com/api/v1",
        balanceOption = BalanceOption(
            enabled = true,
            apiPath = "/check-balance",
            resultPath = "balance"
        )
    ),
    ProviderPreset(
        name = "DeepInfra",
        descriptionRes = R.string.provider_preset_deepinfra_description,
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.deepinfra.com/v1/openai"
    ),
    ProviderPreset(
        name = "Hyperbolic",
        descriptionRes = R.string.provider_preset_hyperbolic_description,
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.hyperbolic.xyz/v1"
    ),
    ProviderPreset(
        name = "SiliconFlow",
        descriptionRes = R.string.provider_preset_siliconflow_description,
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.siliconflow.cn/v1",
        balanceOption = BalanceOption(
            enabled = true,
            apiPath = "/user/info",
            resultPath = "data.balance"
        )
    ),
    ProviderPreset(
        name = "AI21",
        descriptionRes = R.string.provider_preset_ai21_description,
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.ai21.com/studio/v1"
    ),
    ProviderPreset(
        name = "Lepton",
        descriptionRes = R.string.provider_preset_lepton_description,
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.lepton.ai/v1"
    ),
    ProviderPreset(
        name = "SambaNova",
        descriptionRes = R.string.provider_preset_sambanova_description,
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.sambanova.ai/v1"
    ),
    ProviderPreset(
        name = "Anyscale",
        descriptionRes = R.string.provider_preset_anyscale_description,
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.anyscale.com/v1"
    ),
    ProviderPreset(
        name = "Cloudflare",
        descriptionRes = R.string.provider_preset_cloudflare_description,
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/v1"
    ),
    ProviderPreset(
        name = "Hugging Face",
        descriptionRes = R.string.provider_preset_hugging_face_description,
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://router.huggingface.co/v1"
    ),
    ProviderPreset(
        name = "NVIDIA NIM",
        descriptionRes = R.string.provider_preset_nvidia_nim_description,
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://integrate.api.nvidia.com/v1"
    ),
    ProviderPreset(
        name = "AiHubMix",
        descriptionRes = R.string.provider_preset_aihubmix_description,
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://aihubmix.com/v1"
    ),
    ProviderPreset(
        name = "Alibaba Qwen",
        descriptionRes = R.string.provider_preset_alibaba_qwen_description,
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    ),
    ProviderPreset(
        name = "GLhf",
        descriptionRes = R.string.provider_preset_glhf_description,
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://glhf.chat/api/openai/v1"
    ),
    ProviderPreset(
        name = "Featherless",
        descriptionRes = R.string.provider_preset_featherless_description,
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.featherless.ai/v1"
    ),
    ProviderPreset(
        name = "Chutes",
        descriptionRes = R.string.provider_preset_chutes_description,
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.chutes.ai/v1"
    ),
    ProviderPreset(
        name = "Infermatic",
        descriptionRes = R.string.provider_preset_infermatic_description,
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.totalgpt.ai/v1"
    ),
    ProviderPreset(
        name = "RunPod",
        descriptionRes = R.string.provider_preset_runpod_description,
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.runpod.ai/v2/{endpoint_id}/openai/v1"
    ),
    ProviderPreset(
        name = "Avian",
        descriptionRes = R.string.provider_preset_avian_description,
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.avian.io/v1"
    ),
    ProviderPreset(
        name = "Nebius",
        descriptionRes = R.string.provider_preset_nebius_description,
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.studio.nebius.ai/v1"
    ),
    ProviderPreset(
        name = "OVH",
        descriptionRes = R.string.provider_preset_ovh_description,
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.ai.cloud.ovh.net/v1"
    ),
    ProviderPreset(
        name = "Scaleway",
        descriptionRes = R.string.provider_preset_scaleway_description,
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.scaleway.ai/v1"
    ),
    ProviderPreset(
        name = "Lambda",
        descriptionRes = R.string.provider_preset_lambda_description,
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.lambdalabs.com/v1"
    ),
    ProviderPreset(
        name = "Baseten",
        descriptionRes = R.string.provider_preset_baseten_description,
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.baseten.co/v1"
    ),

    ProviderPreset(
        name = "01.AI Yi",
        descriptionRes = R.string.provider_preset_01_ai_yi_description,
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.01.ai/v1"
    ),
    ProviderPreset(
        name = "Zhipu AI",
        descriptionRes = R.string.provider_preset_zhipu_ai_description,
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://open.bigmodel.cn/api/paas/v4"
    ),
)

fun ProviderPreset.resolveDescription(context: Context): String {
    return context.getString(descriptionRes)
}

/**
 * Creates a ProviderSetting from a preset
 */
fun ProviderPreset.toProviderSetting(): ProviderSetting {
    return when (type) {
        ProviderSetting.OpenAI::class -> ProviderSetting.OpenAI(
            name = name,
            baseUrl = baseUrl,
            balanceOption = balanceOption,
            useResponseApi = useResponseApi,
            chatCompletionsPath = chatCompletionsPath
        )
        ProviderSetting.Google::class -> ProviderSetting.Google(
            name = name,
            baseUrl = baseUrl,
            balanceOption = balanceOption
        )
        ProviderSetting.Claude::class -> ProviderSetting.Claude(
            name = name,
            baseUrl = baseUrl,
            balanceOption = balanceOption
        )
        ProviderSetting.OpenAICodex::class -> ProviderSetting.OpenAICodex(name = name)
        else -> ProviderSetting.OpenAI(
            name = name,
            baseUrl = baseUrl,
            balanceOption = balanceOption
        )
    }
}
