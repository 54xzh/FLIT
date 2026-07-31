package me.rerere.ai.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.ai.ui.ResolutionTier
import kotlin.uuid.Uuid

@Serializable
enum class QuotaResetPeriod {
    @SerialName("daily") DAILY,
    @SerialName("weekly") WEEKLY,
    @SerialName("monthly") MONTHLY,
}

@Serializable
data class ModelQuota(
    val enabled: Boolean = false,
    val tokenLimit: Long = 0,
    val reminderPercentage: Float = 80f,
    val sharedModelIds: Set<Uuid> = emptySet(),
    val resetPeriod: QuotaResetPeriod = QuotaResetPeriod.MONTHLY,
    val resetHour: Int = 0,
    val resetMinute: Int = 0,
    val resetDayOfWeek: Int = 1,
    val resetDayOfMonth: Int = 1,
)

@Serializable
data class Model(
    val modelId: String = "",
    val displayName: String = "",
    val id: Uuid = Uuid.random(),
    val type: ModelType = ModelType.CHAT,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBodies: List<CustomBody> = emptyList(),
    val inputModalities: List<Modality> = listOf(Modality.TEXT),
    val outputModalities: List<Modality> = listOf(Modality.TEXT),
    val abilities: List<ModelAbility> = emptyList(),
    val tools: Set<BuiltInTools> = emptySet(),
    val providerOverwrite: ProviderSetting? = null,
    val iconUrl: String? = null,
    val providerSlug: String? = null,
    val customIconUri: String? = null,
    val imageGenerationMethod: ImageGenerationMethod? = null,
    val imageGenCapabilities: ImageGenCapabilities? = null,
    val quota: ModelQuota? = null,
    val capabilitySource: ModelCapabilitySource = ModelCapabilitySource.MANUAL,
)

// 图片生成模型的能力描述: 支持哪些宽高比/分辨率档位/质量档
// null = 退回旧的三档枚举兜底逻辑, 保证老模型不破坏
@Serializable
data class ImageGenCapabilities(
    val aspectRatios: List<String> = listOf("1:1", "16:9", "9:16"),
    val resolutionTiers: List<ResolutionTier> = listOf(ResolutionTier.T1K),
    val supportsQuality: Boolean = false,
)

// OpenAI gpt-image-2 系: 全比例 + 全档位 + 支持质量档 (API 有 quality 字段)
val GPT_IMAGE_2_CAPABILITIES = ImageGenCapabilities(
    aspectRatios = listOf("1:1", "16:9", "9:16", "4:3", "3:4", "3:2", "2:3", "21:9"),
    resolutionTiers = listOf(ResolutionTier.T1K, ResolutionTier.T2K, ResolutionTier.T4K),
    supportsQuality = true,
)

// Gemini 系图片模型 (nano-banana / gemini-3 image 等): 全比例 + 全档位, 但不支持质量档
// (Google 的 imageConfig 只有 aspectRatio + imageSize, 没有 quality 字段)
val GEMINI_IMAGE_CAPABILITIES = ImageGenCapabilities(
    aspectRatios = listOf("1:1", "16:9", "9:16", "4:3", "3:4", "3:2", "2:3", "21:9"),
    resolutionTiers = listOf(ResolutionTier.T1K, ResolutionTier.T2K, ResolutionTier.T4K),
    supportsQuality = false,
)

// 按 modelId 推断图片生成能力: 未显式配置 imageGenCapabilities 时, 对已知模型自动注入能力
// OpenAI gpt-image-2 系用字符串识别; Gemini 系用 ModelRegistry 的统一匹配 (覆盖 nano-banana / gemini-3 image / gemini-2.5 image)
// 其它模型返回 null 走兜底
fun Model.effectiveImageGenCapabilities(): ImageGenCapabilities? {
    if (imageGenCapabilities != null) return imageGenCapabilities
    val id = modelId.lowercase()
    return when {
        id.contains("gpt-image-2") -> GPT_IMAGE_2_CAPABILITIES
        me.rerere.ai.registry.ModelRegistry.IS_GEMINI_IMAGE_MODEL.getData(id) -> GEMINI_IMAGE_CAPABILITIES
        else -> null
    }
}

// 判断该模型是否应走 Gemini 的 generateContent 图片路径 (而非 Imagen 的 :predict)
// 与能力推断共用同一套 ModelRegistry 识别, 避免规则不一致
fun Model.isGeminiImageModel(): Boolean =
    me.rerere.ai.registry.ModelRegistry.IS_GEMINI_IMAGE_MODEL.getData(modelId)

// 是否支持 Pro 模式 (reasoning.mode=pro): 仅 GPT-5.6 系列
// 端点限制是 OpenAI 官方平台行为, 第三方 provider (OpenRouter 等) Chat Completions 也支持, 故只看模型
fun Model.supportsProMode(): Boolean =
    me.rerere.ai.registry.ModelRegistry.IS_PRO_MODE_MODEL.getData(modelId)

// 是否支持快速模式 (service_tier=fast): 基于 OpenAI Priority 定价表的白名单
// 排除长上下文 / -pro 变体 / 嵌入 / 微调模型
fun Model.supportsFastMode(): Boolean =
    me.rerere.ai.registry.ModelRegistry.IS_FAST_MODE_MODEL.getData(modelId)

@Serializable
enum class ModelType {
    CHAT,
    IMAGE,
    EMBEDDING,
}

@Serializable
enum class Modality {
    TEXT,
    IMAGE,
}

@Serializable
enum class ImageGenerationMethod {
    @SerialName("diffusion")
    DIFFUSION,      // Traditional diffusion models like DALL-E, Stable Diffusion
    @SerialName("multimodal")
    MULTIMODAL,     // Chat models with image output (GPT-4o, Gemini 2.0 Flash)
}

@Serializable
enum class ModelAbility {
    TOOL,
    REASONING,
}

@Serializable
enum class ModelCapabilitySource {
    AUTO,
    MANUAL,
}

// 模型(提供商)提供的内置工具选项
@Serializable
sealed class BuiltInTools {
    // https://ai.google.dev/gemini-api/docs/google-search?hl=zh-cn
    @Serializable
    @SerialName("search")
    data object Search : BuiltInTools()

    // https://docs.anthropic.com/en/docs/agents-and-tools/tool-use/web-search-tool
    @Serializable
    @SerialName("claude_web_search")
    data object ClaudeWebSearch : BuiltInTools()

    @Serializable
    @SerialName("claude_web_search_disabled")
    data object ClaudeWebSearchDisabled : BuiltInTools()

    // https://ai.google.dev/gemini-api/docs/url-context?hl=zh-cn
    @Serializable
    @SerialName("url_context")
    data object UrlContext : BuiltInTools()

    // https://docs.x.ai/developers/tools/web-search
    @Serializable
    @SerialName("grok_web_search")
    data object GrokWebSearch : BuiltInTools()

    // https://docs.x.ai/developers/tools/x-search
    @Serializable
    @SerialName("grok_x_search")
    data object GrokXSearch : BuiltInTools()
}
