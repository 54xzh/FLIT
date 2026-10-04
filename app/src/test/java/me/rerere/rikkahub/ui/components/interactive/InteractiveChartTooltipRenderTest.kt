package me.rerere.rikkahub.ui.components.interactive

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 在本机模拟 Android 绘制和触摸，不安装应用或连接设备。 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w400dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InteractiveChartTooltipRenderTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var controller: ChartTooltipController
    private var anchorShift by mutableStateOf(Offset.Zero)

    private fun render(constrainViewport: Boolean = false) {
        compose.setContent {
            MaterialTheme {
                InteractiveChartTooltipScope {
                    controller = LocalChartTooltipController.current ?: error("Missing tooltip host")
                    Box(Modifier.fillMaxSize().background(Color.White).testTag("page")) {
                        val owner = remember { Any() }
                        var selection by remember { mutableStateOf<ChartSelection?>(null) }
                        var bounds by remember { mutableStateOf(Rect.Zero) }
                        Box(Modifier.offset(40.dp, 180.dp).size(200.dp, 120.dp).testTag("chart")
                            .onGloballyPositioned {
                                bounds = Rect(it.positionInWindow(), Size(it.size.width.toFloat(), it.size.height.toFloat()))
                            }
                            .pointerInput(Unit) {
                                detectTapGestures { tap ->
                                    selection = ChartSelection(0, if (tap.x < size.width / 2f) 0 else 1)
                                    controller.select(owner) { selection = null }
                                }
                            })
                        selection?.let { selected ->
                            InteractiveChartTooltip(owner, selected,
                                ChartSelectionDetails(if (selected.pointIndex == 0) "Food" else "Work", "", 120.0, null), "120", bounds,
                                { size, _ -> Offset(size.width / 2f, size.height / 2f) + anchorShift },
                                onDismiss = { controller.release(owner); selection = null })
                        }
                        InteractiveChartTooltipLayer(Modifier.matchParentSize().then(
                            if (constrainViewport) Modifier.padding(start = 40.dp, top = 80.dp, bottom = 100.dp) else Modifier,
                        ))
                    }
                }
            }
        }
    }

    private fun assertCapsuleDrawn() {
        compose.waitForIdle()
        lateinit var bounds: Rect
        compose.runOnIdle {
            assertNotNull("Selection disappeared before rendering", controller.entry)
            bounds = controller.capsuleBounds
            assertTrue("Visible capsule has no click bounds", bounds.width > 0f && bounds.height > 0f)
        }
        val page = compose.onNodeWithTag("page")
        val origin = page.fetchSemanticsNode().boundsInWindow.topLeft
        val pixels = page.captureToImage().toPixelMap()
        val x = (bounds.center.x - origin.x).toInt().coerceIn(0, pixels.width - 1)
        val y = (bounds.bottom - origin.y - 4f).toInt().coerceIn(0, pixels.height - 1)
        assertTrue("Capsule bounds exist but page pixels are still white", pixels[x, y].red < 0.75f)
    }

    @Test fun `first chart tap draws a visible capsule and another node of the same size stays visible`() {
        render()
        compose.onNodeWithTag("chart").performTouchInput { click(Offset(width * 0.25f, height / 2f)) }
        assertCapsuleDrawn()
        compose.onNodeWithTag("chart").performTouchInput { click(Offset(width * 0.75f, height / 2f)) }
        assertCapsuleDrawn()
        compose.runOnIdle { assertEquals("Work", controller.entry?.detail?.category) }
    }

    @Test fun `pill taps and swipes preserve the capsule while blank taps dismiss it`() {
        render()
        compose.onNodeWithTag("chart").performTouchInput { click(center) }
        assertCapsuleDrawn()
        compose.onNodeWithText("120", useUnmergedTree = true).performTouchInput { click(center) }
        assertCapsuleDrawn()
        compose.onNodeWithTag("page").performTouchInput { swipe(Offset(300f, 500f), Offset(300f, 400f)) }
        assertCapsuleDrawn()
        compose.onNodeWithTag("page").performTouchInput { click(Offset(300f, 500f)) }
        compose.runOnIdle { assertNull(controller.entry) }
        compose.onNodeWithText("120", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun `capsule follows movement and dismisses when it crosses the top bar boundary`() {
        render(constrainViewport = true)
        compose.onNodeWithTag("chart").performTouchInput { click(center) }
        assertCapsuleDrawn()
        compose.runOnIdle { anchorShift = Offset(0f, 10f) }
        assertCapsuleDrawn()
        // 节点还在聊天区域里，但胶囊越过顶栏下沿也必须关闭。
        compose.runOnIdle { anchorShift = Offset(0f, -150f) }
        compose.runOnIdle { assertNull(controller.entry) }
    }

    @Test fun `first tap near the top bar places the capsule below the point`() {
        anchorShift = Offset(0f, -150f)
        render(constrainViewport = true)
        compose.onNodeWithTag("chart").performTouchInput { click(center) }
        assertCapsuleDrawn()
        compose.runOnIdle {
            assertTrue(controller.capsuleBounds.top >= 90f)
            assertTrue(controller.capsuleBounds.left >= 40f)
            assertTrue(controller.capsuleBounds.bottom <= 700f)
        }
    }
}
