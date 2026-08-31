package me.rerere.rikkahub.ui.pages.chat

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.net.toFile
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.ChatReadPositionStore
import me.rerere.rikkahub.data.datastore.ConversationReadPosition
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.datastore.sanitizeConversationLargeContextWarningShownAt
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.ChatTarget
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.model.id
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryConsolidationScheduler
import me.rerere.rikkahub.data.repository.ModelQuotaRepository
import me.rerere.rikkahub.data.repository.QuotaUsageResult
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.hooks.writeStringPreference
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.rikkahub.utils.createChatFilesByContents
import me.rerere.rikkahub.utils.createChatUploadFile
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
    private val readPositionStore: ChatReadPositionStore,
    private val conversationRepo: ConversationRepository,
    private val chatService: ChatService,
    val updateChecker: UpdateChecker,
    private val analytics: FirebaseAnalytics,
    private val appScope: me.rerere.rikkahub.AppScope,
    private val modelQuotaRepo: ModelQuotaRepository,
) : ViewModel() {
    private val _conversationId: Uuid = Uuid.parse(id)
    private val memoryConsolidationScheduler = MemoryConsolidationScheduler(context)
    val conversationId: Uuid
        get() = _conversationId
    val conversation: StateFlow<Conversation> = chatService.getConversationFlow(_conversationId)

    private val _conversationInitialized = MutableStateFlow(false)
    val conversationInitialized: StateFlow<Boolean> = _conversationInitialized.asStateFlow()
    private val _conversationExistsInStorage = MutableStateFlow(false)
    val conversationExistsInStorage: StateFlow<Boolean> = _conversationExistsInStorage.asStateFlow()

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

    val manualMemoryConsolidationConversationIds: StateFlow<Set<Uuid>> = memoryConsolidationScheduler
        .observeRunningConversationIds()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptySet())

    // Track recently restored message nodes for fade-in animation
    private val _recentlyRestoredNodeIds = MutableStateFlow<Set<Uuid>>(emptySet())
    val recentlyRestoredNodeIds: StateFlow<Set<Uuid>> = _recentlyRestoredNodeIds

    // 删除消息串行化: 删除流程内部 cancelGenerationAndAwait 的 join() 是挂起点, 挂起期间撤销协程
    // 可能在同一 Main 线程先跑, 导致"先恢复后删除"的竞态. 用 deleteMutex 保证删除/恢复互斥,
    // deleteJob 持有当前删除协程, 撤销时先 cancel 它, 阻止删除协程恢复后用旧快照继续删.
    private val deleteMutex = Mutex()
    private var deleteJob: Job? = null

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
                val result = chatService.initializeConversationWithResult(_conversationId)
                initializedOk = result.initialized
                _conversationExistsInStorage.value = result.existsInStorage
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
        // 切会话会销毁 ChatVM, 取消 viewModelScope 内尚未完成的 saveCurrentConversation.
        // 若编辑发送后立即切会话, 还排在 viewModelScope 里没开始跑的保存协程会被直接丢弃,
        // 而 removeConversationReference 后 500ms cleanupConversation 又会删内存 StateFlow,
        // 内存改动就此丢失——表现为"编辑后切会话即丢会话".
        //
        // 用 appScope (不随 viewModelScope 取消) 兜底落盘, 落盘完成后再移除引用.
        // 关键: 用 flushConversationToDb 而非 saveConversation——
        //   1) 它读「内存当前态」而非退出前捕获的旧快照, 避免旧快照盖掉新页面/生成已写入的
        //      更新版本 (无版本号保护下, 用当前态是防旧盖新的根本手段);
        //   2) 它只写 DB 不回写内存, 避免覆盖正在被生成/新页面推进的内存态;
        //   3) NonCancellable 守护 DB 写入, 保证 viewModelScope 取消后落库仍跑完;
        //   4) 内置 id 校验, 若 500ms cleanup 已删内存 (读到占位会话 id 不匹配) 则跳过,
        //      不会把空会话落库.
        appScope.launch {
            try {
                chatService.flushConversationToDb(_conversationId)
            } finally {
                chatService.removeConversationReference(_conversationId)
            }
        }
    }

    // 用户设置
    val settings: StateFlow<Settings> = settingsStore.settingsFlow

    // 阅读位置来自独立存储，不再挂在全局 Settings 上
    val readPositionsReady: StateFlow<Boolean> = readPositionStore.readyFlow
    val conversationReadPosition: StateFlow<ConversationReadPosition?> = readPositionStore.positionsFlow
        .map { positions -> positions[_conversationId.toString()] }
        .stateIn(viewModelScope, SharingStarted.Lazily, readPositionStore.get(_conversationId))

    /** 组合首帧同步读当前会话的阅读位置（存储未加载完时返回 null） */
    fun peekReadPosition(): ConversationReadPosition? = readPositionStore.get(_conversationId)
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
    @OptIn(FlowPreview::class)
    val conversations: Flow<PagingData<ConversationListItem>> =
        combine(
            settings.map { it.chatTarget.id }.distinctUntilChanged(),
            // 防抖：每次按键都会触发一次全表 LIKE 扫描，只查停止输入 300ms 后的关键词；
            // 空串（初始加载/清空搜索）不延迟，立即显示完整列表
            _searchQuery.debounce { query -> if (query.isBlank()) 0L else 300L }
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

    val quotaUsageFlow: StateFlow<QuotaUsageResult?> = settings.flatMapLatest { settings ->
        val model = settings.getCurrentChatModel() ?: return@flatMapLatest flowOf(null)
        modelQuotaRepo.getQuotaUsageFlowForProviders(model, settings.providers)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // 错误流 (从ChatService获取)
    val errorFlow: SharedFlow<Throwable> = chatService.errorFlow

    // 配额警告流 (从ChatService获取)
    val quotaWarningFlow: SharedFlow<QuotaUsageResult> = chatService.quotaWarningFlow

    // 生成完成 (从ChatService获取)
    val generationDoneFlow: SharedFlow<Uuid> = chatService.generationDoneFlow

    // MCP管理器 (从ChatService获取)
    val mcpManager = chatService.mcpManager

    // 切换聊天目标（助手/群聊）。
    // 走 updateChatTarget 的「锁内读最新值再改写」路径，而不是整份组合期快照覆盖；
    // 用 appScope 而非 viewModelScope：切完立刻导航走会销毁本 VM，写入不能被取消。
    fun selectChatTarget(target: ChatTarget) {
        appScope.launch {
            try {
                settingsStore.updateChatTarget(target)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w(TAG, "selectChatTarget: updateChatTarget failed (${e.message})", e)
            }
        }
    }

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

    fun respondAskUser(toolCallId: String, answer: String) {
        chatService.respondAskUser(
            conversationId = _conversationId,
            toolCallId = toolCallId,
            answer = answer,
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
        if (!isTemporaryChat) {
            _conversationExistsInStorage.value = true
        }
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
                val originalMessage = node.messages.firstOrNull { it.id == messageId }
                    ?: return@map node // 如果这个node没有这个消息，则不修改
                // 用被编辑消息本身的角色，而不是 node.role (= messages.firstOrNull()?.role)。
                // node.role 取的是节点首条消息的角色，当节点因 updateCurrentMessages 索引错位
                // 形成"首条 ASSISTANT + 选中 USER"的混合节点时，会误把新版本写成助手角色。
                node.copy(
                    messages = node.messages + UIMessage(
                        role = originalMessage.role,
                        parts = processedParts,
                    ), selectIndex = node.messages.size
                )
            },
        )
        // 先同步更新内存态, 再异步落库。若只异步落库, 编辑后立即切会话会取消 viewModelScope,
        // launch 还没开始就被丢弃, 内存从未更新 → 退出兜底 flushConversationToDb 读到旧内存,
        // 改动丢失。先 applyConversationState 写内存, 即便落库被取消, 兜底也能正确落盘新内容。
        chatService.applyConversationState(_conversationId, newConversation)
        viewModelScope.launch {
            saveCurrentConversation(newConversation)
        }
    }

    // fork 用户消息进入分支会话后的"编辑并发送": 覆盖目标用户消息为新版本并直接触发 AI 补全
    fun handleForkEditSend(parts: List<UIMessagePart>, messageId: Uuid) {
        if (parts.isEmptyInputMessage()) return
        analytics.logEvent("ai_fork_edit_send", null)

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

        chatService.editUserMessageAndComplete(
            conversationId = _conversationId,
            messageId = messageId,
            content = processedParts,
        )
        _conversationExistsInStorage.value = true
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
            saveCurrentConversation(newConversation)
        }
    }

    suspend fun forkMessage(message: UIMessage): Conversation {
        val sourceConversation = conversation.value
        // 提前生成新会话 id：分叉的文档要复制进新会话专属上传目录，id 必须先确定
        val forkConversationId = Uuid.random()
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
                                            val sourceFile = runCatching { url.toUri().toFile() }.getOrNull()
                                            if (sourceFile?.absolutePath?.contains("/chat_uploads/") == true) {
                                                // 沙盒通道的文档进新会话专属上传目录（保留原名），
                                                // 才能挂进沙盒 /upload 并随新会话删除清理
                                                val copied = context.createChatUploadFile(
                                                    forkConversationId.toString(),
                                                    url.toUri(),
                                                    desiredName = part.fileName,
                                                )
                                                if (copied != null) {
                                                    part.copy(url = copied.uri.toString(), fileName = copied.fileName)
                                                } else {
                                                    part
                                                }
                                            } else {
                                                // 原有通道的文档同样要复制一份：fork 与源会话共享同一文件，
                                                // 任一会话删除都会删掉共享文件破坏另一方附件（与升级前行为一致：各自复制）
                                                val copied = context.createChatFilesByContents(
                                                    listOf(url.toUri()),
                                                    // 带原名落盘：分支里编辑这条消息重发时，芯片不会退化成 UUID 名
                                                    desiredNames = listOf(part.fileName),
                                                ).firstOrNull()
                                                if (copied != null) part.copy(url = copied.toString()) else part
                                            }
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

        // 子分支后缀跟「树根标题」: 从改过后缀的分支再分叉时, 新分支后缀继承根标题, 不堆叠「分支N · 分支M · 」。
        val rootTitle = conversationRepo.getRootTitle(sourceConversation.rootId)?.ifBlank { null }
        val sourceTitle = (rootTitle ?: sourceConversation.title).ifBlank {
            context.getString(R.string.chat_page_new_chat)
        }
        // 分支计数: 同一棵树共享递增编号。第一条分支(=1)沿用旧前缀「分支 · 」, 第二条起带号「分支N · 」。
        return chatService.createForkConversation(sourceConversation.rootId) { branchNumber ->
            val forkTitle = if (branchNumber <= 1) {
                context.getString(R.string.chat_page_fork_title, sourceTitle)
            } else {
                context.getString(R.string.chat_page_fork_title_numbered, branchNumber, sourceTitle)
            }
            Conversation(
                id = forkConversationId,
                assistantId = sourceConversation.assistantId,
                title = forkTitle,
                messageNodes = nodes,
                rootId = sourceConversation.rootId,
                branchNumber = branchNumber,
                // 分支继承源会话的工作区覆写：与 ChatService.forkConversationAtMessage 保持一致，
                // 避免 UI 分叉入口漏带这个设置导致分支退回助手默认工作区。
                workspaceOverrideId = sourceConversation.workspaceOverrideId,
                // 分支继承源会话的注入开关与会话记忆：模式注入、技能注入、会话级记忆
                // 都是「每会话独立存储」的状态，不会随 assistantId 继承，需显式带过来。
                enabledModeIds = sourceConversation.enabledModeIds,
                explicitSkillContexts = sourceConversation.explicitSkillContexts,
                sessionMemories = sourceConversation.sessionMemories,
            )
        }
    }

    fun deleteMessage(message: UIMessage) {
        // 取消上一次未完成的删除 (如重复点击), 避免多个删除协程交错.
        deleteJob?.cancel()
        // 若当前会话正有生成在跑，先取消并等其收尾再删。否则生成流仍持有删除前的旧消息快照，
        // 下一段 chunk 经 updateCurrentMessages 按位置合并回来，会让已删除消息复活、
        // 并把 user/assistant 混进同一节点 (node.role 取节点首条角色就会取错)。
        // 走服务层 cancelGenerationAndAwait: 取消后会 join() 等收尾, 且不误记 ai_cancel_generation.
        // 用 deleteMutex 串行化删除/恢复: cancelGenerationAndAwait 的 join() 是挂起点, 挂起期间
        // 撤销协程可能先跑, 用 Mutex 保证二者互斥; 撤销时另通过 deleteJob.cancel() 主动中止删除.
        deleteJob = viewModelScope.launch {
            deleteMutex.withLock {
                chatService.cancelGenerationAndAwait(_conversationId)
                val relatedMessages = collectRelatedMessages(message)
                deleteMessageInternal(message)
                relatedMessages.forEach { deleteMessageInternal(it) }
                // 顺序落库 (不另起 launch): 让保存与删除在同一 Mutex 内完成, 避免与撤销交错.
                saveCurrentConversation(conversation.value)
            }
        }
    }

    /**
     * 撤销删除: 先取消正在进行的删除协程 (阻止其恢复后用旧快照继续删), 再在同一个 deleteMutex
     * 临界区内恢复会话, 保证与任何残留删除串行.
     */
    fun cancelDeleteAndRestore(backup: Conversation, restoredNodeIds: Set<Uuid>) {
        deleteJob?.cancel()
        viewModelScope.launch {
            deleteMutex.withLock {
                updateConversation(backup)
                markNodesAsRestored(restoredNodeIds)
            }
        }
    }

    private suspend fun deleteMessageInternal(message: UIMessage) {
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
        // 顺序落库 (不另起 launch): 让保存与删除在同一 Mutex 内完成, 避免与撤销的 updateConversation 交错.
        saveCurrentConversation(newConversation)
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
            saveCurrentConversation(conversation.value)
        }
    }

    fun updateConversationReadPosition(nodeId: Uuid, offset: Int, itemIndex: Int = 0) {
        val newPosition = ConversationReadPosition(
            nodeId = nodeId.toString(),
            offset = offset.coerceAtLeast(0),
            updatedAt = System.currentTimeMillis(),
            itemIndex = itemIndex.coerceAtLeast(0),
        )

        viewModelScope.launch {
            readPositionStore.update(_conversationId, newPosition)
        }
    }

    fun markLargeContextWarningShown(conversationId: Uuid = _conversationId) {
        val conversationKey = conversationId.toString()
        viewModelScope.launch {
            settingsStore.update { current ->
                if (current.conversationLargeContextWarningShownAt.containsKey(conversationKey)) {
                    current
                } else {
                    current.copy(
                        conversationLargeContextWarningShownAt = sanitizeConversationLargeContextWarningShownAt(
                            current.conversationLargeContextWarningShownAt + (
                                conversationKey to System.currentTimeMillis()
                            )
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
            // 分支判定: 仅当破坏了「分支N · 」前缀才脱离原树、提升为独立根;
            // 只动后缀(前缀还在)则不解绑, 继续属于原分支树。脱离不可逆。
            if (conversation.value.branchNumber != null && keepsBranchPrefix(conversation.value, title)) {
                val updatedConversation = conversation.value.copy(title = title)
                saveCurrentConversation(updatedConversation)
            } else {
                conversationRepo.detachBranch(conversation.value.id)
                val updatedConversation = conversation.value.copy(
                    title = title,
                    rootId = conversation.value.id,
                    branchNumber = null,
                )
                saveCurrentConversation(updatedConversation)
            }
        }
    }

    /**
     * 判断 [newTitle] 是否保留了该分支会话的「分支N · 」前缀(用户只动了后缀)。
     * 用空串占位取出预期前缀再 startsWith 判断, 保证与本地化文案一致。
     * 非分支会话(branchNumber == null)不应调用本方法。
     */
    private fun keepsBranchPrefix(conversation: Conversation, newTitle: String): Boolean {
        val expectedPrefix = if (conversation.branchNumber!! <= 1) {
            context.getString(R.string.chat_page_fork_title, "")
        } else {
            context.getString(R.string.chat_page_fork_title_numbered, conversation.branchNumber!!, "")
        }
        return newTitle.startsWith(expectedPrefix)
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
            chatService.generateTitle(conversation.id, conversationFull, force)
        }
    }

    fun consolidateConversation(conversation: Conversation) {
        viewModelScope.launch {
            // A single-conversation request is explicitly forced by its work input;
            // keep the durable completion state until the replacement succeeds.
            memoryConsolidationScheduler.enqueueConversation(
                conversationId = conversation.id.toString(),
                assistantId = conversation.assistantId.toString(),
            )
        }
    }

    fun cancelConversationConsolidation(conversation: Conversation) {
        viewModelScope.launch {
            memoryConsolidationScheduler.cancelConversation(conversation.id.toString())
        }
    }

    fun generateSuggestion(conversation: Conversation) {
        viewModelScope.launch {
            chatService.generateSuggestion(_conversationId, conversation)
        }
    }

    fun updateConversation(newConversation: Conversation) {
        viewModelScope.launch {
            saveCurrentConversation(newConversation)
        }
    }

    private suspend fun saveCurrentConversation(conversation: Conversation) {
        chatService.saveConversation(_conversationId, conversation)
        _conversationExistsInStorage.value = true
    }

    // Context Refresh - summarize conversation and update context
    suspend fun refreshContext(): ChatService.ContextRefreshResult {
        return chatService.summarizeAndRefresh(_conversationId)
    }

    suspend fun updateContextSummary(summary: String): Boolean {
        return chatService.updateContextSummary(_conversationId, summary)
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
