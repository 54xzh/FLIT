package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
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
import me.rerere.rikkahub.service.ChatService
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
internal fun toolApprovalDisplayName(toolName: String): String {
    return when (toolName) {
        "workspace_list" -> stringResource(R.string.tool_approval_workspace_list)
        "workspace_read_file" -> stringResource(R.string.tool_approval_workspace_read_file)
        "workspace_write_file" -> stringResource(R.string.tool_approval_workspace_write_file)
        "workspace_mkdir" -> stringResource(R.string.tool_approval_workspace_mkdir)
        "workspace_delete" -> stringResource(R.string.tool_approval_workspace_delete)
        "workspace_rename" -> stringResource(R.string.tool_approval_workspace_rename)
        "eval_python" -> stringResource(R.string.chat_message_tool_run_python_generic)
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
                        "search_web" -> Icons.Rounded.Public
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
    onDismissRequest: () -> Unit = {}
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
            Column(
                modifier = Modifier
                    .fillMaxHeight(0.8f)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (toolName) {
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
                            HighlightText(
                                code = JsonInstantPretty.encodeToString(content),
                                language = "json",
                                fontSize = 12.sp
                            )
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

                    else -> {
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
fun AskUserItem(
    conversationId: Uuid?,
    askUser: me.rerere.ai.ui.UIMessagePart.AskUser,
) {
    val chatService = koinInject<ChatService>()
    val settings = me.rerere.rikkahub.ui.context.LocalSettings.current
    val haptics = rememberPremiumHaptics(enabled = settings.displaySetting.enableUIHaptics)
    var showSheet by remember(askUser.toolCallId) { mutableStateOf(false) }
    val canRespond = conversationId != null && askUser.toolCallId.isNotBlank() && askUser.state == AskUserState.Pending

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
                        text = stringResource(R.string.ask_user_title),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = askUser.question,
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
                chatService.respondAskUser(
                    conversationId = conversationId ?: return@AskUserBottomSheet,
                    toolCallId = askUser.toolCallId,
                    answer = "",
                )
            },
        )
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

    ModalBottomSheet(
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        onDismissRequest = onDismissRequest,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Header
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = AppShapes.CardMedium,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.HelpOutline,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(8.dp)
                            .size(24.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.ask_user_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            }

            // Question card
            Card(
                shape = AppShapes.CardLarge,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Text(
                    text = question,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }

            // Option buttons
            if (options.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { option ->
                        val interactionSource = remember { MutableInteractionSource() }
                        val isPressed by interactionSource.collectIsPressedAsState()
                        val scale by animateFloatAsState(
                            targetValue = if (isPressed) 0.85f else 1f,
                            animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
                            label = "ask_user_option_scale",
                        )
                        Surface(
                            onClick = {
                                haptics.perform(HapticPattern.Pop)
                                onSelect(option)
                            },
                            interactionSource = interactionSource,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            shape = AppShapes.ButtonPill,
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                },
                        ) {
                            Text(
                                text = option,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 13.dp, horizontal = 16.dp),
                            )
                        }
                    }
                }
            }

            // Custom input row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = customInput,
                    onValueChange = { customInput = it },
                    placeholder = { Text(text = stringResource(R.string.ask_user_type_hint)) },
                    modifier = Modifier.weight(1f),
                    shape = AppShapes.ButtonPill,
                    singleLine = true,
                )
                val submitInteractionSource = remember { MutableInteractionSource() }
                val submitPressed by submitInteractionSource.collectIsPressedAsState()
                val submitScale by animateFloatAsState(
                    targetValue = if (submitPressed) 0.85f else 1f,
                    animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
                    label = "ask_user_submit_scale",
                )
                Surface(
                    onClick = {
                        if (customInput.isNotBlank()) {
                            haptics.perform(HapticPattern.Pop)
                            onSelect(customInput.trim())
                        }
                    },
                    enabled = customInput.isNotBlank(),
                    interactionSource = submitInteractionSource,
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = AppShapes.ButtonPill,
                    modifier = Modifier.graphicsLayer {
                        scaleX = submitScale
                        scaleY = submitScale
                    },
                ) {
                    Text(
                        text = stringResource(R.string.ask_user_submit),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}
