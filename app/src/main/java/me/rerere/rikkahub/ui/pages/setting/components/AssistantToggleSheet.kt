package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.ui.HapticSwitch
import me.rerere.rikkahub.ui.components.ui.ItemPosition
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.theme.LocalDarkMode

@Composable
fun AssistantToggleSheet(
    title: String,
    assistants: List<Assistant>,
    isEnabled: (Assistant) -> Boolean,
    onToggle: (Assistant, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val cornerRadius = 28.dp
    val smallCorner = 8.dp
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val defaultAssistantName = stringResource(R.string.assistant_page_default_assistant)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        dragHandle = {
            IconButton(
                onClick = {
                    scope.launch {
                        sheetState.hide()
                        onDismiss()
                    }
                }
            ) {
                Icon(Icons.Rounded.KeyboardArrowDown, null)
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            assistants.forEachIndexed { index, assistant ->
                val position = when {
                    assistants.size == 1 -> ItemPosition.ONLY
                    index == 0 -> ItemPosition.FIRST
                    index == assistants.lastIndex -> ItemPosition.LAST
                    else -> ItemPosition.MIDDLE
                }
                val shape = when (position) {
                    ItemPosition.ONLY -> RoundedCornerShape(cornerRadius)
                    ItemPosition.FIRST -> RoundedCornerShape(
                        topStart = cornerRadius,
                        topEnd = cornerRadius,
                        bottomStart = smallCorner,
                        bottomEnd = smallCorner
                    )
                    ItemPosition.MIDDLE -> RoundedCornerShape(smallCorner)
                    ItemPosition.LAST -> RoundedCornerShape(
                        topStart = smallCorner,
                        topEnd = smallCorner,
                        bottomStart = cornerRadius,
                        bottomEnd = cornerRadius
                    )
                }

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (LocalDarkMode.current) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    shape = shape
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        UIAvatar(
                            name = assistant.name.ifEmpty { defaultAssistantName },
                            value = assistant.avatar,
                            modifier = Modifier.size(40.dp)
                        )
                        Text(
                            text = assistant.name.ifEmpty { defaultAssistantName },
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        HapticSwitch(
                            checked = isEnabled(assistant),
                            onCheckedChange = { enabled ->
                                onToggle(assistant, enabled)
                            }
                        )
                    }
                }
            }
        }
    }
}
