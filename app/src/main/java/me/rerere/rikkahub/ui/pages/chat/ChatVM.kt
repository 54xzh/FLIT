package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.ConversationReadPosition
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.datastore.getConversationReadPosition
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.model.id
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.hooks.writeStringPreference
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.rikkahub.utils.createChatFilesByContents
import me.rerere.rikkahub.utils.deleteChatFiles
import me.rerere.rikkahub.utils.toLocalString
import java.time.LocalDate
import java.time.ZoneId
import kotlin.uuid.Uuid

private const val TAG = "ChatVM"

class ChatVM(
    id: String,
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val chatService: ChatService,
    val updateChecker: UpdateChecker,
    private val analytics: FirebaseAnalytics,
    private val appScope: me.rerere.rikkahub.AppScope
) : ViewModel() {
    private val _conversationId: Uuid = Uuid.parse(id)
    val conversationId: Uuid
        get() = _conversationId
    val conversation: StateFlow<Conversation> = chatService.getConversationFlow(_conversationId)

    private val _conversationInitialized = MutableStateFlow(false)
    val conversationInitialized: StateFlow<Boolean> = _conversationInitialized.asStateFlow()

    // 异步任务 (从ChatService获取，响应式)
    val conversationJob: StateFlow<Job?> =
        chatService
            .getGenerationJobStateFlow(_conversationId)
            .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val conversationJobs = chatService
        .getConversationJobs()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyMap())

    // Track recently restored conversations for fade-in animation
    val recentlyRestoredIds: StateFlow<Set<Uuid>> = chatService.recentlyRestoredIds

    // Track recently restored message nodes for fade-in animation
    private val _recentlyRestoredNodeIds = MutableStateFlow<Set<Uuid>>(emptySet())
    val recentlyRestoredNodeIds: StateFlow<Set<Uuid>> = _recentlyRestoredNodeIds

    fun markNodesAsRestored(nodeIds: Set<Uuid>) {
        _recentlyRestoredNodeIds.value = _recentlyRestoredNodeIds.value + nodeIds
        viewModelScope.launch {
            kotlinx.coroutines.delay(1000)
            _recentlyRestoredNodeIds.value = _recentlyRestoredNodeIds.value - nodeIds
        }
    }

    init {
        // 添加对话引用
        chatService.addConversationReference(_conversationId)

        // 初始化对话
        viewModelScope.launch {
            var initializedOk = false
            try {
                initializedOk = chatService.initializeConversation(_conversationId)
            } finally {
                _conversationInitialized.value = true
            }

            // 记住对话ID, 方便下次启动恢复
            // 如果初始化失败（例如：对话过大/损坏导致无法加载），清空该值避免下次启动 crash loop。
            if (initializedOk) {
                context.writeStringPreference("lastConversationId", _conversationId.toString())
            } else {
                context.writeStringPreference("lastConversationId", null)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // 移除对话引用
        chatService.removeConversationReference(_conversationId)
    }

    // 用户设置
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
    val conversationReadPosition: StateFlow<ConversationReadPosition?> = settings
        .map { current -> current.getConversationReadPosition(_conversationId) }
        .stateIn(viewModelScope, SharingStarted.Lazily, null)
    private val _loadingOlderHistory = MutableStateFlow(false)
    val loadingOlderHistory: StateFlow<Boolean> = _loadingOlderHistory.asStateFlow()

    // 网络搜索 - 从当前助手的searchMode派生
    val enableWebSearch = settings.map { settings ->
        val assistant = settings.assistants.find { it.id == settings.assistantId }
        when (assistant?.searchMode) {
            is me.rerere.rikkahub.data.model.AssistantSearchMode.Off -> false
            is me.rerere.rikkahub.data.model.AssistantSearchMode.BuiltIn -> true
            is me.rerere.rikkahub.data.model.AssistantSearchMode.Provider -> true
            is me.rerere.rikkahub.data.model.AssistantSearchMode.MultiProvider -> true
            null -> false
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, false)
    
    // 获取当前助手的searchMode
    val currentSearchMode = settings.map { settings ->
        val assistant = settings.assistants.find { it.id == settings.assistantId }
        assistant?.searchMode ?: me.rerere.rikkahub.data.model.AssistantSearchMode.Off
    }.stateIn(viewModelScope, SharingStarted.Lazily, me.rerere.rikkahub.data.model.AssistantSearchMode.Off)
    
    // 更新当前助手的searchMode
    fun updateAssistantSearchMode(searchMode: me.rerere.rikkahub.data.model.AssistantSearchMode) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                val assistantId = settings.assistantId
                settings.copy(
                    assistants = settings.assistants.map {
                        if (it.id == assistantId) {
                            it.copy(searchMode = searchMode)
                        } else {
                            it
                        }
                    }
                )
            }
        }
    }

    // 搜索关键词
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    // 聊天列表 (使用 Paging 分页加载)
    val conversations: Flow<PagingData<ConversationListItem>> =
        combine(
            settings.map { it.chatTarget.id }.distinctUntilChanged(),
            _searchQuery
        ) { targetId, query -> targetId to query }
            .flatMapLatest { (targetId, query) ->
                // 根据搜索关键词决定使用哪个数据源
                if (query.isBlank()) {
                    conversationRepo.getConversationsOfAssistantPaging(targetId)
                } else {
                    conversationRepo.searchConversationsOfAssistantPaging(targetId, query)
                }
            }
            .map { pagingData ->

                pagingData
                    .map { ConversationListItem.Item(it) }
                    .insertSeparators { before, after ->
                        when {
                            // 列表开头：检查第一项是否置顶
                            before == null && after is ConversationListItem.Item -> {
                                if (after.conversation.isPinned) {
                                    ConversationListItem.PinnedHeader
                                } else {
                                    val afterDate = after.conversation.updateAt
                                        .atZone(ZoneId.systemDefault())
                                        .toLocalDate()
                                    ConversationListItem.DateHeader(
                                        date = afterDate,
                                        label = getDateLabel(afterDate)
                                    )
                                }
                            }

                            // 中间项：检查置顶状态变化和日期变化
                            before is ConversationListItem.Item && after is ConversationListItem.Item -> {
                                // 从置顶切换到非置顶，显示日期头部
                                if (before.conversation.isPinned && !after.conversation.isPinned) {
                                    val afterDate = after.conversation.updateAt
                                        .atZone(ZoneId.systemDefault())
                                        .toLocalDate()
                                    ConversationListItem.DateHeader(
                                        date = afterDate,
                                        label = getDateLabel(afterDate)
                                    )
                                }
                                // 对于非置顶项，检查日期变化
                                else if (!after.conversation.isPinned) {
                                    val beforeDate = before.conversation.updateAt
                                        .atZone(ZoneId.systemDefault())
                                        .toLocalDate()
                                    val afterDate = after.conversation.updateAt
                                        .atZone(ZoneId.systemDefault())
                                        .toLocalDate()

                                    if (beforeDate != afterDate) {
                                        ConversationListItem.DateHeader(
                                            date = afterDate,
                                            label = getDateLabel(afterDate)
                                        )
                                    } else {
                                        null
                                    }
                                } else {
                                    null
                                }
                            }

                            else -> null
                        }
                    }
            }
            .catch { e ->
                e.printStackTrace()
                emit(PagingData.empty())
            }
            .cachedIn(viewModelScope)

    // 更新搜索关键词
    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    // 当前模型
    val currentChatModel = settings.map { settings ->
        settings.getCurrentChatModel()
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    // 错误流 (从ChatService获取)
    val errorFlow: SharedFlow<Throwable> = chatService.errorFlow

    // 生成完成 (从ChatService获取)
    val generationDoneFlow: SharedFlow<Uuid> = chatService.generationDoneFlow

    // MCP管理器 (从ChatService获取)
    val mcpManager = chatService.mcpManager

    // 更新设置
    fun updateSettings(newSettings: Settings) {
        viewModelScope.launch {
            val oldSettings = settings.value
            // 检查用户头像是否有变化，如果有则删除旧头像
            checkUserAvatarDelete(oldSettings, newSettings)
            settingsStore.update(newSettings)
        }
    }

    // 检查用户头像删除
    private fun checkUserAvatarDelete(oldSettings: Settings, newSettings: Settings) {
        val oldAvatar = oldSettings.displaySetting.userAvatar
        val newAvatar = newSettings.displaySetting.userAvatar

        if (oldAvatar is Avatar.Image && oldAvatar != newAvatar) {
            context.deleteChatFiles(listOf(oldAvatar.url.toUri()))
        }
    }

    // 设置聊天模型
    fun setChatModel(assistant: Assistant, model: Model) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    assistants = settings.assistants.map {
                        if (it.id == assistant.id) {
                            it.copy(
                                chatModelId = model.id
                            )
                        } else {
                            it
                        }
                    })
            }
        }
    }

    // Update checker
    private val updateCheckTrigger = MutableStateFlow(0)
    val updateState = updateCheckTrigger
        .flatMapLatest { updateChecker.checkUpdate() }
        .stateIn(viewModelScope, SharingStarted.Lazily, UiState.Loading)

    fun retryUpdateCheck() {
        updateCheckTrigger.value = updateCheckTrigger.value + 1
    }

    /**
     * 处理消息发送
     *
     * @param content 消息内容
     * @param answer 是否触发消息生成，如果为false，则仅添加消息到消息列表中
     * @param isTemporaryChat 是否为临时对话（不保存历史、不使用记忆）
     */
    fun setPendingUiWelcomePhraseForAppContext(welcomePhrase: String) {
        chatService.setPendingUiWelcomePhraseForAppContext(_conversationId, welcomePhrase)
    }

    fun respondToolApproval(toolCallId: String, approved: Boolean) {
        chatService.respondToolApproval(
            conversationId = _conversationId,
            toolCallId = toolCallId,
            approved = approved,
        )
    }

    fun handleMessageSend(
        content: List<UIMessagePart>,
        answer: Boolean = true,
        isTemporaryChat: Boolean = false,
        groupChatSpeakerSeatIdsOverride: List<Uuid>? = null,
    ) {
        if (content.isEmptyInputMessage()) return
        analytics.logEvent("ai_send_message", null)

        val assistant = settings.value.assistants.find { it.id == settings.value.assistantId }
        val processedContent = if (assistant != null) {
            content.map { part ->
                when (part) {
                    is UIMessagePart.Text -> {
                        part.copy(
                            text = part.text.replaceRegexes(
                                assistant = assistant,
                                scope = AssistantAffectScope.USER,
                                visual = false
                            )
                        )
                    }

                    else -> part
                }
            }
        } else {
            content
        }

        chatService.sendMessage(
            conversationId = _conversationId,
            content = processedContent,
            answer = answer,
            isTemporaryChat = isTemporaryChat,
            groupChatSpeakerSeatIdsOverride = groupChatSpeakerSeatIdsOverride,
        )
    }

    fun handleMessageEdit(parts: List<UIMessagePart>, messageId: Uuid) {
        if (parts.isEmptyInputMessage()) return
        analytics.logEvent("ai_edit_message", null)

        val assistant = settings.value.assistants.find { it.id == settings.value.assistantId }
        val processedParts = if (assistant != null) {
            parts.map { part ->
                when (part) {
                    is UIMessagePart.Text -> {
                        part.copy(
                            text = part.text.replaceRegexes(
                                assistant = assistant,
                                scope = AssistantAffectScope.USER,
                                visual = false
                            )
                        )
                    }

                    else -> part
                }
            }
        } else {
            parts
        }

        val newConversation = conversation.value.copy(
            messageNodes = conversation.value.messageNodes.map { node ->
                if (!node.messages.any { it.id == messageId }) {
                    return@map node // 如果这个node没有这个消息，则不修改
                }
                node.copy(
                    messages = node.messages + UIMessage(
                        role = node.role,
                        parts = processedParts,
                    ), selectIndex = node.messages.size
                )
            },
        )
        viewModelScope.launch {
            chatService.saveConversation(_conversationId, newConversation)
        }
    }

    fun handleMessageTruncate() {
        viewModelScope.launch {
            val currentConversation = conversation.value
            val absoluteLastTruncateIndex = currentConversation.loadedNodeStartIndex + currentConversation.messageNodes.lastIndex + 1
            // 如果截断在最后一个索引，则取消截断，否则更新 truncateIndex 到最后一个截断位置
            val newConversation = conversation.value.copy(
                truncateIndex = if (currentConversation.truncateIndex == absoluteLastTruncateIndex) {
                    -1
                } else {
                    absoluteLastTruncateIndex
                },
                title = "",
                chatSuggestions = emptyList(), // 清空建议
            )
            chatService.saveConversation(conversationId = _conversationId, conversation = newConversation)
        }
    }

    suspend fun forkMessage(message: UIMessage): Conversation {
        val sourceConversation = conversation.value
        val forkEndIndex = sourceConversation.messageNodes
            .indexOfFirst { node -> node.messages.any { it.id == message.id } }
            .takeIf { it >= 0 }
            ?: sourceConversation.messageNodes.lastIndex

        val nodesToCopy = if (forkEndIndex >= 0) {
            sourceConversation.messageNodes.subList(0, forkEndIndex + 1)
        } else {
            emptyList()
        }

        val nodes = withContext(Dispatchers.IO) {
            nodesToCopy.map { messageNode ->
                messageNode.copy(
                    messages = messageNode.messages.map { msg ->
                        msg.copy(
                            parts = msg.parts.map { part ->
                                when (part) {
                                    is UIMessagePart.Image -> {
                                        val url = part.url
                                        if (url.startsWith("file:")) {
                                            val copied = context.createChatFilesByContents(
                                                listOf(url.toUri())
                                            ).firstOrNull()
                                            if (copied != null) part.copy(url = copied.toString()) else part
                                        } else part
                                    }

                                    is UIMessagePart.Document -> {
                                        val url = part.url
                                        if (url.startsWith("file:")) {
                                            val copied = context.createChatFilesByContents(
                                                listOf(url.toUri())
                                            ).firstOrNull()
                                            if (copied != null) part.copy(url = copied.toString()) else part
                                        } else part
                                    }

                                    is UIMessagePart.Video -> {
                                        val url = part.url
                                        if (url.startsWith("file:")) {
                                            val copied = context.createChatFilesByContents(
                                                listOf(url.toUri())
                                            ).firstOrNull()
                                            if (copied != null) part.copy(url = copied.toString()) else part
                                        } else part
                                    }

                                    is UIMessagePart.Audio -> {
                                        val url = part.url
                                        if (url.startsWith("file:")) {
                                            val copied = context.createChatFilesByContents(
                                                listOf(url.toUri())
                                            ).firstOrNull()
                                            if (copied != null) part.copy(url = copied.toString()) else part
                                        } else part
                                    }

                                    else -> part
                                }
                            }
                        )
                    }
                )
            }
        }

        val sourceTitle = sourceConversation.title.ifBlank {
            context.getString(R.string.chat_page_new_chat)
        }
        val forkTitle = context.getString(R.string.chat_page_fork_title, sourceTitle)
        val newConversation = Conversation(
            id = Uuid.random(),
            assistantId = sourceConversation.assistantId,
            title = forkTitle,
            messageNodes = nodes,
        )
        chatService.saveConversation(newConversation.id, newConversation)
        return newConversation
    }

    fun deleteMessage(message: UIMessage) {
        val relatedMessages = collectRelatedMessages(message)
        deleteMessageInternal(message)
        relatedMessages.forEach { deleteMessageInternal(it) }
        saveConversationAsync()
    }

    private fun deleteMessageInternal(message: UIMessage) {
        val conversation = conversation.value
        // Use ID-based lookup instead of object equality to avoid issues after recomposition
        val node = conversation.getMessageNodeByMessageId(message.id) ?: return
        val nodeIndex = conversation.messageNodes.indexOf(node)
        if (nodeIndex == -1) return
        val newConversation = if (node.messages.size == 1) {
            conversation.copy(
                messageNodes = conversation.messageNodes.filterIndexed { index, _ -> index != nodeIndex })
        } else {
            val updatedNodes = conversation.messageNodes.mapNotNull { n ->
                val newMessages = n.messages.filter { it.id != message.id }
                if (newMessages.isEmpty()) {
                    null
                } else {
                    val newSelectIndex = if (n.selectIndex >= newMessages.size) {
                        newMessages.lastIndex
                    } else {
                        n.selectIndex
                    }
                    n.copy(
                        messages = newMessages,
                        selectIndex = newSelectIndex
                    )
                }
            }
            conversation.copy(messageNodes = updatedNodes)
        }
        viewModelScope.launch {
            chatService.saveConversation(_conversationId, newConversation)
        }
    }

    private fun collectRelatedMessages(message: UIMessage): List<UIMessage> {
        val currentMessages = conversation.value.currentMessages
        // Use ID-based lookup instead of object equality
        val index = currentMessages.indexOfFirst { it.id == message.id }
        if (index == -1) return emptyList()

        val relatedMessages = hashSetOf<UIMessage>()
        for (i in index - 1 downTo 0) {
            if (currentMessages[i].hasPart<UIMessagePart.ToolCall>() || currentMessages[i].hasPart<UIMessagePart.ToolResult>()) {
                relatedMessages.add(currentMessages[i])
            } else {
                break
            }
        }
        for (i in index + 1 until currentMessages.size) {
            if (currentMessages[i].hasPart<UIMessagePart.ToolCall>() || currentMessages[i].hasPart<UIMessagePart.ToolResult>()) {
                relatedMessages.add(currentMessages[i])
            } else {
                break
            }
        }
        return relatedMessages.toList()
    }

    fun regenerateAtMessage(
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        analytics.logEvent("ai_regenerate_at_message", null)
        chatService.regenerateAtMessage(_conversationId, message, regenerateAssistantMsg)
    }

    fun continueAtMessage(message: UIMessage) {
        analytics.logEvent("ai_continue_at_message", null)
        chatService.continueAtMessage(_conversationId, message)
    }

    suspend fun loadOlderHistoryNodes(limit: Int = 120): Int {
        if (_loadingOlderHistory.value) return 0
        _loadingOlderHistory.value = true
        return try {
            chatService.loadOlderHistoryNodes(
                conversationId = _conversationId,
                limit = limit,
            )
        } finally {
            _loadingOlderHistory.value = false
        }
    }

    fun cancelGenerationByUser() {
        analytics.logEvent("ai_cancel_generation", null)
        chatService.cancelGenerationByUser(_conversationId)
    }

    fun saveConversationAsync() {
        viewModelScope.launch {
            chatService.saveConversation(_conversationId, conversation.value)
        }
    }

    fun updateConversationReadPosition(nodeId: Uuid, offset: Int, itemIndex: Int = 0) {
        val conversationKey = _conversationId.toString()
        val normalizedOffset = offset.coerceAtLeast(0)
        val normalizedItemIndex = itemIndex.coerceAtLeast(0)
        val newPosition = ConversationReadPosition(
            nodeId = nodeId.toString(),
            offset = normalizedOffset,
            updatedAt = System.currentTimeMillis(),
            itemIndex = normalizedItemIndex,
        )

        viewModelScope.launch {
            settingsStore.update { current ->
                val existing = current.conversationReadPositions[conversationKey]
                if (!shouldPersistConversationReadPosition(existing, newPosition)) {
                    current
                } else {
                    current.copy(
                        conversationReadPositions = current.conversationReadPositions + (
                            conversationKey to newPosition
                        )
                    )
                }
            }
        }
    }

    fun setConversationAssistant(assistantId: Uuid) {
        chatService.setConversationAssistant(_conversationId, assistantId)
    }

    fun updateTitle(title: String) {
        viewModelScope.launch {
            val updatedConversation = conversation.value.copy(title = title)
            chatService.saveConversation(_conversationId, updatedConversation)
        }
    }

    fun deleteConversation(conversation: Conversation) {
        chatService.deleteConversation(conversation)
    }

    fun undoDeleteConversation(conversationId: Uuid) {
        chatService.undoDeleteConversation(conversationId)
    }

    fun updatePinnedStatus(conversation: Conversation) {
        viewModelScope.launch {
            conversationRepo.togglePinStatus(conversation.id, conversation.isPinned)
        }
    }

    fun generateTitle(conversation: Conversation, force: Boolean = false) {
        viewModelScope.launch {
            val conversationFull = conversationRepo.getConversationById(conversation.id) ?: return@launch
            chatService.generateTitle(_conversationId, conversationFull, force)
        }
    }

    fun consolidateConversation(conversation: Conversation) {
        viewModelScope.launch {
            // Mark conversation as not consolidated so it will be picked up by the worker
            conversationRepo.markAsNotConsolidated(conversation.id)
            
            // Trigger a consolidation run with specific conversation ID
            val request = androidx.work.OneTimeWorkRequestBuilder<me.rerere.rikkahub.service.MemoryConsolidationWorker>()
                .setInputData(
                    androidx.work.workDataOf(
                        "FORCE_CONVERSATION_ID" to conversation.id.toString()
                    )
                )
                .build()
            androidx.work.WorkManager.getInstance(context).enqueue(request)
        }
    }

    fun generateSuggestion(conversation: Conversation) {
        viewModelScope.launch {
            chatService.generateSuggestion(_conversationId, conversation)
        }
    }

    fun updateConversation(newConversation: Conversation) {
        viewModelScope.launch {
            chatService.saveConversation(_conversationId, newConversation)
        }
    }

    // Context Refresh - summarize conversation and update context
    suspend fun refreshContext(): ChatService.ContextRefreshResult {
        return chatService.summarizeAndRefresh(_conversationId)
    }

    private fun getDateLabel(date: LocalDate): String {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)

        return when (date) {
            today -> context.getString(R.string.chat_page_today)
            yesterday -> context.getString(R.string.chat_page_yesterday)
            else -> date.toLocalString(date.year != today.year)
        }
    }
}
