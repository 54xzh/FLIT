
package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowRight
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import kotlinx.datetime.toJavaLocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.UsedLorebookEntry
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.MessageToolbarButton
import me.rerere.rikkahub.data.datastore.MessageToolbarConfig
import me.rerere.rikkahub.data.datastore.getEffectiveDisplaySetting
import me.rerere.rikkahub.data.model.SessionMemory
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.utils.toLocalString
import me.rerere.rikkahub.utils.writeClipboardText

@Composable
fun ColumnScope.ChatMessageActionButtons(
    message: UIMessage,
    copyText: String,
    ttsText: String,
    node: MessageNode,
    onUpdate: (MessageNode) -> Unit,
    onRegenerate: () -> Unit,
    onContinue: () -> Unit,
    canContinue: Boolean,
    onFork: () -> Unit,
    onOpenActionSheet: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onSelectAndCopy: (() -> Unit)? = null,
    onWebViewPreview: (() -> Unit)? = null,
    onEditLorebookEntry: ((UsedLorebookEntry) -> Unit)? = null,
    onModeClick: ((me.rerere.ai.ui.UsedMode) -> Unit)? = null,
    assistantId: String? = null,
    currentSessionMemories: List<SessionMemory> = emptyList(),
    onUpdateSessionMemory: ((memoryId: Int, content: String) -> Unit)? = null,
    onDeleteSessionMemory: ((memoryId: Int) -> Unit)? = null,
) {
    val context = LocalContext.current
    val settings = LocalSettings.current
    val effectiveDisplay = settings.getEffectiveDisplaySetting()
    val toolbarConfig: MessageToolbarConfig = if (message.role == MessageRole.ASSISTANT) {
        effectiveDisplay.assistantMessageToolbar
    } else {
        effectiveDisplay.userMessageToolbar
    }
    val haptics = rememberPremiumHaptics(enabled = effectiveDisplay.enableUIHaptics)
    // 仅工具栏上的删除按钮需要二次确认弹窗；更多菜单里的删除保持原样直接删。
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showContextSheet by remember { mutableStateOf(false) }
    
    val usedEntries = message.usedLorebookEntries ?: emptyList()
    val usedModes = message.usedModes ?: emptyList()
    val usedMemories = message.usedMemories ?: emptyList()
    val usedSessionMemories = message.usedSessionMemories ?: emptyList()
    val usedMemorySummary = message.usedMemorySummary
    val hasContextSources = usedMemorySummary != null ||
        usedEntries.isNotEmpty() ||
        usedModes.isNotEmpty() ||
        usedMemories.isNotEmpty() ||
        usedSessionMemories.isNotEmpty()
    val showContextStacks = effectiveDisplay.showContextStacks && hasContextSources
    val regenerateInteractionSource = remember { MutableInteractionSource() }
    val isRegeneratePressed by regenerateInteractionSource.collectIsPressedAsState()
    val regenerateScale by animateFloatAsState(
        targetValue = if (isRegeneratePressed) 0.85f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "message_regenerate_scale"
    )

    // 工具栏删除二次确认弹窗
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.message_delete_confirm_title)) },
            text = { Text(stringResource(R.string.message_delete_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    if (onDelete != null) onDelete.invoke()
                }) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Context sources sheet
    if (showContextSheet && hasContextSources) {
        ContextSourcesSheet(
            modes = usedModes,
            memories = usedMemories,
            sessionMemories = usedSessionMemories,
            memorySummary = usedMemorySummary,
            assistantId = assistantId,
            currentSessionMemories = currentSessionMemories,
            entries = usedEntries,
            onModeClick = { mode ->
                showContextSheet = false
                onModeClick?.invoke(mode)
            },
            onSessionMemorySave = onUpdateSessionMemory,
            onSessionMemoryDelete = onDeleteSessionMemory,
            onEntryClick = { entry ->
                showContextSheet = false
                onEditLorebookEntry?.invoke(entry)
            },
            onDismissRequest = { showContextSheet = false }
        )
    }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        // Context stack indicator at the start
        if (showContextStacks) {
            ContextStackIndicator(
                modes = usedModes,
                memories = usedMemories,
                sessionMemories = usedSessionMemories,
                memorySummary = usedMemorySummary,
                entries = usedEntries,
                onClick = { showContextSheet = true },
                modifier = Modifier.padding(start = 4.dp)
            )
        }
        
        if (toolbarConfig.isOnToolbar(MessageToolbarButton.COPY)) {
            Icon(
                Icons.Rounded.ContentCopy, stringResource(R.string.copy), modifier = Modifier
                    .clip(CircleShape)
                    .clickable {
                        context.writeClipboardText(copyText.ifBlank { message.toContentText() })
                    }
                    .padding(8.dp)
                    .size(16.dp)
            )
        }

        if (toolbarConfig.isOnToolbar(MessageToolbarButton.FORK)) {
            val forkInteractionSource = remember { MutableInteractionSource() }
            val isForkPressed by forkInteractionSource.collectIsPressedAsState()
            val forkScale by animateFloatAsState(
                targetValue = if (isForkPressed) 0.85f else 1f,
                animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
                label = "message_fork_scale"
            )
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.CallSplit,
                contentDescription = stringResource(R.string.create_fork),
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = forkScale
                        scaleY = forkScale
                    }
                    .clip(CircleShape)
                    .combinedClickable(
                        interactionSource = forkInteractionSource,
                        indication = LocalIndication.current,
                        onClick = {
                            haptics.perform(HapticPattern.Pop)
                            onFork()
                        },
                    )
                    .padding(8.dp)
                    .size(16.dp),
            )
        }

        if (toolbarConfig.isOnToolbar(MessageToolbarButton.REGENERATE)) {
            Icon(
                Icons.Rounded.Refresh, stringResource(R.string.regenerate), modifier = Modifier
                    .graphicsLayer {
                        scaleX = regenerateScale
                        scaleY = regenerateScale
                    }
                    .clip(CircleShape)
                    .combinedClickable(
                        interactionSource = regenerateInteractionSource,
                        indication = LocalIndication.current,
                        onClick = {
                            haptics.perform(HapticPattern.Pop)
                            onRegenerate()
                        },
                    )
                    .padding(8.dp)
                    .size(16.dp)
            )
        }

        if (
            message.role == MessageRole.ASSISTANT &&
                canContinue &&
                toolbarConfig.isOnToolbar(MessageToolbarButton.CONTINUE)
        ) {
            val continueInteractionSource = remember { MutableInteractionSource() }
            val isContinuePressed by continueInteractionSource.collectIsPressedAsState()
            val continueScale by animateFloatAsState(
                targetValue = if (isContinuePressed) 0.85f else 1f,
                animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
                label = "message_continue_scale"
            )
            Icon(
                imageVector = Icons.Rounded.KeyboardDoubleArrowRight,
                contentDescription = stringResource(R.string.continue_generation),
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = continueScale
                        scaleY = continueScale
                    }
                    .clip(CircleShape)
                    .combinedClickable(
                        interactionSource = continueInteractionSource,
                        indication = LocalIndication.current,
                        onClick = {
                            haptics.perform(HapticPattern.Pop)
                            onContinue()
                        },
                    )
                    .padding(8.dp)
                    .size(16.dp),
            )
        }

        if (message.role == MessageRole.ASSISTANT && toolbarConfig.isOnToolbar(MessageToolbarButton.TTS)) {
            val tts = LocalTTSState.current
            val isSpeaking by tts.isSpeaking.collectAsState()
            val isAvailable by tts.isAvailable.collectAsState()
            Icon(
                imageVector = if (isSpeaking) Icons.Rounded.StopCircle else Icons.AutoMirrored.Rounded.VolumeUp,
                contentDescription = stringResource(R.string.tts),
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(
                        enabled = isAvailable,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = LocalIndication.current,
                        onClick = {
                            if (!isSpeaking) {
                                tts.speak(ttsText.ifBlank { message.toContentText() })
                            } else {
                                tts.stop()
                            }
                        }
                    )
                    .padding(8.dp)
                    .size(16.dp),
                tint = if (isAvailable) LocalContentColor.current else LocalContentColor.current.copy(alpha = 0.38f)
            )
        }

        // —— 以下按钮默认收在更多菜单；当用户把它们开关打开时显示在工具栏 ——
        if (toolbarConfig.isOnToolbar(MessageToolbarButton.EDIT) && onEdit != null) {
            val editInteractionSource = remember { MutableInteractionSource() }
            val isEditPressed by editInteractionSource.collectIsPressedAsState()
            val editScale by animateFloatAsState(
                targetValue = if (isEditPressed) 0.85f else 1f,
                animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
                label = "message_edit_scale"
            )
            Icon(
                imageVector = Icons.Rounded.Edit,
                contentDescription = stringResource(R.string.edit),
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = editScale
                        scaleY = editScale
                    }
                    .clip(CircleShape)
                    .combinedClickable(
                        interactionSource = editInteractionSource,
                        indication = LocalIndication.current,
                        onClick = {
                            haptics.perform(HapticPattern.Pop)
                            onEdit.invoke()
                        },
                    )
                    .padding(8.dp)
                    .size(16.dp)
            )
        }

        if (toolbarConfig.isOnToolbar(MessageToolbarButton.SHARE) && onShare != null) {
            val shareInteractionSource = remember { MutableInteractionSource() }
            val isSharePressed by shareInteractionSource.collectIsPressedAsState()
            val shareScale by animateFloatAsState(
                targetValue = if (isSharePressed) 0.85f else 1f,
                animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
                label = "message_share_scale"
            )
            Icon(
                imageVector = Icons.Rounded.Share,
                contentDescription = stringResource(R.string.share),
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = shareScale
                        scaleY = shareScale
                    }
                    .clip(CircleShape)
                    .combinedClickable(
                        interactionSource = shareInteractionSource,
                        indication = LocalIndication.current,
                        onClick = {
                            haptics.perform(HapticPattern.Pop)
                            onShare.invoke()
                        },
                    )
                    .padding(8.dp)
                    .size(16.dp)
            )
        }

        if (toolbarConfig.isOnToolbar(MessageToolbarButton.SELECT_AND_COPY) && onSelectAndCopy != null) {
            val selectInteractionSource = remember { MutableInteractionSource() }
            val isSelectPressed by selectInteractionSource.collectIsPressedAsState()
            val selectScale by animateFloatAsState(
                targetValue = if (isSelectPressed) 0.85f else 1f,
                animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
                label = "message_select_scale"
            )
            Icon(
                imageVector = Icons.Rounded.SelectAll,
                contentDescription = stringResource(R.string.select_and_copy),
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = selectScale
                        scaleY = selectScale
                    }
                    .clip(CircleShape)
                    .combinedClickable(
                        interactionSource = selectInteractionSource,
                        indication = LocalIndication.current,
                        onClick = {
                            haptics.perform(HapticPattern.Pop)
                            onSelectAndCopy.invoke()
                        },
                    )
                    .padding(8.dp)
                    .size(16.dp)
            )
        }

        if (toolbarConfig.isOnToolbar(MessageToolbarButton.WEB_VIEW_PREVIEW) && onWebViewPreview != null) {
            val hasTextContent = message.parts.filterIsInstance<UIMessagePart.Text>()
                .any { it.text.isNotBlank() }
            if (hasTextContent) {
                val webInteractionSource = remember { MutableInteractionSource() }
                val isWebPressed by webInteractionSource.collectIsPressedAsState()
                val webScale by animateFloatAsState(
                    targetValue = if (isWebPressed) 0.85f else 1f,
                    animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
                    label = "message_web_scale"
                )
                Icon(
                    imageVector = Icons.Rounded.OpenInBrowser,
                    contentDescription = stringResource(R.string.render_with_webview),
                    modifier = Modifier
                        .graphicsLayer {
                            scaleX = webScale
                            scaleY = webScale
                        }
                        .clip(CircleShape)
                        .combinedClickable(
                            interactionSource = webInteractionSource,
                            indication = LocalIndication.current,
                            onClick = {
                                haptics.perform(HapticPattern.Pop)
                                onWebViewPreview.invoke()
                            },
                        )
                        .padding(8.dp)
                        .size(16.dp)
                )
            }
        }

        if (toolbarConfig.isOnToolbar(MessageToolbarButton.DELETE) && onDelete != null) {
            val deleteInteractionSource = remember { MutableInteractionSource() }
            val isDeletePressed by deleteInteractionSource.collectIsPressedAsState()
            val deleteScale by animateFloatAsState(
                targetValue = if (isDeletePressed) 0.85f else 1f,
                animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
                label = "message_delete_scale"
            )
            Icon(
                imageVector = Icons.Rounded.Delete,
                contentDescription = stringResource(R.string.delete),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = deleteScale
                        scaleY = deleteScale
                    }
                    .clip(CircleShape)
                    .combinedClickable(
                        interactionSource = deleteInteractionSource,
                        indication = LocalIndication.current,
                        onClick = {
                            // 工具栏删除：先弹确认框，避免误点。更多菜单里的删除保持原样直接删。
                            haptics.perform(HapticPattern.Thud)
                            showDeleteConfirm = true
                        },
                    )
                    .padding(8.dp)
                    .size(16.dp)
            )
        }

        Icon(
            imageVector = Icons.Rounded.MoreHoriz,
            contentDescription = stringResource(R.string.a11y_more_options),
            modifier = Modifier
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = LocalIndication.current,
                    onClick = {
                        onOpenActionSheet()
                    }
                )
                .padding(8.dp)
                .size(16.dp)
        )

        ChatMessageBranchSelector(
            node = node,
            onUpdate = onUpdate,
        )
    }
}

@Composable
fun ChatMessageActionsSheet(
    message: UIMessage,
    model: Model?,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onFork: () -> Unit,
    onSelectAndCopy: () -> Unit,
    onWebViewPreview: () -> Unit,
    onDismissRequest: () -> Unit,
    onRegenerate: () -> Unit = {},
    onContinue: () -> Unit = {},
    canContinue: Boolean = false,
    copyText: String = "",
    ttsText: String = "",
    onCustomizeToolbar: () -> Unit = {},
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        val context = LocalContext.current
        val settings = LocalSettings.current
        val effectiveDisplay = settings.getEffectiveDisplaySetting()
        val toolbarConfig = if (message.role == MessageRole.ASSISTANT) {
            effectiveDisplay.assistantMessageToolbar
        } else {
            effectiveDisplay.userMessageToolbar
        }
        // 更多菜单只显示"未放在工具栏上"的按钮，避免与工具栏重复
        fun showInMore(button: MessageToolbarButton): Boolean = !toolbarConfig.isOnToolbar(button)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Select and Copy
            if (showInMore(MessageToolbarButton.SELECT_AND_COPY)) {
                Card(
                onClick = {
                    onDismissRequest()
                    onSelectAndCopy()
                },

                shape = me.rerere.rikkahub.ui.theme.AppShapes.CardMedium,
                colors = CardDefaults.cardColors(
                    containerColor = if(me.rerere.rikkahub.ui.theme.LocalDarkMode.current) androidx.compose.ui.graphics.Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SelectAll,
                        contentDescription = null,
                        modifier = Modifier.padding(4.dp)
                    )
                    Text(
                        text = stringResource(R.string.select_and_copy),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            }

            // Copy (whole message to clipboard)
            if (showInMore(MessageToolbarButton.COPY)) {
                Card(
                    onClick = {
                        onDismissRequest()
                        context.writeClipboardText(copyText.ifBlank { message.toContentText() })
                    },
                    shape = me.rerere.rikkahub.ui.theme.AppShapes.CardMedium,
                    colors = CardDefaults.cardColors(
                        containerColor = if(me.rerere.rikkahub.ui.theme.LocalDarkMode.current) androidx.compose.ui.graphics.Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.padding(4.dp)
                        )
                        Text(
                            text = stringResource(R.string.copy),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }

            // Regenerate
            if (showInMore(MessageToolbarButton.REGENERATE)) {
                Card(
                    onClick = {
                        onDismissRequest()
                        onRegenerate()
                    },
                    shape = me.rerere.rikkahub.ui.theme.AppShapes.CardMedium,
                    colors = CardDefaults.cardColors(
                        containerColor = if(me.rerere.rikkahub.ui.theme.LocalDarkMode.current) androidx.compose.ui.graphics.Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.padding(4.dp)
                        )
                        Text(
                            text = stringResource(R.string.regenerate),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }

            // Continue (latest assistant message only)
            if (
                message.role == MessageRole.ASSISTANT &&
                    canContinue &&
                    showInMore(MessageToolbarButton.CONTINUE)
            ) {
                Card(
                    onClick = {
                        onDismissRequest()
                        onContinue()
                    },
                    shape = me.rerere.rikkahub.ui.theme.AppShapes.CardMedium,
                    colors = CardDefaults.cardColors(
                        containerColor = if(me.rerere.rikkahub.ui.theme.LocalDarkMode.current) androidx.compose.ui.graphics.Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardDoubleArrowRight,
                            contentDescription = null,
                            modifier = Modifier.padding(4.dp)
                        )
                        Text(
                            text = stringResource(R.string.continue_generation),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }

            // TTS (assistant only)
            if (message.role == MessageRole.ASSISTANT && showInMore(MessageToolbarButton.TTS)) {
                val tts = LocalTTSState.current
                val isSpeaking by tts.isSpeaking.collectAsState()
                val isAvailable by tts.isAvailable.collectAsState()
                Card(
                    onClick = {
                        onDismissRequest()
                        if (!isSpeaking) {
                            tts.speak(ttsText.ifBlank { message.toContentText() })
                        } else {
                            tts.stop()
                        }
                    },
                    shape = me.rerere.rikkahub.ui.theme.AppShapes.CardMedium,
                    colors = CardDefaults.cardColors(
                        containerColor = if(me.rerere.rikkahub.ui.theme.LocalDarkMode.current) androidx.compose.ui.graphics.Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = if (isSpeaking) Icons.Rounded.StopCircle else Icons.AutoMirrored.Rounded.VolumeUp,
                            contentDescription = null,
                            modifier = Modifier.padding(4.dp),
                            tint = if (isAvailable) LocalContentColor.current else LocalContentColor.current.copy(alpha = 0.38f)
                        )
                        Text(
                            text = stringResource(R.string.tts),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }

            // WebView Preview (only show if message has text content)
            val hasTextContent = message.parts.filterIsInstance<UIMessagePart.Text>()
                .any { it.text.isNotBlank() }

            if (hasTextContent && showInMore(MessageToolbarButton.WEB_VIEW_PREVIEW)) {
                Card(
                    onClick = {
                        onDismissRequest()
                        onWebViewPreview()
                    },

                    shape = me.rerere.rikkahub.ui.theme.AppShapes.CardMedium,
                    colors = CardDefaults.cardColors(
                        containerColor = if(me.rerere.rikkahub.ui.theme.LocalDarkMode.current) androidx.compose.ui.graphics.Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.OpenInBrowser,
                            contentDescription = null,
                            modifier = Modifier.padding(4.dp)
                        )
                        Text(
                            text = stringResource(R.string.render_with_webview),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }

            // Edit
            if (showInMore(MessageToolbarButton.EDIT)) {
                Card(
                    onClick = {
                        onDismissRequest()
                        onEdit()
                    },

                    shape = me.rerere.rikkahub.ui.theme.AppShapes.CardMedium,
                    colors = CardDefaults.cardColors(
                        containerColor = if(me.rerere.rikkahub.ui.theme.LocalDarkMode.current) androidx.compose.ui.graphics.Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = null,
                            modifier = Modifier.padding(4.dp)
                        )
                        Text(
                            text = stringResource(R.string.edit),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }

            // Share
            if (showInMore(MessageToolbarButton.SHARE)) {
                Card(
                    onClick = {
                        onDismissRequest()
                        onShare()
                    },
                    shape = me.rerere.rikkahub.ui.theme.AppShapes.CardMedium,
                    colors = CardDefaults.cardColors(
                        containerColor = if(me.rerere.rikkahub.ui.theme.LocalDarkMode.current) androidx.compose.ui.graphics.Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Share,
                            contentDescription = null,
                            modifier = Modifier.padding(4.dp)
                        )
                        Text(
                            text = stringResource(R.string.share),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }

            // Create a Fork
            if (showInMore(MessageToolbarButton.FORK)) {
                Card(
                    onClick = {
                        onDismissRequest()
                        onFork()
                    },
                    shape = me.rerere.rikkahub.ui.theme.AppShapes.CardMedium,
                    colors = CardDefaults.cardColors(
                        containerColor = if(me.rerere.rikkahub.ui.theme.LocalDarkMode.current) androidx.compose.ui.graphics.Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.CallSplit,
                            contentDescription = null,
                            modifier = Modifier.padding(4.dp)
                        )
                        Text(
                            text = stringResource(R.string.create_fork),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }

            // Delete
            if (showInMore(MessageToolbarButton.DELETE)) {
                Card(
                    onClick = {
                        onDismissRequest()
                        onDelete()
                    },

                    shape = me.rerere.rikkahub.ui.theme.AppShapes.CardMedium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = null,
                            modifier = Modifier.padding(4.dp)
                        )
                        Text(
                            text = stringResource(R.string.delete),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }

            // Customize toolbar shortcut
            Card(
                onClick = {
                    onDismissRequest()
                    onCustomizeToolbar()
                },
                shape = me.rerere.rikkahub.ui.theme.AppShapes.CardMedium,
                colors = CardDefaults.cardColors(
                    containerColor = if(me.rerere.rikkahub.ui.theme.LocalDarkMode.current) androidx.compose.ui.graphics.Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Tune,
                        contentDescription = null,
                        modifier = Modifier.padding(4.dp)
                    )
                    Text(
                        text = stringResource(R.string.setting_page_message_toolbar),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            // Message Info
            ProvideTextStyle(MaterialTheme.typography.labelSmall) {
                Text(message.createdAt.toJavaLocalDateTime().toLocalString())
                if (model != null) {
                    Text(model.displayName)
                }
            }
        }
    }
}
