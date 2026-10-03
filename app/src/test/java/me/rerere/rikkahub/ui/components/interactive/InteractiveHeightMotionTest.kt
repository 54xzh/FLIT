package me.rerere.rikkahub.ui.components.interactive

import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class InteractiveHeightMotionTest {
    private val converter = TwoWayConverter<IntSize, AnimationVector2D>(
        convertToVector = { AnimationVector2D(it.width.toFloat(), it.height.toFloat()) },
        convertFromVector = { IntSize(it.v1.roundToInt(), it.v2.roundToInt()) },
    )

    @Test
    fun `shrinking cannot compress below target even after a fast interrupted transition`() {
        val initial = IntSize(300, 600)
        val target = IntSize(300, 200)
        val animation = interactiveHeightAnimationSpec(initial, target, 8).vectorize(converter)
        for (velocity in listOf(-20_000f, 0f, 20_000f)) {
            for (timeMs in 0L..2_000L step 8) {
                val size = converter.convertFromVector(animation.getValueFromNanos(
                    timeMs * 1_000_000, converter.convertToVector(initial), converter.convertToVector(target),
                    AnimationVector2D(0f, velocity),
                ))
                assertTrue("height=${size.height}, time=$timeMs, velocity=$velocity", size.height in 200..600)
            }
        }
    }

    @Test
    fun `growth preserves bounce but caps it for large changes`() {
        val initial = IntSize(300, 100)
        val target = IntSize(300, 1_500)
        val animation = interactiveHeightAnimationSpec(initial, target, 8).vectorize(converter)
        val heights = (0L..2_000L step 8).map { timeMs ->
            converter.convertFromVector(animation.getValueFromNanos(
                timeMs * 1_000_000, converter.convertToVector(initial), converter.convertToVector(target),
                AnimationVector2D(0f, 0f),
            )).height
        }
        assertTrue(heights.any { it > target.height })
        assertTrue(heights.all { it in initial.height..target.height + 8 })
    }

    @Test
    fun `interrupting growth continues from the visible height instead of the old measured height`() {
        val oldMeasuredSize = IntSize(300, 1_500)
        val visibleSize = IntSize(300, 400)
        val target = IntSize(300, 600)
        val animation = interactiveHeightAnimationSpec(oldMeasuredSize, target, 8).vectorize(converter)
        val actual = converter.convertFromVector(animation.getValueFromNanos(
            0, converter.convertToVector(visibleSize), converter.convertToVector(target),
            AnimationVector2D(0f, 3_000f),
        ))
        assertEquals(visibleSize, actual)
    }

    @Test
    fun `height constraints leave the existing width spring intact`() {
        val initial = IntSize(100, 600)
        val target = IntSize(400, 200)
        val animation = interactiveHeightAnimationSpec(initial, target, 8).vectorize(converter)
        val original = spring<IntSize>(dampingRatio = 0.5f, stiffness = 400f).vectorize(converter)
        for (timeMs in 0L..2_000L step 8) {
            val start = converter.convertToVector(initial)
            val end = converter.convertToVector(target)
            val velocity = AnimationVector2D(0f, 0f)
            val actual = converter.convertFromVector(animation.getValueFromNanos(timeMs * 1_000_000, start, end, velocity))
            val expected = converter.convertFromVector(original.getValueFromNanos(timeMs * 1_000_000, start, end, velocity))
            assertEquals(expected.width, actual.width)
        }
    }

    @Test
    fun `small targets do not fade and large targets signal once for the entire movement`() {
        val motion = InteractiveHeightMotionState(fadeThresholdPx = 48)
        val firstTabs = Any()
        motion.reportTarget(firstTabs, 200, 220)
        motion.reportTarget(firstTabs, 220, 200)
        assertEquals(0L, motion.largeChangeVersion)
        motion.reportTarget(firstTabs, 200, 1_000)
        repeat(100) { motion.reportTarget(firstTabs, 200, 1_000) }
        assertEquals(1L, motion.largeChangeVersion)
        motion.reportTarget(firstTabs, 1_000, 200)
        assertEquals(2L, motion.largeChangeVersion)
        motion.reportTarget(Any(), 200, 1_000)
        assertEquals(3L, motion.largeChangeVersion)
    }
}
