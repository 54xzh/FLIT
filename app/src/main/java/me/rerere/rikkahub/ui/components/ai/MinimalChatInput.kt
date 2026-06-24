package me.rerere.rikkahub.ui.components.ai

import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Spacer
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.search.SearchServiceOptions
import me.rerere.search.displayName
import coil3.compose.AsyncImage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.deleteChatFiles
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.animation.core.spring
import androidx.core.net.toUri
import androidx.compose.foundation.background
import androidx.compose.foundation.LocalIndication
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Book
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Summarize
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.ViewModule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.FileProvider
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.supportsBuiltInSearch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.McpStatus
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.ConversationWorkDirBinding
import me.rerere.rikkahub.data.datastore.ConversationWorkDirMode
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.datastore.getConversationWorkspaceRootTreeUri
import me.rerere.rikkahub.data.datastore.getEffectiveWorkspaceRootTreeUri
import me.rerere.rikkahub.data.datastore.hasConversationWorkspaceRoot
import me.rerere.rikkahub.data.datastore.rememberWorkspaceForNewChatsIfEnabled
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.buildAssistantProviderSearchMode
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.components.crop.CropImageScreen
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.components.ui.ToastType
import me.rerere.rikkahub.ui.components.ui.permission.PermissionCamera
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.components.workdir.WorkDirPickerBottomSheet
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.utils.createChatFilesByContents
import me.rerere.rikkahub.utils.getFileMimeType
import me.rerere.rikkahub.utils.getFileNameFromUri
import java.io.File
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/**
 * Minimal ChatGPT-style input bar with bottom sheet picker.
 * Shows a simple input bar with + button, text field, and send button.
 * The + button opens a bottom sheet with file upload, model picker, and other options.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MinimalChatInput(
    state: ChatInputState,
    conversation: Conversation,
    settings: Settings,
    mcpManager: McpManager,
    uiMode: ChatInputUiMode = ChatInputUiMode.Normal,
    enableSearch: Boolean,
    onToggleSearch: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    chatSuggestions: List<String> = emptyList(),
    onClickSuggestion: (String) -> Unit = {},
    onUpdateChatModel: (Model) -> Unit,
    onUpdateAssistant: (Assistant) -> Unit,
    onUpdateConversation: (Conversation) -> Unit,
    onUpdateSettings: (Settings) -> Unit,
    onUpdateSearchService: (Int) -> Unit,
    onClearContext: () -> Unit,
    onCancelClick: () -> Unit,
    onSendClick: () -> Unit,
    onLongSendClick: () -> Unit,
    onNavigateToLorebook: (String) -> Unit = {},
    onRefreshContext: suspend () -> ChatService.ContextRefreshResult = { ChatService.ContextRefreshResult(false, errorMessage = "Not configured") },
    autoHideSuggestions: Boolean = false,
    onSuggestionsTopYChanged: (Float) -> Unit = {},
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val assistant = settings.getCurrentAssistant()
    val haptics = rememberPremiumHaptics(enabled = settings.displaySetting.enableUIHaptics)
    val defaultAssistantName = stringResource(R.string.assistant_page_default_assistant)
    val keyboardController = LocalSoftwareKeyboardController.current
    val localSettings = LocalSettings.current
    
    // OLED dark mode handling for picker sheet
    val amoledMode by me.rerere.rikkahub.ui.hooks.rememberAmoledDarkMode()
    val isDarkMode = me.rerere.rikkahub.ui.theme.LocalDarkMode.current
    val isAmoled = amoledMode && isDarkMode
    // Picker sheet styling - optical roundness: outer (40dp) = button corners (24dp) + padding (16dp)
    // Sheet uses surfaceContainerLow always, buttons inside handle OLED colors
    val pickerSheetColor = MaterialTheme.colorScheme.surfaceContainerLow
    val pickerSheetShape = RoundedCornerShape(topStart = 40.dp, topEnd = 40.dp)
    
    // Camera permission - must be in parent, not inside ModalBottomSheet
    val cameraPermission = rememberPermissionState(PermissionCamera)
    
    var showPicker by remember { mutableStateOf(false) }
    var showContextRefreshDialog by remember { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }
    var isFullScreen by remember { mutableStateOf(false) }

    val groupChatTemplateForMentions = remember(
        uiMode,
        settings.groupChatTemplates,
        conversation.assistantId,
    ) {
        if (uiMode != ChatInputUiMode.GroupChat) return@remember null
        settings.groupChatTemplates.firstOrNull { it.id == conversation.assistantId }
    }

    val groupChatMentionKeys = remember(
        groupChatTemplateForMentions,
        settings.assistants,
        settings.assistantTags,
        defaultAssistantName,
    ) {
        groupChatTemplateForMentions?.let { template ->
            buildGroupChatMentionKeySuggestions(
                settings = settings,
                template = template,
                defaultAssistantName = defaultAssistantName,
            )
        }.orEmpty()
    }

    val workDirKey = conversation.id.toString()
    val currentWorkDirBinding = settings.conversationWorkDirs[workDirKey]
    val effectiveWorkspaceRootTreeUri = settings.getEffectiveWorkspaceRootTreeUri(conversation.id)
    var showWorkDirPicker by remember(conversation.id) { mutableStateOf(false) }

    if (showWorkDirPicker) {
        WorkDirPickerBottomSheet(
            conversationId = conversation.id,
            workspaceRootTreeUri = effectiveWorkspaceRootTreeUri,
            initialRelPath = currentWorkDirBinding?.relPath?.trim().orEmpty(),
            onDismissRequest = { showWorkDirPicker = false },
            onConfirm = { relPath ->
                onUpdateSettings(
                    settings.copy(
                        conversationWorkDirs = settings.conversationWorkDirs + (
                            workDirKey to ConversationWorkDirBinding(
                                mode = ConversationWorkDirMode.MANUAL,
                                relPath = relPath,
                            )
                        )
                    ).rememberWorkspaceForNewChatsIfEnabled(
                        workspaceRootTreeUri = settings.getConversationWorkspaceRootTreeUri(conversation.id),
                        workDirRelPath = relPath,
                    )
                )
                toaster.show(
                    message = context.getString(R.string.workdir_manual_saved),
                    type = ToastType.Success,
                )
                showWorkDirPicker = false
            },
        )
    }
    
    // Collapse picker when keyboard opens
    val imeVisible = WindowInsets.isImeVisible
    val focusManager = LocalFocusManager.current
    LaunchedEffect(imeVisible) {
        if (imeVisible) {
            showPicker = false
        } else {
            focusManager.clearFocus()
        }
    }
    
    fun sendMessage() {
        keyboardController?.hide()
        haptics.perform(HapticPattern.Send)
        if (state.loading) onCancelClick() else onSendClick()
    }
    
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .imePadding()
                .navigationBarsPadding()
                .padding(bottom = 24.dp, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Media preview row
            if (state.messageContent.isNotEmpty()) {
                MediaFileInputRow(state = state, context = context)
            }
            
            // Suggestions row
            androidx.compose.animation.AnimatedVisibility(
                visible = uiMode == ChatInputUiMode.Normal && chatSuggestions.isNotEmpty(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                val suggestionAutoHideAlpha by animateFloatAsState(
                    targetValue = if (autoHideSuggestions) 0f else 1f,
                    animationSpec = tween(durationMillis = 200),
                    label = "suggestion_auto_hide_alpha"
                )
                ChatSuggestionsRow(
                    modifier = Modifier
                        .graphicsLayer { alpha = suggestionAutoHideAlpha }
                        .onGloballyPositioned {
                            if (it.isAttached) onSuggestionsTopYChanged(it.boundsInWindow().top)
                        },
                    suggestions = chatSuggestions,
                    onClickSuggestion = onClickSuggestion,
                    onLongPressSuggestion = { suggestion ->
                        haptics.perform(HapticPattern.Pop)
                        if (isFocused) {
                            state.insertTextAtCursor(suggestion)
                        } else {
                            state.appendText(suggestion)
                        }
                    }
                )
            }
            
            // Minimal input bar - plus button + text field with embedded action button
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Plus button - 48dp pill button
                Surface(
                    onClick = {
                        haptics.perform(HapticPattern.Pop)
                        showPicker = true
                        keyboardController?.hide()
                    },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // Text field capsule with embedded action button
                // Corner radius = 24dp (user confirmed this was correct)
                Surface(
                    shape = RoundedCornerShape(24.dp),  // Fixed radius - correct per user
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)  // Matches plus button, allows 4dp padding all around
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val showFullscreenInputButton = settings.displaySetting.showFullscreenInputButton &&
                            (isFocused || state.textContent.text.isNotEmpty())
                        val endPadding = if (showFullscreenInputButton) 96.dp else 48.dp

                        // Text input
                            TextField(
                                state = state.textContent,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 1.dp)  // Override internal min height (56dp)
                                    .onFocusChanged { isFocused = it.isFocused },
                                placeholder = {
                                    Text(
                                        text = stringResource(R.string.chat_input_placeholder),
                                        maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 5),  // MultiLine for proper Enter key
                            colors = TextFieldDefaults.colors().copy(
                                unfocusedIndicatorColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                            ),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                start = 16.dp,
                                end = endPadding,  // Space for action buttons + padding
                                top = 12.dp,  // (48dp height - 24dp text) / 2 = 12dp
                                bottom = 12.dp
                            )
                        )

                        val enableMentionSuggestions = groupChatMentionKeys.isNotEmpty()
                        if (enableMentionSuggestions) {
                            val validMentionKeySet = remember(groupChatMentionKeys) {
                                groupChatMentionKeys.map { it.normalizedKey }.toSet()
                            }
                            val text = state.textContent.text.toString()
                            val selection = state.textContent.selection
                            val cursor = kotlin.math.max(selection.start, selection.end).coerceIn(0, text.length)

                            LaunchedEffect(enableMentionSuggestions, validMentionKeySet) {
                                if (!enableMentionSuggestions) return@LaunchedEffect
                                var previousText = state.textContent.text.toString()
                                var previousSelection = state.textContent.selection
                                var skipNext = false

                                snapshotFlow { state.textContent.text.toString() to state.textContent.selection }
                                    .collect { (currentText, currentSelection) ->
                                        if (skipNext) {
                                            skipNext = false
                                            previousText = currentText
                                            previousSelection = currentSelection
                                            return@collect
                                        }

                                        val previousCursor =
                                            kotlin.math.max(previousSelection.start, previousSelection.end)
                                                .coerceIn(0, previousText.length)
                                        val currentCursor =
                                            kotlin.math.max(currentSelection.start, currentSelection.end)
                                                .coerceIn(0, currentText.length)

                                        val isBackspace = previousSelection.start == previousSelection.end &&
                                            currentSelection.start == currentSelection.end &&
                                            previousText.length == currentText.length + 1 &&
                                            currentCursor == previousCursor - 1

                                        if (isBackspace) {
                                            val mentionStart = findMentionTokenStartForAtomicBackspace(
                                                text = previousText,
                                                cursor = previousCursor,
                                                validMentionKeys = validMentionKeySet,
                                            )
                                            if (mentionStart != null && mentionStart < currentCursor) {
                                                skipNext = true
                                                haptics.perform(HapticPattern.Pop)
                                                state.replaceText(mentionStart, currentCursor, "")
                                            }
                                        }

                                        previousText = currentText
                                        previousSelection = currentSelection
                                    }
                            }

                            val activeMention = remember(text, cursor, selection) {
                                if (selection.start != selection.end) return@remember null
                                findActiveMentionContext(text = text, cursor = cursor)
                            }

                            val queryNormalized = remember(activeMention) {
                                activeMention?.query?.lowercase(Locale.ROOT).orEmpty()
                            }

                            val filteredSuggestions = remember(groupChatMentionKeys, queryNormalized) {
                                filterGroupChatMentionSuggestions(
                                    suggestions = groupChatMentionKeys,
                                    queryNormalized = queryNormalized,
                                )
                            }

                            var menuExpanded by remember { mutableStateOf(false) }
                            LaunchedEffect(activeMention, isFocused, filteredSuggestions) {
                                menuExpanded = isFocused && activeMention != null && filteredSuggestions.isNotEmpty()
                            }

                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                                modifier = Modifier.fillMaxWidth(),
                                properties = PopupProperties(focusable = false),
                            ) {
                                filteredSuggestions.take(8).forEach { suggestion ->
                                    val suggestionAssistant = suggestion.sampleAssistantId
                                        ?.let(settings::getAssistantById)
                                    DropdownMenuItem(
                                        leadingIcon = {
                                            suggestionAssistant?.let { assistant ->
                                                UIAvatar(
                                                    name = assistant.name.ifBlank { suggestion.displayName },
                                                    value = assistant.avatar,
                                                    modifier = Modifier.size(28.dp),
                                                    loading = false,
                                                )
                                            }
                                        },
                                        text = { Text(text = "@${suggestion.displayName}") },
                                        trailingIcon = {
                                            if (suggestion.seatCount > 1) {
                                                Badge { Text(suggestion.seatCount.toString()) }
                                            }
                                        },
                                        onClick = {
                                            val context = activeMention ?: return@DropdownMenuItem
                                            val needsTrailingSpace =
                                                cursor >= text.length || !text[cursor].isWhitespace()
                                            val replacement = buildString {
                                                append('@')
                                                append(suggestion.displayName)
                                                if (needsTrailingSpace) append(' ')
                                            }
                                            haptics.perform(HapticPattern.Pop)
                                            state.replaceText(context.atIndex, context.cursor, replacement)
                                            menuExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                        
                        // Action buttons - bottom-right, 4dp padding ("4dp all around")
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (showFullscreenInputButton) {
                                val fullscreenInteractionSource = remember { MutableInteractionSource() }
                                val isFullscreenPressed by fullscreenInteractionSource.collectIsPressedAsState()
                                val fullscreenScale by animateFloatAsState(
                                    targetValue = if (isFullscreenPressed) 0.85f else 1f,
                                    animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
                                    label = "fullscreen_input_scale",
                                )

                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .graphicsLayer {
                                            scaleX = fullscreenScale
                                            scaleY = fullscreenScale
                                        }
                                        .clip(CircleShape)
                                        .clickable(
                                            interactionSource = fullscreenInteractionSource,
                                            indication = LocalIndication.current,
                                        ) {
                                            haptics.perform(HapticPattern.Pop)
                                            isFullScreen = true
                                        }
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Fullscreen,
                                        contentDescription = stringResource(R.string.chat_input_fullscreen),
                                        modifier = Modifier.size(22.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            val currentAction = when {
                                state.loading -> "loading"
                                !state.isEmpty() -> "send"
                                showPicker -> "picker_open"  // New state when picker is visible
                                else -> "picker"
                            }
                            
                            val containerColor by animateColorAsState(
                                targetValue = when (currentAction) {
                                    "loading" -> MaterialTheme.colorScheme.errorContainer
                                    "send" -> MaterialTheme.colorScheme.primary
                                    else -> Color.Transparent
                                },
                                label = "ActionContainerColor"
                            )
                            
                            Surface(
                                onClick = { 
                                    if (currentAction == "send" || currentAction == "loading") sendMessage()
                                    else showPicker = true
                                },
                                shape = CircleShape,
                                color = containerColor,
                                modifier = Modifier.size(40.dp)  // Same as plus button
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                    AnimatedContent(
                                        targetState = currentAction,
                                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                                        label = "ActionContent"
                                    ) { action ->
                                        when (action) {
                                            "loading" -> {
                                                Icon(
                                                    imageVector = Icons.Rounded.Stop,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(22.dp),
                                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                                )
                                            }
                                            "send" -> {
                                                Icon(
                                                    imageVector = Icons.Rounded.ArrowUpward,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(22.dp),
                                                    tint = MaterialTheme.colorScheme.onPrimary
                                                )
                                            }
                                            "picker_open" -> {
                                                // Show file folder icon when picker is open (like LastChat floating toolbar)
                                                Icon(
                                                    imageVector = Icons.Rounded.FolderOpen,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(22.dp),
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            "picker" -> {
                                                if (uiMode == ChatInputUiMode.GroupChat) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.Group,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(24.dp),
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                } else {
                                                    ModelSelector(
                                                        modelId = assistant.chatModelId ?: settings.chatModelId,
                                                        providers = settings.providers,
                                                        onSelect = { onUpdateChatModel(it) },
                                                        type = me.rerere.ai.provider.ModelType.CHAT,
                                                        onlyIcon = true,
                                                        modifier = Modifier.size(24.dp),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }  // Row ends
        }  // Column ends
    }  // Box ends

    if (isFullScreen) {
        FullScreenEditor(
            state = state,
            onSend = { sendMessage() },
        ) {
            isFullScreen = false
        }
    }
    
    // Bottom sheet picker with custom MinimalPickerContent
    // Optical roundness: sheet corners (40dp) = button corners (24dp) + padding (16dp)
    if (showPicker) {
        ModalBottomSheet(
            onDismissRequest = { showPicker = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = pickerSheetColor,
            shape = pickerSheetShape,
            dragHandle = null
        ) {
            MinimalPickerContent(
                state = state,
                conversation = conversation,
                settings = settings,
                mcpManager = mcpManager,
                assistant = assistant,
                uiMode = uiMode,
                cameraPermission = cameraPermission,
                enableSearch = enableSearch,
                onToggleSearch = onToggleSearch,
                onUpdateChatModel = onUpdateChatModel,
                onUpdateConversation = onUpdateConversation,
                onUpdateAssistant = onUpdateAssistant,
                onUpdateSearchService = onUpdateSearchService,
                onNavigateToLorebook = onNavigateToLorebook,
                onShowContextRefreshDialog = { showContextRefreshDialog = true },
                onOpenWorkDirPicker = {
                    showPicker = false
                    showWorkDirPicker = true
                },
                onDismiss = { showPicker = false }
            )
        }
    }

    if (showContextRefreshDialog) {
        ContextRefreshDialog(
            conversation = conversation,
            onRefresh = onRefreshContext,
            onDismiss = { showContextRefreshDialog = false }
        )
    }
}

@Composable
private fun MinimalPickerContent(
    state: ChatInputState,
    conversation: Conversation,
    settings: Settings,
    mcpManager: McpManager,
    assistant: Assistant,
    uiMode: ChatInputUiMode,
    cameraPermission: me.rerere.rikkahub.ui.components.ui.permission.PermissionState,
    enableSearch: Boolean,
    onToggleSearch: (Boolean) -> Unit,
    onUpdateChatModel: (Model) -> Unit,
    onUpdateConversation: (Conversation) -> Unit,
    onOpenWorkDirPicker: () -> Unit,
    onUpdateAssistant: (Assistant) -> Unit,
    onUpdateSearchService: (Int) -> Unit,
    onNavigateToLorebook: (String) -> Unit,
    onShowContextRefreshDialog: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val localSettings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val haptics = rememberPremiumHaptics(enabled = localSettings.displaySetting.enableUIHaptics)
    
    // OLED dark mode detection for buttons (not sheet backgrounds)
    val amoledMode by me.rerere.rikkahub.ui.hooks.rememberAmoledDarkMode()
    val isDarkMode = me.rerere.rikkahub.ui.theme.LocalDarkMode.current
    val isAmoled = amoledMode && isDarkMode
    // Sheet background uses surfaceContainerLow always
    val sheetContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
    
    // Camera state
    var cameraOutputUri by remember { mutableStateOf<Uri?>(null) }
    var cameraOutputFile by remember { mutableStateOf<File?>(null) }
    
    // Crop state
    var showCropScreen by remember { mutableStateOf(false) }
    var imageToCrop by remember { mutableStateOf<Uri?>(null) }
    
    // Sub-picker states
    var showModelPicker by remember { mutableStateOf(false) }
    var showReasoningPicker by remember { mutableStateOf(false) }
    var showInjectionPicker by remember { mutableStateOf(false) }
    var showSearchPicker by remember { mutableStateOf(false) }
    var showMcpPicker by remember { mutableStateOf(false) }
    var showAttachmentMenu by remember { mutableStateOf(false) }
    val currentChatModel = settings.getCurrentChatModel()
    val showGeminiAttachmentMenu = remember(currentChatModel?.modelId) {
        isGeminiAttachmentMenuEnabled(currentChatModel)
    }

    val mcpServers = settings.mcpServers
    val enabledMcpServersCount = remember(mcpServers, assistant.mcpServers) {
        mcpServers.count { it.commonOptions.enable && it.id in assistant.mcpServers }
    }
    val mcpSyncStatus by mcpManager.syncingStatus.collectAsStateWithLifecycle()
    val mcpLoading = mcpSyncStatus.values.any { it == McpStatus.Connecting }
    val enabledSkills = remember(settings.skills, assistant.enabledSkillIds) {
        settings.skills.filter { skill -> skill.id in assistant.enabledSkillIds }
    }
    val activeExplicitSkillIds = remember(
        conversation.explicitSkillContextIds,
        assistant.enabledSkillIds,
        enabledSkills,
    ) {
        conversation.explicitSkillContextIds.filter { skillId ->
            skillId in assistant.enabledSkillIds && enabledSkills.any { skill -> skill.id == skillId }
        }.toSet()
    }
    
    // Track the last valid search provider index so selection persists when search is disabled
    var lastValidProviderIndex by rememberSaveable { mutableStateOf(settings.searchServiceSelected.coerceAtLeast(0)) }
    
    // Update lastValidProviderIndex when a valid external index is set
    val currentSearchProviderIndices = when (val mode = assistant.searchMode) {
        is me.rerere.rikkahub.data.model.AssistantSearchMode.Provider -> listOf(mode.index)
        is me.rerere.rikkahub.data.model.AssistantSearchMode.MultiProvider -> mode.indices
        else -> emptyList()
    }
    val currentProviderIndex = currentSearchProviderIndices.firstOrNull() ?: -1
    val enableSearchAgent = assistant.enableSearchAgent
    LaunchedEffect(currentProviderIndex) {
        if (currentProviderIndex >= 0 && currentProviderIndex < settings.searchServices.size) {
            lastValidProviderIndex = currentProviderIndex
        }
    }
    
    // Calculate effective provider index (use tracked value when current is invalid)
    val effectiveProviderIndex = if (currentProviderIndex >= 0 && currentProviderIndex < settings.searchServices.size) {
        currentProviderIndex
    } else {
        lastValidProviderIndex.coerceIn(0, (settings.searchServices.size - 1).coerceAtLeast(0))
    }
    
    // Button shapes for grouped appearance (24dp outer corners, 10dp inner, matches floating toolbar)
    val leftButtonShape = RoundedCornerShape(topStart = 24.dp, topEnd = 10.dp, bottomStart = 24.dp, bottomEnd = 10.dp)
    val middleButtonShape = RoundedCornerShape(10.dp)
    val rightButtonShape = RoundedCornerShape(topStart = 10.dp, topEnd = 24.dp, bottomStart = 10.dp, bottomEnd = 24.dp)
    
    // Crop screen dialog
    if (showCropScreen && imageToCrop != null) {
        CropImageScreen(
            sourceUri = imageToCrop!!,
            onCropComplete = { croppedUri ->
                state.addImages(context.createChatFilesByContents(listOf(croppedUri)))
                showCropScreen = false
                imageToCrop = null
                cameraOutputFile?.delete()
                cameraOutputFile = null
                cameraOutputUri = null
            },
            onCancel = {
                showCropScreen = false
                imageToCrop = null
                cameraOutputFile?.delete()
                cameraOutputFile = null
                cameraOutputUri = null
            }
        )
    }
    
    // Camera launcher
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { captureSuccessful ->
        if (captureSuccessful && cameraOutputUri != null) {
            if (localSettings.displaySetting.skipCropImage) {
                state.addImages(context.createChatFilesByContents(listOf(cameraOutputUri!!)))
                cameraOutputFile?.delete()
                cameraOutputFile = null
                cameraOutputUri = null
            } else {
                imageToCrop = cameraOutputUri
                showCropScreen = true
            }
        } else {
            cameraOutputFile?.delete()
            cameraOutputFile = null
            cameraOutputUri = null
        }
    }
    
    // Photo picker launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { selectedUris ->
        if (selectedUris.isNotEmpty()) {
            if (localSettings.displaySetting.skipCropImage) {
                state.addImages(context.createChatFilesByContents(selectedUris))
                onDismiss()
            } else {
                if (selectedUris.size == 1) {
                    imageToCrop = selectedUris.first()
                    showCropScreen = true
                } else {
                    state.addImages(context.createChatFilesByContents(selectedUris))
                    onDismiss()
                }
            }
        }
    }
    
    // File picker launcher
    val videoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { selectedUris ->
        if (selectedUris.isNotEmpty()) {
            scope.launch {
                val localUris = withContext(Dispatchers.IO) {
                    context.createChatFilesByContents(selectedUris)
                }
                state.addVideos(localUris)
                onDismiss()
            }
        }
    }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { selectedUris ->
        if (selectedUris.isNotEmpty()) {
            scope.launch {
                val localUris = withContext(Dispatchers.IO) {
                    context.createChatFilesByContents(selectedUris)
                }
                state.addAudios(localUris)
                onDismiss()
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { selectedUris ->
        if (selectedUris.isNotEmpty()) {
            scope.launch {
                val documents = withContext(Dispatchers.IO) {
                    context.toSupportedChatDocuments(selectedUris)
                }

                if (documents.isNotEmpty()) {
                    state.addFiles(documents)
                    onDismiss()
                } else {
                    val fileName = selectedUris.firstOrNull()?.let(context::getFileNameFromUri) ?: "file"
                    toaster.show(
                        context.getString(R.string.assistant_importer_unsupported_file_type, fileName),
                        type = ToastType.Error,
                    )
                }
            }
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // File upload buttons - grouped with corner shapes (no outer container)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Camera button - icon only, no label
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                PermissionManager(permissionState = cameraPermission) {
                    MinimalFileButtonGroupedIconOnly(
                        icon = Icons.Rounded.CameraAlt,
                        shape = leftButtonShape,
                        modifier = Modifier.fillMaxSize(),
                        onClick = {
                            if (cameraPermission.allRequiredPermissionsGranted) {
                                cameraOutputFile = context.cacheDir.resolve("camera_${Uuid.random()}.jpg")
                                cameraOutputUri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    cameraOutputFile!!
                                )
                                cameraLauncher.launch(cameraOutputUri!!)
                            } else {
                                cameraPermission.requestPermissions()
                            }
                        }
                    )
                }
            }
            
            // Photos button - icon only, no label
            MinimalFileButtonGroupedIconOnly(
                icon = Icons.Rounded.Image,
                shape = middleButtonShape,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                onClick = {
                    imagePickerLauncher.launch("image/*")
                }
            )
            
            // Files button - icon only, no label
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                MinimalFileButtonGroupedIconOnly(
                    shape = rightButtonShape,
                    modifier = Modifier.fillMaxSize(),
                    onClick = {
                        haptics.perform(HapticPattern.Pop)
                        if (showGeminiAttachmentMenu) {
                            showAttachmentMenu = true
                        } else {
                            filePickerLauncher.launch(arrayOf("*/*"))
                        }
                    },
                    content = {
                        if (showGeminiAttachmentMenu) {
                            GeminiAttachmentMenuIcon()
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                )

                if (showGeminiAttachmentMenu) {
                    GeminiAttachmentMenu(
                        expanded = showAttachmentMenu,
                        onDismissRequest = { showAttachmentMenu = false },
                        onPickVideo = {
                            haptics.perform(HapticPattern.Pop)
                            videoPickerLauncher.launch("video/*")
                        },
                        onPickAudio = {
                            haptics.perform(HapticPattern.Pop)
                            audioPickerLauncher.launch("audio/*")
                        },
                        onPickFile = {
                            haptics.perform(HapticPattern.Pop)
                            filePickerLauncher.launch(arrayOf("*/*"))
                        },
                        modifier = Modifier,
                    )
                }
            }
        }

        if (conversation.messageNodes.isEmpty()) {
            val workspaceReady = !settings.getEffectiveWorkspaceRootTreeUri(conversation.id).isNullOrBlank()
            val hasConversationRootOverride = settings.hasConversationWorkspaceRoot(conversation.id)
            val workDirKey = conversation.id.toString()
            val currentWorkDirBinding = settings.conversationWorkDirs[workDirKey]
            val subtitle = if (workspaceReady) {
                when (currentWorkDirBinding?.mode) {
                    ConversationWorkDirMode.MANUAL -> {
                        val relPath = currentWorkDirBinding.relPath.trim()
                        if (relPath.isBlank()) {
                            context.getString(R.string.workdir_current_root)
                        } else {
                            context.getString(R.string.workdir_current_manual, relPath)
                        }
                    }

                    else -> {
                        if (hasConversationRootOverride) {
                            context.getString(R.string.workdir_current_root)
                        } else {
                            context.getString(R.string.workdir_current_auto)
                        }
                    }
                }
            } else {
                context.getString(R.string.workspace_root_required_hint_v2)
            }

            MinimalPickerItem(
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                },
                title = stringResource(R.string.workdir_quick_setup_title),
                subtitle = subtitle,
                onClick = {
                    onOpenWorkDirPicker()
                },
            )
        }
        
        // Separator
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
        )
        
        if (uiMode == ChatInputUiMode.Normal) {
            // Model picker - uses actual model icon, full-width clickable
            val currentModel = currentChatModel
            val provider = currentModel?.findProvider(providers = settings.providers)
            MinimalPickerItem(
                icon = {
                    // Show model icon (not ModelSelector which handles its own clicks)
                    if (currentModel != null) {
                        me.rerere.rikkahub.ui.components.ui.ModelIcon(
                            model = currentModel,
                            provider = provider,
                            modifier = Modifier.size(24.dp),
                            color = androidx.compose.ui.graphics.Color.Transparent
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.ViewModule,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                },
                title = currentModel?.displayName ?: "Select Model",
                subtitle = currentModel?.modelId ?: "Choose a model to use",
                onClick = {
                    // Open model selector sheet
                    showModelPicker = true
                }
            )

            // Reasoning picker - show if model is selected
            if (currentModel != null) {
                MinimalPickerItem(
                    icon = {
                        Icon(
                            imageVector = Icons.Rounded.Lightbulb,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    title = stringResource(R.string.minimal_input_thinking),
                    subtitle = stringResource(R.string.minimal_input_thinking_desc),
                    onClick = {
                        showReasoningPicker = true
                    }
                )
            }

            // Search picker - show selected provider if enabled
            val selectedSearchServices = currentSearchProviderIndices
                .asSequence()
                .filter { index -> index in settings.searchServices.indices }
                .distinct()
                .sorted()
                .map { index -> index to settings.searchServices[index] }
                .toList()
            val firstSearchService = selectedSearchServices.firstOrNull()?.second
            val searchProviderTypeName = firstSearchService?.let { service ->
                SearchServiceOptions.TYPES[service::class]
            }
            val searchProviderName = selectedSearchServices
                .joinToString(", ") { (index, service) ->
                    service.displayName.ifBlank { "Provider ${index + 1}" }
                }
                .takeIf { it.isNotBlank() }
            val modelProviderForSearch = currentChatModel?.findProvider(settings.providers)
            val isUsingBuiltInSearch = enableSearch &&
                currentChatModel?.supportsBuiltInSearch(modelProviderForSearch) == true &&
                !enableSearchAgent &&
                (
                    assistant.searchMode is me.rerere.rikkahub.data.model.AssistantSearchMode.BuiltIn ||
                        assistant.preferBuiltInSearch
                    )
            val searchTitle = when {
                !enableSearch -> stringResource(R.string.minimal_input_search)
                isUsingBuiltInSearch -> stringResource(R.string.built_in_search_title)
                searchProviderName != null -> searchProviderName
                else -> stringResource(R.string.minimal_input_search)
            }
            val searchSubtitle = when {
                enableSearch && enableSearchAgent -> stringResource(R.string.search_agent_enabled_mode)
                enableSearch -> stringResource(R.string.web_search_enabled)
                else -> stringResource(R.string.minimal_input_search_desc)
            }

            // Show provider icon when search is enabled and a provider is configured
            MinimalPickerItem(
                icon = {
                    if (enableSearch && !isUsingBuiltInSearch && searchProviderTypeName != null) {
                        AutoAIIcon(
                            name = searchProviderTypeName,
                            modifier = Modifier.size(24.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = if (enableSearch) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                title = searchTitle,
                subtitle = searchSubtitle,
                onClick = {
                    showSearchPicker = true
                }
            )

            if (uiMode == ChatInputUiMode.Normal && mcpServers.isNotEmpty()) {
                MinimalPickerItem(
                    icon = {
                        if (mcpLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Terminal,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = if (enabledMcpServersCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    title = stringResource(R.string.mcp_picker_title),
                    subtitle = "${enabledMcpServersCount}/${mcpServers.size} · ${stringResource(R.string.assistant_page_mcp_servers_desc)}",
                    onClick = {
                        showMcpPicker = true
                    }
                )
            }

        }

        val activeModesCount = settings.modes.count { mode ->
            conversation.enabledModeIds.contains(mode.id)
        }
        val activeLorebooksCount = settings.lorebooks.count { lorebook ->
            lorebook.id in assistant.enabledLorebookIds
        }
        val showSkillsTab = uiMode == ChatInputUiMode.Normal && enabledSkills.isNotEmpty()
        val activeSkillCount = if (showSkillsTab) activeExplicitSkillIds.size else 0
        val activeInjectionCount = activeModesCount + activeLorebooksCount + activeSkillCount
        val injectionSummary = injectionPickerSummaryText(
            showSkills = showSkillsTab,
            activeSkillCount = activeSkillCount,
            totalSkillCount = enabledSkills.size,
            activeLorebookCount = activeLorebooksCount,
            totalLorebookCount = settings.lorebooks.size,
            activeModeCount = activeModesCount,
            totalModeCount = settings.modes.size,
        )
        MinimalPickerItem(
            icon = {
                Icon(
                    imageVector = Icons.Rounded.AutoFixHigh,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = if (activeInjectionCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            title = stringResource(R.string.injection_picker_title),
            subtitle = injectionSummary,
            onClick = { 
                showInjectionPicker = true
            }
        )
        
        if (uiMode == ChatInputUiMode.Normal) {
            // Summarize button - only show when context refresh is enabled and there are enough messages
            if (assistant.enableContextRefresh && conversation.currentMessages.size > 2) {
                MinimalPickerItem(
                    icon = {
                        Icon(
                            imageVector = Icons.Rounded.Summarize,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    title = stringResource(R.string.minimal_input_summarize),
                    subtitle = stringResource(R.string.minimal_input_summarize_desc),
                    onClick = {
                        onShowContextRefreshDialog()
                    }
                )
            }
        }
    }
    
    // Reasoning picker sheet
    if (uiMode == ChatInputUiMode.Normal && showReasoningPicker) {
        ReasoningPicker(
            reasoningTokens = assistant.thinkingBudget ?: 0,
            onDismissRequest = { showReasoningPicker = false },
            onUpdateReasoningTokens = { tokens ->
                onUpdateAssistant(assistant.copy(thinkingBudget = tokens))
                showReasoningPicker = false
            }
        )
    }
    
    // Model picker sheet - direct ModalBottomSheet (not ModelSelector which shows its own button)
    if (uiMode == ChatInputUiMode.Normal && showModelPicker) {
        val modelPickerSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val filteredProviders = settings.providers.filter { 
            it.enabled && it.models.any { model -> model.type == me.rerere.ai.provider.ModelType.CHAT }
        }
        
        ModalBottomSheet(
            onDismissRequest = { showModelPicker = false },
            sheetState = modelPickerSheetState,
            sheetGesturesEnabled = false,
            containerColor = sheetContainerColor,
            dragHandle = {
                IconButton(
                    onClick = {
                        scope.launch {
                            modelPickerSheetState.hide()
                            showModelPicker = false
                        }
                    }
                ) {
                    Icon(Icons.Rounded.KeyboardArrowDown, null)
                }
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight(0.8f)
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ModelList(
                    currentModel = assistant.chatModelId ?: settings.chatModelId,
                    providers = filteredProviders,
                    modelType = me.rerere.ai.provider.ModelType.CHAT,
                    onSelect = { selectedModel: Model ->
                        onUpdateChatModel(selectedModel)
                        scope.launch {
                            modelPickerSheetState.hide()
                            showModelPicker = false
                        }
                    },
                    onDismiss = {
                        scope.launch {
                            modelPickerSheetState.hide()
                            showModelPicker = false
                        }
                    }
                )
            }
        }
    }
    
    if (showInjectionPicker) {
        InjectionPickerSheet(
            settings = settings,
            conversation = conversation,
            assistant = assistant,
            enabledSkills = enabledSkills,
            selectedSkillIds = activeExplicitSkillIds,
            uiMode = uiMode,
            onUpdateConversation = onUpdateConversation,
            onUpdateAssistant = onUpdateAssistant,
            onNavigateToLorebook = { lorebookId: String ->
                showInjectionPicker = false
                onNavigateToLorebook(lorebookId)
            },
            onSelectedSkillIdsChange = { nextIds: Set<Uuid> ->
                onUpdateConversation(conversation.copy(explicitSkillContextIds = nextIds))
            },
            onDismiss = { showInjectionPicker = false },
        )
    }
    
    if (uiMode == ChatInputUiMode.Normal && showMcpPicker) {
        ModalBottomSheet(
            onDismissRequest = { showMcpPicker = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = sheetContainerColor
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.7f)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(id = R.string.mcp_picker_title),
                    style = MaterialTheme.typography.titleLarge
                )
                if (mcpLoading) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = stringResource(id = R.string.mcp_picker_syncing),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
                McpPicker(
                    assistant = assistant,
                    servers = mcpServers,
                    onUpdateAssistant = onUpdateAssistant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }
        }
    }

    // Search picker sheet (same as floating toolbar) - direct content, no intermediate button
    if (uiMode == ChatInputUiMode.Normal && showSearchPicker) {
        val chatModel = settings.getCurrentChatModel()
        
        ModalBottomSheet(
            onDismissRequest = { showSearchPicker = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = sheetContainerColor
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .padding(bottom = 16.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.search_picker_title),
                    style = MaterialTheme.typography.titleLarge
                )
                
                // Direct SearchPicker content
                SearchPicker(
                    enableSearch = enableSearch,
                    settings = settings,
                    model = chatModel,
                    onToggleSearch = onToggleSearch,
                    onUpdateSearchService = { index ->
                        // Track this selection
                        lastValidProviderIndex = index
                        onUpdateSearchService(index)
                    },
                    selectedProviderIndex = effectiveProviderIndex,  // Use effective index so selection persists when off
                    selectedProviderIndices = currentSearchProviderIndices,
                    onUpdateSearchProviders = { indices ->
                        val sanitized = indices
                            .asSequence()
                            .filter { index -> index >= 0 && index < settings.searchServices.size }
                            .distinct()
                            .sorted()
                            .toList()

                        val nextMode = buildAssistantProviderSearchMode(indices = sanitized)

                        onUpdateAssistant(assistant.copy(searchMode = nextMode))
                    },
                    preferBuiltInSearch = assistant.preferBuiltInSearch,
                    onTogglePreferBuiltInSearch = { enabled ->
                        onUpdateAssistant(
                            assistant.copy(
                                preferBuiltInSearch = enabled,
                                enableSearchAgent = if (enabled) false else assistant.enableSearchAgent,
                            )
                        )
                    },
                    enableSearchAgent = enableSearchAgent,
                    onToggleSearchAgent = { enabled ->
                        val nextSearchMode = if (
                            enabled &&
                            assistant.searchMode !is me.rerere.rikkahub.data.model.AssistantSearchMode.Provider &&
                            assistant.searchMode !is me.rerere.rikkahub.data.model.AssistantSearchMode.MultiProvider &&
                            settings.searchServices.isNotEmpty()
                        ) {
                            buildAssistantProviderSearchMode(
                                indices = listOf(
                                    settings.searchServiceSelected.coerceIn(0, settings.searchServices.lastIndex)
                                )
                            )
                        } else {
                            assistant.searchMode
                        }
                        onUpdateAssistant(
                            assistant.copy(
                                enableSearchAgent = enabled,
                                preferBuiltInSearch = if (enabled) false else assistant.preferBuiltInSearch,
                                searchMode = nextSearchMode,
                            )
                        )
                    },
                    onDismiss = { showSearchPicker = false },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun MinimalFileButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.height(80.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// Compact file button for use inside grouped container (24dp inner radius for optical roundness)
@Composable
private fun MinimalFileButtonCompact(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),  // Optically round with 40dp outer container
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.height(72.dp)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// Grouped file button with custom shape for grouped appearance (same as floating toolbar)
@Composable
private fun MinimalFileButtonGrouped(
    icon: ImageVector,
    label: String,
    shape: androidx.compose.ui.graphics.Shape,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    // OLED-aware button color (same as floating toolbar's BigIconTextButton)
    val amoledMode by me.rerere.rikkahub.ui.hooks.rememberAmoledDarkMode()
    val isDarkMode = me.rerere.rikkahub.ui.theme.LocalDarkMode.current
    val isAmoled = amoledMode && isDarkMode
    val buttonColor = if (isAmoled) androidx.compose.ui.graphics.Color.Black 
                      else MaterialTheme.colorScheme.surfaceContainerHigh
    
    Surface(
        onClick = onClick,
        shape = shape,
        color = buttonColor,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// Grouped file button icon-only variant (no text label) for compact picker display
@Composable
private fun MinimalFileButtonGroupedIconOnly(
    shape: androidx.compose.ui.graphics.Shape,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    // OLED-aware button color (same as floating toolbar's BigIconTextButton)
    val amoledMode by me.rerere.rikkahub.ui.hooks.rememberAmoledDarkMode()
    val isDarkMode = me.rerere.rikkahub.ui.theme.LocalDarkMode.current
    val isAmoled = amoledMode && isDarkMode
    val buttonColor = if (isAmoled) androidx.compose.ui.graphics.Color.Black 
                      else MaterialTheme.colorScheme.surfaceContainerHigh
    
    Surface(
        onClick = onClick,
        shape = shape,
        color = buttonColor,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

@Composable
private fun MinimalFileButtonGroupedIconOnly(
    icon: ImageVector,
    shape: androidx.compose.ui.graphics.Shape,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    MinimalFileButtonGroupedIconOnly(
        shape = shape,
        modifier = modifier,
        onClick = onClick,
        content = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    )
}

@Composable
private fun MinimalPickerItem(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        // Custom Row layout with less padding than ListItem (12dp vertical, 8dp horizontal)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                icon()
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

    }
}

@Composable
private fun MediaFileInputRow(
    state: ChatInputState,
    context: android.content.Context
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .horizontalScroll(rememberScrollState())
    ) {
        state.messageContent.filterIsInstance<UIMessagePart.Image>().fastForEach { image ->
            Box {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = RoundedCornerShape(8.dp),
                    tonalElevation = 4.dp
                ) {
                    AsyncImage(
                        model = image.url,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = null,
                    modifier = Modifier
                        .clip(CircleShape)
                        .size(20.dp)
                        .clickable {
                            state.messageContent = state.messageContent.filterNot { it == image }
                            context.deleteChatFiles(listOf(image.url.toUri()))
                        }
                        .align(Alignment.TopEnd)
                        .background(MaterialTheme.colorScheme.secondary),
                    tint = MaterialTheme.colorScheme.onSecondary
                )
            }
        }
        state.messageContent.filterIsInstance<UIMessagePart.Video>().fastForEach { video ->
            Box {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = RoundedCornerShape(8.dp),
                    tonalElevation = 4.dp
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.VideoLibrary, null)
                    }
                }
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = null,
                    modifier = Modifier
                        .clip(CircleShape)
                        .size(20.dp)
                        .clickable {
                            state.messageContent = state.messageContent.filterNot { it == video }
                            context.deleteChatFiles(listOf(video.url.toUri()))
                        }
                        .align(Alignment.TopEnd)
                        .background(MaterialTheme.colorScheme.secondary),
                    tint = MaterialTheme.colorScheme.onSecondary
                )
            }
        }
        state.messageContent.filterIsInstance<UIMessagePart.Audio>().fastForEach { audio ->
            Box {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = RoundedCornerShape(8.dp),
                    tonalElevation = 4.dp
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.AudioFile, null)
                    }
                }
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = null,
                    modifier = Modifier
                        .clip(CircleShape)
                        .size(20.dp)
                        .clickable {
                            state.messageContent = state.messageContent.filterNot { it == audio }
                            context.deleteChatFiles(listOf(audio.url.toUri()))
                        }
                        .align(Alignment.TopEnd)
                        .background(MaterialTheme.colorScheme.secondary),
                    tint = MaterialTheme.colorScheme.onSecondary
                )
            }
        }
        state.messageContent.filterIsInstance<UIMessagePart.Document>().fastForEach { document ->
            Box {
                Surface(
                    modifier = Modifier
                        .height(48.dp)
                        .defaultMinSize(minWidth = 48.dp),
                    shape = RoundedCornerShape(8.dp),
                    tonalElevation = 4.dp
                ) {
                        androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface.copy(0.8f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(4.dp)
                        ) {
                            Text(
                                text = document.fileName,
                                maxLines = 1,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }
                    }
                }
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = null,
                    modifier = Modifier
                        .clip(CircleShape)
                        .padding(end = 4.dp)
                        .size(24.dp)
                        .clickable {
                            state.messageContent = state.messageContent.filterNot { it == document }
                            context.deleteChatFiles(listOf(document.url.toUri()))
                        }
                        .align(Alignment.TopEnd)
                        .background(MaterialTheme.colorScheme.secondary),
                    tint = MaterialTheme.colorScheme.onSecondary
                )
            }
        }
    }
}

@Composable
private fun ChatSuggestionsRow(
    modifier: Modifier = Modifier,
    suggestions: List<String>,
    onClickSuggestion: (String) -> Unit,
    onLongPressSuggestion: (String) -> Unit,
) {
    val scrollState = rememberScrollState()
    var pressedSuggestionIndex by remember { mutableStateOf<Int?>(null) }
    var selectedSuggestionIndex by remember { mutableStateOf<Int?>(null) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        suggestions.forEachIndexed { index, suggestion ->
            var visible by remember { mutableStateOf(false) }
            val interactionSource = remember { MutableInteractionSource() }
            val isInteractionPressed by interactionSource.collectIsPressedAsState()

            LaunchedEffect(isInteractionPressed) {
                if (isInteractionPressed) {
                    pressedSuggestionIndex = index
                } else if (pressedSuggestionIndex == index) {
                    pressedSuggestionIndex = null
                }
            }

            LaunchedEffect(suggestion) {
                kotlinx.coroutines.delay(index * 50L)
                visible = true
            }

            val isSelected = selectedSuggestionIndex == index
            val isPressed = pressedSuggestionIndex == index
            val isAnythingSelected = selectedSuggestionIndex != null
            val isAnythingPressed = pressedSuggestionIndex != null
            
            val targetScale = when {
                isSelected -> 1.05f
                isPressed -> 0.9f
                else -> 1f
            }
            
            val targetAlpha = when {
                isSelected -> 0f
                isAnythingSelected -> 0f
                isAnythingPressed && !isPressed -> 0.5f 
                visible -> 1f
                else -> 0f
            }

            val scale by animateFloatAsState(
                targetValue = targetScale,
                animationSpec = spring(dampingRatio = 0.7f, stiffness = 300f),
                label = "suggestion_scale"
            )

            val alpha by animateFloatAsState(
                targetValue = targetAlpha,
                animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f),
                label = "suggestion_alpha"
            )
            
            LaunchedEffect(isSelected) {
                if (isSelected) {
                    kotlinx.coroutines.delay(200)
                    onClickSuggestion(suggestion)
                }
            }

            if (visible || targetAlpha > 0f) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            this.alpha = alpha
                        }
                        .combinedClickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = { selectedSuggestionIndex = index },
                            onLongClick = { onLongPressSuggestion(suggestion) },
                        )
                ) {
                    Text(
                        text = suggestion,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
