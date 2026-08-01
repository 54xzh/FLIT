package me.rerere.rikkahub.ui.components.message

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceFileTooltipPositionTest {
    @Test
    fun `keeps a tooltip above an offscreen right card inside the window`() {
        assertEquals(
            IntOffset(x = 220, y = 0),
            calculateWorkspaceFileTooltipPosition(
                anchorBounds = IntRect(left = 290, top = 40, right = 450, bottom = 80),
                windowSize = IntSize(width = 300, height = 600),
                popupContentSize = IntSize(width = 80, height = 40),
            ),
        )
    }

    @Test
    fun `places a tooltip below when there is no space above`() {
        assertEquals(
            IntOffset(x = 90, y = 60),
            calculateWorkspaceFileTooltipPosition(
                anchorBounds = IntRect(left = 100, top = 20, right = 180, bottom = 60),
                windowSize = IntSize(width = 360, height = 320),
                popupContentSize = IntSize(width = 100, height = 80),
            ),
        )
    }

    @Test
    fun `clamps a tooltip when neither side of the card has enough space`() {
        assertEquals(
            IntOffset(x = 0, y = 0),
            calculateWorkspaceFileTooltipPosition(
                anchorBounds = IntRect(left = -40, top = 100, right = 120, bottom = 140),
                windowSize = IntSize(width = 300, height = 300),
                popupContentSize = IntSize(width = 360, height = 400),
            ),
        )
    }
}
