package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.ModelModeState
import me.rerere.rikkahub.ui.components.ui.HapticSwitch
import me.rerere.rikkahub.ui.pages.setting.components.SettingGroupItem
import me.rerere.rikkahub.ui.pages.setting.components.SettingsGroup
import me.rerere.rikkahub.ui.theme.LocalDarkMode

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