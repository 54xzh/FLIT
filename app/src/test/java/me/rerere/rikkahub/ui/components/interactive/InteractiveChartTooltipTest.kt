package me.rerere.rikkahub.ui.components.interactive

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractiveChartTooltipTest {
    private fun entry(owner: Any) = ChartTooltipEntry(owner, ChartSelection(0, 0),
        ChartSelectionDetails("A", "", 120.0, null), "120", { Offset(100f, 100f) }, {})

    @Test fun `selecting another chart clears the old selection and ignores stale publication`() {
        val controller = ChartTooltipController()
        val first = Any()
        val second = Any()
        var dismissed = false
        controller.select(first) { dismissed = true }
        controller.publish(first, entry(first))
        controller.select(second) {}
        assertTrue(dismissed)
        assertNull(controller.entry)
        val current = entry(second)
        controller.publish(second, current)
        controller.publish(first, entry(first))
        controller.release(first)
        assertSame(current, controller.entry)
    }

    @Test fun `outside taps dismiss but taps on the pill preserve selection`() {
        val controller = ChartTooltipController()
        val owner = Any()
        var dismissed = false
        controller.select(owner) { dismissed = true }
        controller.publish(owner, entry(owner))
        controller.capsuleBounds = Rect(20f, 20f, 120f, 60f)
        controller.tap(Offset(50f, 30f), controller.revision)
        assertFalse(dismissed)
        controller.tap(Offset(200f, 300f), controller.revision)
        assertTrue(dismissed)
        assertNull(controller.entry)
    }

    @Test fun `tap observer cannot dismiss a node selected during that tap`() {
        val controller = ChartTooltipController()
        val owner = Any()
        var dismissed = false
        val revision = controller.revision
        controller.select(owner) { dismissed = true }
        val current = entry(owner)
        controller.publish(owner, current)
        controller.tap(Offset(200f, 300f), revision)
        assertFalse(dismissed)
        assertSame(current, controller.entry)
        val previousRevision = controller.revision
        controller.select(owner) { dismissed = true }
        controller.tap(Offset(200f, 300f), previousRevision)
        assertFalse(dismissed)
    }

    @Test fun `only short taps dismiss and scrolling remains scrolling after returning to origin`() {
        val tap = ChartTooltipTapGesture(Offset.Zero, 100, 8f)
        tap.update(Offset(3f, 2f))
        assertTrue(tap.isTap(250, 500))
        assertFalse(tap.isTap(700, 500))
        val drag = ChartTooltipTapGesture(Offset.Zero, 100, 8f)
        drag.update(Offset(0f, 20f))
        drag.update(Offset.Zero)
        assertFalse(drag.isTap(250, 500))
        val consumed = ChartTooltipTapGesture(Offset.Zero, 100, 8f)
        consumed.update(Offset(1f, 1f), consumedMovement = true)
        assertFalse(consumed.isTap(250, 500))
        val multiTouch = ChartTooltipTapGesture(Offset.Zero, 100, 8f)
        multiTouch.update(Offset.Zero, multiplePointers = true)
        assertFalse(multiTouch.isTap(250, 500))
    }

    @Test fun `capsule stays within chat viewport and dismisses when either pill or node leaves`() {
        // 模拟左侧栏宽 300、顶栏高 80、底部输入框上沿 700。
        val viewport = Rect(300f, 80f, 1000f, 700f)
        val size = IntSize(120, 36)
        val point = Offset(400f, 180f)
        val offset = interactiveChartCapsuleOffset(point, viewport, size, 8, above = true)
        assertEquals(IntOffset(40, 56), offset)
        assertTrue(interactiveChartCapsuleVisible(point, viewport, offset, size))
        val nearTop = Offset(400f, 85f)
        val below = interactiveChartCapsuleOffset(nearTop, viewport, size, 8, above = false)
        assertEquals(IntOffset(40, 13), below)
        assertTrue(interactiveChartCapsuleVisible(nearTop, viewport, below, size))
        val scrolledUp = interactiveChartCapsuleOffset(nearTop, viewport, size, 8, above = true)
        assertFalse(interactiveChartCapsuleVisible(nearTop, viewport, scrolledUp, size))
        val scrolledDown = Offset(400f, 680f)
        val outsideBottom = interactiveChartCapsuleOffset(scrolledDown, viewport, size, 8, above = false)
        assertFalse(interactiveChartCapsuleVisible(scrolledDown, viewport, outsideBottom, size))
        assertFalse(interactiveChartCapsuleVisible(Offset(299f, 180f), viewport, offset, size))
        assertFalse(interactiveChartCapsuleVisible(point, viewport, offset, IntSize(900, 36)))
        val rightEdge = interactiveChartCapsuleOffset(Offset(999f, 180f), viewport, size, 8, above = true)
        assertEquals(572, rightEdge.x)
    }
}
