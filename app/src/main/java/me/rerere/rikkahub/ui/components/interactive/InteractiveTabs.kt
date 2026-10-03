package me.rerere.rikkahub.ui.components.interactive

import androidx.a2ui.compose.runtime.A2uiComponentScope
import androidx.a2ui.compose.runtime.A2uiComponentState
import androidx.a2ui.compose.runtime.observeA2uiComponentState
import androidx.a2ui.compose.ui.A2uiComponent
import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics

/** 沿用标签页的内容与视觉过渡，仅为高度动画提供方向和回弹边界。 */
internal object InteractiveTabs : A2uiBasicCatalogV1.Tabs {
    @Composable
    override fun A2uiComponentScope.TypedContent(
        tabs: List<A2uiBasicCatalogV1.Tabs.Tab>,
        accessibility: A2uiBasicCatalogV1.AccessibilityAttributes?,
        modifier: Modifier,
    ) {
        if (tabs.isEmpty()) return
        var selectedIndex by remember { mutableIntStateOf(0) }
        val selected = selectedIndex.coerceIn(tabs.indices)
        SideEffect { selectedIndex = selected }
        val haptics = rememberPremiumHaptics()
        val heightMotion = LocalInteractiveHeightMotion.current
        val transitionOwner = remember { Any() }
        DisposableEffect(heightMotion, transitionOwner) {
            onDispose { heightMotion?.remove(transitionOwner) }
        }
        val maxOvershootPx = with(LocalDensity.current) { 8.dp.roundToPx() }
        val enterSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
        val exitSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()

        Column(modifier.interactiveAccessibility(accessibility)) {
            PrimaryTabRow(selectedTabIndex = selected, containerColor = Color.Transparent) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = index == selected,
                        onClick = { haptics.perform(HapticPattern.Pop); selectedIndex = index },
                        modifier = Modifier.clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                        text = {
                            Text(
                                text = tab.title,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
            val tab = tabs[selected]
            val state = observeA2uiComponentState(tab.childId)
            AnimatedContent(
                targetState = state,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                transitionSpec = {
                    (fadeIn(enterSpec) togetherWith fadeOut(exitSpec)).using(SizeTransform { initialSize, targetSize ->
                        heightMotion?.reportTarget(transitionOwner, initialSize.height, targetSize.height)
                        interactiveHeightAnimationSpec(initialSize, targetSize, maxOvershootPx)
                    })
                },
                contentKey = { content ->
                    when (content) {
                        A2uiComponentState.Loading -> "loading"
                        is A2uiComponentState.Error -> "error"
                        is A2uiComponentState.Success -> tab.childId to content.component.type
                    }
                },
                label = "InteractiveTabContent",
            ) { content ->
                when (content) {
                    is A2uiComponentState.Success -> A2uiComponent(component = content.component)
                    is A2uiComponentState.Error -> Text(stringResource(R.string.interactive_components_error),
                        color = MaterialTheme.colorScheme.error)
                    A2uiComponentState.Loading -> Text(stringResource(R.string.interactive_components_loading),
                        modifier = Modifier.fillMaxWidth().height(120.dp))
                }
            }
        }
    }
}
