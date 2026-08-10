package me.rerere.ai.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ImageGenerationResult
import me.rerere.ai.ui.ImageSizeOptions
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import java.math.BigDecimal

// 提供商实现
// 采用无状态设计，使用时除了需要传入需要的参数外，还需要传入provider setting作为参数
interface Provider<T : ProviderSetting> {
    suspend fun listModels(providerSetting: T): List<Model>

    suspend fun getBalance(providerSetting: T): String {
        return "TODO"
    }

    suspend fun generateText(
        providerSetting: T,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk

    suspend fun streamText(
        providerSetting: T,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk>

    suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): ImageGenerationResult

    suspend fun createEmbedding(
        providerSetting: T,
        input: List<String>,
        model: Model,
        callTimeoutMillis: Long? = null,
    ): List<List<Float>> {
        return emptyList()
    }

    fun buildEmbeddingRequestBodyForLog(
        providerSetting: T,
        input: List<String>,
        model: Model,
    ): String? {
        return null
    }
}

@Serializable
data class TextGenerationParams(
    val model: Model,
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    val tools: List<Tool> = emptyList(),
    val reasoningLevel: ReasoningLevel = ReasoningLevel.OFF,
    // Pro 模式 (reasoning.mode=pro): 质量增强, 两个端点都发, 端点能否处理交给上游兜底
    val proMode: Boolean = false,
    // 快速模式 (service_tier=fast): 算力池加速, 两个端点都发
    val fastMode: Boolean = false,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
    @Transient
    val onRequestBody: ((String) -> Unit)? = null,
)

@Serializable
data class ImageGenerationParams(
    val model: Model,
    val prompt: String,
    val numOfImages: Int = 1,
    val sizeOptions: ImageSizeOptions = ImageSizeOptions(),
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
)

@Serializable
data class CustomHeader(
    val name: String,
    val value: String
)

@Serializable
data class CustomBody(
    val key: String,
    val value: JsonElement
)
