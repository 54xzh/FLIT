package me.rerere.rikkahub.ui.pages.chat

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import me.rerere.rikkahub.ui.components.ui.HapticSwitch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.navigation.compose.rememberNavController
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Terminal
import me.rerere.rikkahub.ui.components.ui.ToastType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyUIMessage
import me.rerere.ai.ui.toSortedMessageParts
import me.rerere.ai.util.encodeBase64
import me.rerere.common.android.appTempFolder
import me.rerere.highlight.Highlighter
import me.rerere.rikkahub.R
import me.rerere.highlight.LocalHighlighter
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getEffectiveDisplaySetting
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.model.buildSeatDisplayNames
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.richtext.MermaidExportAssets
import me.rerere.rikkahub.ui.components.richtext.MermaidImageRenderer
import me.rerere.rikkahub.ui.components.richtext.MermaidTheme
import me.rerere.rikkahub.ui.components.richtext.extractMermaidCodeBlocks
import me.rerere.rikkahub.ui.components.richtext.mermaidExportKey
import me.rerere.rikkahub.ui.components.ui.ModelIcon
import me.rerere.rikkahub.ui.components.ui.BitmapComposer
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.exportImage
import me.rerere.rikkahub.utils.getActivity
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import me.rerere.rikkahub.utils.toLocalString
import me.rerere.rikkahub.utils.toLocalStringSeconds
import org.koin.compose.koinInject
import java.io.FileOutputStream
import java.time.LocalDateTime
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime as KxLocalDateTime

private const val TAG = "ChatExport"

@Composable
fun ChatExportSheet(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    conversation: Conversation,
    selectedMessages: List<UIMessage>
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val settings = LocalSettings.current
    val colorScheme = MaterialTheme.colorScheme
    val mermaidTheme = if (LocalDarkMode.current) MermaidTheme.DARK else MermaidTheme.DEFAULT
    var imageExportOptions by remember { mutableStateOf(ImageExportOptions()) }

    if (visible) {
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = stringResource(id = R.string.chat_page_export_format))

                val markdownSuccessMessage =
                    stringResource(id = R.string.chat_page_export_success, "Markdown")
                OutlinedCard(
                    onClick = {
                        exportToMarkdown(context, conversation, selectedMessages)
                        toaster.show(
                            markdownSuccessMessage,
                            type = ToastType.Success
                        )
                        onDismissRequest()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ListItem(
                        headlineContent = {
                            Text(stringResource(id = R.string.chat_page_export_markdown))
                        },
                        supportingContent = {
                            Text(stringResource(id = R.string.chat_page_export_markdown_desc))
                        },
                        leadingContent = {
                            Icon(Icons.Rounded.Description, contentDescription = null)
                        }
                    )
                }

                val imageSuccessMessage =
                    stringResource(id = R.string.chat_page_export_success, "Image")
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        ListItem(
                            headlineContent = {
                                Text(stringResource(id = R.string.chat_page_export_image))
                            },
                            supportingContent = {
                                Text(stringResource(id = R.string.chat_page_export_image_desc))
                            },
                            leadingContent = {
                                Icon(Icons.Rounded.Image, contentDescription = null)
                            }
                        )

                        HorizontalDivider()

                        ListItem(
                            headlineContent = { Text(stringResource(R.string.chat_page_export_image_expand_reasoning)) },
                            trailingContent = {
                                HapticSwitch(
                                    checked = imageExportOptions.expandReasoning,
                                    onCheckedChange = {
                                        imageExportOptions = imageExportOptions.copy(expandReasoning = it)
                                    }
                                )
                            }
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        runCatching {
                                            exportToImage(
                                                context = context,
                                                scope = scope,
                                                density = density,
                                                colorScheme = colorScheme,
                                                mermaidTheme = mermaidTheme,
                                                conversation = conversation,
                                                messages = selectedMessages,
                                                settings = settings,
                                                options = imageExportOptions
                                            )
                                        }.onFailure {
                                            it.printStackTrace()
                                            toaster.show(
                                                message = "Failed to export image: ${it.message}",
                                                type = ToastType.Error
                                            )
                                        }
                                    }
                                    toaster.show(
                                        imageSuccessMessage,
                                        type = ToastType.Success
                                    )
                                    onDismissRequest()
                                }
                            ) {
                                Text(stringResource(R.string.mermaid_export))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun exportToMarkdown(
    context: Context,
    conversation: Conversation,
    messages: List<UIMessage>
) {
    val filename = "chat-export-${LocalDateTime.now().toLocalString()}.md"

    val markdown = buildChatMarkdown(
        conversation = conversation,
        messages = messages,
        exportedAt = LocalDateTime.now()
    )

    try {
        val dir = context.appTempFolder
        val file = dir.resolve(filename)
        if (!file.exists()) {
            file.createNewFile()
        } else {
            file.delete()
            file.createNewFile()
        }
        FileOutputStream(file).use {
            it.write(markdown.toByteArray())
        }

        // Share the file
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        shareFile(context, uri, "text/markdown")

    } catch (e: Exception) {
        e.printStackTrace()
    }
}

internal fun buildChatMarkdown(
    conversation: Conversation,
    messages: List<UIMessage>,
    exportedAt: LocalDateTime = LocalDateTime.now(),
): String {
    val sb = StringBuilder()
    sb.append("# ").append(conversation.title).append("\n\n")
    sb.append("*Exported on ").append(exportedAt.toLocalStringSeconds()).append("*\n\n")

    messages.forEach { message ->
        if (message.parts.isEmptyUIMessage()) return@forEach

        val role = if (message.role == MessageRole.USER) "**User**" else "**Assistant**"
        sb.append(role)
            .append(" · ")
            .append(message.createdAt.toLocalStringSeconds())
            .append("\n\n")

        message.parts.toSortedMessageParts()
            .filterNot { it is UIMessagePart.Reasoning || it is UIMessagePart.Thinking }
            .forEach { part ->
                when (part) {
                    is UIMessagePart.Text -> {
                        sb.append(part.text).append("\n")
                    }

                    is UIMessagePart.Image -> {
                        sb.append("![Image](").append(part.encodeBase64().getOrNull()).append(")\n")
                    }

                    else -> Unit
                }
            }

        sb.append("\n---\n")
    }

    return sb.toString().trimEnd() + "\n"
}

private fun KxLocalDateTime.toLocalStringSeconds(): String {
    return "%04d-%02d-%02d %02d:%02d:%02d".format(
        year,
        month.ordinal + 1,
        day,
        hour,
        minute,
        second
    )
}

private suspend fun exportToImage(
    context: Context,
    scope: CoroutineScope,
    density: Density,
    colorScheme: ColorScheme,
    mermaidTheme: MermaidTheme,
    conversation: Conversation,
    messages: List<UIMessage>,
    settings: Settings,
    options: ImageExportOptions = ImageExportOptions()
) {
    val filename = "chat-export-${LocalDateTime.now().toLocalString()}.png"
    val composer = BitmapComposer(scope)
    val activity = context.getActivity()
    if (activity == null) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, context.getString(R.string.toast_failed_to_get_activity), Toast.LENGTH_SHORT).show()
        }
        return
    }

    val mermaidAssets = prepareMermaidExportAssets(
        activity = activity,
        density = density,
        colorScheme = colorScheme,
        mermaidTheme = mermaidTheme,
        messages = messages,
        options = options,
    )

    var bitmap: Bitmap? = null
    try {
        val renderedBitmap = composer.composableToBitmap(
            activity = activity,
            width = 540.dp,
            screenDensity = density,
            content = {
                CompositionLocalProvider(LocalSettings provides settings) {
                    ExportedChatImage(
                        conversation = conversation,
                        messages = messages,
                        options = options,
                        mermaidAssets = mermaidAssets,
                    )
                }
            }
        )
        bitmap = renderedBitmap

        val dir = context.appTempFolder
        val file = dir.resolve(filename)
        if (!file.exists()) {
            file.createNewFile()
        } else {
            file.delete()
            file.createNewFile()
        }

        FileOutputStream(file).use { fos ->
            renderedBitmap.compress(Bitmap.CompressFormat.PNG, 90, fos)
        }

        // Save to gallery
        context.exportImage(activity, renderedBitmap, filename)

        // Share the file
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        shareFile(context, uri, "image/png")
    } catch (e: Exception) {
        e.printStackTrace()
        withContext(Dispatchers.Main) {
            Toast.makeText(
                context,
                context.getString(R.string.toast_failed_to_export_image, e.message ?: ""),
                Toast.LENGTH_SHORT
            ).show()
        }
    } finally {
        bitmap?.recycle()
        mermaidAssets.images.values.forEach { it.recycle() }
    }
}

private suspend fun prepareMermaidExportAssets(
    activity: Activity,
    density: Density,
    colorScheme: ColorScheme,
    mermaidTheme: MermaidTheme,
    messages: List<UIMessage>,
    options: ImageExportOptions,
): MermaidExportAssets {
    val codes = collectMermaidCodeBlocks(messages = messages, options = options)
    if (codes.isEmpty()) return MermaidExportAssets()

    val renderer = MermaidImageRenderer(activity = activity, density = density)
    val images = mutableMapOf<String, Bitmap>()
    codes.forEach { code ->
        val key = mermaidExportKey(code)
        if (images.containsKey(key)) return@forEach

        val bitmap = runCatching {
            renderer.render(
                code = code,
                theme = mermaidTheme,
                colorScheme = colorScheme,
            )
        }.onFailure {
            Log.w(TAG, "Failed to render Mermaid export image", it)
        }.getOrNull()

        if (bitmap != null) {
            images[key] = bitmap
        }
    }
    return MermaidExportAssets(images = images)
}

private fun collectMermaidCodeBlocks(
    messages: List<UIMessage>,
    options: ImageExportOptions,
): List<String> {
    return messages.asSequence()
        .flatMap { message ->
            message.parts.asSequence().flatMap { part ->
                when (part) {
                    is UIMessagePart.Text -> extractMermaidCodeBlocks(part.text).asSequence()
                    is UIMessagePart.Reasoning -> {
                        if (options.expandReasoning) {
                            extractMermaidCodeBlocks(part.reasoning).asSequence()
                        } else {
                            emptySequence()
                        }
                    }

                    else -> emptySequence()
                }
            }
        }
        .distinctBy(::mermaidExportKey)
        .toList()
}

data class ImageExportOptions(val expandReasoning: Boolean = false)

@Composable
private fun ExportedChatImage(
    conversation: Conversation,
    messages: List<UIMessage>,
    options: ImageExportOptions = ImageExportOptions(),
    mermaidAssets: MermaidExportAssets = MermaidExportAssets(),
) {
    val navBackStack = rememberNavController()
    val highlighter = koinInject<Highlighter>()
    RikkahubTheme {
        CompositionLocalProvider(
            LocalNavController provides navBackStack,
            LocalHighlighter provides highlighter
        ) {
            Surface(
                modifier = Modifier.width(540.dp) // like 1080p but with density independence
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f, fill = false)) {
                            Text(
                                text = conversation.title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            )
                            Text(
                                text = "${LocalDateTime.now().toLocalString()}  rikka-ai.com",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // Use painterResource for the logo
                        val painter = painterResource(id = R.mipmap.ic_launcher_lastchat_foreground)
                        Image(
                            painter = painter,
                            contentDescription = stringResource(R.string.a11y_logo),
                            modifier = Modifier.size(60.dp)
                        )
                    }

                    // Messages
                    val settings = LocalSettings.current
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
                    val forceUseAssistantAvatar = groupChatTemplateForConversation != null

                    messages.forEachIndexed { index, message ->
                        val previousMessage = messages.getOrNull(index - 1)
                        val speakerChanged = previousMessage?.role == MessageRole.ASSISTANT &&
                            message.role == MessageRole.ASSISTANT &&
                            (previousMessage.speakerSeatId != message.speakerSeatId ||
                                previousMessage.speakerAssistantId != message.speakerAssistantId ||
                                previousMessage.modelId != message.modelId)
                        val previousRole = if (speakerChanged) null else previousMessage?.role
                        val model = message.modelId?.let { settings.findModelById(it) }
                        val assistant = message.speakerSeatId
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
                            ?: message.speakerAssistantId?.let { speakerId -> settings.getAssistantById(speakerId) }
                            ?: settings.getAssistantById(conversation.assistantId)
                        ExportedChatMessage(
                            message = message,
                            previousRole = previousRole,
                            options = options,
                            model = model,
                            assistant = assistant,
                            forceUseAssistantAvatar = forceUseAssistantAvatar,
                            mermaidAssets = mermaidAssets,
                        )
                    }

                    // Watermark
                    Column {
                        Text(
                            text = stringResource(R.string.export_image_warning),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExportedChatMessage(
    message: UIMessage,
    previousRole: MessageRole? = null,
    model: Model? = null,
    assistant: Assistant? = null,
    forceUseAssistantAvatar: Boolean = false,
    options: ImageExportOptions = ImageExportOptions(),
    mermaidAssets: MermaidExportAssets = MermaidExportAssets(),
) {
    if (message.parts.isEmptyUIMessage()) return
    val context = LocalContext.current
    val settings = LocalSettings.current
    val effectiveDisplaySetting = settings.getEffectiveDisplaySetting(assistant)
    val defaultAssistantName = stringResource(R.string.assistant_page_default_assistant)
    val headerInfo = resolveExportedAssistantHeaderInfo(
        assistant = assistant,
        model = model,
        forceUseAssistantAvatar = forceUseAssistantAvatar,
        defaultAssistantName = defaultAssistantName,
    )
    val showHeader = message.role == MessageRole.ASSISTANT && previousRole != message.role
    val messageContent: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .widthIn(max = (540 * 0.9).dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = if (message.role == MessageRole.USER) Alignment.End else Alignment.Start
        ) {
            message.parts.toSortedMessageParts().forEach { part ->
                when (part) {
                    is UIMessagePart.Text -> {
                        if (part.text.isNotBlank()) {
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = when (message.role) {
                                        MessageRole.USER -> MaterialTheme.colorScheme.primaryContainer
                                        else -> Color.Transparent
                                    }
                                )
                            ) {
                                ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                                    MarkdownBlock(
                                        content = part.text,
                                        modifier = Modifier.padding(12.dp),
                                        exportAssets = mermaidAssets,
                                    )
                                }
                            }
                        }
                    }

                    is UIMessagePart.Image -> {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(part.url)
                                .allowHardware(false)
                                .crossfade(false)
                                .build(),
                            contentDescription = stringResource(R.string.a11y_image),
                            modifier = Modifier
                                .sizeIn(maxHeight = 300.dp)
                                .clip(RoundedCornerShape(12.dp)),
                        )
                    }

                    is UIMessagePart.Reasoning -> {
                        ExportedReasoningCard(
                            reasoning = part,
                            expanded = options.expandReasoning,
                            mermaidAssets = mermaidAssets,
                        )
                    }

                    is UIMessagePart.ToolCall -> {
                        ExportedToolCall(toolCall = part)
                    }

                    is UIMessagePart.ToolResult -> {
                        ExportedToolResult(toolResult = part)
                    }

                    else -> {
                        // Other parts are not rendered in image export for now
                    }
                }
            }
        }
    }

    if (showHeader) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 8.dp)
        ) {
            if (effectiveDisplaySetting.showModelIcon) {
                when {
                    headerInfo.identity != null -> {
                        UIAvatar(
                            name = headerInfo.identity.name,
                            value = headerInfo.identity.avatar,
                            modifier = Modifier.size(36.dp),
                            loading = false,
                        )
                    }

                    model != null -> {
                        ModelIcon(
                            model = model,
                            provider = model.findProvider(settings.providers),
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
            }

            if (effectiveDisplaySetting.showModelName) {
                Text(
                    text = headerInfo.name,
                    style = if (headerInfo.identity != null) {
                        MaterialTheme.typography.titleMedium
                    } else {
                        MaterialTheme.typography.titleSmall
                    },
                )
            }
        }
    }
    messageContent()
}

internal data class ExportedAssistantHeaderInfo(
    val identity: Assistant?,
    val name: String,
)

internal fun resolveExportedAssistantHeaderInfo(
    assistant: Assistant?,
    model: Model?,
    forceUseAssistantAvatar: Boolean,
    defaultAssistantName: String,
): ExportedAssistantHeaderInfo {
    val identity = assistant?.takeIf { forceUseAssistantAvatar || it.useAssistantAvatar || model == null }
    val name = when {
        identity != null -> identity.name.ifBlank { defaultAssistantName }
        model != null -> model.displayName
        else -> defaultAssistantName
    }
    return ExportedAssistantHeaderInfo(identity = identity, name = name)
}

@Composable
private fun ExportedReasoningCard(
    reasoning: UIMessagePart.Reasoning,
    expanded: Boolean,
    mermaidAssets: MermaidExportAssets = MermaidExportAssets(),
) {
    val duration = reasoning.finishedAt?.let { endTime ->
        endTime - reasoning.createdAt
    } ?: (kotlin.time.Clock.System.now() - reasoning.createdAt)

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(
            modifier = Modifier
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Lightbulb,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.secondary
                )
                Text(
                    text = stringResource(R.string.deep_thinking),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                if (duration > 0.seconds) {
                    Text(
                        text = "(${duration.toString(DurationUnit.SECONDS, 1)})",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
            if (expanded) {
                MarkdownBlock(
                    content = reasoning.reasoning,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    exportAssets = mermaidAssets,
                )
            }
        }
    }
}

@Composable
private fun ExportedToolCall(
    toolCall: UIMessagePart.ToolCall
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 16.dp)
        ) {
            Icon(
                imageVector = when (toolCall.toolName) {
                    "create_memory", "edit_memory" -> Icons.Rounded.Favorite
                    "delete_memory" -> Icons.Rounded.Delete
                    "search_agent" -> Icons.Rounded.Public
                    "search_web" -> Icons.Rounded.Public
                    "scrape_web" -> Icons.Rounded.Public
                    "run_skill_script" -> Icons.Rounded.Terminal
                    "eval_python" -> Icons.Rounded.Terminal
                    else -> Icons.Rounded.Build
                },
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
            Column {
                Text(
                    text = when (toolCall.toolName) {
                        "create_memory" -> stringResource(R.string.chat_message_tool_create_memory)
                        "edit_memory" -> stringResource(R.string.chat_message_tool_edit_memory)
                        "delete_memory" -> stringResource(R.string.chat_message_tool_delete_memory)
                        "search_agent" -> stringResource(R.string.chat_message_tool_search_agent)
                        "search_web" -> {
                            val query = runCatching {
                                JsonInstant.parseToJsonElement(toolCall.arguments).jsonObject["query"]?.jsonPrimitiveOrNull?.contentOrNull
                                    ?: ""
                            }.getOrDefault("")
                            stringResource(R.string.chat_message_tool_search_web, query)
                        }
                        "scrape_web" -> stringResource(R.string.chat_message_tool_scrape_web)
                        "run_skill_script" -> {
                            val name = runCatching {
                                JsonInstant.parseToJsonElement(toolCall.arguments).jsonObject["path"]?.jsonPrimitiveOrNull?.contentOrNull
                                    ?.replace('\\', '/')
                                    ?.substringAfterLast('/')
                                    ?: ""
                            }.getOrDefault("")
                            if (name.isBlank()) {
                                stringResource(R.string.chat_message_tool_run_script_generic)
                            } else {
                                stringResource(R.string.chat_message_tool_run_script, name)
                            }
                        }
                        "eval_python" -> stringResource(R.string.chat_message_tool_run_python_generic)
                        else -> stringResource(R.string.chat_message_tool_call_generic, toolCall.toolName)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun ExportedToolResult(toolResult: UIMessagePart.ToolResult) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 16.dp)
        ) {
            Icon(
                imageVector = when (toolResult.toolName) {
                    "create_memory", "edit_memory" -> Icons.Rounded.Favorite
                    "delete_memory" -> Icons.Rounded.Delete
                    "search_agent" -> Icons.Rounded.Public
                    "search_web" -> Icons.Rounded.Public
                    "scrape_web" -> Icons.Rounded.Public
                    "run_skill_script" -> Icons.Rounded.Terminal
                    "eval_python" -> Icons.Rounded.Terminal
                    else -> Icons.Rounded.Build
                },
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
            Column {
                Text(
                    text = when (toolResult.toolName) {
                        "create_memory" -> stringResource(R.string.chat_message_tool_create_memory)
                        "edit_memory" -> stringResource(R.string.chat_message_tool_edit_memory)
                        "delete_memory" -> stringResource(R.string.chat_message_tool_delete_memory)
                        "search_agent" -> stringResource(R.string.chat_message_tool_search_agent)
                        "search_web" -> {
                            val query =
                                toolResult.arguments.jsonObject["query"]?.jsonPrimitiveOrNull?.contentOrNull
                                    ?: ""
                            stringResource(R.string.chat_message_tool_search_web, query)
                        }
                        "scrape_web" -> stringResource(R.string.chat_message_tool_scrape_web)
                        "run_skill_script" -> {
                            val name = toolResult.arguments.jsonObject["path"]?.jsonPrimitiveOrNull?.contentOrNull
                                ?.replace('\\', '/')
                                ?.substringAfterLast('/')
                                .orEmpty()
                            if (name.isBlank()) {
                                stringResource(R.string.chat_message_tool_run_script_generic)
                            } else {
                                stringResource(R.string.chat_message_tool_run_script, name)
                            }
                        }
                        "eval_python" -> stringResource(R.string.chat_message_tool_run_python_generic)
                        else -> stringResource(R.string.chat_message_tool_call_generic, toolResult.toolName)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

private fun shareFile(context: Context, uri: Uri, mimeType: String) {
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        android.content.Intent.createChooser(
            intent,
            context.getString(R.string.chat_page_export_share_via)
        )
    )
}
