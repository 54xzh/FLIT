package me.rerere.rikkahub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import kotlin.uuid.Uuid

@Serializable
enum class ToolResultHistoryMode {
    @SerialName("keep_all")
    KEEP_ALL,

    @SerialName("rag")
    RAG,

    @SerialName("discard")
    DISCARD,
}

@Serializable
enum class OverlayColorMode {
    @SerialName("auto")
    Auto,

    @SerialName("manual")
    Manual,
}

@Serializable
data class BackgroundOverlaySettings(
    val blurEnabled: Boolean = false,
    val blurRadius: Float = 10f,
    val overlayEnabled: Boolean = false,
    val overlayOpacity: Float = 0.4f,
    val overlayColorMode: OverlayColorMode = OverlayColorMode.Auto,
    val overlayColorArgb: Long = 0xFF000000,
    val overlayColorArgbLight: Long = 0xFFFFFFFF,
)

/**
 * Per-assistant UI settings. All nullable - null means "use global setting".
 */
@Serializable
data class AssistantUISettings(
    val showUserAvatar: Boolean? = null,
    val showAssistantAvatar: Boolean? = null,
    val showAssistantName: Boolean? = null,
    val showTokenUsage: Boolean? = null,
    val autoCloseThinking: Boolean? = null,
    val showMessageJumper: Boolean? = null,
    val messageJumperOnLeft: Boolean? = null,
    val fontSizeRatio: Float? = null,
    val codeBlockAutoWrap: Boolean? = null,
    val codeBlockAutoCollapse: Boolean? = null,
    val showContextStacks: Boolean? = null,
)

@Serializable
data class Assistant(
    val id: Uuid = Uuid.random(),
    val chatModelId: Uuid? = null, // 如果为null, 使用全局默认模型
    val backgroundModelId: Uuid? = null, // 用于后台检查的模型
    val searchMode: AssistantSearchMode = AssistantSearchMode.Off, // Search mode for this assistant
    val preferBuiltInSearch: Boolean = false, // If true, use built-in search when model supports it, otherwise fall back to searchMode
    val enableSearchAgent: Boolean = false, // Use a search sub-agent for external web search
    val embeddingModelId: Uuid? = null, // 用于生成嵌入的模型
    val name: String = "",
    val avatar: Avatar = Avatar.Dummy,
    val useAssistantAvatar: Boolean = true, // 使用助手头像替代模型头像
    val tags: List<Uuid> = emptyList(),
    val systemPrompt: String = "",
    val temperature: Float? = null,
    val topP: Float? = null,
    /** Limit how many recent chat messages are sent to model as context. `0` means no limit. */
    val contextMessageSize: Int = 0,
    val maxTokenUsage: Int = 81920, // 80k default
    val contextPriority: ContextPriority = ContextPriority.BALANCED,
    val summarizerModelId: Uuid? = null, // Model used for memory consolidation
    val contextSummarizerModelId: Uuid? = null, // Model used for context summarization
    val consolidationPrompt: String = "", // Memory consolidation prompt
    val contextSummaryPrompt: String = "", // Context summarization prompt
    val streamOutput: Boolean = true,
    val enableMemory: Boolean = false,
    val enableSessionMemory: Boolean = true,
    val useRagMemoryRetrieval: Boolean = true, // If true, use vector-based RAG. If false, inject all memories
    val ragSimilarityThreshold: Float = 0.45f, // Similarity threshold for RAG (0.0 = include all, 1.0 = only perfect matches)
    val ragLimit: Int = 5, // Maximum number of memories to retrieve via RAG
    val enableRecentChatsReference: Boolean = false, // Use chat episodes in memory
    val ragIncludeEpisodes: Boolean = true, // Include episodic memories in RAG
    val ragIncludeCore: Boolean = true, // Include core memories in RAG
    val enableRagLogging: Boolean = false, // Enable detailed RAG logging
    val enableMemoryConsolidation: Boolean = false, // Enable episodic memory creation from chats (requires RAG)

    // Spontaneous Notification Settings
    val notificationStartHour: Int = 7, // Hour when notifications can start (0-23)
    val notificationEndHour: Int = 22, // Hour when notifications must stop (0-23)
    val notificationFrequencyHours: Int = 4, // Minimum hours between notifications
    val lastNotificationTime: Long = 0L, // Timestamp of last notification
    val lastNotificationContent: String = "", // Content of last notification to avoid repetition
    val messageTemplate: String = "{{ message }}",
    val presetMessages: List<UIMessage> = emptyList(),

    val enableWelcomePhrases: Boolean = true,
    val welcomePhrases: List<String> = emptyList(),
    val lastWelcomePhrasesRequestEpochDay: Long = -1L,

    val quickMessages: List<QuickMessage> = emptyList(),
    val regexes: List<AssistantRegex> = emptyList(),
    val thinkingBudget: Int? = 1024,
    val maxTokens: Int? = null,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBodies: List<CustomBody> = emptyList(),
    val mcpServers: Set<Uuid> = emptySet(),
    val localTools: List<LocalToolOption> = emptyList(),
    val enabledSkillIds: Set<Uuid> = emptySet(), // Skills enabled for this assistant
    val enabledModeIds: Set<Uuid> = emptySet(), // Modes enabled by default for new chats of this assistant
    val background: String? = null,
    val backgroundOverlay: BackgroundOverlaySettings = BackgroundOverlaySettings(),
    val learningMode: Boolean = false,
    val enableSpontaneous: Boolean = false, // 是否启用自发消息
    val backgroundPrompt: String = "", // 后台任务提示词
    val spontaneousPrompt: String = "", // 自发消息的Prompt
    val enabledLorebookIds: Set<Uuid> = emptySet(), // Lorebooks enabled for this assistant

    // Context Management Settings
    val maxHistoryMessages: Int? = null, // null = unlimited (use token budgeting only)
    val enableHistorySummarization: Boolean = false, // Generate summaries of pruned messages
    val maxSearchResultsRetained: Int? = null, // null = keep all, e.g. 2 = keep last 2 search results
    val archiveImagesAfterMessageAge: Int? = null, // null = 关闭；N = 只保留最近 N 条消息中的图片，更旧的替换为 OCR 文字
    val archiveDocumentsAfterMessageAge: Int? = null, // null = 关闭；N = 只保留最近 N 条消息中的文件原文，更旧的替换为 AI 摘要
    val enableContextRefresh: Boolean = false, // Show Summarize Messages button in chat input
    val autoRegenerateSummary: Boolean = false, // Automatically summarize when maxHistoryMessages reached

    // Memory System Configuration & Stats
    val consolidationDelayMinutes: Int = 30, // Wait time before consolidating a chat
    val lastConsolidationTime: Long = 0L,
    val lastConsolidationResult: String = "",

    // Per-assistant UI customization (null = use global setting)
    val uiSettings: AssistantUISettings = AssistantUISettings(),
)

@Serializable
data class QuickMessage(
    val title: String = "",
    val content: String = "",
)

@Serializable
data class AssistantMemory(
    val id: Int,
    val content: String = "",
    val type: Int = 0, // 0: CORE, 1: EPISODIC
    val hasEmbedding: Boolean = false,
    val embeddingModelId: String? = null, // UUID of the embedding model used (for model mismatch detection)
    val timestamp: Long = 0L, // Timestamp of the memory (e.g. creation time or episode start time)
    val significance: Int? = null, // Significance score (1-10) for episodic memories, null for core memories
    val pinned: Boolean = false,
)

@Serializable
enum class AssistantAffectScope {
    USER,
    ASSISTANT,
}

@Serializable
enum class ContextPriority {
    CHAT_HISTORY,
    BALANCED,
    MEMORIES
}

@Serializable
sealed class AssistantSearchMode {
    @Serializable
    @SerialName("off")
    data object Off : AssistantSearchMode()
    
    @Serializable
    @SerialName("builtin")
    data object BuiltIn : AssistantSearchMode()
    
    @Serializable
    @SerialName("provider")
    data class Provider(val index: Int) : AssistantSearchMode()

    @Serializable
    @SerialName("multi_provider")
    data class MultiProvider(val indices: List<Int>) : AssistantSearchMode()
}

fun buildAssistantProviderSearchMode(
    indices: List<Int>,
): AssistantSearchMode {
    val sanitized = indices
        .asSequence()
        .distinct()
        .sorted()
        .toList()

    return when (sanitized.size) {
        0 -> AssistantSearchMode.Off
        1 -> AssistantSearchMode.Provider(sanitized.first())
        else -> AssistantSearchMode.MultiProvider(sanitized)
    }
}

@Serializable
data class AssistantRegex(
    val id: Uuid,
    val name: String = "",
    val enabled: Boolean = true,
    val findRegex: String = "", // 正则表达式
    val replaceString: String = "", // 替换字符串
    val affectingScope: Set<AssistantAffectScope> = setOf(),
    val visualOnly: Boolean = false, // 是否仅在视觉上影响
)

fun String.replaceRegexes(
    assistant: Assistant?,
    scope: AssistantAffectScope,
    visual: Boolean = false
): String {
    if (assistant == null) return this
    if (assistant.regexes.isEmpty()) return this
    return assistant.regexes.fold(this) { acc, regex ->
        if (regex.enabled && regex.visualOnly == visual && regex.affectingScope.contains(scope)) {
            try {
                val result = acc.replace(
                    regex = Regex(regex.findRegex),
                    replacement = regex.replaceString,
                )
                // println("Regex: ${regex.findRegex} -> ${result}")
                result
            } catch (e: Exception) {
                e.printStackTrace()
                // 如果正则表达式格式错误，返回原字符串
                acc
            }
        } else {
            acc
        }
    }
}

@Serializable
sealed class PromptInjection {
    @Serializable
    @SerialName("mode")
    data class ModeInjection(
        val name: String,
        val priority: Int,
        val prompt: String,
    ) : PromptInjection()

    @Serializable
    @SerialName("regex")
    data class RegexInjection(
        val name: String,
        val regex: String,
    ) : PromptInjection()
}
