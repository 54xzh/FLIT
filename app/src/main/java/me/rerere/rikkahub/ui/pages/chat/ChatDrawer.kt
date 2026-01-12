package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.ui.theme.LocalDarkMode

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.ConversationWorkDirBinding
import me.rerere.rikkahub.data.datastore.ConversationWorkDirMode
import me.rerere.rikkahub.data.datastore.getEffectiveWorkspaceRootTreeUri
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.hasConversationWorkspaceRoot
import me.rerere.rikkahub.data.model.ChatTarget
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.id
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.ui.components.ai.AssistantPicker
import me.rerere.rikkahub.ui.components.ui.Greeting
import me.rerere.rikkahub.ui.components.ui.Tooltip
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.components.ui.UpdateEntryButton
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.readBooleanPreference
import me.rerere.rikkahub.ui.hooks.rememberIsPlayStoreVersion
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.modifier.onClick
import me.rerere.rikkahub.ui.components.workdir.WorkDirPickerBottomSheet
import me.rerere.rikkahub.utils.navigateToChatPage
import me.rerere.rikkahub.utils.toDp
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
fun ChatDrawerContent(
    navController: NavHostController,
    vm: ChatVM,
    settings: Settings,
    current: Conversation,
    drawerState: androidx.compose.material3.DrawerState? = null,  // Optional for animated close
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val toaster = me.rerere.rikkahub.ui.context.LocalToaster.current
    val isPlayStore = rememberIsPlayStoreVersion()
    val repo = koinInject<ConversationRepository>()

    val conversations = vm.conversations.collectAsLazyPagingItems()
    val searchQuery by vm.searchQuery.collectAsStateWithLifecycle()

    val conversationJobs by vm.conversationJobs.collectAsStateWithLifecycle(
        initialValue = emptyMap(),
    )

    val recentlyRestoredIds by vm.recentlyRestoredIds.collectAsStateWithLifecycle()

    var managingWorkDirConversation by remember { mutableStateOf<Conversation?>(null) }

    // 昵称编辑状态
    val nicknameEditState = useEditState<String> { newNickname ->
        vm.updateSettings(
            settings.copy(
                displaySetting = settings.displaySetting.copy(
                    userNickname = newNickname
                )
            )
        )
    }

    ModalDrawerSheet(
        modifier = Modifier.width(300.dp),
        drawerShape = me.rerere.rikkahub.ui.theme.AppShapes.CardLarge,
        drawerContainerColor = if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 用户头像和昵称自定义区域
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                UIAvatar(
                    name = settings.displaySetting.userNickname.ifBlank { stringResource(R.string.user_default_name) },
                    value = settings.displaySetting.userAvatar,
                    onUpdate = { newAvatar ->
                        vm.updateSettings(
                            settings.copy(
                                displaySetting = settings.displaySetting.copy(
                                    userAvatar = newAvatar
                                )
                            )
                        )
                    },
                    modifier = Modifier.size(50.dp),
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = settings.displaySetting.userNickname.ifBlank { stringResource(R.string.user_default_name) },
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable {
                                nicknameEditState.open(settings.displaySetting.userNickname)
                            }
                    )
                    }
                    Greeting(
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                        ),
                        assistant = settings.getCurrentAssistant()
                    )
                }

                if (settings.displaySetting.showUpdates && !isPlayStore) {
                    UpdateEntryButton(vm = vm)
                }
            }

            val canConsolidate = when (val target = settings.chatTarget) {
                is ChatTarget.Assistant -> {
                    val assistant = settings.getCurrentAssistant()
                    assistant.enableMemory && assistant.enableMemoryConsolidation
                }

                is ChatTarget.GroupChat -> {
                    val template = settings.groupChatTemplates.firstOrNull { it.id == target.templateId }
                    if (template == null || template.integrationModelId == null) {
                        false
                    } else {
                        template.seats
                            .asSequence()
                            .filter { seat -> seat.overrides.memoryEnabled }
                            .map { seat -> seat.assistantId }
                            .distinct()
                            .mapNotNull { assistantId -> settings.assistants.firstOrNull { it.id == assistantId } }
                            .any { assistant -> assistant.enableMemory && assistant.enableMemoryConsolidation }
                    }
                }
            }

            ConversationList(
                current = current,
                conversations = conversations,
                conversationJobs = conversationJobs.keys,
                recentlyRestoredIds = recentlyRestoredIds,
                searchQuery = searchQuery,
                onSearchQueryChange = { vm.updateSearchQuery(it) },
                drawerState = drawerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                onClick = {
                    // Only pass search query if the match was from message content (not title)
                    // This scrolls to the matching message; for title matches, just open normally
                    val titleMatches = searchQuery.isNotBlank() && it.title.contains(searchQuery, ignoreCase = true)
                    navigateToChatPage(navController, it.id, searchQuery = if (titleMatches) null else searchQuery.ifBlank { null })
                },
                onRegenerateTitle = {
                    vm.generateTitle(it, true)
                },
                onConsolidate = {
                    vm.consolidateConversation(it)
                },
                onDelete = {
                    vm.deleteConversation(it)
                    toaster.show(
                        message = context.getString(R.string.conversation_deleted),
                        action = me.rerere.rikkahub.ui.components.ui.ToastAction(
                            label = context.getString(R.string.undo),
                            onClick = {
                                vm.undoDeleteConversation(it.id)
                            }
                        )
                    )
                    if (it.id == current.id) {
                        navigateToChatPage(navController)
                    }
                },
                onPin = {
                    vm.updatePinnedStatus(it)
                },
                onManageWorkDir = { conversation ->
                    managingWorkDirConversation = conversation
                },
                showUnconsolidatedDot = canConsolidate,
                showConsolidateOption = canConsolidate,
            )

            // 助手选择器
            if (settings.assistants.size > 1 || settings.groupChatTemplates.isNotEmpty()) {
                AssistantPicker(
                    settings = settings,
                    onUpdateSettings = { newSettings ->
                        // Just update settings - don't navigate yet
                        vm.updateSettings(newSettings)
                    },
                    onNavigate = { target ->
                        // Called after sheet closes - just close drawer and navigate
                        scope.launch {
                            // Close drawer with animation
                            drawerState?.close()

                            // Avoid racing: wait until settings reflect the selected chat target.
                            withTimeoutOrNull(1500) {
                                vm.settings.first { settings -> !settings.init && settings.chatTarget == target }
                            }
                            
                            // Navigate to new chat
                            val id = if (context.readBooleanPreference("create_new_conversation_on_start", true)) {
                                Uuid.random()
                            } else {
                                withContext(Dispatchers.IO) {
                                    repo.getConversationsOfAssistant(target.id)
                                        .first()
                                        .firstOrNull()
                                        ?.id
                                } ?: Uuid.random()
                            }
                            navigateToChatPage(navController = navController, chatId = id)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    onClickSetting = {
                        when (val target = settings.chatTarget) {
                            is ChatTarget.Assistant -> {
                                navController.navigate(Screen.AssistantDetail(id = target.assistantId.toString()))
                            }

                            is ChatTarget.GroupChat -> {
                                navController.navigate(Screen.GroupChatTemplateDetail(id = target.templateId.toString()))
                            }
                        }
                    }
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            ) {
                DrawerAction(
                    icon = {
                        Icon(imageVector = Icons.Rounded.Group, contentDescription = stringResource(R.string.assistant_page_title))
                    },
                    label = {
                        Text(stringResource(R.string.assistant_page_title))
                    },
                    onClick = {
                        navController.navigate(Screen.Assistant)
                    },
                )

                DrawerAction(
                    icon = {
                        Icon(Icons.Rounded.Home, contentDescription = stringResource(R.string.menu))
                    },
                    label = {
                        Text(stringResource(R.string.menu))
                    },
                    onClick = {
                        navController.navigate(Screen.Menu)
                    },
                )

                Spacer(Modifier.weight(1f))

                DrawerAction(
                    icon = {
                        Icon(Icons.Rounded.Settings, null)
                    },
                    label = { Text(stringResource(R.string.settings)) },
                    onClick = {
                        navController.navigate(Screen.Setting)
                    },
                )
            }
        }
    }

    // 昵称编辑对话框
    managingWorkDirConversation?.let { conversation ->
        val haptics = rememberPremiumHaptics()
        val effectiveWorkspaceRootTreeUri = settings.getEffectiveWorkspaceRootTreeUri(conversation.id)
        val workspaceReady = !effectiveWorkspaceRootTreeUri.isNullOrBlank()
        val hasConversationRootOverride = settings.hasConversationWorkspaceRoot(conversation.id)
        val currentBinding = settings.conversationWorkDirs[conversation.id.toString()]
        var showWorkDirPicker by remember(conversation.id) { mutableStateOf(false) }

        if (showWorkDirPicker) {
            WorkDirPickerBottomSheet(
                conversationId = conversation.id,
                workspaceRootTreeUri = effectiveWorkspaceRootTreeUri,
                initialRelPath = currentBinding?.relPath?.trim().orEmpty(),
                onDismissRequest = { showWorkDirPicker = false },
                onConfirm = { relPath ->
                    haptics.perform(HapticPattern.Thud)
                    vm.updateSettings(
                        settings.copy(
                            conversationWorkDirs = settings.conversationWorkDirs + (
                                conversation.id.toString() to ConversationWorkDirBinding(
                                    mode = ConversationWorkDirMode.MANUAL,
                                    relPath = relPath,
                                )
                            )
                        )
                    )
                    showWorkDirPicker = false
                    managingWorkDirConversation = null
                    toaster.show(message = context.getString(R.string.workdir_manual_saved))
                },
            )
        }

        AlertDialog(
            onDismissRequest = { managingWorkDirConversation = null },
            title = { Text(stringResource(R.string.workdir_manage_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = conversation.title.ifBlank { stringResource(R.string.chat_page_new_message) },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Text(
                        text = if (workspaceReady) {
                            when (currentBinding?.mode) {
                                ConversationWorkDirMode.MANUAL -> {
                                    val relPath = currentBinding.relPath.trim()
                                    if (relPath.isBlank()) {
                                        stringResource(R.string.workdir_current_root)
                                    } else {
                                        stringResource(R.string.workdir_current_manual, relPath)
                                    }
                                }

                                else -> {
                                    if (hasConversationRootOverride) {
                                        stringResource(R.string.workdir_current_root)
                                    } else {
                                        stringResource(R.string.workdir_current_auto)
                                    }
                                }
                            }
                        } else {
                            stringResource(R.string.workspace_root_required_hint_v2)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
             },
             confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val canRestoreDefaultRoot = !settings.workspaceRootTreeUri.isNullOrBlank()
                    TextButton(
                        enabled = if (hasConversationRootOverride) canRestoreDefaultRoot else true,
                        onClick = {
                            val key = conversation.id.toString()
                            if (hasConversationRootOverride) {
                                haptics.perform(HapticPattern.Thud)
                                vm.updateSettings(
                                    settings.copy(
                                        conversationWorkspaceRoots = settings.conversationWorkspaceRoots - key,
                                        conversationWorkDirs = settings.conversationWorkDirs - key,
                                    )
                                )
                                toaster.show(message = context.getString(R.string.workspace_root_reset_to_default_success))
                            } else {
                                haptics.perform(HapticPattern.Pop)
                                vm.updateSettings(
                                    settings.copy(
                                        conversationWorkDirs = settings.conversationWorkDirs - key,
                                    )
                                )
                                toaster.show(message = context.getString(R.string.workdir_reset_success))
                            }
                            managingWorkDirConversation = null
                        }
                    ) {
                        Text(stringResource(R.string.workdir_reset_to_auto))
                    }
                    TextButton(
                        onClick = {
                            haptics.perform(HapticPattern.Pop)
                            showWorkDirPicker = true
                        }
                    ) {
                        Text(stringResource(R.string.workdir_choose_directory))
                    }
                }
             },
             dismissButton = {
                 TextButton(onClick = { managingWorkDirConversation = null }) {
                     Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    nicknameEditState.EditStateContent { nickname, onUpdate ->
        AlertDialog(
            onDismissRequest = {
                nicknameEditState.dismiss()
            },
            title = {
                Text(stringResource(R.string.chat_page_edit_nickname))
            },
            text = {
                OutlinedTextField(
                    value = nickname,
                    onValueChange = onUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.chat_page_nickname_placeholder)) }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        nicknameEditState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        nicknameEditState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }
}

@Composable
private fun DrawerAction(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    label: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.88f else 1f,
        animationSpec = spring(
            dampingRatio = 0.4f,
            stiffness = 400f
        ),
        label = "drawer_scale"
    )
    val alpha by animateFloatAsState(
        targetValue = if (isPressed) 0.7f else 1f,
        animationSpec = spring(
            dampingRatio = 0.6f,
            stiffness = 300f
        ),
        label = "drawer_alpha"
    )
    val haptics = rememberPremiumHaptics()
    Surface(
        onClick = {
            haptics.perform(HapticPattern.Tick)
            onClick()
        },
        modifier = modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
            this.alpha = alpha
        },
        interactionSource = interactionSource,
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = CircleShape,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Tooltip(
            tooltip = {
               label()
            }
        ) {
            Box(
                modifier = Modifier
                    .padding(10.dp)
                    .size(20.dp),
            ) {
                icon()
            }
        }
    }
}
