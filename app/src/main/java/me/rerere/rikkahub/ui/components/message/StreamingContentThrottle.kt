package me.rerere.rikkahub.ui.components.message

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal fun <T> rememberThrottledStreamingValue(
    value: T,
    intervalMs: Long,
    key: Any? = Unit,
): T {
    // 可见场景（intervalMs <= 0）直接透传最新值：
    // 若经由 state + LaunchedEffect 回写，每个流式分块都会多一帧延迟和一次额外重组。
    if (intervalMs <= 0L) return value

    // 离屏节流：按固定间隔把最新值写入展示状态。
    // 从透传切换到节流时（消息滚出屏幕），remember 以当前值初始化，不会回退旧内容；
    // 切回透传时 state 被丢弃，立即显示实时值，保证最终内容不丢。
    var displayedValue by remember(key) { mutableStateOf(value) }
    val latestValue by rememberUpdatedState(value)
    LaunchedEffect(key, intervalMs) {
        while (isActive) {
            delay(intervalMs)
            displayedValue = latestValue
        }
    }
    return displayedValue
}
