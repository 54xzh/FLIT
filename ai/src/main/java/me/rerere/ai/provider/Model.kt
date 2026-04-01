package me.rerere.ai.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

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
    val iconUrl: String? = null, // Remote icon URL
    val providerSlug: String? = null, // Provider slug for LobeHub CDN icons (e.g., "anthropic")
    val customIconUri: String? = null, // User-selected custom icon URI
    val imageGenerationMethod: ImageGenerationMethod? = null, // Only for IMAGE type models
)

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

