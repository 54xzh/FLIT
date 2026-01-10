package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.ui.unit.IntOffset
import me.rerere.rikkahub.ui.theme.LocalDarkMode

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Memory
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.components.ui.Tooltip
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.theme.extendColors
import me.rerere.rikkahub.utils.toLocalString
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId
import kotlin.uuid.Uuid

/**
 * Represents different types of items in the conversation list
 */
sealed class ConversationListItem {
    data class DateHeader(
        val date: LocalDate,
        val label: String
    ) : ConversationListItem()
    data object PinnedHeader : ConversationListItem()
    data class Item(
        val conversation: Conversation
    ) : ConversationListItem()
}

@Composable
fun ColumnScope.ConversationList(
    current: Conversation,
    conversations: LazyPagingItems<ConversationListItem>,
    conversationJobs: Collection<Uuid>,
    recentlyRestoredIds: Set<Uuid> = emptySet(),
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    drawerState: DrawerState? = null,
    modifier: Modifier = Modifier,
    onClick: (Conversation) -> Unit = {},
    onDelete: (Conversation) -> Unit = {},
    onRegenerateTitle: (Conversation) -> Unit = {},
    onConsolidate: (Conversation) -> Unit = {},
    onPin: (Conversation) -> Unit = {},
    onManageWorkDir: (Conversation) -> Unit = {},
    showUnconsolidatedDot: Boolean = false,
    showConsolidateOption: Boolean = false
) {
    val navController = LocalNavController.current

    // fix: compose很奇怪，会自动聚焦到第一个文本框
    // 在这里放一个空的Box，防止自动聚焦到第一个文本框弹出IME
    Box(modifier = Modifier.focusable())

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier
                .weight(1f),
            shape = RoundedCornerShape(50),
            trailingIcon = {
                AnimatedVisibility(searchQuery.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            onSearchQueryChange("")
                        }
                    ) {
                        Icon(Icons.Rounded.Close, null)
                    }
                }
            },
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
            placeholder = {
                Text(stringResource(id = R.string.chat_page_search_placeholder))
            }
        )
    }

    val density = LocalDensity.current
    var viewportHeight by remember { mutableIntStateOf(0) }

    val listState = rememberLazyListState()

    var autoCenterRequest by remember { mutableIntStateOf(0) }
    var autoCentering by remember { mutableStateOf(false) }
    var lastCenteredConversationId by remember { mutableStateOf<Uuid?>(null) }

    fun requestAutoCenter() {
        autoCentering = true
        autoCenterRequest += 1
    }

    suspend fun centerOnConversation(conversationId: Uuid): Boolean {
        val initialIndex = listState.firstVisibleItemIndex
        val initialOffset = listState.firstVisibleItemScrollOffset

        val fallbackItemHeightPx = with(density) { 52.dp.toPx().toInt() }
        val viewportStartTime = System.currentTimeMillis()
        while (viewportHeight <= 0 && System.currentTimeMillis() - viewportStartTime < 1500) {
            kotlinx.coroutines.delay(16)
        }
        if (viewportHeight <= 0) return false

        val centerOffset = -(viewportHeight / 2) + (fallbackItemHeightPx / 2)
        val startTime = System.currentTimeMillis()
        var centered = false

        try {
            while (System.currentTimeMillis() - startTime < 5000) {
                val snapshot = conversations.itemSnapshotList
                val indexInSnapshot = snapshot.items.indexOfFirst { item ->
                    item is ConversationListItem.Item && item.conversation.id == conversationId
                }
                if (indexInSnapshot >= 0) {
                    val targetIndex = snapshot.placeholdersBefore + indexInSnapshot
                    runCatching { listState.scrollToItem(targetIndex, centerOffset) }
                    centered = true
                    break
                }

                val append = conversations.loadState.append
                if (append is LoadState.NotLoading && append.endOfPaginationReached) {
                    break
                }
                if (append is LoadState.Loading) {
                    kotlinx.coroutines.delay(16)
                    continue
                }

                val itemCountBefore = conversations.itemCount
                if (itemCountBefore <= 0) {
                    kotlinx.coroutines.delay(16)
                    continue
                }

                runCatching { listState.scrollToItem(itemCountBefore - 1) }
                val itemCountChanged = kotlinx.coroutines.withTimeoutOrNull(1200) {
                    snapshotFlow { conversations.itemCount }.first { it != itemCountBefore }
                }
                if (itemCountChanged == null) {
                    kotlinx.coroutines.delay(16)
                }
            }
        } finally {
            if (!centered) {
                runCatching { listState.scrollToItem(initialIndex, initialOffset) }
            }
        }

        return centered
    }

    LaunchedEffect(current.id, searchQuery) {
        if (drawerState == null) return@LaunchedEffect
        if (searchQuery.isNotBlank()) return@LaunchedEffect
        lastCenteredConversationId = null
        requestAutoCenter()
    }

    LaunchedEffect(drawerState?.currentValue) {
        if (drawerState == null) return@LaunchedEffect
        if (drawerState.currentValue == DrawerValue.Closed) {
            lastCenteredConversationId = null
        }
    }

    LaunchedEffect(drawerState?.targetValue, searchQuery) {
        if (drawerState == null) return@LaunchedEffect
        if (searchQuery.isNotBlank()) return@LaunchedEffect
        if (drawerState.targetValue != DrawerValue.Open) return@LaunchedEffect
        if (autoCentering) return@LaunchedEffect
        if (lastCenteredConversationId == current.id) return@LaunchedEffect
        requestAutoCenter()
    }

    LaunchedEffect(autoCenterRequest) {
        if (drawerState == null) return@LaunchedEffect
        if (searchQuery.isNotBlank()) return@LaunchedEffect
        autoCentering = true
        try {
            val centered = centerOnConversation(current.id)
            if (centered) {
                lastCenteredConversationId = current.id
            }
        } finally {
            autoCentering = false
        }
    }

    Box(
        modifier = modifier.onGloballyPositioned { coordinates ->
            viewportHeight = coordinates.size.height
        }
    ) {
        val canScrollBackward by remember {
            derivedStateOf { listState.canScrollBackward }
        }
        val canScrollForward by remember {
            derivedStateOf { listState.canScrollForward }
        }

        val currentConversationIsInSnapshot = conversations.itemSnapshotList.items.any { item ->
            item is ConversationListItem.Item && item.conversation.id == current.id
        }
        val shouldMaskListWhileAutoCentering = drawerState?.targetValue == DrawerValue.Open &&
            searchQuery.isBlank() &&
            lastCenteredConversationId != current.id &&
            (
                autoCentering || (autoCenterRequest == 0 && !currentConversationIsInSnapshot)
            )
        val listAlpha by animateFloatAsState(
            targetValue = if (shouldMaskListWhileAutoCentering) 0f else 1f,
            animationSpec = spring(dampingRatio = 1f, stiffness = 600f),
            label = "conversation_list_alpha"
        )
        val blockerInteractionSource = remember { MutableInteractionSource() }
        var showAutoCenterSpinner by remember { mutableStateOf(false) }

        LaunchedEffect(autoCentering) {
            if (!autoCentering) {
                showAutoCenterSpinner = false
                return@LaunchedEffect
            }

            showAutoCenterSpinner = false
            kotlinx.coroutines.delay(120)
            if (autoCentering) {
                showAutoCenterSpinner = true
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = listAlpha },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (conversations.itemCount == 0) {
                item {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Text(
                            text = stringResource(id = R.string.chat_page_no_conversations),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }

            items(
                count = conversations.itemCount,
                key = conversations.itemKey { item ->
                    when (item) {
                        is ConversationListItem.DateHeader -> "date_${item.date}"
                        is ConversationListItem.PinnedHeader -> "pinned_header"
                        is ConversationListItem.Item -> item.conversation.id.toString()
                    }
                }
            ) { index ->
                when (val item = conversations[index]) {
                    is ConversationListItem.DateHeader -> {
                        DateHeaderItem(
                            label = item.label,
                            modifier = Modifier.animateItem(
                                fadeInSpec = androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 300f),
                                fadeOutSpec = androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 300f),
                                placementSpec = androidx.compose.animation.core.spring(
                                    stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
                                    visibilityThreshold = androidx.compose.ui.unit.IntOffset.VisibilityThreshold
                                )
                            )
                        )
                    }

                    is ConversationListItem.PinnedHeader -> {
                        PinnedHeader(
                            modifier = Modifier.animateItem(
                                fadeInSpec = androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 300f),
                                fadeOutSpec = androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 300f),
                                placementSpec = androidx.compose.animation.core.spring(
                                    stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
                                    visibilityThreshold = androidx.compose.ui.unit.IntOffset.VisibilityThreshold
                                )
                            )
                        )
                    }

                    is ConversationListItem.Item -> {
                        ConversationItem(
                            conversation = item.conversation,
                            selected = item.conversation.id == current.id,
                            loading = item.conversation.id in conversationJobs,
                            isRecentlyRestored = item.conversation.id in recentlyRestoredIds,
                            onClick = onClick,
                            onDelete = onDelete,
                            onRegenerateTitle = onRegenerateTitle,
                            onConsolidate = onConsolidate,
                            onPin = onPin,
                            onManageWorkDir = onManageWorkDir,
                            showUnconsolidatedDot = showUnconsolidatedDot,
                            showConsolidateOption = showConsolidateOption,
                            modifier = Modifier.animateItem(
                                fadeInSpec = androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 300f),
                                fadeOutSpec = androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 300f),
                                placementSpec = androidx.compose.animation.core.spring(
                                    stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
                                    visibilityThreshold = androidx.compose.ui.unit.IntOffset.VisibilityThreshold
                                )
                            )
                        )
                    }

                    null -> {
                        // Placeholder for loading state
                    }
                }
            }
        }

        // Top Fade - only show when can scroll backward
        if (canScrollBackward) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .size(32.dp)
                    .graphicsLayer { alpha = listAlpha }
                    .background(
                        brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                            colors = listOf(
                                if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh,
                                Color.Transparent
                            )
                        )
                    )
            )
        }

        // Bottom Fade - only show when can scroll forward
        if (canScrollForward) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .size(32.dp)
                    .graphicsLayer { alpha = listAlpha }
                    .background(
                        brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                        )
                    )
            )
        }

        if (shouldMaskListWhileAutoCentering) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = blockerInteractionSource,
                        indication = null,
                        onClick = {},
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (showAutoCenterSpinner) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun DateHeaderItem(
    label: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun PinnedHeader(
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Rounded.PushPin,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.pinned_chats),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun ConversationItem(
    conversation: Conversation,
    selected: Boolean,
    loading: Boolean,
    isRecentlyRestored: Boolean = false,
    modifier: Modifier = Modifier,
    onDelete: (Conversation) -> Unit = {},
    onRegenerateTitle: (Conversation) -> Unit = {},
    onConsolidate: (Conversation) -> Unit = {},
    onPin: (Conversation) -> Unit = {},
    onManageWorkDir: (Conversation) -> Unit = {},
    showUnconsolidatedDot: Boolean = false,
    showConsolidateOption: Boolean = false,
    onClick: (Conversation) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val haptics = rememberPremiumHaptics()

    // Fade-in animation for recently restored items
    var hasAnimated by remember { mutableStateOf(!isRecentlyRestored) }
    val restoredAlpha by animateFloatAsState(
        targetValue = if (hasAnimated) 1f else 0f,
        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "restored_alpha"
    )
    LaunchedEffect(isRecentlyRestored) {
        if (isRecentlyRestored && !hasAnimated) {
            kotlinx.coroutines.delay(50) // Small delay to ensure item is in layout
            hasAnimated = true
        }
    }
    
    // Physics-based press feedback
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = 400f),
        label = "conversation_scale"
    )
    val pressAlpha by animateFloatAsState(
        targetValue = if (isPressed) 0.7f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "conversation_alpha"
    )
    
    // Combine alphas: restored fade-in * press feedback
    val combinedAlpha = restoredAlpha * pressAlpha
    
    val backgroundColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        Color.Transparent
    }
    val titleColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    var showDropdownMenu by remember {
        mutableStateOf(false)
    }
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = combinedAlpha
            }
            .clip(RoundedCornerShape(50f))
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = {
                    haptics.perform(HapticPattern.Tick)
                    onClick(conversation)
                },
                onLongClick = {
                    haptics.perform(HapticPattern.Buildup)
                    showDropdownMenu = true
                }
            )
            .background(backgroundColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = conversation.title.ifBlank { stringResource(id = R.string.chat_page_new_message) },
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.weight(1f))
            
            // Unconsolidated Dot
            AnimatedVisibility(showUnconsolidatedDot && !conversation.isConsolidated) {
                Box(
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error)
                        .size(6.dp)
                )
            }

            // 置顶图标
            AnimatedVisibility(conversation.isPinned) {
                Icon(
                    imageVector = Icons.Rounded.PushPin,
                    contentDescription = stringResource(R.string.a11y_pinned),
                    modifier = Modifier.size(12.dp),
                    tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
                )
            }
            val loadingDesc = stringResource(R.string.a11y_loading)
            AnimatedVisibility(loading) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.extendColors.green6)
                        .size(4.dp)
                        .semantics {
                            contentDescription = loadingDesc
                        }
                )
            }
            DropdownMenu(
                expanded = showDropdownMenu,
                onDismissRequest = { showDropdownMenu = false },
                shape = RoundedCornerShape(20.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            if (conversation.isPinned) stringResource(R.string.unpin_chat) else stringResource(R.string.pin_chat)
                        )
                    },
                    onClick = {
                        haptics.perform(HapticPattern.Pop)
                        onPin(conversation)
                        showDropdownMenu = false
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.PushPin,
                            null
                        )
                    }
                )

                DropdownMenuItem(
                    text = {
                        Text(stringResource(id = R.string.chat_page_regenerate_title))
                    },
                    onClick = {
                        haptics.perform(HapticPattern.Tick)
                        onRegenerateTitle(conversation)
                        showDropdownMenu = false
                    },
                    leadingIcon = {
                        Icon(Icons.Rounded.Refresh, null)
                    }
                )

                DropdownMenuItem(
                    text = {
                        Text(stringResource(id = R.string.chat_page_manage_work_dir))
                    },
                    onClick = {
                        haptics.perform(HapticPattern.Pop)
                        onManageWorkDir(conversation)
                        showDropdownMenu = false
                    },
                    leadingIcon = {
                        Icon(Icons.Rounded.Folder, null)
                    }
                )

                if (showConsolidateOption && !conversation.isConsolidated) {
                    DropdownMenuItem(
                        text = {
                            Text(stringResource(id = R.string.chat_page_consolidate))
                        },
                        onClick = {
                            haptics.perform(HapticPattern.Pop)
                            onConsolidate(conversation)
                            showDropdownMenu = false
                        },
                        leadingIcon = {
                            Icon(Icons.Rounded.Memory, null)
                        }
                    )
                }

                DropdownMenuItem(
                    text = {
                        Text(stringResource(id = R.string.chat_page_delete))
                    },
                    onClick = {
                        haptics.perform(HapticPattern.Error)
                        onDelete(conversation)
                        showDropdownMenu = false
                    },
                    leadingIcon = {
                        Icon(Icons.Rounded.Delete, null)
                    }
                )
            }
        }
    }
}
