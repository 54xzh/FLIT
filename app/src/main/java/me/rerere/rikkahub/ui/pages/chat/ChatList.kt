package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalScrollCaptureInProgress
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtLeast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowDown
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowUp
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TouchApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getEffectiveDisplaySetting
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.buildSeatDisplayNames
import me.rerere.rikkahub.ui.components.message.ChatMessage
import me.rerere.rikkahub.ui.components.message.ChatMessageAssistantAvatar
import me.rerere.rikkahub.ui.components.message.ChatProcessTimeline
import me.rerere.rikkahub.ui.components.message.ReasoningBodyState
import me.rerere.rikkahub.ui.components.message.ToolCallPreviewSheet
import me.rerere.rikkahub.ui.components.message.planChatProcessDisplay
import me.rerere.rikkahub.ui.components.ui.ListSelectableItem
import me.rerere.rikkahub.ui.components.ui.ListSelectableItemContentPadding
import me.rerere.rikkahub.ui.components.ui.Tooltip
import me.rerere.rikkahub.ui.hooks.ImeLazyListAutoScroller
import me.rerere.rikkahub.utils.plus
import kotlin.uuid.Uuid
import androidx.compose.ui.platform.LocalContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.openUrl
import kotlin.math.abs

private const val TAG = "ChatList"
private const val LoadingIndicatorKey = "LoadingIndicator"
private const val ScrollBottomKey = "ScrollBottomKey"
private const val SendScrollMaxAnimationRetryCount = 20
private const val SendScrollAnimationRetryDelayMs = 40L
private val ChatListItemSpacing = 4.dp
private val ScrollBottomBaseHeight = 5.dp

private data class AssistantDisplayIdentity(
    val seatId: Uuid?,
    val assistantId: Uuid?,
    val modelId: Uuid?,
    val displayName: String?,
    val usesAssistantAvatar: Boolean,
)

private data class VisibleMessageNeighbors(
    val previousVisibleIndexByIndex: IntArray,
    val nextVisibleIndexByIndex: IntArray,
)

private data class AutoFollowScrollSample(
    val scrolling: Boolean,
    val index: Int,
    val offset: Int,
    val atBottom: Boolean,
)

private data class ToolPreviewContent(
    val toolCallId: String,
    val toolName: String,
    val arguments: JsonElement,
    val content: JsonElement,
    val metadata: JsonObject?,
    val hasResult: Boolean,
)

private val EmptyToolPreviewJson = JsonObject(emptyMap())

private fun toolPreviewKey(toolName: String, toolCallId: String): String = "$toolName:$toolCallId"

private fun parseToolPreviewKey(key: String): Pair<String, String>? {
    val separatorIndex = key.indexOf(':')
    if (separatorIndex <= 0 || separatorIndex == key.lastIndex) return null
    return key.substring(0, separatorIndex) to key.substring(separatorIndex + 1)
}

private fun Conversation.findToolPreviewContent(key: String): ToolPreviewContent? {
    val (targetToolName, targetToolCallId) = parseToolPreviewKey(key) ?: return null
    var callToolName = targetToolName
    var callArguments: JsonElement? = null
    var resultPart: UIMessagePart.ToolResult? = null

    messageNodes.forEach { node ->
        node.currentMessage.parts.forEach { part ->
            when (part) {
                is UIMessagePart.ToolCall -> {
                    if (part.toolCallId == targetToolCallId && part.toolName == targetToolName) {
                        callToolName = part.toolName
                        if (callArguments == null) {
                            callArguments = runCatching {
                                JsonInstant.parseToJsonElement(part.arguments)
                            }.getOrElse { EmptyToolPreviewJson }
                        }
                    }
                }

                is UIMessagePart.ToolResult -> {
                    if (part.toolCallId == targetToolCallId && part.toolName == targetToolName) {
                        resultPart = part
                    }
                }

                else -> Unit
            }
        }
    }

    resultPart?.let { result ->
        return ToolPreviewContent(
            toolCallId = result.toolCallId,
            toolName = result.toolName,
            arguments = result.arguments,
            content = result.content,
            metadata = result.metadata,
            hasResult = true,
        )
    }

    val arguments = callArguments ?: if (targetToolName == "search_agent") {
        EmptyToolPreviewJson
    } else {
        return null
    }

    return ToolPreviewContent(
        toolCallId = targetToolCallId,
        toolName = callToolName,
        arguments = arguments,
        content = EmptyToolPreviewJson,
        metadata = null,
        hasResult = false,
    )
}

private suspend fun LazyListState.tryAnimateSendScrollToItem(
    index: Int,
    scrollOffset: Int,
): Boolean {
    return try {
        animateScrollToItem(
            index = index,
            scrollOffset = scrollOffset,
        )
        true
    } catch (error: CancellationException) {
        if (!currentCoroutineContext().isActive) {
            throw error
        }
        false
    } catch (_: Exception) {
        false
    }
}

@Composable
internal fun ChatList(
    modifier: Modifier = Modifier,
    innerPadding: PaddingValues,
    conversation: Conversation,
    state: LazyListState,
    loading: Boolean,
    previewMode: Boolean,
    settings: Settings,
    recentlyRestoredNodeIds: Set<Uuid> = emptySet(),
    initialSearchQuery: String? = null,
    onAssistantAvatarLongPress: ((Assistant) -> Unit)? = null,
    onRegenerate: (UIMessage) -> Unit = {},
    onContinue: (UIMessage) -> Unit = {},
    onEdit: (UIMessage) -> Unit = {},
    onForkMessage: (UIMessage) -> Unit = {},
    onDelete: (UIMessage) -> Unit = {},
    onUpdateMessage: (MessageNode) -> Unit = {},
    onUpdateConversation: (Conversation) -> Unit = {},
    canLoadOlderHistory: Boolean = false,
    loadingOlderHistory: Boolean = false,
    onLoadOlderHistory: () -> Unit = {},
    onJumpToMessage: (Uuid) -> Unit = {},
    onReadPositionSample: (nodeId: Uuid, offset: Int) -> Unit = { _, _ -> },
    onEditContextSummary: () -> Unit = {},
    sendScrollRequest: ChatSendScrollRequest? = null,
    onSendScrollRequestHandled: (Long) -> Unit = {},
) {
    SharedTransitionLayout(modifier = modifier) {
        AnimatedContent(
            targetState = previewMode,
            label = "ChatListMode",
            transitionSpec = {
                (fadeIn() + scaleIn(initialScale = 0.8f) togetherWith fadeOut() + scaleOut(targetScale = 0.8f))
            }
        ) { target ->
            if (target) {
                ChatListPreview(
                    innerPadding = innerPadding,
                    conversation = conversation,
                    settings = settings,
                    onJumpToMessage = onJumpToMessage,
                    animatedVisibilityScope = this@AnimatedContent,
                    initialSearchQuery = initialSearchQuery,
                )
            } else {
                ChatListNormal(
                    innerPadding = innerPadding,
                    conversation = conversation,
                    state = state,
                    loading = loading,
                    settings = settings,
                    recentlyRestoredNodeIds = recentlyRestoredNodeIds,
                    onAssistantAvatarLongPress = onAssistantAvatarLongPress,
                    onRegenerate = onRegenerate,
                    onContinue = onContinue,
                    onEdit = onEdit,
                    onForkMessage = onForkMessage,
                    onDelete = onDelete,
                    onUpdateMessage = onUpdateMessage,
                    onUpdateConversation = onUpdateConversation,
                    canLoadOlderHistory = canLoadOlderHistory,
                    loadingOlderHistory = loadingOlderHistory,
                    onLoadOlderHistory = onLoadOlderHistory,
                    onReadPositionSample = onReadPositionSample,
                    onEditContextSummary = onEditContextSummary,
                    sendScrollRequest = sendScrollRequest,
                    onSendScrollRequestHandled = onSendScrollRequestHandled,
                    animatedVisibilityScope = this@AnimatedContent,
                )
            }
        }
    }
}

private fun Conversation.updateSessionMemoryContent(
    memoryId: Int,
    content: String,
): Conversation {
    val trimmedContent = content.trim()
    if (trimmedContent.isBlank()) return this

    var memoryChanged = false
    val updatedSessionMemories = sessionMemories.map { memory ->
        if (memory.id == memoryId && memory.content != trimmedContent) {
            memoryChanged = true
            memory.copy(
                content = trimmedContent,
                updatedAt = System.currentTimeMillis()
            )
        } else {
            memory
        }
    }
    if (!memoryChanged) return this

    return copy(
        sessionMemories = updatedSessionMemories,
        messageNodes = messageNodes.mapUsedSessionMemory(memoryId) { usedMemory ->
            usedMemory.copy(memoryContent = trimmedContent)
        }
    )
}

private fun Conversation.deleteSessionMemory(memoryId: Int): Conversation {
    val updatedSessionMemories = sessionMemories.filterNot { it.id == memoryId }
    if (updatedSessionMemories.size == sessionMemories.size) return this

    return copy(
        sessionMemories = updatedSessionMemories,
        messageNodes = messageNodes.mapUsedSessionMemoryList { usedMemories ->
            usedMemories.filterNot { it.memoryId == memoryId }
        }
    )
}

private fun List<MessageNode>.mapUsedSessionMemory(
    memoryId: Int,
    transform: (me.rerere.ai.ui.UsedSessionMemory) -> me.rerere.ai.ui.UsedSessionMemory,
): List<MessageNode> = mapUsedSessionMemoryList { usedMemories ->
    usedMemories.map { usedMemory ->
        if (usedMemory.memoryId == memoryId) transform(usedMemory) else usedMemory
    }
}

private fun List<MessageNode>.mapUsedSessionMemoryList(
    transform: (List<me.rerere.ai.ui.UsedSessionMemory>) -> List<me.rerere.ai.ui.UsedSessionMemory>,
): List<MessageNode> {
    var anyNodeChanged = false
    val updatedNodes = map { node ->
        var nodeChanged = false
        val updatedMessages = node.messages.map { message ->
            val usedMemories = message.usedSessionMemories ?: return@map message
            val updatedUsedMemories = transform(usedMemories)
            if (updatedUsedMemories == usedMemories) {
                message
            } else {
                nodeChanged = true
                message.copy(usedSessionMemories = updatedUsedMemories.ifEmpty { null })
            }
        }
        if (nodeChanged) {
            anyNodeChanged = true
            node.copy(messages = updatedMessages)
        } else {
            node
        }
    }
    return if (anyNodeChanged) updatedNodes else this
}

@Composable
private fun SharedTransitionScope.ChatListNormal(
    innerPadding: PaddingValues,
    conversation: Conversation,
    state: LazyListState,
    loading: Boolean,
    settings: Settings,
    recentlyRestoredNodeIds: Set<Uuid> = emptySet(),
    onAssistantAvatarLongPress: ((Assistant) -> Unit)?,
    onRegenerate: (UIMessage) -> Unit,
    onContinue: (UIMessage) -> Unit,
    onEdit: (UIMessage) -> Unit,
    onForkMessage: (UIMessage) -> Unit,
    onDelete: (UIMessage) -> Unit,
    onUpdateMessage: (MessageNode) -> Unit,
    onUpdateConversation: (Conversation) -> Unit,
    canLoadOlderHistory: Boolean,
    loadingOlderHistory: Boolean,
    onLoadOlderHistory: () -> Unit,
    onReadPositionSample: (nodeId: Uuid, offset: Int) -> Unit,
    onEditContextSummary: () -> Unit,
    sendScrollRequest: ChatSendScrollRequest?,
    onSendScrollRequestHandled: (Long) -> Unit,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val listItemSpacingPx = with(density) { ChatListItemSpacing.roundToPx() }
    val bottomMarkerHeightPx = with(density) { ScrollBottomBaseHeight.roundToPx() }
    val loadingState by rememberUpdatedState(loading)
    val loadingOlderState by rememberUpdatedState(loadingOlderHistory)
    var isRecentScroll by remember { mutableStateOf(false) }
    var userScrolledAway by remember(conversation.id) { mutableStateOf(false) }
    var shouldAutoFollowGeneration by remember(conversation.id) { mutableStateOf(false) }
    var activeSendScrollAnchor by remember(conversation.id) { mutableStateOf<ChatSendScrollAnchor?>(null) }
    var animatedSendScrollRequestId by remember(conversation.id) { mutableStateOf<Long?>(null) }
    var sendScrollAnimationRetryCount by remember(conversation.id) { mutableStateOf(0) }
    var loadingIndicatorHeightPx by remember(conversation.id) { mutableStateOf(0) }
    val messageItemHeightsPx = remember(conversation.id) { mutableStateMapOf<Uuid, Int>() }
    val conversationUpdated by rememberUpdatedState(conversation)
    val context = LocalContext.current
    val navController = LocalNavController.current
    val defaultAssistantName = stringResource(R.string.assistant_page_default_assistant)
    val groupChatTemplateForConversation = remember(settings.groupChatTemplates, conversation.assistantId) {
        settings.groupChatTemplates.firstOrNull { it.id == conversation.assistantId }
    }
    val assistantsById = remember(settings.assistants) { settings.assistants.associateBy { it.id } }
    val seatDisplayNames = remember(groupChatTemplateForConversation, assistantsById, defaultAssistantName) {
        groupChatTemplateForConversation?.buildSeatDisplayNames(
            assistantsById = assistantsById,
            defaultName = defaultAssistantName,
        ).orEmpty()
    }
    val effectiveDisplay = settings.getEffectiveDisplaySetting()
    val compressionMarkerIndexes = remember(
        conversation.contextSummaryBoundaries,
        conversation.messageNodes,
    ) {
        conversation.contextSummaryBoundaries
            .asSequence()
            .filter { it in conversation.messageNodes.indices }
            .toSet()
    }
    val latestCompressionMarkerIndex = remember(compressionMarkerIndexes) {
        compressionMarkerIndexes.maxOrNull()
    }
    val pendingCompressionMarkerIndex = remember(
        conversation.contextSummaryPendingBoundaryIndex,
        conversation.messageNodes,
    ) {
        conversation.contextSummaryPendingBoundaryIndex
            .takeIf { it in conversation.messageNodes.indices }
    }
    val processDisplayPlan = remember(conversation.messageNodes) {
        planChatProcessDisplay(conversation.messageNodes)
    }
    val reasoningBodyStates = remember(conversation.id) {
        mutableStateMapOf<String, ReasoningBodyState>()
    }
    var activeToolPreviewKey by remember(conversation.id) { mutableStateOf<String?>(null) }
    var activeToolPreviewSnapshot by remember(conversation.id) { mutableStateOf<ToolPreviewContent?>(null) }
    val toolPreviewTabStates = remember(conversation.id) {
        mutableStateMapOf<String, Int>()
    }
    val activeToolPreviewContent = activeToolPreviewKey?.let { key ->
        conversation.findToolPreviewContent(key)
    }
    val displayedToolPreviewContent = activeToolPreviewContent
        ?: activeToolPreviewSnapshot?.takeIf { snapshot ->
            activeToolPreviewKey == toolPreviewKey(snapshot.toolName, snapshot.toolCallId)
        }
    fun openToolPreview(toolCallId: String, toolName: String, hasResult: Boolean) {
        val key = toolPreviewKey(toolName, toolCallId)
        if (toolName == "search_agent" && key !in toolPreviewTabStates) {
            toolPreviewTabStates[key] = if (hasResult) 0 else 1
        }
        activeToolPreviewSnapshot = conversation.findToolPreviewContent(key)
        activeToolPreviewKey = key
    }
    LaunchedEffect(activeToolPreviewKey, activeToolPreviewContent) {
        if (activeToolPreviewContent != null) {
            activeToolPreviewSnapshot = activeToolPreviewContent
        }
    }
    val visibleMessageNeighbors = remember(conversation.messageNodes, processDisplayPlan.hiddenNodeIndexes) {
        val previousVisibleIndexByIndex = IntArray(conversation.messageNodes.size) { -1 }
        val nextVisibleIndexByIndex = IntArray(conversation.messageNodes.size) { -1 }
        var lastVisibleIndex = -1
        conversation.messageNodes.indices.forEach { index ->
            previousVisibleIndexByIndex[index] = lastVisibleIndex
            if (index !in processDisplayPlan.hiddenNodeIndexes) {
                lastVisibleIndex = index
            }
        }
        var nextVisibleIndex = -1
        conversation.messageNodes.indices.reversed().forEach { index ->
            nextVisibleIndexByIndex[index] = nextVisibleIndex
            if (index !in processDisplayPlan.hiddenNodeIndexes) {
                nextVisibleIndex = index
            }
        }
        VisibleMessageNeighbors(
            previousVisibleIndexByIndex = previousVisibleIndexByIndex,
            nextVisibleIndexByIndex = nextVisibleIndexByIndex,
        )
    }

    fun resolveAssistantForMessage(message: UIMessage): Assistant? {
        return message.speakerSeatId
            ?.let { seatId ->
                groupChatTemplateForConversation?.seats?.firstOrNull { it.id == seatId }
            }
            ?.let { seat ->
                assistantsById[seat.assistantId]?.let { resolved ->
                    val displayName = seatDisplayNames[seat.id]
                    if (displayName.isNullOrBlank() || displayName == resolved.name) {
                        resolved
                    } else {
                        resolved.copy(name = displayName)
                    }
                }
            }
            ?: message.speakerAssistantId
                ?.let { speakerId -> settings.getAssistantById(speakerId) }
            ?: settings.getAssistantById(conversation.assistantId)
    }

    fun buildAssistantDisplayIdentity(
        message: UIMessage,
        model: Model?,
        assistant: Assistant?,
    ): AssistantDisplayIdentity? {
        if (message.role != MessageRole.ASSISTANT) return null
        val assistantIdentity = assistant?.takeIf {
            groupChatTemplateForConversation != null || it.useAssistantAvatar || model == null
        }
        return AssistantDisplayIdentity(
            seatId = message.speakerSeatId,
            assistantId = assistantIdentity?.id ?: message.speakerAssistantId,
            modelId = if (assistantIdentity == null) {
                model?.id ?: message.modelId
            } else {
                null
            },
            displayName = when {
                assistantIdentity != null -> assistantIdentity.name
                model != null -> model.displayName
                else -> defaultAssistantName
            },
            usesAssistantAvatar = assistantIdentity != null,
        )
    }

    val currentConversationState = rememberUpdatedState(conversation)
    val onCitationClick = remember {
        { citationId: String ->
            run findCitation@{
                currentConversationState.value.currentMessages.forEach { message ->
                    message.parts.forEach { part ->
                        if (part is UIMessagePart.ToolResult && part.toolName == "search_web") {
                            val items = part.content.jsonObject["items"]?.jsonArray ?: return@forEach
                            items.forEach { item ->
                                val id = item.jsonObject["id"]?.jsonPrimitive?.content ?: return@forEach
                                val url = item.jsonObject["url"]?.jsonPrimitive?.content ?: return@forEach
                                if (citationId == id) {
                                    context.openUrl(url)
                                    return@findCitation
                                }
                            }
                        }
                        if (part is UIMessagePart.ToolResult && part.toolName == "search_agent") {
                            val sources = part.content.jsonObject["sources"]?.jsonArray ?: return@forEach
                            sources.forEach { source ->
                                val id = source.jsonObject["id"]?.jsonPrimitive?.content ?: return@forEach
                                val url = source.jsonObject["url"]?.jsonPrimitive?.content ?: return@forEach
                                if (citationId == id) {
                                    context.openUrl(url)
                                    return@findCitation
                                }
                            }
                        }
                    }
                }
            }
            Unit
        }
    }
    val isAtListTop by remember(state) {
        derivedStateOf {
            !state.canScrollBackward ||
                (state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset <= 20)
        }
    }
    val canTriggerLoadOlder by remember(canLoadOlderHistory, loadingOlderHistory, isAtListTop) {
        derivedStateOf {
            canLoadOlderHistory &&
                !loadingOlderHistory &&
                isAtListTop
        }
    }

    fun List<LazyListItemInfo>.isCloseToBottom(): Boolean {
        val lastItem = lastOrNull() ?: return false
        val lastMessageId = conversationUpdated.messageNodes.lastOrNull()?.id
        val isBottomItem = lastItem.key == ScrollBottomKey ||
            lastItem.key == LoadingIndicatorKey ||
            lastItem.key == lastMessageId
        if (!isBottomItem) return false
        return lastItem.offset + lastItem.size <= state.layoutInfo.viewportEndOffset + 32
    }

    LaunchedEffect(sendScrollRequest, conversation.messageNodes) {
        val request = sendScrollRequest ?: return@LaunchedEffect
        val node = conversation.messageNodes
            .getOrNull(request.expectedMessageIndex)
            ?.takeIf { it.role == MessageRole.USER }
            ?: conversation.messageNodes
                .drop(request.expectedMessageIndex.coerceAtLeast(0))
                .firstOrNull { it.role == MessageRole.USER }
            ?: return@LaunchedEffect

        activeSendScrollAnchor = ChatSendScrollAnchor(
            requestId = request.id,
            nodeId = node.id,
        )
        animatedSendScrollRequestId = null
        sendScrollAnimationRetryCount = 0
        onSendScrollRequestHandled(request.id)
    }

    val sendScrollAnchor = activeSendScrollAnchor
    val sendScrollAnchorIndex = sendScrollAnchor
        ?.nodeId
        ?.let { nodeId -> conversation.messageNodes.indexOfFirst { it.id == nodeId } }
        ?: -1

    LaunchedEffect(sendScrollAnchor?.nodeId, sendScrollAnchorIndex) {
        if (sendScrollAnchor != null && sendScrollAnchorIndex < 0) {
            activeSendScrollAnchor = null
            animatedSendScrollRequestId = null
            sendScrollAnimationRetryCount = 0
        }
    }

    val sendScrollLayoutInfo = state.layoutInfo
    val sendScrollTotalItemsCount = sendScrollLayoutInfo.totalItemsCount
    val sendScrollViewportHeightPx = sendScrollLayoutInfo.viewportSize.height
    val sendScrollMessageHeightsPx = conversation.messageNodes.map { node ->
        messageItemHeightsPx[node.id] ?: 0
    }
    val sendScrollUserMessageHeightPx = sendScrollMessageHeightsPx.getOrElse(sendScrollAnchorIndex) { 0 }
    val sendScrollTrailingContentHeightPx = resolveSendScrollTrailingContentHeightPx(
        anchorIndex = sendScrollAnchorIndex,
        messageItemHeightsPx = sendScrollMessageHeightsPx,
        loading = loading,
        loadingIndicatorHeightPx = loadingIndicatorHeightPx,
        bottomMarkerHeightPx = bottomMarkerHeightPx,
        itemSpacingPx = listItemSpacingPx,
    )
    val sendScrollLayout = if (
        sendScrollAnchor != null &&
        sendScrollAnchorIndex >= 0 &&
        sendScrollUserMessageHeightPx > 0 &&
        sendScrollViewportHeightPx > 0
    ) {
        resolveSendScrollLayout(
            viewportHeightPx = sendScrollViewportHeightPx,
            userMessageHeightPx = sendScrollUserMessageHeightPx,
            trailingContentHeightPx = sendScrollTrailingContentHeightPx,
            afterContentPaddingPx = sendScrollLayoutInfo.afterContentPadding,
        )
    } else {
        null
    }
    val sendScrollDynamicSpacerHeight = with(density) {
        (sendScrollLayout?.dynamicSpacerHeightPx ?: 0).toDp()
    }
    val sendScrollInitialAnimationDone = sendScrollAnchor != null &&
        animatedSendScrollRequestId == sendScrollAnchor.requestId
    val sendScrollLocksBottom = shouldLockSendScrollPosition(
        hasPendingRequest = sendScrollRequest != null,
        hasActiveAnchor = sendScrollAnchor != null,
        initialAnimationDone = sendScrollInitialAnimationDone,
        replyAreaOverflowing = sendScrollLayout?.replyAreaOverflowing,
    )

    LaunchedEffect(
        sendScrollAnchor?.requestId,
        sendScrollAnchorIndex,
        sendScrollUserMessageHeightPx,
        sendScrollLayout?.userMessageScrollOffsetPx,
        sendScrollTotalItemsCount,
        sendScrollViewportHeightPx,
        sendScrollAnimationRetryCount,
    ) {
        val anchor = sendScrollAnchor ?: return@LaunchedEffect
        if (sendScrollAnchorIndex < 0) return@LaunchedEffect
        if (animatedSendScrollRequestId == anchor.requestId) return@LaunchedEffect

        repeat(2) { withFrameNanos { } }
        if (
            sendScrollTotalItemsCount <= sendScrollAnchorIndex ||
            sendScrollViewportHeightPx <= 0
        ) {
            if (sendScrollAnimationRetryCount < SendScrollMaxAnimationRetryCount) {
                delay(SendScrollAnimationRetryDelayMs)
                sendScrollAnimationRetryCount += 1
            } else {
                activeSendScrollAnchor = null
                animatedSendScrollRequestId = null
                sendScrollAnimationRetryCount = 0
            }
            return@LaunchedEffect
        }

        val scrollOffset = sendScrollLayout?.userMessageScrollOffsetPx
        val targetOffset = scrollOffset ?: 0
        val animated = state.tryAnimateSendScrollToItem(
            index = sendScrollAnchorIndex,
            scrollOffset = targetOffset,
        )
        if (!animated) {
            if (sendScrollAnimationRetryCount < SendScrollMaxAnimationRetryCount) {
                delay(SendScrollAnimationRetryDelayMs)
                sendScrollAnimationRetryCount += 1
            } else {
                activeSendScrollAnchor = null
                animatedSendScrollRequestId = null
                sendScrollAnimationRetryCount = 0
            }
            return@LaunchedEffect
        }

        if (scrollOffset == null) {
            if (sendScrollAnimationRetryCount < SendScrollMaxAnimationRetryCount) {
                delay(SendScrollAnimationRetryDelayMs)
                sendScrollAnimationRetryCount += 1
            } else {
                activeSendScrollAnchor = null
                animatedSendScrollRequestId = null
                sendScrollAnimationRetryCount = 0
            }
            return@LaunchedEffect
        }

        val reachedTarget = state.firstVisibleItemIndex == sendScrollAnchorIndex &&
            abs(state.firstVisibleItemScrollOffset - scrollOffset) <= 2
        if (!reachedTarget) {
            if (sendScrollAnimationRetryCount < SendScrollMaxAnimationRetryCount) {
                delay(SendScrollAnimationRetryDelayMs)
                sendScrollAnimationRetryCount += 1
            } else {
                activeSendScrollAnchor = null
                animatedSendScrollRequestId = null
                sendScrollAnimationRetryCount = 0
            }
            return@LaunchedEffect
        }

        sendScrollAnimationRetryCount = 0
        animatedSendScrollRequestId = anchor.requestId
    }

    LaunchedEffect(
        sendScrollAnchor?.requestId,
        sendScrollInitialAnimationDone,
        sendScrollLayout?.replyAreaOverflowing,
        loading,
        effectiveDisplay.autoScrollOnMessageGeneration,
    ) {
        if (
            sendScrollAnchor != null &&
            sendScrollInitialAnimationDone &&
            sendScrollLayout?.replyAreaOverflowing == true &&
            loading &&
            effectiveDisplay.autoScrollOnMessageGeneration
        ) {
            userScrolledAway = false
            shouldAutoFollowGeneration = true
        }
    }

    // 聊天选择
    val selectedItems = remember { mutableStateListOf<Uuid>() }
    var selecting by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }

    // 自动跟随键盘滚动
    ImeLazyListAutoScroller(
        lazyListState = state,
        enabled = !sendScrollLocksBottom,
    )

    Box(
        modifier = Modifier
            .fillMaxSize(),
    ) {
        // Empty chat state removed - assistant icon now shown in TopBar

        LaunchedEffect(loading, effectiveDisplay.autoScrollOnMessageGeneration, conversation.id, sendScrollLocksBottom) {
            if (sendScrollLocksBottom) {
                userScrolledAway = true
                shouldAutoFollowGeneration = false
                return@LaunchedEffect
            }

            if (!loading || !effectiveDisplay.autoScrollOnMessageGeneration) {
                userScrolledAway = false
                shouldAutoFollowGeneration = false
                return@LaunchedEffect
            }

            val atBottom = state.layoutInfo.visibleItemsInfo.isCloseToBottom()
            userScrolledAway = !atBottom
            shouldAutoFollowGeneration = atBottom
        }

        LaunchedEffect(state, effectiveDisplay.autoScrollOnMessageGeneration, sendScrollLocksBottom) {
            if (!effectiveDisplay.autoScrollOnMessageGeneration || sendScrollLocksBottom) return@LaunchedEffect

            var lastIndex = state.firstVisibleItemIndex
            var lastOffset = state.firstVisibleItemScrollOffset
            snapshotFlow {
                val currentIndex = state.firstVisibleItemIndex
                val currentOffset = state.firstVisibleItemScrollOffset
                val atBottom = state.layoutInfo.visibleItemsInfo.isCloseToBottom()
                AutoFollowScrollSample(
                    scrolling = state.isScrollInProgress,
                    index = currentIndex,
                    offset = currentOffset,
                    atBottom = atBottom,
                )
            }.collect { sample ->
                if (!loadingState) return@collect

                val movingAwayFromBottom = sample.index < lastIndex ||
                    (sample.index == lastIndex && sample.offset < lastOffset)
                lastIndex = sample.index
                lastOffset = sample.offset

                when {
                    sample.scrolling && movingAwayFromBottom -> {
                        userScrolledAway = true
                        shouldAutoFollowGeneration = false
                    }

                    sample.atBottom -> {
                        userScrolledAway = false
                        shouldAutoFollowGeneration = true
                    }

                    sample.scrolling -> {
                        userScrolledAway = true
                        shouldAutoFollowGeneration = false
                    }
                }
            }
        }

        LaunchedEffect(state, effectiveDisplay.autoScrollOnMessageGeneration, sendScrollLocksBottom) {
            if (!effectiveDisplay.autoScrollOnMessageGeneration || sendScrollLocksBottom) return@LaunchedEffect

            snapshotFlow { state.layoutInfo.visibleItemsInfo }.collect { visibleItemsInfo ->
                if (
                    !state.isScrollInProgress &&
                    loadingState &&
                    shouldAutoFollowGeneration &&
                    !userScrolledAway &&
                    visibleItemsInfo.isNotEmpty()
                ) {
                    val targetIndex = (state.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                    state.requestScrollToItem(targetIndex)
                }
            }
        }

        // 判断最近是否滚动
        LaunchedEffect(state.isScrollInProgress) {
            if (state.isScrollInProgress) {
                isRecentScroll = true
                delay(1500)
                isRecentScroll = false
            } else {
                delay(1500)
                isRecentScroll = false
            }
        }

        LaunchedEffect(state, conversation.id) {
            var lastNodeId: Uuid? = null
            var lastOffset = -1
            snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
                .collect { (index, offset) ->
                    val nodeId = conversationUpdated.messageNodes.getOrNull(index)?.id ?: return@collect
                    if (nodeId == lastNodeId && offset == lastOffset) {
                        return@collect
                    }
                    lastNodeId = nodeId
                    lastOffset = offset
                    onReadPositionSample(nodeId, offset)
                }
        }

        LazyColumn(
            state = state,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 16.dp) + PaddingValues(bottom = 32.dp) + innerPadding + androidx.compose.foundation.layout.WindowInsets.ime.asPaddingValues(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ChatListItemSpacing),
            modifier = Modifier
                .sharedBounds(
                    sharedContentState = rememberSharedContentState(key = "conversation_list"),
                    animatedVisibilityScope = animatedVisibilityScope
                )
                .fillMaxSize(),
        ) {
            itemsIndexed(
                items = conversation.messageNodes,
                key = { index, item -> item.id },
            ) { index, node ->
                Column(
                    modifier = Modifier.onSizeChanged { size ->
                        messageItemHeightsPx[node.id] = size.height
                    }
                ) {
                    val message = node.currentMessage
                    val standaloneProcessParts = processDisplayPlan
                        .standaloneProcessPartsByIndex[index]
                        .orEmpty()
                    val standaloneAssistantOwnerMessage = processDisplayPlan
                        .standaloneAssistantOwnerIndexByIndex[index]
                        ?.let { ownerIndex -> conversation.messageNodes.getOrNull(ownerIndex)?.currentMessage }
                    val prefixedDisplaySegments = processDisplayPlan
                        .prefixedDisplaySegmentsByIndex[index]
                        .orEmpty()
                    val hideMessageCard = index in processDisplayPlan.hiddenNodeIndexes
                    val isSelected by remember(node.id) {
                        derivedStateOf { selectedItems.contains(node.id) }
                    }
                    val previousVisibleMessage = visibleMessageNeighbors.previousVisibleIndexByIndex[index]
                        .takeIf { it >= 0 }
                        ?.let { conversation.messageNodes[it].currentMessage }
                    val nextVisibleMessage = visibleMessageNeighbors.nextVisibleIndexByIndex[index]
                        .takeIf { it >= 0 }
                        ?.let { conversation.messageNodes[it].currentMessage }
                    val previousMessage = previousVisibleMessage
                    val isLast = index == conversation.messageNodes.lastIndex
                    val canContinue = isLast &&
                        message.role == MessageRole.ASSISTANT &&
                        groupChatTemplateForConversation == null
                    val hiddenToolCallIds = conversation.messageNodes
                        .getOrNull(index + 1)
                        ?.currentMessage
                        ?.parts
                        ?.filterIsInstance<UIMessagePart.ToolResult>()
                        ?.asSequence()
                        ?.map { it.toolCallId }
                        ?.filter { it.isNotBlank() }
                        ?.toSet()
                        .orEmpty()
                    val modelForMessage = message.modelId?.let { settings.findModelById(it) }
                    val assistantForMessage = resolveAssistantForMessage(message)
                    val standaloneModelForMessage = standaloneAssistantOwnerMessage
                        ?.modelId
                        ?.let { settings.findModelById(it) }
                    val standaloneAssistantForMessage = standaloneAssistantOwnerMessage
                        ?.let(::resolveAssistantForMessage)
                    val currentAssistantDisplayIdentity = buildAssistantDisplayIdentity(
                        message = message,
                        model = modelForMessage,
                        assistant = assistantForMessage,
                    )
                    val standaloneAssistantDisplayIdentity = standaloneAssistantOwnerMessage?.let { ownerMessage ->
                        buildAssistantDisplayIdentity(
                            message = ownerMessage,
                            model = standaloneModelForMessage,
                            assistant = standaloneAssistantForMessage,
                        )
                    }
                    val previousAssistantDisplayIdentity = previousVisibleMessage?.let { visibleMessage ->
                        buildAssistantDisplayIdentity(
                            message = visibleMessage,
                            model = visibleMessage.modelId?.let { settings.findModelById(it) },
                            assistant = resolveAssistantForMessage(visibleMessage),
                        )
                    }
                    val speakerChanged = previousMessage?.role == MessageRole.ASSISTANT &&
                        message.role == MessageRole.ASSISTANT &&
                        previousAssistantDisplayIdentity != currentAssistantDisplayIdentity
                    val previousRole = if (speakerChanged) null else previousMessage?.role
                    val standaloneSpeakerChanged = previousVisibleMessage?.role == MessageRole.ASSISTANT &&
                        standaloneAssistantOwnerMessage?.role == MessageRole.ASSISTANT &&
                        previousAssistantDisplayIdentity != standaloneAssistantDisplayIdentity
                    val standalonePreviousRole = if (standaloneSpeakerChanged) null else previousVisibleMessage?.role
                    val showAssistantHeader = message.role != MessageRole.ASSISTANT ||
                        previousAssistantDisplayIdentity != currentAssistantDisplayIdentity
                    val showStandaloneAssistantHeader = standaloneAssistantOwnerMessage?.role == MessageRole.ASSISTANT &&
                        previousAssistantDisplayIdentity != standaloneAssistantDisplayIdentity
                    val showInlineTokenUsage = message.role != MessageRole.ASSISTANT ||
                        nextVisibleMessage?.role != MessageRole.ASSISTANT

                    if (standaloneProcessParts.isNotEmpty()) {
                        Column(
                            modifier = Modifier.padding(
                                horizontal = ListSelectableItemContentPadding,
                                vertical = ListSelectableItemContentPadding,
                            ),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            standaloneAssistantOwnerMessage?.takeIf { showStandaloneAssistantHeader }?.let { ownerMessage ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                                ) {
                                    ChatMessageAssistantAvatar(
                                        message = ownerMessage,
                                        previousRole = standalonePreviousRole,
                                        model = standaloneModelForMessage,
                                        assistant = standaloneAssistantForMessage,
                                        forceUseAssistantAvatar = groupChatTemplateForConversation != null,
                                        onAvatarLongPress = onAssistantAvatarLongPress,
                                        loading = loading && isLast,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                            ChatProcessTimeline(
                                processParts = standaloneProcessParts,
                                conversationId = conversation.id,
                                hiddenToolCallIds = emptySet(),
                                loading = loading && isLast,
                                model = standaloneModelForMessage ?: modelForMessage,
                                assistant = standaloneAssistantForMessage ?: assistantForMessage,
                                reasoningBodyStates = reasoningBodyStates,
                                onOpenToolPreview = ::openToolPreview,
                            )
                        }
                    }

                    if (!hideMessageCard) {
                        ListSelectableItem(
                            isSelected = isSelected,
                            onSelectChange = { checked ->
                                if (checked) {
                                    selectedItems.add(node.id)
                                } else {
                                    selectedItems.remove(node.id)
                                }
                            },
                            enabled = selecting,
                        ) {
                            ChatMessage(
                                node = node,
                                previousRole = previousRole,
                                isLast = isLast,
                                showAssistantHeader = showAssistantHeader,
                                showInlineTokenUsage = showInlineTokenUsage,
                                hiddenToolCallIds = hiddenToolCallIds,
                                leadingProcessParts = prefixedDisplaySegments,
                                reasoningBodyStates = reasoningBodyStates,
                                onOpenToolPreview = ::openToolPreview,
                                conversationId = conversation.id,
                                onCitationClick = onCitationClick,
                                model = modelForMessage,
                                assistant = assistantForMessage,
                                forceUseAssistantAvatar = groupChatTemplateForConversation != null,
                                onAssistantAvatarLongPress = onAssistantAvatarLongPress,
                                loading = loading && isLast,
                                isRecentlyRestored = node.id in recentlyRestoredNodeIds,
                                onRegenerate = {
                                    onRegenerate(message)
                                },
                                onContinue = {
                                    onContinue(message)
                                },
                                canContinue = canContinue,
                                onEdit = {
                                    onEdit(message)
                                },
                                onFork = {
                                    onForkMessage(message)
                                },
                                onDelete = {
                                    onDelete(message)
                                },
                                onShare = {
                                    selecting = true  // 使用 CoroutineScope 延迟状态更新
                                    selectedItems.clear()
                                    selectedItems.addAll(conversation.messageNodes.map { it.id }
                                        .subList(0, conversation.messageNodes.indexOf(node) + 1))
                                },
                                onUpdate = {
                                    onUpdateMessage(it)
                                },
                                onEditLorebookEntry = { entry ->
                                    navController.navigate(Screen.SettingLorebookDetail(entry.lorebookId, entry.entryId))
                                },
                                onModeClick = { mode ->
                                    // Navigate to Modes page and scroll to the specific mode
                                    navController.navigate(Screen.SettingModes(scrollToModeId = mode.modeId))
                                },
                                onMemoryClick = { memory ->
                                    // Navigate to AssistantDetail memory page
                                    // memoryType: 0 = CORE, 1 = EPISODIC
                                    navController.navigate(
                                        Screen.AssistantDetail(
                                            id = conversation.assistantId.toString(),
                                            startRoute = "memory",
                                            initialMemoryTab = memory.memoryType,
                                            scrollToMemoryId = memory.memoryId
                                        )
                                    )
                                },
                                currentSessionMemories = conversation.sessionMemories,
                                onUpdateSessionMemory = { memoryId, content ->
                                    val updatedConversation = conversation.updateSessionMemoryContent(
                                        memoryId = memoryId,
                                        content = content
                                    )
                                    if (updatedConversation != conversation) {
                                        onUpdateConversation(updatedConversation)
                                    }
                                },
                                onDeleteSessionMemory = { memoryId ->
                                    val updatedConversation = conversation.deleteSessionMemory(memoryId)
                                    if (updatedConversation != conversation) {
                                        onUpdateConversation(updatedConversation)
                                    }
                                },
                            )
                        }
                    }
                    if (index == conversation.truncateIndex - 1) {
                        ContextDivider(
                            label = stringResource(R.string.chat_page_clear_context),
                            enableHaptics = effectiveDisplay.enableUIHaptics,
                        )
                    }
                    if (
                        effectiveDisplay.showContextCompressionDivider &&
                        index != conversation.truncateIndex - 1
                    ) {
                        when {
                            index == pendingCompressionMarkerIndex -> {
                                ContextDivider(
                                    label = stringResource(R.string.chat_page_context_compressing),
                                    enableHaptics = effectiveDisplay.enableUIHaptics,
                                )
                            }
                            index in compressionMarkerIndexes -> {
                                val showEditAction = index == latestCompressionMarkerIndex &&
                                    !conversation.contextSummary.isNullOrBlank()
                                ContextDivider(
                                    label = stringResource(R.string.chat_page_context_compressed),
                                    showEditAction = showEditAction,
                                    isEditActionEnabled = pendingCompressionMarkerIndex == null,
                                    onEditActionClick = onEditContextSummary,
                                    enableHaptics = effectiveDisplay.enableUIHaptics,
                                )
                            }
                        }
                    }
                }
            }

            if (loading) {
                item(LoadingIndicatorKey) {
                    Box(
                        modifier = Modifier.onSizeChanged { size ->
                            loadingIndicatorHeightPx = size.height
                        }
                    ) {
                        LoadingIndicator()
                    }
                }
            }

            // 为了能正确滚动到这
            item(ScrollBottomKey) {
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .height(ScrollBottomBaseHeight + sendScrollDynamicSpacerHeight)
                )
            }
        }

        displayedToolPreviewContent?.let { preview ->
            ToolCallPreviewSheet(
                toolCallId = preview.toolCallId,
                toolName = preview.toolName,
                arguments = preview.arguments,
                content = preview.content,
                metadata = preview.metadata,
                hasResult = preview.hasResult,
                searchAgentSelectedTabStates = toolPreviewTabStates,
                onDismissRequest = {
                    activeToolPreviewKey = null
                    activeToolPreviewSnapshot = null
                },
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // 完成选择
            AnimatedVisibility(
                visible = selecting,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = -(48).dp),
                enter = slideInVertically(
                    initialOffsetY = { it * 2 },
                ),
                exit = slideOutVertically(
                    targetOffsetY = { it * 2 },
                ),
            ) {
                HorizontalFloatingToolbar(
                    expanded = true,
                ) {
                    Tooltip(
                        tooltip = {
                            Text(stringResource(R.string.chat_list_tooltip_clear_selection))
                        }
                    ) {
                        IconButton(
                            onClick = {
                                selecting = false
                                selectedItems.clear()
                            }
                        ) {
                            Icon(Icons.Rounded.Close, null)
                        }
                    }
                    Tooltip(
                        tooltip = {
                            Text(stringResource(R.string.chat_list_tooltip_select_all))
                        }
                    ) {
                        IconButton(
                            onClick = {
                                if (selectedItems.isNotEmpty()) {
                                    selectedItems.clear()
                                } else {
                                    selectedItems.addAll(conversation.messageNodes.map { it.id })
                                }
                            }
                        ) {
                            Icon(Icons.Rounded.TouchApp, null)
                        }
                    }
                    Tooltip(
                        tooltip = {
                            Text(stringResource(R.string.chat_list_tooltip_confirm))
                        }
                    ) {
                        FilledIconButton(
                            onClick = {
                                selecting = false
                                val messages = conversation.messageNodes.filter { it.id in selectedItems }
                                if (messages.isNotEmpty()) {
                                    showExportSheet = true
                                }
                            }
                        ) {
                            Icon(Icons.Rounded.Check, null)
                        }
                    }
                }
            }

            // 导出对话框
            ChatExportSheet(
                visible = showExportSheet,
                onDismissRequest = {
                    showExportSheet = false
                    selectedItems.clear()
                },
                conversation = conversation,
                selectedMessages = conversation.messageNodes.filter { it.id in selectedItems }
                    .map { it.currentMessage }
            )

            val captureProgress = LocalScrollCaptureInProgress.current

            // 消息快速跳转
            MessageJumper(
                show = isRecentScroll && !state.isScrollInProgress && effectiveDisplay.showMessageJumper && !captureProgress,
                onLeft = effectiveDisplay.messageJumperOnLeft,
                scope = scope,
                state = state
            )

            AnimatedVisibility(
                visible = canLoadOlderHistory && (isAtListTop || loadingOlderState),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp),
                enter = slideInVertically(initialOffsetY = { -it / 2 }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it / 2 }) + fadeOut(),
            ) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp),
                    tonalElevation = 4.dp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable(enabled = canTriggerLoadOlder) {
                            onLoadOlderHistory()
                        }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        if (loadingOlderState) {
                            LoadingIndicator(
                                modifier = Modifier.size(16.dp),
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.KeyboardDoubleArrowUp,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Text(
                            text = if (loadingOlderState) {
                                stringResource(R.string.chat_page_loading_older_messages)
                            } else {
                                stringResource(R.string.chat_page_load_older_messages)
                            },
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextDivider(
    label: String,
    showEditAction: Boolean = false,
    isEditActionEnabled: Boolean = true,
    onEditActionClick: () -> Unit = {},
    enableHaptics: Boolean = true,
) {
    val haptics = rememberPremiumHaptics(enabled = enableHaptics)
    val editInteractionSource = remember { MutableInteractionSource() }
    val isEditPressed by editInteractionSource.collectIsPressedAsState()
    val editScale by animateFloatAsState(
        targetValue = if (showEditAction && isEditActionEnabled && isEditPressed) 0.85f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "context_divider_edit_scale",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .padding(vertical = 8.dp)
            .fillMaxWidth()
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
            )
            if (showEditAction) {
                IconButton(
                    onClick = {
                        haptics.perform(HapticPattern.Pop)
                        onEditActionClick()
                    },
                    enabled = isEditActionEnabled,
                    interactionSource = editInteractionSource,
                    modifier = Modifier
                        .size(28.dp)
                        .graphicsLayer {
                            scaleX = editScale
                            scaleY = editScale
                        },
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = stringResource(R.string.chat_page_edit_context_summary),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

/**
 * 提取包含搜索词的文本片段，确保匹配词在开头可见
 */
private fun extractMatchingSnippet(
    text: String,
    query: String
): String {
    if (query.isBlank()) {
        return text
    }

    val matchIndex = text.indexOf(query, ignoreCase = true)
    if (matchIndex == -1) {
        return text
    }

    // 直接从匹配词开始显示，确保匹配词在最前面
    val snippet = text.substring(matchIndex)

    // 只在前面有内容时添加省略号
    return if (matchIndex > 0) {
        "...$snippet"
    } else {
        snippet
    }
}

private fun buildHighlightedText(
    text: String,
    query: String,
    highlightColor: Color
): AnnotatedString {
    if (query.isBlank()) {
        return AnnotatedString(text)
    }

    return buildAnnotatedString {
        var startIndex = 0
        var index = text.indexOf(query, startIndex, ignoreCase = true)

        while (index >= 0) {
            // 添加高亮前的文本
            append(text.substring(startIndex, index))

            // 添加高亮文本
            withStyle(
                style = SpanStyle(
                    background = highlightColor,
                    color = Color.Black
                )
            ) {
                append(text.substring(index, index + query.length))
            }

            startIndex = index + query.length
            index = text.indexOf(query, startIndex, ignoreCase = true)
        }

        // 添加剩余文本
        if (startIndex < text.length) {
            append(text.substring(startIndex))
        }
    }
}

@Composable
private fun SharedTransitionScope.ChatListPreview(
    innerPadding: PaddingValues,
    conversation: Conversation,
    settings: Settings,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onJumpToMessage: (Uuid) -> Unit,
    initialSearchQuery: String? = null,
) {
    var searchQuery by remember { mutableStateOf(initialSearchQuery ?: "") }
    val keyboardController = LocalSoftwareKeyboardController.current
    val haptics = rememberPremiumHaptics()

    // Filter messages
    val filteredMessages = remember(conversation.messageNodes, searchQuery) {
        if (searchQuery.isBlank()) {
            conversation.messageNodes
        } else {
            conversation.messageNodes.filterIndexed { index, node ->
                node.currentMessage.toContentText().contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Column(
        modifier = Modifier
            .padding(bottom = innerPadding.calculateBottomPadding())
            .fillMaxSize(),
    ) {
        // 搜索框 (kept below the status bar so it isn't hidden under the floating top bar)
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .statusBarsPadding()
                .padding(top = 56.dp)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.chat_page_search_placeholder)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.a11y_clear),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            },
            singleLine = true,
            shape = CircleShape,
            maxLines = 1,
        )

        // 消息预览
        LazyColumn(
            contentPadding = PaddingValues(16.dp) + PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .sharedBounds(
                    sharedContentState = rememberSharedContentState(key = "conversation_list"),
                    animatedVisibilityScope = animatedVisibilityScope
                )
                .fillMaxWidth()
                .weight(1f),
        ) {
            itemsIndexed(
                items = filteredMessages,
                key = { index, item -> item.id },
            ) { _, node ->
                val message = node.currentMessage
                val isUser = message.role == me.rerere.ai.core.MessageRole.USER
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .then(
                            if (!isUser) Modifier.padding(end = 24.dp) else Modifier
                        ),
                    horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Row(
                            modifier = Modifier
                                .clickable {
                                    haptics.perform(HapticPattern.Pop)
                                    keyboardController?.hide()
                                    onJumpToMessage(node.id)
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val highlightColor = MaterialTheme.colorScheme.tertiaryContainer
                            val highlightedText = remember(searchQuery, message) {
                                val fullText = message.toContentText().ifBlank { "[...]" }
                                val messageText = extractMatchingSnippet(
                                    text = fullText,
                                    query = searchQuery
                                )
                                buildHighlightedText(
                                    text = messageText,
                                    query = searchQuery,
                                    highlightColor = highlightColor
                                )
                            }
                            Text(
                                text = highlightedText,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun BoxScope.MessageJumper(
    show: Boolean,
    onLeft: Boolean,
    scope: CoroutineScope,
    state: LazyListState
) {
    AnimatedVisibility(
        visible = show,
        modifier = Modifier.align(if (onLeft) Alignment.CenterStart else Alignment.CenterEnd),
        enter = slideInHorizontally(
            initialOffsetX = { if (onLeft) -it * 2 else it * 2 },
        ),
        exit = slideOutHorizontally(
            targetOffsetX = { if (onLeft) -it * 2 else it * 2 },
        )
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                onClick = {
                    scope.launch {
                        state.animateScrollToItem(0)
                    }
                },
                shape = CircleShape,
                tonalElevation = 4.dp,
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(
                    4.dp
                ).copy(alpha = 0.65f)
            ) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardDoubleArrowUp,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(4.dp)
                )
            }
            Surface(
                onClick = {
                    scope.launch {
                        state.animateScrollToItem(
                            (state.firstVisibleItemIndex - 1).fastCoerceAtLeast(
                                0
                            )
                        )
                    }
                },
                shape = CircleShape,
                tonalElevation = 4.dp,
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(
                    4.dp
                ).copy(alpha = 0.65f)
            ) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowUp,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(4.dp)
                )
            }
            Surface(
                onClick = {
                    scope.launch {
                        state.animateScrollToItem(state.firstVisibleItemIndex + 1)
                    }
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(
                    4.dp
                ).copy(alpha = 0.65f)
            ) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(4.dp)
                )
            }
            Surface(
                onClick = {
                    scope.launch {
                        state.animateScrollToItem(state.layoutInfo.totalItemsCount - 1)
                    }
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(
                    4.dp
                ).copy(alpha = 0.65f),
            ) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardDoubleArrowDown,
                    contentDescription = stringResource(R.string.chat_page_scroll_to_bottom),
                    modifier = Modifier
                        .padding(4.dp)
                )
            }
        }
    }
}
