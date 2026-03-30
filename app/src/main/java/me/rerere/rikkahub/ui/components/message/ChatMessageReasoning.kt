package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import me.rerere.ai.provider.Model
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.data.datastore.getEffectiveDisplaySetting
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.utils.extractGeminiThinkingTitle
import me.rerere.rikkahub.utils.extractGeminiLastSection
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

enum class ReasoningCardState(val expanded: Boolean) {
    Collapsed(false),
    Preview(true),
    Expanded(true),
}

@Composable
fun ChatMessageReasoning(
    reasoning: UIMessagePart.Reasoning,
    model: Model?,
    assistant: Assistant?,
    modifier: Modifier = Modifier,
    fadeHeight: Float = 64f,
) {
    var expandState by remember { mutableStateOf(ReasoningCardState.Collapsed) }
    val scrollState = rememberScrollState()
    val settings = LocalSettings.current
    val effectiveDisplay = settings.getEffectiveDisplaySetting()
    val loading = reasoning.finishedAt == null
    val isGemini = model != null && ModelRegistry.GEMINI_SERIES.match(model.modelId)

    LaunchedEffect(reasoning.reasoning, loading) {
        if (loading) {
            if (!expandState.expanded) expandState = ReasoningCardState.Preview
            if (!isGemini) {
                scrollState.animateScrollTo(scrollState.maxValue)
            }
        } else {
            if (expandState.expanded) {
                expandState = if (effectiveDisplay.autoCloseThinking) {
                    ReasoningCardState.Collapsed
                } else {
                    ReasoningCardState.Expanded
                }
            }
        }
    }

    var duration by remember(reasoning.finishedAt, reasoning.createdAt) {
        mutableStateOf(
            value = reasoning.finishedAt?.let { endTime ->
                endTime - reasoning.createdAt
            } ?: (Clock.System.now() - reasoning.createdAt)
        )
    }

    LaunchedEffect(loading) {
        if (loading) {
            while (isActive) {
                duration = (reasoning.finishedAt ?: Clock.System.now()) - reasoning.createdAt
                delay(50)
            }
        }
    }

    fun toggle() {
        expandState = if (loading) {
            if (expandState == ReasoningCardState.Expanded) ReasoningCardState.Preview else ReasoningCardState.Expanded
        } else {
            if (expandState == ReasoningCardState.Expanded) ReasoningCardState.Collapsed else ReasoningCardState.Expanded
        }
    }

    Surface(
        modifier = modifier,
        shape = me.rerere.rikkahub.ui.theme.AppShapes.CardLarge,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(
            modifier = Modifier
                .padding(8.dp)
                .clipToBounds()
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = 0.7f,
                        stiffness = 300f
                    )
                ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Press feedback for reasoning header
            val headerInteractionSource = remember { MutableInteractionSource() }
            val isHeaderPressed by headerInteractionSource.collectIsPressedAsState()
            val headerScale by animateFloatAsState(
                targetValue = if (isHeaderPressed) 0.95f else 1f,
                animationSpec = spring(
                    dampingRatio = 0.4f,
                    stiffness = 400f
                ),
                label = "header_scale"
            )
            val headerAlpha by animateFloatAsState(
                targetValue = if (isHeaderPressed) 0.7f else 1f,
                animationSpec = spring(
                    dampingRatio = 0.6f,
                    stiffness = 300f
                ),
                label = "header_alpha"
            )
            Row(
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = headerScale
                        scaleY = headerScale
                        alpha = headerAlpha
                    }
                    .clip(MaterialTheme.shapes.small)
                    .let { if (expandState.expanded) it.fillMaxWidth() else it.wrapContentWidth() }
                    .clickable(
                        onClick = {
                            toggle()
                        },
                        indication = LocalIndication.current,
                        interactionSource = headerInteractionSource
                    )
                    .padding(horizontal = 8.dp)
                    .semantics {
                        role = Role.Button
                    },
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
                    text = stringResource(R.string.chat_message_reasoning),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.shimmer(
                        isLoading = loading
                    )
                )
                if (duration > 0.seconds) {
                    Text(
                        text = "(${duration.toString(DurationUnit.SECONDS, 1)})",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.shimmer(
                            isLoading = loading
                        )
                    )
                }
                Spacer(
                    modifier = if (expandState.expanded) Modifier.weight(1f) else Modifier.width(4.dp)
                )
                Icon(
                    imageVector = when (expandState) {
                        ReasoningCardState.Collapsed -> Icons.Rounded.KeyboardArrowDown
                        ReasoningCardState.Expanded -> Icons.Rounded.KeyboardArrowUp
                        ReasoningCardState.Preview -> Icons.Rounded.KeyboardArrowDown
                    },
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.secondary,
                )
            }

            if (expandState.expanded) {
                if (isGemini && loading && expandState == ReasoningCardState.Preview) {
                    // Gemini Preview: 用 AnimatedContent 做段落切换动画
                    val sectionTitle = remember(reasoning.reasoning) {
                        reasoning.reasoning.extractGeminiThinkingTitle() ?: ""
                    }
                    AnimatedContent(
                        targetState = sectionTitle,
                        transitionSpec = {
                            (slideInVertically { it } + fadeIn()) togetherWith
                                    (slideOutVertically { -it } + fadeOut())
                        },
                    ) {
                        MarkdownBlock(
                            content = reasoning.reasoning.extractGeminiLastSection()
                                .replaceRegexes(
                                    assistant = assistant,
                                    scope = AssistantAffectScope.ASSISTANT,
                                    visual = true,
                                ),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .let {
                                if (expandState == ReasoningCardState.Preview) {
                                    it
                                        .graphicsLayer { alpha = 0.99f }
                                        .drawWithCache {
                                            val brush = Brush.verticalGradient(
                                                startY = 0f,
                                                endY = size.height,
                                                colorStops = arrayOf(
                                                    0.0f to Color.Transparent,
                                                    (fadeHeight / size.height) to Color.Black,
                                                    (1 - fadeHeight / size.height) to Color.Black,
                                                    1.0f to Color.Transparent
                                                )
                                            )
                                            onDrawWithContent {
                                                drawContent()
                                                drawRect(
                                                    brush = brush,
                                                    size = Size(size.width, size.height),
                                                    blendMode = BlendMode.DstIn
                                                )
                                            }
                                        }
                                        .heightIn(max = 100.dp)
                                        .verticalScroll(scrollState)
                                } else {
                                    it
                                }
                            }
                    ) {
                        MarkdownBlock(
                            content = reasoning.reasoning.replaceRegexes(
                                assistant = assistant,
                                scope = AssistantAffectScope.ASSISTANT,
                                visual = true,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            // 如果是gemini且完全折叠, 显示当前的思考标题
            if (loading && isGemini && !expandState.expanded) {
                GeminiReasoningTitle(reasoning = reasoning)
            }
        }
    }
}

@Composable
private fun GeminiReasoningTitle(reasoning: UIMessagePart.Reasoning) {
    val title = reasoning.reasoning.extractGeminiThinkingTitle()
    if (title != null) {
        AnimatedContent(
            targetState = title,
            transitionSpec = {
                (slideInVertically { height -> height } + fadeIn()).togetherWith(slideOutVertically { height -> -height } + fadeOut())
            }
        ) {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .shimmer(true),
            )
        }
    }
}
