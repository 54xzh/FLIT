package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
    val coroutineScope = rememberCoroutineScope()
    val itemColor = MaterialTheme.colorScheme.surfaceContainerHigh

    ModalBottomSheet(
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
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

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth(),
                userScrollEnabled = false,
            ) { page ->
                val q = questions[page]
                Column(
                    modifier = Modifier.fillMaxWidth(),
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
                            Surface(
                                onClick = {
                                    haptics.perform(HapticPattern.Pop)
                                    answers[page] = option
                                    if (page < questions.size - 1) {
                                        coroutineScope.launch {
                                            pagerState.animateScrollToPage(page + 1)
                                        }
                                    } else {
                                        onComplete(answers.filterNotNull().joinToString("\n---\n"))
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
                                Text(
                                    text = option,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                )
                            }
                        }

                        var customInput by remember { mutableStateOf("") }
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
                                            answers[page] = customInput.trim()
                                            if (page < questions.size - 1) {
                                                customInput = ""
                                                coroutineScope.launch {
                                                    pagerState.animateScrollToPage(page + 1)
                                                }
                                            } else {
                                                onComplete(answers.filterNotNull().joinToString("\n---\n"))
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
                        Surface(
                            modifier = Modifier.size(
                                width = if (index == pagerState.currentPage) 16.dp else 6.dp,
                                height = 6.dp,
                            ),
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

            TextButton(
                onClick = {
                    haptics.perform(HapticPattern.Pop)
                    if (pagerState.currentPage < questions.size - 1) {
                        answers[pagerState.currentPage] = ""
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    } else {
                        onComplete(answers.map { it ?: "" }.joinToString("\n---\n"))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = AppShapes.ButtonPill,
            ) {
                Text(
                    text = if (pagerState.currentPage < questions.size - 1) {
                        stringResource(R.string.ask_user_wizard_skip)
                    } else {
                        stringResource(R.string.ask_user_submit)
                    }
                )
            }
        }
    }
}
