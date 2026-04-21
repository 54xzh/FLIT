package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Send
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.floor
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.theme.AppShapes

@Composable
fun AskUserWizardBottomSheet(
    questions: List<UIMessagePart.AskUserQuestion>,
    onComplete: (String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val settings = LocalSettings.current
    val haptics = rememberPremiumHaptics(enabled = settings.displaySetting.enableUIHaptics)
    val pagerState = rememberPagerState(pageCount = { questions.size })
    val answers = remember { mutableStateListOf<String?>().also { repeat(questions.size) { _ -> it.add(null) } } }
    val customInputs = remember { mutableStateListOf<String>().also { repeat(questions.size) { _ -> it.add("") } } }
    val coroutineScope = rememberCoroutineScope()
    val itemColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val density = LocalDensity.current
    val pageHeights = remember { mutableStateMapOf<Int, Int>() }
    val pagerHeightPx by remember(pagerState) {
        derivedStateOf {
            val position = pagerState.currentPage + pagerState.currentPageOffsetFraction
            val fromIndex = floor(position).toInt().coerceIn(0, questions.size - 1)
            val fraction = (position - fromIndex).coerceIn(0f, 1f)
            val from = pageHeights[fromIndex] ?: return@derivedStateOf null
            val toIndex = (fromIndex + 1).coerceAtMost(questions.size - 1)
            val to = pageHeights[toIndex] ?: from
            lerp(from.toFloat(), to.toFloat(), fraction)
        }
    }

    fun completeWithAnimation(result: String) {
        coroutineScope.launch {
            sheetState.hide()
            onComplete(result)
        }
    }

    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = onDismissRequest,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.ask_user_wizard_progress, pagerState.currentPage + 1, questions.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        pagerHeightPx?.let { h ->
                            Modifier.height(with(density) { h.toDp() })
                        } ?: Modifier
                    )
                    .clipToBounds(),
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight(align = Alignment.Top, unbounded = true),
                    verticalAlignment = Alignment.Top,
                    userScrollEnabled = false,
                ) { page ->
                    val q = questions[page]
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { size ->
                                if (size.height > 0) pageHeights[page] = size.height
                            },
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                    Text(
                        text = q.question,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                    )

                    Column(
                        modifier = Modifier.clip(RoundedCornerShape(24.dp)),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        q.options.forEach { option ->
                            val interaction = remember { MutableInteractionSource() }
                            val pressed by interaction.collectIsPressedAsState()
                            val scale by animateFloatAsState(
                                targetValue = if (pressed) 0.98f else 1f,
                                animationSpec = spring(dampingRatio = 0.5f, stiffness = 400f),
                                label = "wizard_option_scale",
                            )
                            val isSelected = answers[page] == option
                            Surface(
                                onClick = {
                                    haptics.perform(HapticPattern.Pop)
                                    answers[page] = option
                                    if (page < questions.size - 1) {
                                        coroutineScope.launch {
                                            pagerState.animateScrollToPage(page + 1)
                                        }
                                    }
                                },
                                interactionSource = interaction,
                                color = itemColor,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .graphicsLayer { scaleX = scale; scaleY = scale },
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Text(
                                        text = option,
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }
                        }

                        val customInput = customInputs[page]
                        val submitInteraction = remember { MutableInteractionSource() }
                        val submitPressed by submitInteraction.collectIsPressedAsState()
                        val submitScale by animateFloatAsState(
                            targetValue = if (submitPressed) 0.85f else 1f,
                            animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
                            label = "wizard_submit_scale",
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
                                    onValueChange = { customInputs[page] = it },
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
                                            answers[page] = customInput.trim()
                                            if (page < questions.size - 1) {
                                                coroutineScope.launch {
                                                    pagerState.animateScrollToPage(page + 1)
                                                }
                                            }
                                        }
                                    },
                                    enabled = canSubmit,
                                    interactionSource = submitInteraction,
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
                                        imageVector = if (page < questions.size - 1) {
                                            Icons.Rounded.Send
                                        } else {
                                            Icons.Rounded.Check
                                        },
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(questions.size) { index ->
                        val dotWidth by animateDpAsState(
                            targetValue = if (index == pagerState.currentPage) 16.dp else 6.dp,
                            animationSpec = spring(dampingRatio = 0.85f, stiffness = 400f),
                            label = "dot_width_$index",
                        )
                        Surface(
                            modifier = Modifier.size(width = dotWidth, height = 6.dp),
                            shape = CircleShape,
                            color = if (index == pagerState.currentPage) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                            },
                        ) {}
                    }
                }
            }

            val isLast = pagerState.currentPage == questions.size - 1
            val allAnswered = answers.all { !it.isNullOrBlank() }
            val canGoPrev = pagerState.currentPage > 0
            val nextEnabled = if (isLast) allAnswered else true

            val prevInteraction = remember { MutableInteractionSource() }
            val prevPressed by prevInteraction.collectIsPressedAsState()
            val prevScale by animateFloatAsState(
                targetValue = if (prevPressed) 0.85f else 1f,
                animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
                label = "wizard_prev_scale",
            )

            val nextInteraction = remember { MutableInteractionSource() }
            val nextPressed by nextInteraction.collectIsPressedAsState()
            val nextScale by animateFloatAsState(
                targetValue = if (nextPressed) 0.85f else 1f,
                animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
                label = "wizard_next_scale",
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    onClick = {
                        haptics.perform(HapticPattern.Pop)
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage - 1)
                        }
                    },
                    enabled = canGoPrev,
                    interactionSource = prevInteraction,
                    modifier = Modifier
                        .weight(1f)
                        .graphicsLayer { scaleX = prevScale; scaleY = prevScale },
                    shape = AppShapes.ButtonPill,
                ) {
                    Text(text = stringResource(R.string.ask_user_wizard_previous))
                }

                FilledTonalButton(
                    onClick = {
                        if (isLast) {
                            haptics.perform(HapticPattern.Success)
                            completeWithAnimation(answers.map { it ?: "" }.joinToString("\n---\n"))
                        } else {
                            haptics.perform(HapticPattern.Pop)
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        }
                    },
                    enabled = nextEnabled,
                    interactionSource = nextInteraction,
                    modifier = Modifier
                        .weight(1f)
                        .graphicsLayer { scaleX = nextScale; scaleY = nextScale },
                    shape = AppShapes.ButtonPill,
                ) {
                    Text(
                        text = if (isLast) {
                            stringResource(R.string.ask_user_submit)
                        } else {
                            stringResource(R.string.ask_user_wizard_next)
                        }
                    )
                }
            }
        }
    }
}
