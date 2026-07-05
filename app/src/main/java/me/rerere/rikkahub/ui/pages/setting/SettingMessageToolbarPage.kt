package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.MessageToolbarButton
import me.rerere.rikkahub.data.datastore.MessageToolbarConfig
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.message.ChatMessageAssistantAvatar
import me.rerere.rikkahub.ui.components.message.ChatMessageUserAvatar
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.components.ui.HapticSwitch
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.pages.setting.components.SettingsGroup
import me.rerere.rikkahub.ui.pages.setting.components.SettingGroupItem
import me.rerere.rikkahub.ui.theme.AppShapes
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingMessageToolbarPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var displaySetting by remember(settings) { mutableStateOf(settings.displaySetting) }

    fun updateDisplaySetting(setting: DisplaySetting) {
        displaySetting = setting
        vm.updateSettings(settings.copy(displaySetting = setting))
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            OneUITopAppBar(
                title = stringResource(R.string.setting_page_message_toolbar),
                scrollBehavior = scrollBehavior,
                navigationIcon = { BackButton() }
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            // 第一块：预览
            item {
                SettingsGroup(
                    title = stringResource(R.string.setting_page_message_toolbar_preview)
                ) {
                    ToolbarPreviewCard(
                        userConfig = displaySetting.userMessageToolbar,
                        assistantConfig = displaySetting.assistantMessageToolbar,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    )
                }
            }

            // 第二块：用户消息工具栏
            item {
                SettingsGroup(
                    title = stringResource(R.string.setting_page_message_toolbar_user)
                ) {
                    USER_BUTTONS.forEach { (button, iconVector) ->
                        ToolbarToggleRow(
                            button = button,
                            iconVector = iconVector,
                            title = buttonTitle(button),
                            config = displaySetting.userMessageToolbar,
                            onChange = { newConfig ->
                                updateDisplaySetting(
                                    displaySetting.copy(userMessageToolbar = newConfig)
                                )
                            }
                        )
                    }
                }
            }

            // 第三块：助手消息工具栏
            item {
                SettingsGroup(
                    title = stringResource(R.string.setting_page_message_toolbar_assistant)
                ) {
                    ASSISTANT_BUTTONS.forEach { (button, iconVector) ->
                        ToolbarToggleRow(
                            button = button,
                            iconVector = iconVector,
                            title = buttonTitle(button),
                            config = displaySetting.assistantMessageToolbar,
                            onChange = { newConfig ->
                                updateDisplaySetting(
                                    displaySetting.copy(assistantMessageToolbar = newConfig)
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

// 用户消息可选按钮（不含 TTS）
private val USER_BUTTONS: List<Pair<MessageToolbarButton, androidx.compose.ui.graphics.vector.ImageVector>> = listOf(
    MessageToolbarButton.COPY to Icons.Rounded.ContentCopy,
    MessageToolbarButton.FORK to Icons.AutoMirrored.Rounded.CallSplit,
    MessageToolbarButton.REGENERATE to Icons.Rounded.Refresh,
    MessageToolbarButton.EDIT to Icons.Rounded.Edit,
    MessageToolbarButton.SHARE to Icons.Rounded.Share,
    MessageToolbarButton.SELECT_AND_COPY to Icons.Rounded.SelectAll,
    MessageToolbarButton.WEB_VIEW_PREVIEW to Icons.Rounded.OpenInBrowser,
    MessageToolbarButton.DELETE to Icons.Rounded.Delete,
)

// 助手消息可选按钮（含 TTS）
private val ASSISTANT_BUTTONS: List<Pair<MessageToolbarButton, androidx.compose.ui.graphics.vector.ImageVector>> = listOf(
    MessageToolbarButton.COPY to Icons.Rounded.ContentCopy,
    MessageToolbarButton.FORK to Icons.AutoMirrored.Rounded.CallSplit,
    MessageToolbarButton.REGENERATE to Icons.Rounded.Refresh,
    MessageToolbarButton.TTS to Icons.AutoMirrored.Rounded.VolumeUp,
    MessageToolbarButton.EDIT to Icons.Rounded.Edit,
    MessageToolbarButton.SHARE to Icons.Rounded.Share,
    MessageToolbarButton.SELECT_AND_COPY to Icons.Rounded.SelectAll,
    MessageToolbarButton.WEB_VIEW_PREVIEW to Icons.Rounded.OpenInBrowser,
    MessageToolbarButton.DELETE to Icons.Rounded.Delete,
)

@Composable
private fun buttonTitle(button: MessageToolbarButton): String = when (button) {
    MessageToolbarButton.COPY -> stringResource(R.string.copy)
    MessageToolbarButton.FORK -> stringResource(R.string.create_fork)
    MessageToolbarButton.REGENERATE -> stringResource(R.string.regenerate)
    MessageToolbarButton.TTS -> stringResource(R.string.tts)
    MessageToolbarButton.EDIT -> stringResource(R.string.edit)
    MessageToolbarButton.SHARE -> stringResource(R.string.share)
    MessageToolbarButton.SELECT_AND_COPY -> stringResource(R.string.select_and_copy)
    MessageToolbarButton.WEB_VIEW_PREVIEW -> stringResource(R.string.render_with_webview)
    MessageToolbarButton.DELETE -> stringResource(R.string.delete)
}

@Composable
private fun ToolbarToggleRow(
    button: MessageToolbarButton,
    iconVector: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    config: MessageToolbarConfig,
    onChange: (MessageToolbarConfig) -> Unit,
) {
    SettingGroupItem(
        title = title,
        icon = {
            Icon(
                imageVector = iconVector,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
        },
        trailing = {
            HapticSwitch(
                checked = config.isOnToolbar(button),
                onCheckedChange = { onChange(config.toggle(button)) }
            )
        }
    )
}

/**
 * 预览卡片：参考助手「UI 自定义」页的 ChatPreview 样式，模拟一段真实对话，
 * 同时展示用户消息与助手消息各自底部的工具栏。
 * 开关拨到"开"的按钮显示在工具栏图标行；"关"的按钮收进 ⋮ 更多菜单。
 */
@Composable
private fun ToolbarPreviewCard(
    userConfig: MessageToolbarConfig,
    assistantConfig: MessageToolbarConfig,
    modifier: Modifier = Modifier,
) {
    val settings = LocalSettings.current
    val userNickname = settings.displaySetting.userNickname.takeIf(String::isNotBlank)
        ?: stringResource(R.string.user_default_name)
    val userAvatar = settings.displaySetting.userAvatar
    // 内置一个空助手作为预览（最简实现）
    val assistant = remember { Assistant() }

    val previewUserMessageText = stringResource(R.string.assistant_ui_preview_sample_user_message)
    val previewAssistantMessageText = stringResource(R.string.assistant_ui_preview_sample_assistant_message)

    val userMessage = remember(previewUserMessageText) {
        UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text(previewUserMessageText))
        )
    }
    val assistantMessage = remember(previewAssistantMessageText) {
        UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text(previewAssistantMessageText))
        )
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // 用户消息
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ChatMessageUserAvatar(
                message = userMessage,
                previousRole = null,
                avatar = userAvatar,
                nickname = userNickname,
                modifier = Modifier.fillMaxWidth()
            )
            Card(shape = AppShapes.CardLarge) {
                Column(modifier = Modifier.padding(12.dp)) {
                    ProvideTextStyle(LocalTextStyle.current) {
                        MarkdownBlock(content = previewUserMessageText)
                    }
                }
            }
            ToolbarPreviewIcons(config = userConfig, includeTts = false)
        }

        // 助手消息
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ChatMessageAssistantAvatar(
                message = assistantMessage,
                previousRole = MessageRole.USER,
                loading = false,
                model = null,
                assistant = assistant,
                modifier = Modifier.fillMaxWidth()
            )
            ProvideTextStyle(LocalTextStyle.current) {
                MarkdownBlock(content = previewAssistantMessageText)
            }
            ToolbarPreviewIcons(config = assistantConfig, includeTts = true)
        }
    }
}

@Composable
private fun ToolbarPreviewIcons(
    config: MessageToolbarConfig,
    includeTts: Boolean,
) {
    val order = if (includeTts) ASSISTANT_BUTTONS else USER_BUTTONS
    val toolbarIcons = order.filter { (btn, _) ->
        config.isOnToolbar(btn) && (btn != MessageToolbarButton.TTS || includeTts)
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        toolbarIcons.forEach { (_, icon) ->
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .clip(CircleShape)
                    .padding(8.dp)
                    .size(16.dp)
            )
        }
        // 更多按钮 ⋮ 始终保留
        Icon(
            imageVector = Icons.Rounded.MoreHoriz,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .clip(CircleShape)
                .padding(8.dp)
                .size(16.dp)
        )
    }
}