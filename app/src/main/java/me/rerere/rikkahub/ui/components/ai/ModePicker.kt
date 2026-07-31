package me.rerere.rikkahub.ui.components.ai

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.ModelModeState
import me.rerere.rikkahub.ui.components.ui.HapticSwitch
import me.rerere.rikkahub.ui.components.ui.ToggleSurface
import me.rerere.rikkahub.ui.pages.setting.components.SettingGroupItem
import me.rerere.rikkahub.ui.pages.setting.components.SettingsGroup
import me.rerere.rikkahub.ui.theme.LocalDarkMode

/**
 * "模式" 按钮: 收纳 Pro / 快速 两个独立开关, 跟随模型显隐
 * 点击弹出 ModalBottomSheet, 内含两个 HapticSwitch 子项 (不是单选, 可同时开)
 */
@Composable
fun ModeButton(
    modifier: Modifier = Modifier,
    onlyIcon: Boolean = false,
    states: ModelModeState,
    supportsPro: Boolean,
    supportsFast: Boolean,
    onUpdate: (ModelModeState) -> Unit,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    var showPicker by remember { mutableStateOf(false) }

    if (showPicker) {
        ModePicker(
            states = states,
            supportsPro = supportsPro,
            supportsFast = supportsFast,
            onDismissRequest = { showPicker = false },
            onUpdate = onUpdate,
        )
    }

    val active = states.pro || states.fast
    val effectiveContentColor by animateColorAsState(
        if (active) contentColor else MaterialTheme.colorScheme.onSurface,
        label = "mode_btn_content_color"
    )

    ToggleSurface(
        checked = active,
        checkedColor = Color.Transparent,
        uncheckedColor = Color.Transparent,
        contentColor = if (active) MaterialTheme.colorScheme.primary else effectiveContentColor,
        onClick = { showPicker = true },
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = if (onlyIcon) 8.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Tune,
                    contentDescription = null,
                )
            }
            if (!onlyIcon) Text(stringResource(R.string.mode_button))
        }
    }
}

@Composable
fun ModePicker(
    states: ModelModeState,
    supportsPro: Boolean,
    supportsFast: Boolean,
    onDismissRequest: () -> Unit = {},
    onUpdate: (ModelModeState) -> Unit,
) {
    val isDarkMode = LocalDarkMode.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = if (isDarkMode) Color.Black else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.mode_button),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
            )
            SettingsGroup {
                if (supportsPro) {
                    SettingGroupItem(
                        title = stringResource(R.string.mode_pro),
                        icon = {
                            Icon(
                                imageVector = Icons.Rounded.Psychology,
                                contentDescription = null,
                            )
                        },
                        trailing = {
                            HapticSwitch(
                                checked = states.pro,
                                onCheckedChange = { onUpdate(states.copy(pro = it)) },
                            )
                        },
                    )
                }
                if (supportsFast) {
                    SettingGroupItem(
                        title = stringResource(R.string.mode_fast),
                        icon = {
                            Icon(
                                imageVector = Icons.Rounded.Bolt,
                                contentDescription = null,
                            )
                        },
                        trailing = {
                            HapticSwitch(
                                checked = states.fast,
                                onCheckedChange = { onUpdate(states.copy(fast = it)) },
                            )
                        },
                    )
                }
            }
        }
    }
}