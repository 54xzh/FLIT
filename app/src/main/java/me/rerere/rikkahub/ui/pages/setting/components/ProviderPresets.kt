package me.rerere.rikkahub.ui.pages.setting.components

import me.rerere.ai.provider.BalanceOption
import me.rerere.ai.provider.ProviderSetting
import kotlin.reflect.KClass

/**
 * Data class representing a provider preset for quick setup
 */
data class ProviderPreset(
    val name: String,
    val description: String,
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
        description = "Creator of GPT models, industry-leading AI",
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.openai.com/v1",
        useResponseApi = false // Response API available but default off for compatibility
    ),
    ProviderPreset(
        name = "OpenAI Codex",
        description = "Use your ChatGPT account and Codex subscription quota",
        type = ProviderSetting.OpenAICodex::class,
        baseUrl = "https://chatgpt.com/backend-api/codex",
        requiresCodexLogin = true,
    ),
    ProviderPreset(
        name = "Google Gemini",
        description = "Multimodal AI with text, image, audio, and video",
        type = ProviderSetting.Google::class,
        baseUrl = "https://generativelanguage.googleapis.com/v1beta"
    ),
    ProviderPreset(
        name = "Anthropic Claude",
        description = "Safety-focused AI with strong reasoning",
        type = ProviderSetting.Claude::class,
        baseUrl = "https://api.anthropic.com/v1"
    ),
    ProviderPreset(
        name = "OpenRouter",
        description = "Access 300+ models via single API",
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
        description = "Cloud LLMs hosted by Ollama",
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://ollama.com/v1"
    ),
    ProviderPreset(
        name = "Vercel",
        description = "Unified API for 100+ models with auto-fallbacks",
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://ai-gateway.vercel.sh/v1"
    ),
    ProviderPreset(
        name = "Groq",
        description = "Ultra-fast inference for open models",
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.groq.com/openai/v1",
        useResponseApi = true // Groq supports OpenAI Responses API
    ),
    ProviderPreset(
        name = "DeepSeek",
        description = "Strong reasoning and coding AI",
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
        description = "50+ open-source models, fast inference",
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.together.xyz/v1"
    ),
    ProviderPreset(
        name = "Mistral",
        description = "European high-performance open models",
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.mistral.ai/v1"
    ),
    ProviderPreset(
        name = "Perplexity",
        description = "AI with real-time web search",
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.perplexity.ai"
    ),
    ProviderPreset(
        name = "Fireworks AI",
        description = "Fast, production-ready AI inference",
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.fireworks.ai/inference/v1"
    ),
    ProviderPreset(
        name = "Cohere",
        description = "Enterprise RAG AI (add models manually)",
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.cohere.ai/compatibility/v1"
    ),
    ProviderPreset(
        name = "xAI Grok",
        description = "Grok models from xAI",
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.x.ai/v1"
    ),
    ProviderPreset(
        name = "Cerebras",
        description = "Ultra-fast inference, 2000+ tokens/sec",
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.cerebras.ai/v1"
    ),
    ProviderPreset(
        name = "Novita",
        description = "200+ models, low-cost inference",
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
        description = "Aggregated AI with custom models",
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
        description = "Cost-effective open model hosting",
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.deepinfra.com/v1/openai"
    ),
    ProviderPreset(
        name = "Hyperbolic",
        description = "High-performance model inference",
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.hyperbolic.xyz/v1"
    ),
    ProviderPreset(
        name = "SiliconFlow",
        description = "Leading Chinese AI provider",
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
        description = "Jamba models (requires Maestro adapter)",
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.ai21.com/studio/v1"
    ),
    ProviderPreset(
        name = "Lepton",
        description = "Serverless AI inference platform",
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.lepton.ai/v1"
    ),
    ProviderPreset(
        name = "SambaNova",
        description = "Enterprise-grade AI inference",
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.sambanova.ai/v1"
    ),
    ProviderPreset(
        name = "Anyscale",
        description = "Scalable open-source model hosting",
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.anyscale.com/v1"
    ),
    ProviderPreset(
        name = "Cloudflare",
        description = "Edge AI (replace {account_id} in URL)",
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/v1"
    ),
    ProviderPreset(
        name = "Hugging Face",
        description = "Thousands of community models",
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://router.huggingface.co/v1"
    ),
    ProviderPreset(
        name = "NVIDIA NIM",
        description = "NVIDIA-optimized model inference",
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://integrate.api.nvidia.com/v1"
    ),
    ProviderPreset(
        name = "AiHubMix",
        description = "Aggregated AI provider",
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://aihubmix.com/v1"
    ),
    ProviderPreset(
        name = "Alibaba Qwen",
        description = "Qwen models from Alibaba Cloud",
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    ),
    ProviderPreset(
        name = "GLhf",
        description = "Free open-source LLMs",
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://glhf.chat/api/openai/v1"
    ),
    ProviderPreset(
        name = "Featherless",
        description = "Hugging Face models inference",
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.featherless.ai/v1"
    ),
    ProviderPreset(
        name = "Chutes",
        description = "vLLM-powered inference",
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.chutes.ai/v1"
    ),
    ProviderPreset(
        name = "Infermatic",
        description = "OpenAI-compatible API",
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.totalgpt.ai/v1"
    ),
    ProviderPreset(
        name = "RunPod",
        description = "GPU cloud (use your endpoint URL)",
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.runpod.ai/v2/{endpoint_id}/openai/v1"
    ),
    ProviderPreset(
        name = "Avian",
        description = "Fast model inference",
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.avian.io/v1"
    ),
    ProviderPreset(
        name = "Nebius",
        description = "AI cloud platform",
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.studio.nebius.ai/v1"
    ),
    ProviderPreset(
        name = "OVH",
        description = "European AI cloud",
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.ai.cloud.ovh.net/v1"
    ),
    ProviderPreset(
        name = "Scaleway",
        description = "European inference platform",
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.scaleway.ai/v1"
    ),
    ProviderPreset(
        name = "Lambda",
        description = "GPU cloud provider",
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.lambdalabs.com/v1"
    ),
    ProviderPreset(
        name = "Baseten",
        description = "Model deployment platform",
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.baseten.co/v1"
    ),

    ProviderPreset(
        name = "01.AI Yi",
        description = "Yi open models",
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://api.01.ai/v1"
    ),
    ProviderPreset(
        name = "Zhipu AI",
        description = "GLM models with vision, tools, and reasoning",
        type = ProviderSetting.OpenAI::class,
        baseUrl = "https://open.bigmodel.cn/api/paas/v4"
    ),
)

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
