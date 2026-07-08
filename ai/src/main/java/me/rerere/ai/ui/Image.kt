package me.rerere.ai.ui

import kotlinx.serialization.Serializable

@Serializable
data class ImageGenerationResult(
    val items: List<ImageGenerationItem>, // 一个item代表一个图片
)

@Serializable
data class ImageGenerationItem(
    val data: String,
    val mimeType: String,
)

// 旧的宽高比枚举, 保留用于兼容旧模型/旧数据
@Serializable
enum class ImageAspectRatio {
    SQUARE,
    LANDSCAPE,
    PORTRAIT;

    fun toRatioString(): String = when (this) {
        SQUARE -> "1:1"
        LANDSCAPE -> "16:9"
        PORTRAIT -> "9:16"
    }
}

// 分辨率档位: 1K / 2K / 4K, 由模型能力决定是否可用
@Serializable
enum class ResolutionTier {
    T1K,
    T2K,
    T4K;

    // Google nano-banana 系 API 期望的 "1K"/"2K"/"4K" 字符串
    fun toGoogleSize(): String = when (this) {
        T1K -> "1K"
        T2K -> "2K"
        T4K -> "4K"
    }
}

// gpt-image-2 等支持的渲染质量档
@Serializable
enum class ImageQuality {
    LOW,
    MEDIUM,
    HIGH;

    fun toOpenAIQuality(): String = name.lowercase()
}

// 统一的尺寸/分辨率选项, UI 选择后由各 Provider 翻译成自家 API 参数
@Serializable
data class ImageSizeOptions(
    val aspectRatio: String = "1:1",
    val resolutionTier: ResolutionTier = ResolutionTier.T1K,
    val quality: ImageQuality = ImageQuality.MEDIUM,
)