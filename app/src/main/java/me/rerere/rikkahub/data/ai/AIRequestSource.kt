package me.rerere.rikkahub.data.ai

enum class AIRequestSource {
    CHAT,
    TITLE_SUMMARY,
    CONTEXT_SUMMARY,
    CHAT_SUGGESTION,
    GROUP_CHAT_ROUTING,
    WELCOME_PHRASES,
    MEMORY_CONSOLIDATION,
    MEMORY_EMBEDDING,
    MEMORY_RETRIEVAL,
    TOOL_RESULT_EMBEDDING,
    TOOL_RESULT_RAG,
    TRANSLATION,
    OCR,
    DOCUMENT_SUMMARY,
    SCHEDULED_MESSAGE,
    SPONTANEOUS,
    MODEL_NAME_GENERATION,
    SEARCH_AGENT,
    OTHER,
}

fun AIRequestSource.displayNameZh(): String {
    return when (this) {
        AIRequestSource.CHAT -> "聊天"
        AIRequestSource.TITLE_SUMMARY -> "标题总结"
        AIRequestSource.CONTEXT_SUMMARY -> "上下文总结"
        AIRequestSource.CHAT_SUGGESTION -> "聊天建议"
        AIRequestSource.GROUP_CHAT_ROUTING -> "群聊路由"
        AIRequestSource.WELCOME_PHRASES -> "欢迎词"
        AIRequestSource.MEMORY_CONSOLIDATION -> "记忆整合"
        AIRequestSource.MEMORY_EMBEDDING -> "记忆嵌入"
        AIRequestSource.MEMORY_RETRIEVAL -> "记忆检索"
        AIRequestSource.TOOL_RESULT_EMBEDDING -> "工具结果嵌入"
        AIRequestSource.TOOL_RESULT_RAG -> "工具结果检索"
        AIRequestSource.TRANSLATION -> "翻译"
        AIRequestSource.OCR -> "OCR"
        AIRequestSource.DOCUMENT_SUMMARY -> "文件摘要"
        AIRequestSource.SCHEDULED_MESSAGE -> "定时消息"
        AIRequestSource.SPONTANEOUS -> "主动通知"
        AIRequestSource.MODEL_NAME_GENERATION -> "模型名称生成"
        AIRequestSource.SEARCH_AGENT -> "搜索子代理"
        AIRequestSource.OTHER -> "其他"
    }
}
