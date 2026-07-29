package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.Model
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.AskUserState
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.getEffectiveDisplaySetting
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.theme.AppShapes
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.extractGeminiThinkingTitle
import me.rerere.rikkahub.utils.extractGeminiLastSection
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import org.koin.compose.koinInject
import me.rerere.rikkahub.data.ai.tools.SearchAgentProgress
import me.rerere.rikkahub.data.ai.tools.SearchAgentStep
import java.util.Locale
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.uuid.Uuid

private val ProcessEmptyJson = JsonObject(emptyMap())
private val ProcessHeaderTextStyle
    @Composable get() = MaterialTheme.typography.bodyMedium.copy(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold,
    )
private val ProcessStepTitleTextStyle
    @Composable get() = MaterialTheme.typography.bodyMedium.copy(
        fontSize = 14.sp,
        lineHeight = 19.sp,
        fontWeight = FontWeight.Medium,
    )
private val ProcessStepSubtitleTextStyle
    @Composable get() = MaterialTheme.typography.labelSmall.copy(
        fontSize = 11.sp,
        lineHeight = 14.sp,
    )
enum class ReasoningBodyState {
    Collapsed,
    Preview,
    Expanded,
}

internal fun processReasoningStateKey(part: UIMessagePart): String? {
    return when (part) {
        is UIMessagePart.Reasoning -> "reasoning:${part.createdAt}"
        is UIMessagePart.Thinking -> "thinking:${part.createdAt}"
        else -> null
    }
}

@Composable
internal fun ChatProcessTimeline(
    processParts: List<UIMessagePart>,
    conversationId: Uuid?,
    hiddenToolCallIds: Set<String>,
    loading: Boolean,
    model: Model?,
    assistant: Assistant?,
    reasoningBodyStates: SnapshotStateMap<String, ReasoningBodyState>? = null,
    onOpenToolPreview: (toolCallId: String, toolName: String, hasResult: Boolean) -> Unit = { _, _, _ -> },
    streamingContentUpdateIntervalMs: Long = 0L,
    modifier: Modifier = Modifier,
) {
    if (processParts.isEmpty()) return
    val displayedProcessParts = rememberThrottledStreamingValue(
        value = processParts,
        intervalMs = streamingContentUpdateIntervalMs,
        key = conversationId,
    )

    val localReasoningBodyStates = remember { mutableStateMapOf<String, ReasoningBodyState>() }
    val resolvedReasoningBodyStates = reasoningBodyStates ?: localReasoningBodyStates

    val toolApprovalsById = remember(displayedProcessParts) {
        displayedProcessParts.filterIsInstance<UIMessagePart.ToolApproval>()
            .associateBy { it.toolCallId }
    }
    val toolCallArgumentsById = remember(displayedProcessParts) {
        displayedProcessParts.filterIsInstance<UIMessagePart.ToolCall>()
            .associate { toolCall ->
                val parsedArguments = runCatching {
                    JsonInstant.parseToJsonElement(toolCall.arguments)
                }.getOrElse { ProcessEmptyJson }
                toolCall.toolCallId to parsedArguments
            }
    }
    val hiddenResolvedToolCallIds = remember(displayedProcessParts, hiddenToolCallIds) {
        hiddenToolCallIds + displayedProcessParts
            .filterIsInstance<UIMessagePart.ToolResult>()
            .mapNotNull { it.toolCallId.takeIf(String::isNotBlank) }
            .toSet()
    }
    val visibleParts = remember(displayedProcessParts, hiddenResolvedToolCallIds, toolApprovalsById) {
        displayedProcessParts.filter { part ->
            when (part) {
                is UIMessagePart.ToolCall -> {
                    val shouldHide = part.toolCallId.isNotBlank() &&
                        part.toolCallId in hiddenResolvedToolCallIds &&
                        toolApprovalsById[part.toolCallId] == null
                    !shouldHide
                }

                else -> part.isProcessDisplayPart()
            }
        }
    }
    if (visibleParts.isEmpty()) return

    val settings = LocalSettings.current
    val haptics = rememberPremiumHaptics(enabled = settings.displaySetting.enableUIHaptics)
    var expanded by remember { mutableStateOf(true) }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "process_timeline_group_scale",
    )

    Card(
        modifier = modifier
            .fillMaxWidth(),
        shape = AppShapes.CardLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.36f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .animateContentSize(
                    animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f)
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (visibleParts.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                        ) {
                            expanded = !expanded
                            haptics.perform(HapticPattern.Pop)
                        }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = if (expanded) {
                            Icons.Rounded.KeyboardArrowUp
                        } else {
                            Icons.Rounded.KeyboardArrowDown
                        },
                        contentDescription = if (expanded) {
                            stringResource(R.string.a11y_collapse)
                        } else {
                            stringResource(R.string.a11y_expand)
                        },
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = if (expanded) {
                            stringResource(R.string.a11y_collapse)
                        } else {
                            stringResource(R.string.a11y_expand)
                        },
                        style = ProcessHeaderTextStyle,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }

            if (visibleParts.size == 1 || expanded) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    visibleParts.forEachIndexed { index, part ->
                        ProcessTimelineStep(
                            part = part,
                            isLast = index == visibleParts.lastIndex,
                            conversationId = conversationId,
                            toolCallArgumentsById = toolCallArgumentsById,
                            reasoningBodyStates = resolvedReasoningBodyStates,
                            onOpenToolPreview = onOpenToolPreview,
                            loading = loading,
                            model = model,
                            assistant = assistant,
                            streamingContentUpdateIntervalMs = streamingContentUpdateIntervalMs,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProcessTimelineStep(
    part: UIMessagePart,
    isLast: Boolean,
    conversationId: Uuid?,
    toolCallArgumentsById: Map<String, JsonElement>,
    reasoningBodyStates: SnapshotStateMap<String, ReasoningBodyState>,
    onOpenToolPreview: (toolCallId: String, toolName: String, hasResult: Boolean) -> Unit,
    loading: Boolean,
    model: Model?,
    assistant: Assistant?,
    streamingContentUpdateIntervalMs: Long,
) {
    Box(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Spacer(modifier = Modifier.width(18.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 1.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                when (part) {
                    is UIMessagePart.Reasoning -> CompactReasoningTimelineItem(
                        reasoning = part,
                        stateKey = processReasoningStateKey(part) ?: "reasoning:${part.createdAt}",
                        reasoningBodyStates = reasoningBodyStates,
                        model = model,
                        assistant = assistant,
                        streamingContentUpdateIntervalMs = streamingContentUpdateIntervalMs,
                    )

                    is UIMessagePart.Thinking -> CompactReasoningTimelineItem(
                        reasoning = UIMessagePart.Reasoning(
                            reasoning = part.thinking,
                            createdAt = part.createdAt,
                            finishedAt = part.finishedAt,
                            metadata = part.metadata,
                        ),
                        stateKey = processReasoningStateKey(part) ?: "thinking:${part.createdAt}",
                        reasoningBodyStates = reasoningBodyStates,
                        model = model,
                        assistant = assistant,
                        streamingContentUpdateIntervalMs = streamingContentUpdateIntervalMs,
                    )

                    is UIMessagePart.ToolCall -> {
                        val parsedArguments = toolCallArgumentsById[part.toolCallId] ?: runCatching {
                            JsonInstant.parseToJsonElement(part.arguments)
                        }.getOrElse { ProcessEmptyJson }
                        CompactToolTimelineItem(
                            toolCallId = part.toolCallId,
                            toolName = part.toolName,
                            arguments = parsedArguments,
                            content = null,
                            metadata = null,
                            onOpenToolPreview = onOpenToolPreview,
                            loading = loading,
                        )
                    }

                    is UIMessagePart.ToolResult -> {
                        CompactToolTimelineItem(
                            toolCallId = part.toolCallId,
                            toolName = part.toolName,
                            arguments = part.arguments,
                            content = part.content,
                            metadata = part.metadata,
                            onOpenToolPreview = onOpenToolPreview,
                            loading = false,
                        )
                    }

                    is UIMessagePart.ToolApproval -> CompactApprovalTimelineItem(
                        conversationId = conversationId,
                        approval = part,
                        arguments = toolCallArgumentsById[part.toolCallId] ?: ProcessEmptyJson,
                        loading = loading && part.state == ToolApprovalState.Approved,
                    )

                    is UIMessagePart.AskUser -> CompactAskUserTimelineItem(
                        conversationId = conversationId,
                        askUser = part,
                        loading = loading && part.state == AskUserState.Pending,
                    )

                    else -> Unit
                }
            }
        }

        Box(
            modifier = Modifier.matchParentSize(),
        ) {
            Column(
                modifier = Modifier
                    .width(18.dp)
                    .fillMaxHeight()
                    .align(Alignment.TopStart),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = processTimelineIcon(part),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = 5.75.dp)
                        .size(14.dp),
                )
                if (!isLast) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .weight(1f)
                            .background(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f),
                                shape = MaterialTheme.shapes.extraSmall,
                            )
                    )
                }
            }
        }
    }
}

// 预览尾部最少保留的字符数：88dp 窗口最多显示几行，1500 字足够撑满并留出滚动余量
private const val ReasoningPreviewTailMinChars = 1500

// 裁剪点按 1000 字一档推进：两次裁剪之间内容高度持续增长，自动跟底动画保持平滑；
// 裁剪瞬间视口锚定在底部，画面里的文字不变，用户无感。若每 tick 都推进裁剪点，
// 总高度不再变化，跟随动画会退化成逐行跳变
private const val ReasoningPreviewTrimStepChars = 1000

// 对齐换行符时允许向后查找的范围，避免一段超长无换行文本把尾部裁得过短
private const val ReasoningPreviewNewlineSearchRange = 400

/**
 * 取思维链尾部一段（约 1500~2500 字）用于流式预览渲染。
 * 裁剪点尽量对齐到换行，减少首行残句随流式输出反复跳变；
 * 无法对齐换行时避开 emoji 代理对和代码围栏本身；
 * 若裁掉的前文含奇数个代码围栏（```），说明裁剪点位于代码块内部，补一个围栏保持渲染一致。
 */
internal fun String.reasoningPreviewTail(): String {
    if (length <= ReasoningPreviewTailMinChars + ReasoningPreviewTrimStepChars) return this
    var cut = (length - ReasoningPreviewTailMinChars) / ReasoningPreviewTrimStepChars * ReasoningPreviewTrimStepChars
    val newline = indexOf('\n', cut)
    if (newline >= 0 && newline < cut + ReasoningPreviewNewlineSearchRange && newline < length - 1) {
        cut = newline + 1
    } else {
        while (cut < length && (get(cut).isLowSurrogate() || get(cut) == '`')) cut++
    }
    val tail = substring(cut)
    var fenceCount = 0
    var fenceIndex = indexOf("```")
    while (fenceIndex in 0 until cut) {
        fenceCount++
        fenceIndex = indexOf("```", fenceIndex + 3)
    }
    return if (fenceCount % 2 == 1) "```\n$tail" else tail
}

@Composable
private fun CompactReasoningTimelineItem(
    reasoning: UIMessagePart.Reasoning,
    stateKey: String,
    reasoningBodyStates: SnapshotStateMap<String, ReasoningBodyState>,
    model: Model?,
    assistant: Assistant?,
    streamingContentUpdateIntervalMs: Long,
) {
    val settings = LocalSettings.current
    val effectiveDisplay = remember(settings, assistant) { settings.getEffectiveDisplaySetting(assistant) }
    val haptics = rememberPremiumHaptics(enabled = settings.displaySetting.enableUIHaptics)
    val loading = reasoning.finishedAt == null
    val scrollState = rememberScrollState()
    val defaultBodyState = if (loading) {
        ReasoningBodyState.Preview
    } else {
        ReasoningBodyState.Collapsed
    }
    val bodyState = reasoningBodyStates[stateKey] ?: defaultBodyState
    var duration by remember(reasoning.finishedAt, reasoning.createdAt) {
        mutableStateOf(
            reasoning.finishedAt?.let { it - reasoning.createdAt } ?: (Clock.System.now() - reasoning.createdAt)
        )
    }

    LaunchedEffect(stateKey, loading) {
        if (reasoningBodyStates[stateKey] == null) {
            reasoningBodyStates[stateKey] = defaultBodyState
        }
    }

    LaunchedEffect(stateKey, loading, effectiveDisplay.autoCloseThinking) {
        val currentState = reasoningBodyStates[stateKey] ?: defaultBodyState
        if (loading) {
            if (currentState == ReasoningBodyState.Collapsed) {
                reasoningBodyStates[stateKey] = ReasoningBodyState.Preview
            }
        } else {
            val targetState = if (effectiveDisplay.autoCloseThinking) {
                ReasoningBodyState.Collapsed
            } else {
                ReasoningBodyState.Expanded
            }
            if (currentState != targetState) {
                reasoningBodyStates[stateKey] = targetState
            }
        }
    }

    LaunchedEffect(loading, streamingContentUpdateIntervalMs) {
        if (loading) {
            val durationUpdateIntervalMs = streamingContentUpdateIntervalMs.takeIf { it > 0L } ?: 50L
            while (isActive) {
                duration = (reasoning.finishedAt ?: Clock.System.now()) - reasoning.createdAt
                delay(durationUpdateIntervalMs)
            }
        }
    }

    val isGemini = model != null && ModelRegistry.GEMINI_SERIES.match(model.modelId)

    // 用滚动范围变化驱动自动跟底，而不是拿思维链全文当 key：
    // 后者每个流式分块都会重启协程，动画永不收敛
    LaunchedEffect(loading, bodyState, isGemini) {
        if (loading && bodyState == ReasoningBodyState.Preview && !isGemini) {
            snapshotFlow { scrollState.maxValue }
                .collectLatest { max ->
                    // 首次布局前 maxValue 是 Int.MAX_VALUE 占位值，跳过
                    if (max != Int.MAX_VALUE && scrollState.value < max) {
                        scrollState.animateScrollTo(max)
                    }
                }
        }
    }

    val geminiTitle = remember(reasoning.reasoning, model) {
        if (loading && isGemini) {
            reasoning.reasoning.extractGeminiThinkingTitle()
        } else {
            null
        }
    }

    Column {
        ProcessStepRow(
            title = if (duration > 0.seconds) {
                stringResource(
                    R.string.chat_process_reasoning_duration,
                    formatDurationSeconds(duration),
                )
            } else {
                stringResource(R.string.notification_live_update_inference)
            },
            subtitle = geminiTitle?.takeIf { bodyState == ReasoningBodyState.Collapsed },
            trailingIcon = if (bodyState == ReasoningBodyState.Expanded) {
                Icons.Rounded.KeyboardArrowUp
            } else {
                Icons.Rounded.KeyboardArrowDown
            },
            onClick = {
                reasoningBodyStates[stateKey] = when (bodyState) {
                    ReasoningBodyState.Collapsed,
                    ReasoningBodyState.Preview,
                        -> ReasoningBodyState.Expanded

                    ReasoningBodyState.Expanded -> ReasoningBodyState.Collapsed
                }
                haptics.perform(HapticPattern.Pop)
            },
        )

        if (bodyState != ReasoningBodyState.Collapsed) {
            Spacer(modifier = Modifier.height(4.dp))
            SelectionContainer {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize(
                            animationSpec = spring(dampingRatio = 0.5f, stiffness = 400f)
                        )
                ) {
                    if (isGemini && loading && bodyState == ReasoningBodyState.Preview) {
                        val geminiSectionText = remember(reasoning.reasoning, assistant?.regexes) {
                            reasoning.reasoning.extractGeminiLastSection().replaceRegexes(
                                assistant = assistant,
                                scope = AssistantAffectScope.ASSISTANT,
                                visual = true,
                            )
                        }
                        AnimatedContent(
                            targetState = geminiTitle ?: "",
                            transitionSpec = {
                                (slideInVertically { it } + fadeIn()) togetherWith
                                        (slideOutVertically { -it } + fadeOut())
                            },
                        ) {
                            MarkdownBlock(
                                content = geminiSectionText,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 4.dp, end = 8.dp),
                                lazyRenderOffscreen = true,
                            )
                        }
                    } else {
                        // 预览窗口只有 88dp 高，整篇思维链参与排版是长思维链流式卡顿的大头，
                        // 预览态只取尾部一段；展开态仍渲染全文
                        val reasoningDisplayText = remember(reasoning.reasoning, assistant?.regexes, bodyState) {
                            val source = if (bodyState == ReasoningBodyState.Preview) {
                                reasoning.reasoning.reasoningPreviewTail()
                            } else {
                                reasoning.reasoning
                            }
                            source.replaceRegexes(
                                assistant = assistant,
                                scope = AssistantAffectScope.ASSISTANT,
                                visual = true,
                            )
                        }
                        MarkdownBlock(
                            content = reasoningDisplayText,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (bodyState == ReasoningBodyState.Preview) {
                                        Modifier
                                            .heightIn(max = 88.dp)
                                            .clipToBounds()
                                            .verticalScroll(scrollState)
                                    } else {
                                        Modifier
                                    }
                                )
                                .padding(start = 4.dp, end = 8.dp),
                            lazyRenderOffscreen = true,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactToolTimelineItem(
    toolCallId: String,
    toolName: String,
    arguments: JsonElement,
    content: JsonElement?,
    metadata: JsonObject?,
    onOpenToolPreview: (toolCallId: String, toolName: String, hasResult: Boolean) -> Unit,
    loading: Boolean,
) {
    val settings = LocalSettings.current
    val haptics = rememberPremiumHaptics(enabled = settings.displaySetting.enableUIHaptics)
    val chatService = koinInject<ChatService>()
    val progressStore = chatService.searchAgentProgressStore
    // search_agent 实时进度订阅（仅执行中有数据）
    val progress by if (toolName == "search_agent") {
        progressStore.stateOf(toolCallId).collectAsState()
    } else {
        remember { mutableStateOf(null as SearchAgentProgress?) }
    }
    val isSandboxShell = toolName == "sandbox_shell"
    val title = if (isSandboxShell) {
        shellCommandPreview(arguments)
            ?: toolTimelineTitle(toolName = toolName, arguments = arguments)
    } else {
        toolTimelineTitle(toolName = toolName, arguments = arguments)
    }
    val subtitle = toolTimelineSubtitle(
        toolName = toolName,
        arguments = arguments,
        content = content,
        loading = loading,
        progress = progress,
    )
    var showArgumentsSheet by remember(toolName, arguments) { mutableStateOf(false) }

    ProcessStepRow(
        title = title,
        subtitle = subtitle,
        subtitleIsCode = isSandboxShell && content != null,
        trailing = {
            if (loading && content == null) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.secondary,
                )
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        onClick = {
            haptics.perform(HapticPattern.Pop)
            if (content != null || toolName == "search_agent") {
                onOpenToolPreview(toolCallId, toolName, content != null)
            } else {
                showArgumentsSheet = true
            }
        },
    )

    if (showArgumentsSheet) {
        ToolApprovalArgumentsSheet(
            approvalLabel = title,
            argumentsJson = remember(arguments) { JsonInstantPretty.encodeToString(arguments) },
            onDismissRequest = {
                showArgumentsSheet = false
            }
        )
    }

}

@Composable
private fun CompactApprovalTimelineItem(
    conversationId: Uuid?,
    approval: UIMessagePart.ToolApproval,
    arguments: JsonElement,
    loading: Boolean,
) {
    val settings = LocalSettings.current
    val haptics = rememberPremiumHaptics(enabled = settings.displaySetting.enableUIHaptics)
    val chatService = koinInject<ChatService>()
    val approvalLabel = toolApprovalDisplayName(approval.toolName)
    val argumentsJson = remember(arguments) { JsonInstantPretty.encodeToString(arguments) }
    val canRespond = conversationId != null && approval.toolCallId.isNotBlank()
    var locked by remember(approval.toolName, approval.state) { mutableStateOf(false) }
    var expanded by remember(approval.toolCallId, approval.state) { mutableStateOf(approval.state == ToolApprovalState.Pending) }
    var showArgumentsSheet by remember(approval.toolCallId) { mutableStateOf(false) }

    val subtitle = when (approval.state) {
        ToolApprovalState.Pending -> stringResource(R.string.mcp_tool_approval_subtitle, approvalLabel)
        ToolApprovalState.Approved -> stringResource(R.string.mcp_tool_approval_approved_calling, approvalLabel)
        ToolApprovalState.Rejected -> stringResource(R.string.mcp_tool_approval_rejected_result)
    }

    ProcessStepRow(
        title = stringResource(R.string.mcp_tool_approval_title),
        subtitle = subtitle,
        trailing = {
            when (approval.state) {
                ToolApprovalState.Pending -> {
                    Icon(
                        imageVector = if (expanded) {
                            Icons.Rounded.KeyboardArrowUp
                        } else {
                            Icons.Rounded.KeyboardArrowDown
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                ToolApprovalState.Approved -> {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                ToolApprovalState.Rejected -> {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        onClick = {
            haptics.perform(HapticPattern.Pop)
            if (approval.state == ToolApprovalState.Pending) {
                expanded = !expanded
            } else {
                showArgumentsSheet = true
            }
        },
    )

    if (expanded && approval.state == ToolApprovalState.Pending) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                onClick = {
                    haptics.perform(HapticPattern.Pop)
                    showArgumentsSheet = true
                },
                modifier = Modifier.align(Alignment.Start),
            ) {
                Text(text = stringResource(R.string.tool_approval_view_params))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolApprovalButton(
                    text = stringResource(R.string.mcp_tool_approval_approve),
                    icon = Icons.Rounded.Check,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    enabled = !locked && canRespond,
                    onClick = {
                        if (locked || !canRespond) return@ToolApprovalButton
                        locked = true
                        haptics.perform(HapticPattern.Pop)
                        chatService.respondToolApproval(
                            conversationId = conversationId ?: return@ToolApprovalButton,
                            toolCallId = approval.toolCallId,
                            approved = true,
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
                ToolApprovalButton(
                    text = stringResource(R.string.mcp_tool_approval_reject),
                    icon = Icons.Rounded.Close,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    enabled = !locked && canRespond,
                    onClick = {
                        if (locked || !canRespond) return@ToolApprovalButton
                        locked = true
                        haptics.perform(HapticPattern.Pop)
                        chatService.respondToolApproval(
                            conversationId = conversationId ?: return@ToolApprovalButton,
                            toolCallId = approval.toolCallId,
                            approved = false,
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    if (showArgumentsSheet) {
        ToolApprovalArgumentsSheet(
            approvalLabel = approvalLabel,
            argumentsJson = argumentsJson,
            onDismissRequest = {
                showArgumentsSheet = false
            }
        )
    }
}

@Composable
private fun ProcessStepRow(
    title: String,
    subtitle: String? = null,
    subtitleIsCode: Boolean = false,
    trailingIcon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val rowModifier = Modifier
        .fillMaxWidth()
        .then(
            if (onClick != null) {
                Modifier.clickable(onClick = onClick)
            } else {
                Modifier
            }
        )
        .padding(vertical = 0.dp)

    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = title,
                style = ProcessStepTitleTextStyle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = if (subtitleIsCode) {
                        ProcessStepSubtitleTextStyle.copy(fontFamily = FontFamily.Monospace)
                    } else {
                        ProcessStepSubtitleTextStyle
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (trailing != null) {
            trailing()
        } else if (trailingIcon != null) {
            Icon(
                imageVector = trailingIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun toolTimelineTitle(
    toolName: String,
    arguments: JsonElement,
): String {
    val skillNameOrId = if (toolName == "read_skill_file") {
        arguments.jsonObject["skill_name"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: arguments.jsonObject["skill_id"]?.jsonPrimitiveOrNull?.contentOrNull
    } else {
        null
    }
    val scriptName = if (toolName == "run_skill_script") {
        arguments.jsonObject["path"]?.jsonPrimitiveOrNull?.contentOrNull
            ?.replace('\\', '/')
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
    } else {
        null
    }

    return when (toolName) {
        "create_memory" -> stringResource(R.string.chat_message_tool_create_memory)
        "edit_memory" -> stringResource(R.string.chat_message_tool_edit_memory)
        "delete_memory" -> stringResource(R.string.chat_message_tool_delete_memory)
        "search_agent" -> stringResource(R.string.chat_message_tool_search_agent)
        "search_web" -> stringResource(
            R.string.chat_message_tool_search_web,
            arguments.jsonObject["query"]?.jsonPrimitiveOrNull?.contentOrNull ?: ""
        )
        "scrape_web" -> stringResource(R.string.chat_message_tool_scrape_web)
        "workspace_list" -> stringResource(
            R.string.chat_message_tool_workspace_list,
            arguments.jsonObject["path"]?.jsonPrimitiveOrNull?.contentOrNull?.ifBlank { "/" } ?: "/"
        )
        "workspace_read_file" -> stringResource(
            R.string.chat_message_tool_workspace_read_file,
            arguments.jsonObject["path"]?.jsonPrimitiveOrNull?.contentOrNull?.ifBlank { "/" } ?: "/"
        )
        "workspace_write_file" -> stringResource(
            R.string.chat_message_tool_workspace_write_file,
            arguments.jsonObject["path"]?.jsonPrimitiveOrNull?.contentOrNull?.ifBlank { "/" } ?: "/"
        )
        "workspace_mkdir" -> stringResource(
            R.string.chat_message_tool_workspace_mkdir,
            arguments.jsonObject["path"]?.jsonPrimitiveOrNull?.contentOrNull?.ifBlank { "/" } ?: "/"
        )
        "workspace_delete" -> stringResource(
            R.string.chat_message_tool_workspace_delete,
            arguments.jsonObject["path"]?.jsonPrimitiveOrNull?.contentOrNull?.ifBlank { "/" } ?: "/"
        )
        "workspace_rename" -> stringResource(
            R.string.chat_message_tool_workspace_rename,
            arguments.jsonObject["from"]?.jsonPrimitiveOrNull?.contentOrNull?.ifBlank { "/" } ?: "/",
            arguments.jsonObject["to"]?.jsonPrimitiveOrNull?.contentOrNull?.ifBlank { "/" } ?: "/"
        )
        "read_skill_file" -> {
            val name = skillNameOrId.orEmpty()
            if (name.isBlank()) {
                stringResource(R.string.chat_message_tool_call_generic, toolName)
            } else {
                stringResource(R.string.chat_message_tool_call_skill, name)
            }
        }
        "run_skill_script" -> {
            val name = scriptName.orEmpty()
            if (name.isBlank()) {
                stringResource(R.string.chat_message_tool_run_script_generic)
            } else {
                stringResource(R.string.chat_message_tool_run_script, name)
            }
        }
        "eval_python" -> stringResource(R.string.chat_message_tool_run_python_generic)
        "ask_user" -> stringResource(R.string.ask_user_answer_title)
        else -> stringResource(R.string.chat_message_tool_call_generic, toolName)
    }
}

@Composable
private fun toolTimelineSubtitle(
    toolName: String,
    arguments: JsonElement,
    content: JsonElement?,
    loading: Boolean,
    progress: SearchAgentProgress? = null,
): String? {
    val isRunning = loading && content == null
    return when (toolName) {
        // search_agent：执行中读 store 最新步骤动作；完成读 summary；出错读 error
        "search_agent" -> if (isRunning) {
            progress?.let { liveSearchAgentSubtitle(it) }
                ?: stringResource(R.string.notification_live_update_tool_call)
        } else {
            content?.jsonObject?.get("summary")?.jsonPrimitiveOrNull?.contentOrNull
                ?: content?.jsonObject?.get("notes")?.jsonArray?.firstOrNull()
                    ?.jsonPrimitiveOrNull?.contentOrNull
                ?: progress?.let { liveSearchAgentSubtitle(it) }
        }

        "create_memory", "edit_memory" -> {
            content?.jsonObject?.get("content")?.jsonPrimitiveOrNull?.contentOrNull
        }

        "search_web" -> {
            content?.jsonObject?.get("answer")?.jsonPrimitiveOrNull?.contentOrNull
                ?: content?.jsonObject?.get("items")?.jsonArray?.size
                    ?.takeIf { it > 0 }
                    ?.let { stringResource(R.string.chat_message_tool_search_results_count, it) }
        }

        "scrape_web" -> {
            arguments.jsonObject["url"]?.jsonPrimitiveOrNull?.contentOrNull
                ?: arguments.jsonObject["urls"]?.jsonArray
                    ?.firstOrNull()
                    ?.jsonPrimitiveOrNull
                    ?.contentOrNull
        }

        "sandbox_shell" -> {
            if (isRunning) {
                stringResource(R.string.notification_live_update_tool_call)
            } else {
                val preview = shellResultPreview(content)
                preview?.output ?: when (preview?.state) {
                    ShellResultState.TimedOut -> stringResource(R.string.chat_message_tool_shell_timed_out)
                    ShellResultState.Failed -> preview.exitCode?.let { exitCode ->
                        stringResource(R.string.chat_message_tool_shell_failed_exit_code, exitCode)
                    } ?: stringResource(R.string.chat_message_tool_shell_failed)
                    else -> null
                }
            }
        }

        "ask_user" -> {
            val singleAnswer = runCatching {
                content?.jsonObject?.get("answer")?.jsonPrimitiveOrNull?.contentOrNull
            }.getOrNull()
            if (singleAnswer != null) {
                singleAnswer
            } else {
                val answers = content?.jsonObject?.get("answers")?.jsonArray
                if (answers != null && answers.isNotEmpty()) {
                    answers.mapNotNull { it.jsonObjectOrNull?.get("answer")?.jsonPrimitiveOrNull?.contentOrNull }.joinToString(", ")
                } else if (content != null) {
                    stringResource(R.string.ask_user_no_reply)
                } else {
                    null
                }
            }
        }

        else -> if (isRunning) stringResource(R.string.notification_live_update_tool_call) else null
    }
}

/** search_agent 执行中副标题：基于 store 里最新一条步骤派生。 */
private fun liveSearchAgentSubtitle(progress: SearchAgentProgress): String? {
    val last = progress.steps.lastOrNull() ?: return null
    return when (last) {
        is SearchAgentStep.TaskStep -> last.text.lineSequence().firstOrNull()?.trim()?.takeIf(String::isNotBlank)
        is SearchAgentStep.ReasoningStep -> null
        is SearchAgentStep.ToolCallStep -> when (last.status) {
            SearchAgentStep.ToolCallStep.Status.Running ->
                // title 形如 "搜索：xxx" / "读取网页"，前缀换成"正在"
                "正在${last.title}"
            SearchAgentStep.ToolCallStep.Status.Done -> last.detail
        }
        is SearchAgentStep.ErrorStep ->
            if (last.detail.isNotBlank()) "出错：${last.detail}" else last.title
        is SearchAgentStep.FinalStep -> "完成总结"
    }
}

private fun processTimelineIcon(part: UIMessagePart): ImageVector {
    return when (part) {
        is UIMessagePart.Reasoning,
        is UIMessagePart.Thinking,
            -> Icons.Rounded.Lightbulb

        is UIMessagePart.ToolApproval -> Icons.Rounded.Extension
        is UIMessagePart.AskUser -> Icons.Rounded.HelpOutline
        is UIMessagePart.ToolCall -> processTimelineToolIcon(part.toolName)
        is UIMessagePart.ToolResult -> processTimelineToolIcon(part.toolName)
        else -> Icons.Rounded.Build
    }
}

private fun processTimelineToolIcon(toolName: String): ImageVector {
    return when (toolName) {
        "search_agent", "search_web", "scrape_web" -> Icons.Rounded.Public
        "run_skill_script", "eval_python", "sandbox_shell" -> Icons.Rounded.Terminal
        "create_memory", "edit_memory", "delete_memory" -> Icons.Rounded.Bookmark
        "ask_user" -> Icons.Rounded.HelpOutline
        else -> Icons.Rounded.Build
    }
}

@Composable
private fun CompactAskUserTimelineItem(
    conversationId: Uuid?,
    askUser: UIMessagePart.AskUser,
    loading: Boolean,
) {
    val chatService = koinInject<ChatService>()
    val settings = LocalSettings.current
    val haptics = rememberPremiumHaptics(enabled = settings.displaySetting.enableUIHaptics)
    var showSheet by remember(askUser.toolCallId) { mutableStateOf(false) }
    val canRespond = conversationId != null && askUser.toolCallId.isNotBlank() && askUser.state == AskUserState.Pending
    val multiQuestions = askUser.questions
    val isMultiQuestion = multiQuestions != null && multiQuestions.size > 1

    LaunchedEffect(askUser.toolCallId) {
        if (askUser.state == AskUserState.Pending) {
            showSheet = true
        }
    }

    val subtitle = if (isMultiQuestion) {
        "${multiQuestions!!.first().question}… (+${multiQuestions.size - 1})"
    } else {
        askUser.question
    }

    ProcessStepRow(
        title = stringResource(R.string.ask_user_step_title),
        subtitle = subtitle,
        trailing = {
            when (askUser.state) {
                AskUserState.Pending -> {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                AskUserState.Answered -> Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                AskUserState.Dismissed -> Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        },
        onClick = if (canRespond) ({
            haptics.perform(HapticPattern.Pop)
            showSheet = true
        }) else null,
    )

    if (showSheet && canRespond) {
        if (isMultiQuestion && multiQuestions != null) {
            AskUserWizardBottomSheet(
                questions = multiQuestions,
                onComplete = { combinedAnswers: String ->
                    showSheet = false
                    haptics.perform(HapticPattern.Success)
                    chatService.respondAskUser(
                        conversationId = conversationId ?: return@AskUserWizardBottomSheet,
                        toolCallId = askUser.toolCallId,
                        answer = combinedAnswers,
                    )
                },
                onDismissRequest = {
                    showSheet = false
                    haptics.perform(HapticPattern.Pop)
                },
            )
        } else {
            AskUserBottomSheet(
                question = askUser.question,
                options = askUser.options,
                onSelect = { answer ->
                    showSheet = false
                    haptics.perform(HapticPattern.Pop)
                    chatService.respondAskUser(
                        conversationId = conversationId ?: return@AskUserBottomSheet,
                        toolCallId = askUser.toolCallId,
                        answer = answer,
                    )
                },
                onDismissRequest = {
                    showSheet = false
                    haptics.perform(HapticPattern.Pop)
                },
            )
        }
    }
}

private fun formatDurationSeconds(duration: Duration): String {
    return String.format(Locale.US, "%.1f", duration.toDouble(DurationUnit.SECONDS))
}
