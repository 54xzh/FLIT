package me.rerere.rikkahub.ui.components.ai

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.LightbulbCircle
import androidx.compose.material.icons.rounded.AutoAwesome
import me.rerere.ai.core.ReasoningLevel
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.ToggleSurface
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val levels = ReasoningLevel.entries
private val levelCount = levels.size

@Composable
fun ReasoningButton(
    modifier: Modifier = Modifier,
    onlyIcon: Boolean = false,
    reasoningLevel: ReasoningLevel,
    onUpdateReasoningLevel: (ReasoningLevel) -> Unit,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    var showPicker by remember { mutableStateOf(false) }

    if (showPicker) {
        ReasoningPicker(
            reasoningLevel = reasoningLevel,
            onDismissRequest = { showPicker = false },
            onUpdateReasoningLevel = onUpdateReasoningLevel
        )
    }

    val effectiveContentColor by animateColorAsState(
        if (reasoningLevel.isEnabled) contentColor else MaterialTheme.colorScheme.onSurface,
        label = "reasoning_btn_content_color"
    )

    ToggleSurface(
        checked = reasoningLevel.isEnabled,
        checkedColor = Color.Transparent,
        uncheckedColor = Color.Transparent,
        contentColor = if (reasoningLevel.isEnabled) MaterialTheme.colorScheme.primary else effectiveContentColor,
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
                    imageVector = if (reasoningLevel == ReasoningLevel.AUTO) Icons.Rounded.AutoAwesome else Icons.Rounded.Lightbulb,
                    contentDescription = null,
                )
            }
            if (!onlyIcon) Text(stringResource(R.string.setting_provider_page_reasoning))
        }
    }
}

@Composable
fun ReasoningPicker(
    reasoningLevel: ReasoningLevel,
    onDismissRequest: () -> Unit = {},
    onUpdateReasoningLevel: (ReasoningLevel) -> Unit,
) {
    val currentIndex = levels.indexOf(reasoningLevel).coerceAtLeast(0)
    var sliderValue by remember { mutableFloatStateOf(currentIndex.toFloat()) }
    var lastHapticIndex by remember { mutableIntStateOf(currentIndex) }
    var targetPreviewIndex by remember { mutableStateOf<Int?>(null) }
    val previewIndex = targetPreviewIndex
        ?: sliderValue.roundToInt().coerceIn(0, levelCount - 1)
    val previewLevel = levels[previewIndex]
    val haptics = rememberPremiumHaptics()
    val scope = rememberCoroutineScope()
    var snapAnimation by remember { mutableStateOf<Job?>(null) }

    val animateToLevel: (Int, Boolean) -> Unit = { targetIndex, keepTargetPreview ->
        val targetValue = targetIndex.toFloat()
        val initialValue = sliderValue
        snapAnimation?.cancel()
        targetPreviewIndex = if (keepTargetPreview) targetIndex else null
        snapAnimation = scope.launch {
            animate(
                initialValue = initialValue,
                targetValue = targetValue,
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
            ) { value, _ ->
                sliderValue = value
            }
            sliderValue = targetValue
            lastHapticIndex = targetIndex
            onUpdateReasoningLevel(levels[targetIndex])
        }
    }

    LaunchedEffect(currentIndex) {
        snapAnimation?.cancel()
        targetPreviewIndex = null
        sliderValue = currentIndex.toFloat()
        lastHapticIndex = currentIndex
    }

    val isDarkMode = LocalDarkMode.current

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = if (isDarkMode) Color.Black else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 标题
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.reasoning_picker_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = stringResource(R.string.reasoning_picker_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            // 当前等级展示
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val iconColor by animateColorAsState(
                    if (previewLevel.isEnabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                )
                Icon(
                    imageVector = when (previewLevel) {
                        ReasoningLevel.OFF -> Icons.Rounded.LightbulbCircle
                        ReasoningLevel.AUTO -> Icons.Rounded.AutoAwesome
                        else -> Icons.Rounded.Lightbulb
                    },
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = iconColor,
                )
                Text(
                    text = previewLevel.label(),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = previewLevel.desc(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Slider(
                    value = sliderValue,
                    onValueChange = { value ->
                        snapAnimation?.cancel()
                        targetPreviewIndex = null
                        sliderValue = value
                        val crossedIndex = value.roundToInt().coerceIn(0, levelCount - 1)
                        if (crossedIndex != lastHapticIndex) {
                            haptics.perform(HapticPattern.Tick)
                            lastHapticIndex = crossedIndex
                        }
                    },
                    onValueChangeFinished = {
                        animateToLevel(
                            sliderValue.roundToInt().coerceIn(0, levelCount - 1),
                            false,
                        )
                    },
                    valueRange = 0f..(levelCount - 1).toFloat(),
                    steps = 0,
                    modifier = Modifier.fillMaxWidth(),
                    thumb = {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.onPrimary)
                            )
                        }
                    },
                    track = { sliderState ->
                        SliderDefaults.Track(
                            sliderState = sliderState,
                            drawStopIndicator = null,
                            thumbTrackGapSize = 0.dp,
                        )
                    }
                )

                ReasoningScale(
                    selectedLevel = previewLevel,
                    onSelect = { level ->
                        animateToLevel(levels.indexOf(level), true)
                    }
                )
            }
        }
    }
}

@Composable
private fun ReasoningScale(
    selectedLevel: ReasoningLevel,
    onSelect: (ReasoningLevel) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        levels.forEach { level ->
            val selected = level == selectedLevel
            val tickColor by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant
            )
            val labelColor by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )

            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ToggleSurface(
                    checked = selected,
                    checkedColor = Color.Transparent,
                    uncheckedColor = Color.Transparent,
                    onClick = { onSelect(level) },
                    modifier = Modifier,
                ) {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 4.dp, vertical = 10.dp)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(16.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(tickColor)
                        )
                        Text(
                            text = level.label(),
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                            color = labelColor,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReasoningLevel.label(): String = when (this) {
    ReasoningLevel.OFF -> stringResource(R.string.reasoning_off)
    ReasoningLevel.AUTO -> stringResource(R.string.reasoning_auto)
    ReasoningLevel.LOW -> stringResource(R.string.reasoning_light)
    ReasoningLevel.MEDIUM -> stringResource(R.string.reasoning_medium)
    ReasoningLevel.HIGH -> stringResource(R.string.reasoning_heavy)
    ReasoningLevel.XHIGH -> stringResource(R.string.reasoning_xhigh)
    ReasoningLevel.MAX -> stringResource(R.string.reasoning_max)
}

@Composable
private fun ReasoningLevel.desc(): String = when (this) {
    ReasoningLevel.OFF -> stringResource(R.string.reasoning_off_desc)
    ReasoningLevel.AUTO -> stringResource(R.string.reasoning_auto_desc)
    ReasoningLevel.LOW -> stringResource(R.string.reasoning_light_desc)
    ReasoningLevel.MEDIUM -> stringResource(R.string.reasoning_medium_desc)
    ReasoningLevel.HIGH -> stringResource(R.string.reasoning_heavy_desc)
    ReasoningLevel.XHIGH -> stringResource(R.string.reasoning_xhigh_desc)
    ReasoningLevel.MAX -> stringResource(R.string.reasoning_max_desc)
}

@Composable
@Preview(showBackground = true)
private fun ReasoningPickerPreview() {
    MaterialTheme {
        var level by remember { mutableStateOf(ReasoningLevel.AUTO) }
        ReasoningPicker(
            reasoningLevel = level,
            onUpdateReasoningLevel = { level = it }
        )
    }
}