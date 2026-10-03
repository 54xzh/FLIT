package me.rerere.rikkahub.ui.components.interactive

import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.VectorizedFiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 记录完整高度变化，底部栏无需从逐帧位移猜测变化幅度。 */
internal class InteractiveHeightMotionState(val fadeThresholdPx: Int) {
    private val targets = mutableMapOf<Any, Pair<Int, Int>>()
    var largeChangeVersion: Long = 0
        private set

    fun reportTarget(owner: Any, initialHeight: Int, targetHeight: Int) {
        val target = initialHeight to targetHeight
        if (targets[owner] == target) return
        targets[owner] = target
        if (abs(targetHeight - initialHeight) >= fadeThresholdPx) largeChangeVersion++
    }

    fun remove(owner: Any) {
        targets.remove(owner)
    }
}

internal val LocalInteractiveHeightMotion = compositionLocalOf<InteractiveHeightMotionState?> { null }

/** 只约束尺寸过渡：收缩不越过目标，增高的回弹最多为指定像素数。 */
internal fun interactiveHeightAnimationSpec(
    initialSize: IntSize,
    targetSize: IntSize,
    maxGrowthOvershootPx: Int,
): FiniteAnimationSpec<IntSize> {
    val growth = targetSize.height - initialSize.height
    val overshoot = maxGrowthOvershootPx.coerceAtLeast(0)
    val damping = if (growth <= 0 || overshoot == 0) {
        1f
    } else {
        // 回弹距离按高度差放大；据允许的回弹比例提高阻尼，避免大幅增高甩动底部栏。
        val ratio = (overshoot.toDouble() / growth).coerceIn(0.000001, 0.999999)
        val logRatio = -ln(ratio)
        (logRatio / sqrt(Math.PI * Math.PI + logRatio * logRatio)).toFloat().coerceAtLeast(0.7f)
    }
    val springSpec = spring<IntSize>(dampingRatio = damping, stiffness = 400f)
    return object : FiniteAnimationSpec<IntSize> {
        override fun <V : AnimationVector> vectorize(converter: TwoWayConverter<IntSize, V>): VectorizedFiniteAnimationSpec<V> {
            val animation = springSpec.vectorize(converter)
            val widthAnimation = spring<IntSize>(dampingRatio = 0.5f, stiffness = 400f).vectorize(converter)
            return object : VectorizedFiniteAnimationSpec<V> by animation {
                private fun heightBounds(initialValue: V, targetValue: V): IntRange {
                    // 连续切换时从屏幕上的当前高度接续，不能跳回上一页的测量高度。
                    val initialHeight = converter.convertFromVector(initialValue).height
                    val targetHeight = converter.convertFromVector(targetValue).height
                    val extra = if (targetHeight > initialHeight) overshoot else 0
                    return min(initialHeight, targetHeight)..(max(initialHeight, targetHeight) + extra)
                }

                override fun getValueFromNanos(playTimeNanos: Long, initialValue: V, targetValue: V, initialVelocity: V): V {
                    val value = animation.getValueFromNanos(playTimeNanos, initialValue, targetValue, initialVelocity)
                    val size = converter.convertFromVector(value)
                    val height = size.height.coerceIn(heightBounds(initialValue, targetValue))
                    val width = converter.convertFromVector(
                        widthAnimation.getValueFromNanos(playTimeNanos, initialValue, targetValue, initialVelocity)
                    ).width
                    return converter.convertToVector(IntSize(width, height))
                }

                override fun getVelocityFromNanos(playTimeNanos: Long, initialValue: V, targetValue: V, initialVelocity: V): V {
                    val velocity = converter.convertFromVector(
                        animation.getVelocityFromNanos(playTimeNanos, initialValue, targetValue, initialVelocity)
                    )
                    val widthVelocity = converter.convertFromVector(
                        widthAnimation.getVelocityFromNanos(playTimeNanos, initialValue, targetValue, initialVelocity)
                    ).width
                    val height = converter.convertFromVector(
                        animation.getValueFromNanos(playTimeNanos, initialValue, targetValue, initialVelocity)
                    ).height
                    // 快速反向切换时也不能携带越界的高度速度，宽度动画沿用原来的速度。
                    return converter.convertToVector(IntSize(widthVelocity, if (height in heightBounds(initialValue, targetValue)) velocity.height else 0))
                }

                override fun getDurationNanos(initialValue: V, targetValue: V, initialVelocity: V): Long {
                    return max(
                        animation.getDurationNanos(initialValue, targetValue, initialVelocity),
                        widthAnimation.getDurationNanos(initialValue, targetValue, initialVelocity),
                    )
                }
            }
        }
    }
}
