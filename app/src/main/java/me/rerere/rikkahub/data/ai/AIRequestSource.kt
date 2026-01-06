package me.rerere.rikkahub.data.ai

enum class AIRequestSource {
    CHAT,
    TITLE_SUMMARY,
    CHAT_SUGGESTION,
    GROUP_CHAT_ROUTING,
    WELCOME_PHRASES,
    MEMORY_CONSOLIDATION,
    MEMORY_REFLECTION,
    MEMORY_EMBEDDING,
    MEMORY_RETRIEVAL,
    TOOL_RESULT_EMBEDDING,
    TOOL_RESULT_RAG,
    TRANSLATION,
    OCR,
    SCHEDULED_MESSAGE,
    SPONTANEOUS,
    OTHER,
}

fun AIRequestSource.displayNameZh(): String {
    return when (this) {
        AIRequestSource.CHAT -> "聊天"
        AIRequestSource.TITLE_SUMMARY -> "标题总结"
        AIRequestSource.CHAT_SUGGESTION -> "聊天建议"
        AIRequestSource.GROUP_CHAT_ROUTING -> "群聊路由"
        AIRequestSource.WELCOME_PHRASES -> "欢迎词"
        AIRequestSource.MEMORY_CONSOLIDATION -> "记忆整合"
        AIRequestSource.MEMORY_REFLECTION -> "记忆反思"
        AIRequestSource.MEMORY_EMBEDDING -> "记忆嵌入"
        AIRequestSource.MEMORY_RETRIEVAL -> "记忆检索"
        AIRequestSource.TOOL_RESULT_EMBEDDING -> "工具结果嵌入"
        AIRequestSource.TOOL_RESULT_RAG -> "工具结果检索"
        AIRequestSource.TRANSLATION -> "翻译"
        AIRequestSource.OCR -> "OCR"
        AIRequestSource.SCHEDULED_MESSAGE -> "定时消息"
        AIRequestSource.SPONTANEOUS -> "主动通知"
        AIRequestSource.OTHER -> "其他"
    }
}
