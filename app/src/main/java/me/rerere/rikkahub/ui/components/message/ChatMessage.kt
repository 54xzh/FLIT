package me.rerere.rikkahub.ui.components.message

import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.core.net.toFile
import androidx.core.net.toUri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Videocam
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyUIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.FontConfig
import me.rerere.rikkahub.data.datastore.FontSource
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.SessionMemory
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.richtext.buildMarkdownPreviewHtml
import me.rerere.rikkahub.ui.components.ui.Favicon
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.extendColors
import me.rerere.rikkahub.utils.base64Encode
import me.rerere.rikkahub.utils.formatNumber
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.urlDecode
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

private fun hasGlyphInConfiguredFont(context: android.content.Context, config: FontConfig, sample: String): Boolean? {
    val typeface = runCatching {
        when (config.fontSource) {
            FontSource.System -> ResourcesCompat.getFont(context, R.font.google_sans_flex)
            FontSource.SystemCode -> ResourcesCompat.getFont(context, R.font.google_sans_code)
            FontSource.Custom -> config.customFontPath
                ?.let { path -> kotlin.runCatching { Typeface.createFromFile(path) }.getOrNull() }
        }
    }.getOrNull() ?: return null

    val paint = Paint().apply { this.typeface = typeface }
    return runCatching { paint.hasGlyph(sample) }.getOrNull()
}

@Composable
private fun MarkdownFontDebugInfo(role: MessageRole) {
    val context = LocalContext.current
    val settings = LocalSettings.current
    if (!settings.developerMode || !settings.showMarkdownFontDebugInfo) return

    val contentConfig = settings.displaySetting.fontSettings.contentFont
    val style = LocalTextStyle.current
    val styleWeight = style.fontWeight?.weight
    val configuredWeight = contentConfig.weight.roundToInt()
    val styleFamily = style.fontFamily?.toString() ?: "null"
    val glyphZh = remember(contentConfig, context) { hasGlyphInConfiguredFont(context, contentConfig, "测试") }

    Text(
        text = "字体诊断 role=${role.name} 设备=${Build.MANUFACTURER} src=${contentConfig.fontSource.name} cfgW=$configuredWeight styleW=${styleWeight ?: "null"} zhGlyph=${glyphZh ?: "unknown"} family=$styleFamily",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
fun ChatMessage(
    node: MessageNode,
    previousRole: MessageRole?,
    isLast: Boolean,
    showAssistantHeader: Boolean = true,
    showInlineTokenUsage: Boolean = true,
    hiddenToolCallIds: Set<String> = emptySet(),
    leadingProcessParts: List<List<UIMessagePart>> = emptyList(),
    reasoningBodyStates: SnapshotStateMap<String, ReasoningBodyState>? = null,
    onOpenToolPreview: (toolCallId: String, toolName: String, hasResult: Boolean) -> Unit = { _, _, _ -> },
    conversationId: Uuid? = null,
    onCitationClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    isRecentlyRestored: Boolean = false,
    model: Model? = null,
    assistant: Assistant? = null,
    forceUseAssistantAvatar: Boolean = false,
    onAssistantAvatarLongPress: ((Assistant) -> Unit)? = null,
    onFork: () -> Unit,
    onRegenerate: () -> Unit,
    onContinue: () -> Unit,
    canContinue: Boolean,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onUpdate: (MessageNode) -> Unit,
    onEditLorebookEntry: ((me.rerere.ai.ui.UsedLorebookEntry) -> Unit)? = null,
    onModeClick: ((me.rerere.ai.ui.UsedMode) -> Unit)? = null,
    onMemoryClick: ((me.rerere.ai.ui.UsedMemory) -> Unit)? = null,
    currentSessionMemories: List<SessionMemory> = emptyList(),
    onUpdateSessionMemory: ((memoryId: Int, content: String) -> Unit)? = null,
    onDeleteSessionMemory: ((memoryId: Int) -> Unit)? = null,
) {
    val rawMessage = node.messages[node.selectIndex]
    val displayState = remember(rawMessage, leadingProcessParts) {
        buildChatMessageDisplayState(
            message = rawMessage,
            leadingDisplaySegments = leadingProcessParts,
        )
    }
    val message = displayState.message
    val settings = LocalSettings.current.displaySetting
    val textStyle = LocalTextStyle.current.copy(
        fontSize = LocalTextStyle.current.fontSize * settings.fontSizeRatio,
        lineHeight = LocalTextStyle.current.lineHeight * settings.fontSizeRatio
    )
    var showActionsSheet by remember { mutableStateOf(false) }
    var showSelectCopySheet by remember { mutableStateOf(false) }
    val navController = LocalNavController.current

    // Action buttons always shown when not loading
    val showInlineActions = !loading
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme

    // Fade-in animation for recently restored messages
    var hasAnimated by remember { mutableStateOf(!isRecentlyRestored) }
    val restoredAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (hasAnimated) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "restored_message_alpha"
    )
    LaunchedEffect(isRecentlyRestored) {
        if (isRecentlyRestored && !hasAnimated) {
            kotlinx.coroutines.delay(50)
            hasAnimated = true
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = restoredAlpha },
        horizontalAlignment = if (message.role == MessageRole.USER) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        val shouldShowMessageHeader = !message.parts.isEmptyUIMessage() && when (message.role) {
            MessageRole.ASSISTANT -> showAssistantHeader
            MessageRole.USER -> true
            else -> false
        }
        if (shouldShowMessageHeader) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                ChatMessageAssistantAvatar(
                    message = message,
                    previousRole = previousRole,
                    model = model,
                    assistant = assistant,
                    forceUseAssistantAvatar = forceUseAssistantAvatar,
                    onAvatarLongPress = onAssistantAvatarLongPress,
                    loading = loading,
                    modifier = Modifier.weight(1f)
                )
                ChatMessageUserAvatar(
                    message = message,
                    previousRole = previousRole,
                    avatar = settings.userAvatar,
                    nickname = settings.userNickname,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        ProvideTextStyle(textStyle) {
            MessagePartsBlock(
                assistant = assistant,
                role = message.role,
                parts = message.parts,
                annotations = message.annotations,
                isLast = isLast,
                hiddenToolCallIds = hiddenToolCallIds,
                leadingProcessParts = emptyList(),
                reasoningBodyStates = reasoningBodyStates,
                onOpenToolPreview = onOpenToolPreview,
                renderBlocksOverride = displayState.renderBlocks,
                conversationId = conversationId,
                onCitationClick = onCitationClick,
                loading = loading,
                model = model,
                usage = message.usage,
                generationDurationMs = message.generationDurationMs,
                showTokenUsage = settings.showTokenUsage && showInlineTokenUsage,
            )
        }


        // Action buttons show inline based on role and position
        val showActions = showInlineActions

        AnimatedVisibility(
            visible = showActions,
            enter = expandVertically(
                animationSpec = spring(
                    dampingRatio = 0.7f,
                    stiffness = 300f
                )
            ) + slideInVertically(
                animationSpec = spring(
                    dampingRatio = 0.6f,
                    stiffness = 300f
                )
            ) { -it } + fadeIn(
                animationSpec = spring(
                    dampingRatio = 0.8f,
                    stiffness = 400f
                )
            ),
            exit = shrinkVertically(
                animationSpec = spring(
                    dampingRatio = 0.8f,
                    stiffness = 400f
                )
            ) + slideOutVertically(
                animationSpec = spring(
                    dampingRatio = 0.8f,
                    stiffness = 500f
                )
            ) { -it } + fadeOut()
        ) {
            ChatMessageActionButtons(
                message = message,
                copyText = displayState.selectionCopyText,
                ttsText = displayState.ttsText,
                onRegenerate = onRegenerate,
                onContinue = onContinue,
                canContinue = canContinue,
                node = node,
                onUpdate = onUpdate,
                onOpenActionSheet = {
                    showActionsSheet = true
                },
                onEditLorebookEntry = onEditLorebookEntry,
                onModeClick = onModeClick,
                onMemoryClick = onMemoryClick,
                currentSessionMemories = currentSessionMemories,
                onUpdateSessionMemory = onUpdateSessionMemory,
                onDeleteSessionMemory = onDeleteSessionMemory,
            )
        }
    }
    if (showActionsSheet) {
        ChatMessageActionsSheet(
            message = message,
            onEdit = onEdit,
            onDelete = onDelete,
            onShare = onShare,
            onFork = onFork,
            model = model,
            onSelectAndCopy = {
                showSelectCopySheet = true
            },
            onWebViewPreview = {
                val textContent = displayState.previewText
                if (textContent.isNotBlank()) {
                    val htmlContent = buildMarkdownPreviewHtml(
                        context = context,
                        markdown = textContent,
                        colorScheme = colorScheme
                    )
                    navController.navigate(Screen.WebView(content = htmlContent.base64Encode()))
                }
            },
            onDismissRequest = {
                showActionsSheet = false
            }
        )
    }

    if (showSelectCopySheet) {
        ChatMessageCopySheet(
            copyBlocks = displayState.selectionCopyBlocks,
            copyText = displayState.selectionCopyText,
            onDismissRequest = {
                showSelectCopySheet = false
            }
        )
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun MessagePartsBlock(
    assistant: Assistant?,
    role: MessageRole,
    model: Model?,
    parts: List<UIMessagePart>,
    annotations: List<UIMessageAnnotation>,
    isLast: Boolean,
    hiddenToolCallIds: Set<String>,
    leadingProcessParts: List<UIMessagePart>,
    reasoningBodyStates: SnapshotStateMap<String, ReasoningBodyState>? = null,
    onOpenToolPreview: (toolCallId: String, toolName: String, hasResult: Boolean) -> Unit = { _, _, _ -> },
    renderBlocksOverride: List<MessageRenderBlock>? = null,
    conversationId: Uuid?,
    onCitationClick: (String) -> Unit,
    loading: Boolean,
    usage: me.rerere.ai.core.TokenUsage? = null,
    generationDurationMs: Long? = null,
    showTokenUsage: Boolean = false,
) {
    val contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)

    fun handleClickCitation(citationId: String) {
        onCitationClick(citationId)
    }

    // 消息输出HapticFeedback
    val hapticFeedback = LocalHapticFeedback.current
    val settings = LocalSettings.current
    val partsState by rememberUpdatedState(parts)
    LaunchedEffect(settings.displaySetting) {
        snapshotFlow { partsState }
            .debounce(50.milliseconds)
            .collect { parts ->
                if (parts.isNotEmpty() && loading && settings.displaySetting.enableMessageGenerationHapticEffect) {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                }
            }
    }

    val renderBlocks = renderBlocksOverride ?: remember(leadingProcessParts, parts) {
        buildMessageRenderBlocks(
            leadingProcessParts = leadingProcessParts,
            parts = parts,
        )
    }
    renderBlocks.fastForEach { block ->
        when (block) {
            is MessageRenderBlock.ProcessGroup -> {
                ChatProcessTimeline(
                    processParts = block.parts,
                    conversationId = conversationId,
                    hiddenToolCallIds = hiddenToolCallIds,
                    loading = loading,
                    model = model,
                    assistant = assistant,
                    reasoningBodyStates = reasoningBodyStates,
                    onOpenToolPreview = onOpenToolPreview,
                )
            }

            is MessageRenderBlock.TextBlock -> {
                MessageTextPart(
                    assistant = assistant,
                    role = role,
                    part = block.part,
                    textIndex = block.textIndex,
                    onCitationClick = ::handleClickCitation,
                )
            }

            is MessageRenderBlock.ImageGroup -> {
                ImagePartsBlock(parts = block.parts)
            }

            is MessageRenderBlock.VideoGroup -> {
                VideoPartsBlock(parts = block.parts)
            }

            is MessageRenderBlock.AudioGroup -> {
                AudioPartsBlock(parts = block.parts)
            }

            is MessageRenderBlock.DocumentGroup -> {
                DocumentPartsBlock(parts = block.parts)
            }
        }
    }

    // Token Statistics (shown after all text parts, for assistant messages only)
    // Just shows immediately when conditions are met - no special delay or animation
    val textParts = parts.filterIsInstance<UIMessagePart.Text>()
    val hasTextContent = textParts.any { it.text.isNotBlank() }
    val shouldShowTokenStats = role == MessageRole.ASSISTANT && showTokenUsage && !loading && hasTextContent && usage != null
    
    if (shouldShowTokenStats) {
        usage?.let { tokenUsage ->
            // Calculate tokens per second from generation duration
            val tokensPerSecond: Float? = generationDurationMs?.let { durationMs ->
                if (durationMs > 0) {
                    val durationSeconds = durationMs / 1000.0
                    (tokenUsage.completionTokens / durationSeconds).toFloat()
                } else null
            }
            TokenStatisticsRowInline(usage = tokenUsage, tokensPerSecond = tokensPerSecond)
        }
    }

    // Annotations
    if (annotations.isNotEmpty()) {
        Column(
            modifier = Modifier.animateContentSize(),
        ) {
            var expand by remember { mutableStateOf(false) }
            if (expand) {
                ProvideTextStyle(
                    MaterialTheme.typography.labelMedium.copy(
                        color = MaterialTheme.extendColors.gray8.copy(alpha = 0.65f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .drawWithContent {
                                drawContent()
                                drawRoundRect(
                                    color = contentColor.copy(alpha = 0.2f),
                                    size = Size(width = 10f, height = size.height),
                                )
                            }
                            .padding(start = 16.dp)
                            .padding(4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        annotations.fastForEachIndexed { index, annotation ->
                            when (annotation) {
                                is UIMessageAnnotation.UrlCitation -> {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Favicon(annotation.url, modifier = Modifier.size(20.dp))
                                        Text(
                                            text = buildAnnotatedString {
                                                append("${index + 1}. ")
                                                withLink(LinkAnnotation.Url(annotation.url)) {
                                                    append(annotation.title.urlDecode())
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            TextButton(
                onClick = {
                    expand = !expand
                }
            ) {
                Text(stringResource(R.string.citations_count, annotations.size))
            }
        }
    }

}

@Composable
private fun MessageTextPart(
    assistant: Assistant?,
    role: MessageRole,
    part: UIMessagePart.Text,
    textIndex: Int,
    onCitationClick: (String) -> Unit,
) {
    if (role == MessageRole.USER) {
        Card(
            modifier = Modifier.animateContentSize(
                animationSpec = spring(
                    dampingRatio = 0.7f,
                    stiffness = 300f
                )
            ),
            shape = me.rerere.rikkahub.ui.theme.AppShapes.CardLarge,
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                SelectionContainer {
                    MarkdownBlock(
                        content = part.text.replaceRegexes(
                            assistant = assistant,
                            scope = AssistantAffectScope.USER,
                            visual = true,
                        ),
                        onClickCitation = onCitationClick,
                    )
                }
                if (textIndex == 0) {
                    MarkdownFontDebugInfo(role = role)
                }
            }
        }
        return
    }

    Column {
        SelectionContainer(
            modifier = Modifier.animateContentSize(
                animationSpec = spring(
                    dampingRatio = 0.7f,
                    stiffness = 300f
                )
            )
        ) {
            MarkdownBlock(
                content = part.text.replaceRegexes(
                    assistant = assistant,
                    scope = AssistantAffectScope.ASSISTANT,
                    visual = true,
                ),
                onClickCitation = onCitationClick,
            )
        }
        if (textIndex == 0) {
            MarkdownFontDebugInfo(role = role)
        }
    }
}

@Composable
private fun ImagePartsBlock(parts: List<UIMessagePart.Image>) {
    if (parts.isEmpty()) return

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        parts.fastForEach { image ->
            ZoomableAsyncImage(
                model = image.url,
                contentDescription = null,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.medium)
                    .height(72.dp)
            )
        }
    }
}

@Composable
private fun VideoPartsBlock(parts: List<UIMessagePart.Video>) {
    if (parts.isEmpty()) return

    val context = LocalContext.current
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        parts.fastForEach { video ->
            Surface(
                tonalElevation = 2.dp,
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW)
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    intent.data = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        video.url.toUri().toFile()
                    )
                    val chooserIndent = Intent.createChooser(intent, null)
                    context.startActivity(chooserIndent)
                },
                modifier = Modifier,
                shape = RoundedCornerShape(8.dp),
            ) {
                Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Videocam, null)
                }
            }
        }
    }
}

@Composable
private fun AudioPartsBlock(parts: List<UIMessagePart.Audio>) {
    if (parts.isEmpty()) return

    val context = LocalContext.current
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        parts.fastForEach { audio ->
            Surface(
                tonalElevation = 2.dp,
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW)
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    intent.data = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        audio.url.toUri().toFile()
                    )
                    val chooserIndent = Intent.createChooser(intent, null)
                    context.startActivity(chooserIndent)
                },
                modifier = Modifier,
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MusicNote,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DocumentPartsBlock(parts: List<UIMessagePart.Document>) {
    if (parts.isEmpty()) return

    val context = LocalContext.current
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        parts.fastForEach { document ->
            Surface(
                tonalElevation = 2.dp,
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW)
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    intent.data = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        document.url.toUri().toFile()
                    )
                    val chooserIndent = Intent.createChooser(intent, null)
                    context.startActivity(chooserIndent)
                },
                modifier = Modifier,
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.tertiaryContainer
            ) {
                ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        when (document.mime) {
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> {
                                Icon(
                                    painter = painterResource(R.drawable.docx),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            "application/pdf" -> {
                                Icon(
                                    painter = painterResource(R.drawable.pdf),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            else -> {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.InsertDriveFile,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Text(
                            text = document.fileName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 200.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Token statistics row shown at the bottom of AI responses.
 * Shows: sent tokens (arrow up), received tokens (arrow down), tokens per second (bolt)
 */
@Composable
private fun TokenStatisticsRow(
    usage: me.rerere.ai.core.TokenUsage,
    message: UIMessage
) {
    val grayColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    
    // Calculate tokens per second from generation duration
    val tokensPerSecond: Float? = message.generationDurationMs?.let { durationMs ->
        if (durationMs > 0) {
            val durationSeconds = durationMs / 1000.0
            (usage.completionTokens / durationSeconds).toFloat()
        } else null
    }
    
    Row(
        modifier = Modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Sent tokens (prompt tokens)
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.ArrowUpward,
                contentDescription = stringResource(R.string.a11y_sent),
                modifier = Modifier.size(14.dp),
                tint = grayColor
            )
            Text(
                text = buildString {
                    append(stringResource(R.string.tokens_format, usage.promptTokens.formatNumber()))
                    if (usage.cachedTokens > 0) {
                        append(stringResource(R.string.tokens_cached_suffix, usage.cachedTokens.formatNumber()))
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = grayColor
            )
        }
        
        // Received tokens (completion tokens)
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.ArrowDownward,
                contentDescription = stringResource(R.string.a11y_received),
                modifier = Modifier.size(14.dp),
                tint = grayColor
            )
            Text(
                text = stringResource(R.string.tokens_format, usage.completionTokens.formatNumber()),
                style = MaterialTheme.typography.labelSmall,
                color = grayColor
            )
        }
        
        // Tokens per second (only shown if calculable)
        tokensPerSecond?.let { tps ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Bolt,
                    contentDescription = stringResource(R.string.a11y_speed),
                    modifier = Modifier.size(14.dp),
                    tint = grayColor
                )
                Text(
                    text = if (tps < 1000) {
                        String.format("%.0f tok/s", tps)
                    } else {
                        String.format("%.1f tok/s", tps)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = grayColor
                )
            }
        }
    }
}

/**
 * Token statistics row (inline version that takes pre-computed values).
 * Used when the full UIMessage is not available.
 */
@Composable
private fun TokenStatisticsRowInline(
    usage: me.rerere.ai.core.TokenUsage,
    tokensPerSecond: Float?
) {
    val grayColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    
    Row(
        modifier = Modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Sent tokens (prompt tokens)
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.ArrowUpward,
                contentDescription = "Sent",
                modifier = Modifier.size(14.dp),
                tint = grayColor
            )
            Text(
                text = buildString {
                    append("${usage.promptTokens.formatNumber()} tokens")
                    if (usage.cachedTokens > 0) {
                        append(" (${usage.cachedTokens.formatNumber()} cached)")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = grayColor
            )
        }
        
        // Received tokens (completion tokens)
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.ArrowDownward,
                contentDescription = "Received",
                modifier = Modifier.size(14.dp),
                tint = grayColor
            )
            Text(
                text = "${usage.completionTokens.formatNumber()} tokens",
                style = MaterialTheme.typography.labelSmall,
                color = grayColor
            )
        }
        
        // Tokens per second (only shown if calculable)
        tokensPerSecond?.let { tps ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Bolt,
                    contentDescription = "Speed",
                    modifier = Modifier.size(14.dp),
                    tint = grayColor
                )
                Text(
                    text = if (tps < 1000) {
                        String.format("%.0f tok/s", tps)
                    } else {
                        String.format("%.1f tok/s", tps)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = grayColor
                )
            }
        }
    }
}
