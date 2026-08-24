package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberPlatformOverscrollFactory
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardColors
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkRemove
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.AskUserState
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.highlight.HighlightText
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.ui.Favicon
import me.rerere.rikkahub.ui.components.ui.FaviconRow
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.ui.theme.AppShapes
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import me.rerere.rikkahub.data.ai.tools.SearchAgentProgress
import me.rerere.rikkahub.data.ai.tools.SearchAgentStep
import me.rerere.rikkahub.data.ai.tools.parseSearchAgentStepsFromMetadata
import me.rerere.rikkahub.service.ChatService
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
private fun stepLabelTask() = stringResource(R.string.search_agent_step_task)

@Composable
private fun stepLabelReasoning() = stringResource(R.string.search_agent_step_reasoning)

@Composable
private fun stepLabelFinal() = stringResource(R.string.search_agent_step_final)

// 新步骤进入动画：淡入 + 弹簧移位（上面卡片被推开时有重量感）。
private val StepFadeInSpec = spring<Float>(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessMedium,
)
private val StepPlacementSpec: SpringSpec<IntOffset> = spring(
    // animateItem placementSpec 需要 IntOffset 维度的位移弹簧。
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessMedium,
)

@Composable
internal fun toolApprovalDisplayName(toolName: String): String {
    return when (toolName) {
        "workspace_list" -> stringResource(R.string.tool_approval_workspace_list)
        "workspace_read_file" -> stringResource(R.string.tool_approval_workspace_read_file)
        "workspace_write_file" -> stringResource(R.string.tool_approval_workspace_write_file)
        "workspace_mkdir" -> stringResource(R.string.tool_approval_workspace_mkdir)
        "workspace_delete" -> stringResource(R.string.tool_approval_workspace_delete)
        "workspace_rename" -> stringResource(R.string.tool_approval_workspace_rename)
        "sandbox_read_file" -> stringResource(R.string.tool_approval_sandbox_read_file)
        "sandbox_write_file" -> stringResource(R.string.tool_approval_sandbox_write_file)
        "sandbox_edit_file" -> stringResource(R.string.tool_approval_sandbox_edit_file)
        "sandbox_shell" -> stringResource(R.string.tool_approval_sandbox_shell)
        "eval_python" -> stringResource(R.string.chat_message_tool_run_python_generic)
        "web.run" -> stringResource(R.string.codex_web_search_title)
        else -> toolName
    }
}

@Composable
fun ToolCallItem(
    toolName: String,
    arguments: JsonElement,
    content: JsonElement?,
    loading: Boolean = false,
) {
    var showResult by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.animateContentSize(),
        onClick = {
            showResult = true
        },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .padding(vertical = 4.dp, horizontal = 12.dp)
                .height(IntrinsicSize.Min)
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 4.dp,
                )
            } else {
                Icon(
                    imageVector = when (toolName) {
                        "create_memory", "edit_memory" -> Icons.Rounded.Bookmark
                        "delete_memory" -> Icons.Rounded.BookmarkRemove
                        "search_agent" -> Icons.Rounded.Public
                        "search_web" -> Icons.Rounded.Public
                        "web.run" -> Icons.Rounded.Public
                        "scrape_web" -> Icons.Rounded.Public
                        "read_skill_file" -> Icons.Rounded.Extension
                        "run_skill_script" -> Icons.Rounded.Terminal
                        else -> Icons.Rounded.Build
                    },
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.7f)
                )
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val skillNameOrId = if (toolName == "read_skill_file") {
                    arguments.jsonObject["skill_name"]?.jsonPrimitiveOrNull?.contentOrNull
                        ?: arguments.jsonObject["skill_id"]?.jsonPrimitiveOrNull?.contentOrNull
                } else null
                val scriptName = if (toolName == "run_skill_script") {
                    arguments.jsonObject["path"]?.jsonPrimitiveOrNull?.contentOrNull
                        ?.replace('\\', '/')
                        ?.substringAfterLast('/')
                        ?.takeIf { it.isNotBlank() }
                } else null
                Text(
                    text = when (toolName) {
                        "create_memory" -> stringResource(R.string.chat_message_tool_create_memory)
                        "edit_memory" -> stringResource(R.string.chat_message_tool_edit_memory)
                        "delete_memory" -> stringResource(R.string.chat_message_tool_delete_memory)
                        "search_agent" -> stringResource(R.string.chat_message_tool_search_agent)
                        "web.run" -> stringResource(R.string.codex_web_search_title)
                        "search_web" -> stringResource(
                            R.string.chat_message_tool_search_web,
                            arguments.jsonObject["query"]?.jsonPrimitiveOrNull?.contentOrNull
                                ?: ""
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
                        else -> stringResource(
                            R.string.chat_message_tool_call_generic,
                            toolName
                        )
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.shimmer(isLoading = loading),
                )
                if (toolName == "create_memory" || toolName == "edit_memory") {
                    val content = content?.jsonObject["content"]?.jsonPrimitiveOrNull?.contentOrNull
                    if (content != null) {
                        Text(
                            text = content,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.shimmer(isLoading = loading),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (toolName == "search_agent") {
                    val summary = content?.jsonObject?.get("summary")?.jsonPrimitiveOrNull?.contentOrNull
                    if (!summary.isNullOrBlank()) {
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.shimmer(isLoading = loading),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (toolName == "search_web") {
                    val answer = content?.jsonObject["answer"]?.jsonPrimitiveOrNull?.contentOrNull
                    if (answer != null) {
                        Text(
                            text = answer,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.shimmer(isLoading = loading),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    val items = content?.jsonObject["items"]?.jsonArray ?: emptyList()
                    if (items.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            FaviconRow(
                                urls = items.mapNotNull {
                                    it.jsonObject["url"]?.jsonPrimitiveOrNull?.contentOrNull
                                },
                                size = 18.dp,
                            )
                            Text(
                                text = stringResource(R.string.chat_message_tool_search_results_count, items.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                            )
                        }
                    }
                }
                if(toolName == "scrape_web") {
                    val url = arguments.jsonObject["url"]?.jsonPrimitiveOrNull?.contentOrNull
                        ?: arguments.jsonObject["urls"]?.jsonArray
                            ?.firstOrNull()
                            ?.jsonPrimitiveOrNull
                            ?.contentOrNull
                        ?: ""
                    Text(
                        text = url,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                }
            }
        }
    }
    if (showResult && content != null) {
        ToolCallPreviewSheet(
            toolName = toolName,
            arguments = arguments,
            content = content,
            onDismissRequest = {
                showResult = false
            }
        )
    }
}

@Composable
fun ToolApprovalItem(
    conversationId: Uuid?,
    toolCallId: String,
    toolName: String,
    arguments: JsonElement,
    state: ToolApprovalState,
    loading: Boolean = false,
) {
    val chatService = koinInject<ChatService>()
    val settings = me.rerere.rikkahub.ui.context.LocalSettings.current
    val haptics = rememberPremiumHaptics(enabled = settings.displaySetting.enableUIHaptics)
    val approvalLabel = toolApprovalDisplayName(toolName)
    val argumentsJson = remember(arguments) {
        JsonInstantPretty.encodeToString(arguments)
    }
    var locked by remember(toolName, state) { mutableStateOf(false) }
    var showArgumentsSheet by remember(toolCallId) { mutableStateOf(false) }
    val canRespond = conversationId != null && toolCallId.isNotBlank()

    Card(
        modifier = Modifier.animateContentSize(),
        shape = AppShapes.CardLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Extension,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.mcp_tool_approval_title),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.mcp_tool_approval_subtitle, approvalLabel),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(
                    onClick = {
                        haptics.perform(HapticPattern.Pop)
                        showArgumentsSheet = true
                    }
                ) {
                    Text(text = stringResource(R.string.tool_approval_view_params))
                }
            }

            when (state) {
                ToolApprovalState.Pending -> {
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
                                    toolCallId = toolCallId,
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
                                    toolCallId = toolCallId,
                                    approved = false,
                                )
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                ToolApprovalState.Approved -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = stringResource(R.string.mcp_tool_approval_approved_calling, approvalLabel),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                ToolApprovalState.Rejected -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = stringResource(R.string.mcp_tool_approval_rejected_result),
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                }
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
internal fun ToolApprovalButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.85f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "tool_approval_button_scale",
    )

    Surface(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        color = containerColor,
        contentColor = contentColor,
        shape = AppShapes.ButtonPill,
        modifier = modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp, horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun ToolApprovalArgumentsSheet(
    approvalLabel: String,
    argumentsJson: String,
    onDismissRequest: () -> Unit = {},
) {
    val clipboardManager = LocalClipboardManager.current

    ModalBottomSheet(
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        onDismissRequest = onDismissRequest,
        content = {
            Column(
                modifier = Modifier
                    .fillMaxHeight(0.8f)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = approvalLabel,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.request_log_section_params),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(argumentsJson))
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = stringResource(R.string.copy),
                        )
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    HighlightText(
                        code = argumentsJson,
                        language = "json",
                        fontSize = 11.sp,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
    )
}

@Composable
internal fun ToolCallPreviewSheet(
    toolName: String,
    arguments: JsonElement,
    content: JsonElement,
    metadata: JsonObject? = null,
    toolCallId: String = "",
    hasResult: Boolean = true,
    searchAgentSelectedTabStates: SnapshotStateMap<String, Int>? = null,
    onDismissRequest: () -> Unit = {},
) {
    val navController = LocalNavController.current
    val memoryRepo: MemoryRepository = koinInject()
    val scope = rememberCoroutineScope()

    // Check if this is a memory creation/update operation
    val isMemoryOperation = toolName in listOf("create_memory", "edit_memory")
    val memoryId = (content as? JsonObject)?.get("id")?.jsonPrimitiveOrNull?.intOrNull

    ModalBottomSheet(
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        onDismissRequest = {
            onDismissRequest()
        },
        content = {
            val sheetHeight = if (toolName == "search_agent") 0.92f else 0.8f
            Column(
                modifier = Modifier
                    .fillMaxHeight(sheetHeight)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (toolName) {
                    "search_agent" -> SearchAgentPreviewContent(
                        toolCallId = toolCallId,
                        hasResult = hasResult,
                        content = content,
                        metadata = metadata,
                        selectedTabStates = searchAgentSelectedTabStates,
                    )

                    "web.run" -> CodexWebSearchPreviewContent(
                        arguments = arguments,
                        content = content,
                        metadata = metadata,
                        hasResult = hasResult,
                        onOpenUrl = { url -> navController.navigate(Screen.WebView(url = url)) },
                    )

                    "search_web" -> {
                        Text(
                            stringResource(
                                R.string.chat_message_tool_search_prefix,
                                arguments.jsonObject["query"]?.jsonPrimitiveOrNull?.contentOrNull ?: ""
                            )
                        )
                        val items = content.jsonObject["items"]?.jsonArray ?: emptyList()
                        val answer = content.jsonObject["answer"]?.jsonPrimitive?.contentOrNull
                        if (items.isNotEmpty()) {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (answer != null) {
                                    item {
                                        Card(
                                            colors = CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.primaryContainer
                                            )
                                        ) {
                                            MarkdownBlock(
                                                content = answer,
                                                modifier = Modifier
                                                    .padding(16.dp)
                                                    .fillMaxWidth(),
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                }

                                items(items) {
                                    val url =
                                        it.jsonObject["url"]?.jsonPrimitive?.content ?: return@items
                                    val title =
                                        it.jsonObject["title"]?.jsonPrimitive?.content
                                            ?: return@items
                                    val text =
                                        it.jsonObject["text"]?.jsonPrimitive?.content
                                            ?: return@items
                                    Card(
                                        onClick = {
                                            navController.navigate(Screen.WebView(url = url))
                                        },
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                        )
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 8.dp, horizontal = 16.dp),
                                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Favicon(
                                                url = url,
                                                modifier = Modifier.size(24.dp)
                                            )
                                            Column {
                                                Text(
                                                    text = title,
                                                    maxLines = 1
                                                )
                                                Text(
                                                    text = text,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                                Text(
                                                    text = url,
                                                    maxLines = 1,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurface.copy(
                                                        alpha = 0.6f
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                            ) {
                                HighlightText(
                                    code = JsonInstantPretty.encodeToString(content),
                                    language = "json",
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    "scrape_web" -> {
                        val urls = content.jsonObject["urls"]?.jsonArray ?: emptyList()
                        Text(
                            text = stringResource(
                                R.string.chat_message_tool_scrape_prefix,
                                urls.joinToString(", ") { it.jsonObject["url"]?.jsonPrimitiveOrNull?.contentOrNull ?: "" }),
                        )
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(urls) { url ->
                                val urlObject = url.jsonObject
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = urlObject["url"]?.jsonPrimitive?.content ?: "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Card {
                                        MarkdownBlock(
                                            content = urlObject["content"]?.jsonPrimitive?.content ?: "",
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(
                                                    8.dp
                                                )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    else -> Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.chat_message_tool_call_title),
                                style = MaterialTheme.typography.headlineSmall,
                                textAlign = TextAlign.Center
                            )

                            // 如果是memory操作，允许用户快速删除
                            if (isMemoryOperation && memoryId != null) {
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            try {
                                                memoryRepo.deleteMemory(memoryId)
                                                onDismissRequest()
                                            } catch (e: Exception) {
                                                // Handle error if needed
                                            }
                                        }
                                    }
                                ) {
                                    Icon(
                                        Icons.Rounded.Delete,
                                        contentDescription = stringResource(R.string.a11y_delete_memory)
                                    )
                                }
                            }
                        }
                        val clipboardManager = LocalClipboardManager.current

                        val toolCallLabel = when (toolName) {
                            "read_skill_file" -> {
                                val name =
                                    arguments.jsonObject["skill_name"]?.jsonPrimitiveOrNull?.contentOrNull
                                        ?: arguments.jsonObject["skill_id"]?.jsonPrimitiveOrNull?.contentOrNull
                                if (name.isNullOrBlank()) {
                                    stringResource(R.string.chat_message_tool_call_label, toolName)
                                } else {
                                    stringResource(R.string.chat_message_tool_call_skill, name)
                                }
                            }

                            "run_skill_script" -> {
                                val name = arguments.jsonObject["path"]?.jsonPrimitiveOrNull?.contentOrNull
                                    ?.replace('\\', '/')
                                    ?.substringAfterLast('/')
                                    .orEmpty()
                                if (name.isBlank()) {
                                    stringResource(R.string.chat_message_tool_run_script_generic)
                                } else {
                                    stringResource(R.string.chat_message_tool_run_script, name)
                                }
                            }

                            else -> stringResource(R.string.chat_message_tool_call_label, toolName)
                        }

                        val argumentsJson = remember(arguments) {
                            JsonInstantPretty.encodeToString(arguments)
                        }
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = toolCallLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                IconButton(
                                    modifier = Modifier.size(24.dp),
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString(argumentsJson))
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.ContentCopy,
                                        contentDescription = stringResource(R.string.copy),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                HighlightText(
                                    code = argumentsJson,
                                    language = "json",
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val contentJson = remember(content) {
                                JsonInstantPretty.encodeToString(content)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.chat_message_tool_call_result),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                IconButton(
                                    modifier = Modifier.size(24.dp),
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString(contentJson))
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.ContentCopy,
                                        contentDescription = stringResource(R.string.copy),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                HighlightText(
                                    code = contentJson,
                                    language = "json",
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun CodexWebSearchPreviewContent(
    arguments: JsonElement,
    content: JsonElement,
    metadata: JsonObject?,
    hasResult: Boolean,
    onOpenUrl: (String) -> Unit,
) {
    val result = content as? JsonObject
    val actions = (metadata?.get("actions") as? JsonArray)
        ?.mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull }
        ?.takeIf { it.isNotEmpty() }
        ?: (arguments as? JsonObject)?.keys?.sorted().orEmpty()
    val error = (metadata?.get("error") as? JsonObject ?: result?.get("error") as? JsonObject)
    val errorMessage = error?.get("message")?.jsonPrimitiveOrNull?.contentOrNull
    val sources = (metadata?.get("sources") as? JsonArray ?: result?.get("sources") as? JsonArray)
        .orEmpty()
        .mapNotNull { element ->
            val source = element as? JsonObject ?: return@mapNotNull null
            val url = source["url"]?.jsonPrimitiveOrNull?.contentOrNull?.trim().orEmpty()
            if (url.isBlank()) return@mapNotNull null
            val title = source["title"]?.jsonPrimitiveOrNull?.contentOrNull?.trim()
                ?.takeIf { it.isNotBlank() } ?: url
            title to url
        }
        .distinctBy { it.second }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.codex_web_search_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        if (!hasResult) {
            Text(
                text = stringResource(R.string.codex_web_search_running),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!errorMessage.isNullOrBlank()) {
            Text(
                text = stringResource(R.string.codex_web_search_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (actions.isNotEmpty()) {
            Text(
                text = stringResource(R.string.codex_web_search_actions),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = actions.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.codex_web_search_sources),
            style = MaterialTheme.typography.labelLarge,
        )
        if (sources.isEmpty()) {
            Text(
                text = stringResource(R.string.codex_web_search_no_sources),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(sources, key = { it.second }) { (title, url) ->
                    Card(
                        onClick = { onOpenUrl(url) },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Favicon(url = url, modifier = Modifier.size(20.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    text = url,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AskUserItem(
    conversationId: Uuid?,
    askUser: me.rerere.ai.ui.UIMessagePart.AskUser,
) {
    val chatService = koinInject<ChatService>()
    val settings = me.rerere.rikkahub.ui.context.LocalSettings.current
    val haptics = rememberPremiumHaptics(enabled = settings.displaySetting.enableUIHaptics)
    var showSheet by remember(askUser.toolCallId) { mutableStateOf(false) }
    val canRespond = conversationId != null && askUser.toolCallId.isNotBlank() && askUser.state == AskUserState.Pending
    val multiQuestions = askUser.questions
    val isMultiQuestion = multiQuestions != null && multiQuestions.size > 1

    Card(
        modifier = Modifier.animateContentSize(),
        shape = AppShapes.CardLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.HelpOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = if (isMultiQuestion) {
                            stringResource(R.string.ask_user_multi_title, multiQuestions!!.size)
                        } else {
                            stringResource(R.string.ask_user_title)
                        },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (isMultiQuestion) {
                            multiQuestions!!.first().question
                        } else {
                            askUser.question
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            when (askUser.state) {
                AskUserState.Pending -> {
                    TextButton(
                        onClick = {
                            haptics.perform(HapticPattern.Pop)
                            showSheet = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = AppShapes.ButtonPill,
                    ) {
                        Text(text = stringResource(R.string.ask_user_respond))
                    }
                }

                AskUserState.Answered -> {
                    val multiAnswers = askUser.answers
                    if (isMultiQuestion && multiQuestions != null && multiAnswers != null) {
                        multiQuestions.zip(multiAnswers).forEach { (q, a) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = "${q.question}: $a",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = askUser.answer ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                AskUserState.Dismissed -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = stringResource(R.string.ask_user_dismissed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

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

@Composable
internal fun AskUserBottomSheet(
    question: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val settings = me.rerere.rikkahub.ui.context.LocalSettings.current
    val haptics = rememberPremiumHaptics(enabled = settings.displaySetting.enableUIHaptics)
    var customInput by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun selectWithAnimation(answer: String) {
        coroutineScope.launch {
            sheetState.hide()
            onSelect(answer)
        }
    }

    // In a ModalBottomSheet the sheet surface is surfaceContainerLow in dark mode,
    // so items must sit at a higher elevation to be visible.
    val itemColor = MaterialTheme.colorScheme.surfaceContainerHigh

    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = onDismissRequest,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Question (plain text, no card wrapper, larger font)
            Text(
                text = question,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
            )

            // Options + input as a settings-style grouped list
            Column(
                modifier = Modifier.clip(RoundedCornerShape(24.dp)),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                options.forEach { option ->
                    val optionInteraction = remember { MutableInteractionSource() }
                    val optionPressed by optionInteraction.collectIsPressedAsState()
                    val optionScale by animateFloatAsState(
                        targetValue = if (optionPressed) 0.98f else 1f,
                        animationSpec = spring(dampingRatio = 0.5f, stiffness = 400f),
                        label = "ask_user_option_scale",
                    )
                    Surface(
                        onClick = {
                            haptics.perform(HapticPattern.Pop)
                            selectWithAnimation(option)
                        },
                        interactionSource = optionInteraction,
                        color = itemColor,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer { scaleX = optionScale; scaleY = optionScale },
                    ) {
                        Text(
                            text = option,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        )
                    }
                }

                // Custom input row (matches SettingGroupItem visual style)
                val submitInteractionSource = remember { MutableInteractionSource() }
                val submitPressed by submitInteractionSource.collectIsPressedAsState()
                val submitScale by animateFloatAsState(
                    targetValue = if (submitPressed) 0.85f else 1f,
                    animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
                    label = "ask_user_submit_scale",
                )
                val canSubmit = customInput.isNotBlank()
                Surface(
                    color = itemColor,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        BasicTextField(
                            value = customInput,
                            onValueChange = { customInput = it },
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 10.dp),
                            textStyle = MaterialTheme.typography.titleMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            singleLine = true,
                            decorationBox = { innerTextField ->
                                if (customInput.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.ask_user_type_hint),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                innerTextField()
                            },
                        )
                        Surface(
                            onClick = {
                                if (canSubmit) {
                                    haptics.perform(HapticPattern.Pop)
                                    selectWithAnimation(customInput.trim())
                                }
                            },
                            enabled = canSubmit,
                            interactionSource = submitInteractionSource,
                            color = if (canSubmit) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                            contentColor = if (canSubmit) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            shape = CircleShape,
                            modifier = Modifier
                                .size(40.dp)
                                .graphicsLayer {
                                    scaleX = submitScale
                                    scaleY = submitScale
                                },
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Send,
                                contentDescription = stringResource(R.string.ask_user_submit),
                                modifier = Modifier
                                    .padding(9.dp)
                                    .size(22.dp),
                        )
                    }
                }
            }
        }
    }
    }
}

/**
 * search_agent 详情卡片：双 Tab —— 结果 / 步骤。
 *
 * - 左右滑动切换标签页（HorizontalPager + 自定义药丸标签）。
 * - 默认进入哪个 Tab 由 [hasResult]（点开时是否已拿到 ToolResult）决定：
 *   有结果 -> 结果 Tab；执行中 -> 步骤 Tab。
 * - 「步伐更新时跟随」：执行中停在步骤页并自动滚到最新步骤；
 *   当结果产出（finished 或 hasResult 翻 true）时，一次性自动横滑到结果页。
 *   用户手动滑走后不再自动切回。
 * - 选中页持久化到 [selectedTabStates]（key = "search_agent:$toolCallId"）。
 * - 执行中读 store（带 Running 态）；完成 / 历史 / store 缺失读 metadata（全 Done）。
 */
@Composable
private fun SearchAgentPreviewContent(
    toolCallId: String,
    hasResult: Boolean,
    content: JsonElement,
    metadata: JsonObject?,
    selectedTabStates: SnapshotStateMap<String, Int>? = null,
) {
    val chatService = koinInject<ChatService>()
    val progressStore = chatService.searchAgentProgressStore
    val liveProgress by if (toolCallId.isNotBlank()) {
        progressStore.stateOf(toolCallId).collectAsState()
    } else {
        remember { mutableStateOf(null as SearchAgentProgress?) }
    }

    val tabs = listOf(
        stringResource(R.string.search_agent_tab_result),
        stringResource(R.string.search_agent_tab_steps),
    )
    val scope = rememberCoroutineScope()
    val selectedTabKey = remember(toolCallId) { "search_agent:$toolCallId" }
    var localSelectedTab by remember(toolCallId) {
        mutableStateOf(if (hasResult) 0 else 1)
    }

    // 持久化初始页：有外部 map 取之，否则退回 hasResult 派生。
    val initialPage = if (selectedTabStates != null && toolCallId.isNotBlank()) {
        selectedTabStates[selectedTabKey] ?: (if (hasResult) 0 else 1)
    } else {
        localSelectedTab
    }
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { tabs.size })

    fun persist(index: Int) {
        if (selectedTabStates != null && toolCallId.isNotBlank()) {
            selectedTabStates[selectedTabKey] = index
        } else {
            localSelectedTab = index
        }
    }

    // 双向同步：仅 settle 后写回，避免拖拽中途帧抖动。
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            persist(page)
        }
    }

    // 一次性自动横滑到结果页：执行完成 / 结果到达时触发，仅一次。
    LaunchedEffect(toolCallId) {
        var autoSwiped = false
        snapshotFlow { Triple(liveProgress?.finished == true, hasResult, liveProgress?.steps?.lastIndex ?: -1) }
            .collect { (finished, hasResultNow, _) ->
                if (!autoSwiped && pagerState.settledPage == 1 && (finished || hasResultNow)) {
                    autoSwiped = true
                    scope.launch { pagerState.animateScrollToPage(0) }
                    persist(0)
                }
            }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        PrimaryTabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor = Color.Transparent,
        ) {
            tabs.forEachIndexed { index, label ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(index) }
                    },
                    text = { Text(label) },
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // 关闭 pager 横滑到边的回弹动画，避免多余手势反馈。
        CompositionLocalProvider(LocalOverscrollFactory provides null) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                beyondViewportPageCount = 1,
            ) { page ->
                // pager 外层关掉到边回弹；页面内恢复平台过滚动工厂，保留 LazyColumn 垂直触顶/触底气。
                CompositionLocalProvider(
                    LocalOverscrollFactory provides rememberPlatformOverscrollFactory()
                ) {
                    when (page) {
                        0 -> SearchAgentResultTab(content = content)
                        else -> SearchAgentStepsTab(
                            liveProgress = liveProgress,
                            metadata = metadata,
                            pagerPageVisible = pagerState.settledPage == page,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchAgentResultTab(content: JsonElement) {
    val summary = (content as? JsonObject)?.get("summary")?.jsonPrimitiveOrNull?.contentOrNull.orEmpty()
    val sources = (content as? JsonObject)?.get("sources")?.jsonArray ?: emptyList()
    val notes = (content as? JsonObject)?.get("notes")?.jsonArray ?: emptyList()
    val navController = LocalNavController.current

    if (summary.isBlank() && sources.isEmpty() && notes.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.search_agent_no_result),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        if (summary.isNotBlank()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    MarkdownBlock(
                        content = summary,
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        items(sources) {
            val obj = it as? JsonObject ?: return@items
            val url = obj["url"]?.jsonPrimitiveOrNull?.contentOrNull ?: return@items
            val title = obj["title"]?.jsonPrimitiveOrNull?.contentOrNull ?: url
            val snippet = obj["snippet"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty()
            Card(
                onClick = { navController.navigate(Screen.WebView(url = url)) },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp, horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Favicon(url = url, modifier = Modifier.size(24.dp))
                    Column {
                        Text(text = title, maxLines = 1)
                        if (snippet.isNotBlank()) {
                            Text(
                                text = snippet,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(
                            text = url,
                            maxLines = 1,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                }
            }
        }
        if (notes.isNotEmpty()) {
            item {
                Card {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        notes.forEach { note ->
                            Text(
                                text = note.jsonPrimitiveOrNull?.contentOrNull.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchAgentStepsTab(
    liveProgress: SearchAgentProgress?,
    metadata: JsonObject?,
    pagerPageVisible: Boolean = true,
) {
    // 执行中读 store；完成 / 历史回落 metadata。store 缺失且无 task 时显示空态。
    val fromStore = liveProgress
    val steps: List<SearchAgentStep> = if (fromStore != null && fromStore.steps.isNotEmpty()) {
        fromStore.steps
    } else {
        parseSearchAgentStepsFromMetadata(metadata)
    }
    val hasLiveTask = fromStore?.task != null
    val showEmpty = steps.isEmpty() && !hasLiveTask

    if (showEmpty) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.search_agent_steps_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val expandedReasoning = remember { mutableStateMapOf<Int, Boolean>() }
    val lazyListState = rememberLazyListState()

    // 贴底跟随：参考 ChatList 模式——只在执行中跟随，用户上滑打断、回到底部恢复。
    // 关键：跟随用 requestScrollToItem 而非 animateScrollToItem，它把滚动请求排到
    // 下一帧测量之后才计算目标，从而不会与 StepCard 的 animateContentSize（卡片长高过渡）
    // 在同一帧内争夺布局，避免"卡片还没长完就滚到位、长完又被挤出屏幕"的跟随错位。
    // 触发源是可见项变化（卡片长高/新增步骤会改变 visibleItemsInfo）而不是每帧贴底判定，
    // 避免被弹簧过冲反复触发，连续多次请求会被 request 自动合并到最后一次。
    val liveRunning = liveProgress?.let { !it.finished } ?: hasLiveTask

    val isPinnedToBottom by remember {
        derivedStateOf {
            val layout = lazyListState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            lastVisible.index == layout.totalItemsCount - 1 &&
                lastVisible.offset + lastVisible.size >= layout.viewportEndOffset - 32
        }
    }
    var userDetached by remember { mutableStateOf(false) }

    // 用户上滑切断 / 回到贴底恢复跟随
    LaunchedEffect(lazyListState, pagerPageVisible, liveRunning) {
        if (!pagerPageVisible || !liveRunning) return@LaunchedEffect

        var lastIndex = lazyListState.firstVisibleItemIndex
        var lastOffset = lazyListState.firstVisibleItemScrollOffset

        snapshotFlow {
            Triple(
                isPinnedToBottom,
                lazyListState.isScrollInProgress,
                lazyListState.firstVisibleItemIndex,
            )
        }.collect { (atBottom, scrolling, index) ->
            val offset = lazyListState.firstVisibleItemScrollOffset
            val movingUp = index < lastIndex || (index == lastIndex && offset < lastOffset)

            // 用户主动向上滑动 → 打断跟随
            if (scrolling && movingUp) userDetached = true
            // 回到贴底且不在滚动 → 恢复跟随
            if (atBottom && !scrolling) userDetached = false

            lastIndex = index
            lastOffset = offset
        }
    }

    // 跟随：每次可见项变化（含卡片长高导致重排）排一次到末尾的滚动请求。
    // requestScrollToItem 在下一帧测量后再定滚动位置，连续请求合并，不与高度动画打架。
    LaunchedEffect(lazyListState, pagerPageVisible, liveRunning) {
        if (!pagerPageVisible || !liveRunning) return@LaunchedEffect

        snapshotFlow { lazyListState.layoutInfo.visibleItemsInfo }.collect { visible ->
            if (userDetached || lazyListState.isScrollInProgress || visible.isEmpty()) return@collect
            val total = lazyListState.layoutInfo.totalItemsCount
            if (total > 1) lazyListState.requestScrollToItem(total - 1)
        }
    }

    LazyColumn(
        state = lazyListState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        itemsIndexed(steps, key = { index, _ -> "step:$index" }) { index, step ->
            val itemModifier = Modifier.animateItem(
                fadeInSpec = StepFadeInSpec,
                fadeOutSpec = StepFadeInSpec,
                placementSpec = StepPlacementSpec,
            )
            when (step) {
                is SearchAgentStep.TaskStep -> StepCard(
                    title = stepLabelTask(),
                    detail = step.text,
                    variant = StepCardVariant.Task,
                    modifier = itemModifier,
                )
                    is SearchAgentStep.ReasoningStep -> {
                        val expanded = expandedReasoning[index] ?: false
                        StepCard(
                            title = stepLabelReasoning(),
                            detail = if (expanded) step.text else "",
                            expandable = true,
                            expanded = expanded,
                            onToggle = { expandedReasoning[index] = !expanded },
                            collapsedPreview = step.text.take(80),
                            variant = StepCardVariant.Reasoning,
                            modifier = itemModifier,
                        )
                    }
                    is SearchAgentStep.ToolCallStep -> StepCard(
                        title = step.title,
                        detail = step.detail,
                        urls = step.urls,
                        running = step.status == SearchAgentStep.ToolCallStep.Status.Running,
                        variant = StepCardVariant.ToolCall,
                        modifier = itemModifier,
                    )
                    is SearchAgentStep.ErrorStep -> StepCard(
                        title = "${step.title}",
                        detail = step.detail,
                        variant = StepCardVariant.Error,
                        modifier = itemModifier,
                    )
                    is SearchAgentStep.FinalStep -> StepCard(
                    title = stepLabelFinal(),
                    detail = step.detail,
                    variant = StepCardVariant.Final,
                    modifier = itemModifier,
                )
            }
        }
        // store 存在但尚未产出步骤（execute 刚开始）时显示 loading
        if (steps.isEmpty() && hasLiveTask) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = stringResource(R.string.search_agent_steps_running),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // 尾部 spacer：让"滚到最后"等于"最后一步贴底"，步骤页固定有个底部 item。
        if (steps.isNotEmpty()) {
            item(key = "bottom") {
                Spacer(Modifier.fillMaxWidth().height(1.dp))
            }
        }
    }
}

private enum class StepCardVariant { Task, Reasoning, ToolCall, Final, Error }

@Composable
private fun StepCard(
    title: String,
    detail: String,
    urls: List<String> = emptyList(),
    running: Boolean = false,
    expandable: Boolean = false,
    expanded: Boolean = false,
    onToggle: () -> Unit = {},
    collapsedPreview: String = "",
    variant: StepCardVariant = StepCardVariant.ToolCall,
    modifier: Modifier = Modifier,
) {
    // running 始终优先（活跃工作态最高语义权重），否则按 variant 取主题色容器。
    val baseColors: CardColors = when (variant) {
        StepCardVariant.Task -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        StepCardVariant.Reasoning -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        StepCardVariant.Final -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        StepCardVariant.Error -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
        StepCardVariant.ToolCall -> CardDefaults.cardColors()
    }
    val cardColors = if (running) {
        // 二次容器保留 running 视觉的同时让色彩不与 variant 主题色冲突：用 secondary.
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    } else baseColors

    Card(
        modifier = modifier,
        shape = AppShapes.CardMedium,
        colors = cardColors,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(animationSpec = spring(dampingRatio = 0.9f, stiffness = 800f))
                .then(if (expandable) Modifier.clickable { onToggle() } else Modifier)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                )
                if (expandable) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = if (expanded) "▾" else "▸",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (expandable && collapsedPreview.isNotBlank()) {
                Text(
                    text = collapsedPreview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // URL 胶囊换行排列，贴合内容，避免横向滚动抢手势 / 卡片高度虚高。
            if (urls.isNotEmpty()) {
                val navController = LocalNavController.current
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    urls.take(8).forEach { url ->
                        StepUrlChip(
                            url = url,
                            onClick = { navController.navigate(Screen.WebView(url = url)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StepUrlChip(url: String, onClick: () -> Unit) {
    val host = remember(url) {
        runCatching { android.net.Uri.parse(url).host }.getOrNull() ?: url
    }
    // onClick 版 Surface 会套 minimumInteractiveComponentSize，把节点强制撑到 48dp 触摸目标，
    // 胶囊视觉高度只有 ~18dp 却居中在 48dp 节点里，FlowRow 行高虚高 -> 上下出现大片留白。
    // 关掉最小交互尺寸，胶囊贴合内容。
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Surface(
            onClick = onClick,
            shape = AppShapes.ButtonPill,
            color = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Favicon(url = url, modifier = Modifier.size(12.dp))
                Text(
                    text = host,
                    style = MaterialTheme.typography.labelSmall.copy(lineHeight = 12.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
