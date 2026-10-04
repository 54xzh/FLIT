package me.rerere.rikkahub.ui.components.interactive

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.theme.AppShapes
import kotlin.math.roundToInt

internal class ChartTooltipEntry(
    val owner: Any,
    val selection: ChartSelection,
    val detail: ChartSelectionDetails,
    val valueText: String,
    val anchor: () -> Offset?,
    val onDismiss: () -> Unit,
)

/** 图表只提供详情和锚点，实际浮层由聊天区域绘制。 */
@Composable
internal fun InteractiveChartTooltip(
    owner: Any,
    selection: ChartSelection,
    detail: ChartSelectionDetails,
    valueText: String,
    anchorBounds: Rect,
    anchor: (Size, Float) -> Offset?,
    onDismiss: () -> Unit,
) {
    val controller = LocalChartTooltipController.current ?: return
    val density = LocalDensity.current.density
    val currentAnchor by rememberUpdatedState(anchor)
    val currentDismiss by rememberUpdatedState(onDismiss)
    val entry = remember(owner, selection, detail, valueText, anchorBounds, density) {
        ChartTooltipEntry(owner, selection, detail, valueText,
            anchor = { currentAnchor(anchorBounds.size, density)?.plus(anchorBounds.topLeft) },
            onDismiss = { currentDismiss() })
    }
    SideEffect { controller.publish(owner, entry) }
    DisposableEffect(controller, owner) { onDispose { controller.release(owner) } }
}

/** 浮层属于页面内容，受顶栏、侧栏和输入框边界约束，不创建独立窗口。 */
@Composable
internal fun InteractiveChartTooltipLayer(modifier: Modifier = Modifier) {
    val controller = LocalChartTooltipController.current ?: return
    var viewport by remember { mutableStateOf(Rect.Zero) }
    Box(modifier.clipToBounds().onGloballyPositioned {
        viewport = Rect(it.positionInWindow(), Size(it.size.width.toFloat(), it.size.height.toFloat()))
    }) {
        val entry = controller.entry ?: return@Box
        if (viewport.width <= 0f || viewport.height <= 0f) return@Box
        val density = LocalDensity.current
        val gap = with(density) { 8.dp.roundToPx() }
        var capsuleSize by remember { mutableStateOf(IntSize.Zero) }
        var above by remember(entry.owner, entry.selection) { mutableStateOf<Boolean?>(null) }
        // 两个 effect 必须使用同一帧的方向；先更新 above 再读旧 visible 会误关闭胶囊。
        val placementAbove = above
        val measuredSize = capsuleSize
        val point = entry.anchor()
        val ready = measuredSize != IntSize.Zero && point != null
        LaunchedEffect(ready, entry.owner, entry.selection) {
            if (ready && placementAbove == null && point != null) {
                above = point.y - measuredSize.height - gap >= viewport.top
            }
        }
        val position = point?.let { interactiveChartCapsuleOffset(it, viewport, measuredSize, gap, placementAbove ?: true) }
            ?: IntOffset.Zero
        val visible = ready && placementAbove != null && point != null &&
            interactiveChartCapsuleVisible(point, viewport, position, measuredSize)
        LaunchedEffect(ready, placementAbove, visible, entry) {
            if (ready && placementAbove != null && !visible) entry.onDismiss()
        }
        SideEffect {
            // 透明度变化不一定重新布局，点击范围不能只依赖 onGloballyPositioned。
            controller.capsuleBounds = if (visible) Rect(
                viewport.topLeft + Offset(position.x.toFloat(), position.y.toFloat()),
                Size(measuredSize.width.toFloat(), measuredSize.height.toFloat()),
            ) else Rect.Zero
        }
        val scale = remember { Animatable(0.85f) }
        LaunchedEffect(entry.owner, entry.selection) {
            scale.snapTo(0.85f)
            scale.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 300f))
        }
        Surface(
            modifier = Modifier.widthIn(max = with(density) { viewport.width.coerceAtLeast(1f).toDp() })
                .offset { position }
                .pointerInput(Unit) { detectTapGestures { } }
                .onSizeChanged { capsuleSize = it }
                .graphicsLayer { alpha = if (visible) 1f else 0f; scaleX = scale.value; scaleY = scale.value }
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            shape = AppShapes.ButtonPill,
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shadowElevation = 4.dp,
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val name = listOf(entry.detail.category, entry.detail.series).filter { it.isNotBlank() }.joinToString(" ")
                if (name.isNotBlank()) {
                    Text(name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(entry.valueText, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

internal fun interactiveChartCapsuleOffset(
    point: Offset, viewport: Rect, capsuleSize: IntSize, gap: Int, above: Boolean,
): IntOffset {
    val maxX = (viewport.width.roundToInt() - capsuleSize.width - gap).coerceAtLeast(0)
    val minX = gap.coerceAtMost(maxX)
    return IntOffset(
        (point.x - viewport.left - capsuleSize.width / 2f).roundToInt().coerceIn(minX, maxX),
        (point.y - viewport.top + if (above) -capsuleSize.height - gap else gap).roundToInt(),
    )
}

internal fun interactiveChartCapsuleVisible(point: Offset, viewport: Rect, offset: IntOffset, size: IntSize): Boolean =
    viewport.contains(point) && offset.x >= 0 && offset.y >= 0 &&
        offset.x.toFloat() + size.width <= viewport.width && offset.y.toFloat() + size.height <= viewport.height
