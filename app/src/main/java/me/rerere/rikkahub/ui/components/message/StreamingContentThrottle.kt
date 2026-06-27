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
    var displayedValue by remember(key) { mutableStateOf(value) }
    val latestValue by rememberUpdatedState(value)

    if (intervalMs <= 0L) {
        LaunchedEffect(key, value) {
            displayedValue = value
        }
    } else {
        LaunchedEffect(key, intervalMs) {
            while (isActive) {
                delay(intervalMs)
                displayedValue = latestValue
            }
        }
    }

    return displayedValue
}
