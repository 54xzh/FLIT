package me.rerere.rikkahub.ui.components.interactive

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow

internal val LocalChartTooltipController = compositionLocalOf<ChartTooltipController?> { null }

internal class ChartTooltipController {
    private var owner: Any? = null
    private var dismiss: (() -> Unit)? = null
    var revision = 0L
        private set
    var entry by mutableStateOf<ChartTooltipEntry?>(null)
        private set
    var capsuleBounds = Rect.Zero

    fun select(owner: Any, onDismiss: () -> Unit) {
        if (this.owner !== owner) dismiss()
        this.owner = owner
        dismiss = onDismiss
        revision++
    }

    fun publish(owner: Any, entry: ChartTooltipEntry) {
        if (this.owner === owner) this.entry = entry
    }

    fun release(owner: Any) {
        if (this.owner === owner) dismiss()
    }

    fun tap(position: Offset, revisionAtDown: Long) {
        // 子图表已处理本次点击时，保留它刚选中的节点，避免立刻关闭新胶囊。
        if (revision == revisionAtDown && !capsuleBounds.contains(position)) dismiss()
    }

    private fun dismiss() {
        val previous = dismiss
        owner = null
        dismiss = null
        entry = null
        capsuleBounds = Rect.Zero
        revision++
        previous?.invoke()
    }
}

/** 旁观手势，不消耗事件；滑动、拖动和长按都不作为空白点击。 */
internal class ChartTooltipTapGesture(private val down: Offset, private val downTime: Long, private val slop: Float) {
    private var moved = false
    private var cancelled = false
    fun update(position: Offset, consumedMovement: Boolean = false, multiplePointers: Boolean = false) {
        moved = moved || (position - down).getDistance() > slop || consumedMovement
        cancelled = cancelled || multiplePointers
    }
    fun isTap(upTime: Long, longPressTimeout: Long): Boolean =
        !moved && !cancelled && upTime - downTime in 0..longPressTimeout
}

@Composable
internal fun InteractiveChartTooltipScope(content: @Composable BoxScope.() -> Unit) {
    val controller = remember { ChartTooltipController() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    CompositionLocalProvider(LocalChartTooltipController provides controller) {
        Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }
            .pointerInput(controller) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val revision = controller.revision
                    val gesture = ChartTooltipTapGesture(down.position, down.uptimeMillis, viewConfiguration.touchSlop)
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val final = awaitPointerEvent(PointerEventPass.Final)
                        val finalChange = final.changes.firstOrNull { it.id == down.id } ?: break
                        gesture.update(change.position,
                            consumedMovement = change.pressed && change.previousPressed && finalChange.isConsumed && change.position != change.previousPosition,
                            multiplePointers = event.changes.any { it.id != down.id && (it.pressed || it.previousPressed) })
                        if (change.changedToUpIgnoreConsumed()) {
                            if (gesture.isTap(change.uptimeMillis, viewConfiguration.longPressTimeoutMillis)) {
                                controller.tap(origin + change.position, revision)
                            }
                            break
                        }
                        if (!change.pressed) break
                    }
                }
            }, content = content)
    }
}
