package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.abs
import me.rerere.rikkahub.ui.components.interactive.InteractiveHeightMotionState
import me.rerere.rikkahub.ui.components.interactive.LocalInteractiveHeightMotion

// 临界阻尼保留柔和渐变、避免透明度回弹；相比原来的 700f 放慢约两倍。
internal val MessageFooterFadeSpec = spring<Float>(dampingRatio = 1f, stiffness = 160f)

@Composable
internal fun Modifier.messageFooterMotion(
    enabled: Boolean,
    completionOffset: () -> Float,
    heightMotion: InteractiveHeightMotionState? = null,
): Modifier {
    if (!enabled) return this
    val motion = heightMotion ?: LocalInteractiveHeightMotion.current
    val thresholdPx = motion?.fadeThresholdPx ?: with(LocalDensity.current) { 48.dp.roundToPx() }
    return this
        .then(MessageFooterFadeElement(motion, thresholdPx))
        .graphicsLayer { translationY = completionOffset() }
}

private data class MessageFooterFadeElement(
    val heightMotion: InteractiveHeightMotionState?,
    val thresholdPx: Int,
) : ModifierNodeElement<MessageFooterFadeNode>() {
    override fun create() = MessageFooterFadeNode(heightMotion, thresholdPx)
    override fun update(node: MessageFooterFadeNode) {
        node.update(heightMotion, thresholdPx)
    }
    override fun InspectorInfo.inspectableProperties() {
        name = "messageFooterFade"
    }
}

/** 布局当帧就阻止新位置以旧透明度绘制，淡入只改变绘制，不影响消息高度。 */
private class MessageFooterFadeNode(
    private var heightMotion: InteractiveHeightMotionState?,
    private var thresholdPx: Int,
) : Modifier.Node(), LayoutAwareModifierNode, DrawModifierNode {
    private val alpha = Animatable(1f)
    private val paint = Paint()
    private var lastTopPx: Int? = null
    private var fadePending = false
    private var fadeActive = false
    private var fadeJob: Job? = null
    private var placementVersion = 0L
    private var lastLargeChangeVersion = heightMotion?.largeChangeVersion ?: 0L

    fun update(heightMotion: InteractiveHeightMotionState?, thresholdPx: Int) {
        if (this.heightMotion !== heightMotion) {
            lastLargeChangeVersion = heightMotion?.largeChangeVersion ?: 0L
            lastTopPx = null
        }
        this.heightMotion = heightMotion
        this.thresholdPx = thresholdPx
    }

    override fun onPlaced(coordinates: LayoutCoordinates) {
        // 只比较消息内的位置，列表滚动和生成完成时的绘制位移不会触发淡入。
        val topPx = coordinates.positionInParent().y.roundToInt()
        val previousTopPx = lastTopPx
        lastTopPx = topPx
        if (previousTopPx == null) {
            lastLargeChangeVersion = heightMotion?.largeChangeVersion ?: 0L
            return
        }
        if (previousTopPx == topPx) return
        placementVersion++
        val largeChangeVersion = heightMotion?.largeChangeVersion ?: 0L
        val hasLargeTargetChange = lastLargeChangeVersion != largeChangeVersion
        lastLargeChangeVersion = largeChangeVersion

        // A2UI 的弹簧会连续更新位置，一次淡入覆盖整个过渡，避免每帧归零而迟迟不显示。
        if (fadeJob?.isActive == true) return
        // 标签页看起止高度差；图片加载等直接改变布局的场景则看这次实际位移。
        if (!hasLargeTargetChange && abs(topPx - previousTopPx) < thresholdPx) return

        fadeJob?.cancel()
        fadePending = true
        fadeActive = true
        invalidateDraw()
        fadeJob = coroutineScope.launch {
            alpha.snapTo(0f)
            fadePending = false
            invalidateDraw()
            alpha.animateTo(1f, MessageFooterFadeSpec) {
                invalidateDraw()
            }
            fadeActive = false
            invalidateDraw()
            // 淡入结束后继续覆盖尚未收敛的组件弹簧，停稳后才允许下一次淡入。
            do {
                val version = placementVersion
                delay(80)
            } while (version != placementVersion)
        }
    }

    override fun ContentDrawScope.draw() {
        // 协程还没开始或动画被连续高度变化打断时，也不会漏出一帧不透明内容。
        if (fadePending) return
        val opacity = if (fadeActive) alpha.value else 1f
        if (opacity <= 0f) return
        if (opacity >= 1f) {
            drawContent()
        } else {
            paint.alpha = opacity
            val canvas = drawContext.canvas
            canvas.saveLayer(Rect(Offset.Zero, size), paint)
            try {
                drawContent()
            } finally {
                canvas.restore()
            }
        }
    }

    override fun onDetach() {
        fadeJob?.cancel()
        fadeJob = null
        lastTopPx = null
        fadePending = false
        fadeActive = false
        placementVersion = 0L
        lastLargeChangeVersion = heightMotion?.largeChangeVersion ?: 0L
    }
}
