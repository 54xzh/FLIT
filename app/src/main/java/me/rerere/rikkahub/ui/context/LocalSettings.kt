package me.rerere.rikkahub.ui.context

import androidx.compose.runtime.compositionLocalOf
import me.rerere.rikkahub.data.datastore.Settings

// Settings 会随用户操作频繁变化（含流式期间的偏好写入）。
// 用普通 compositionLocalOf：值变化时只重组真正读取它的作用域，
// 而不是 staticCompositionLocalOf 那样使整个 Provider 子树全量失效。
val LocalSettings = compositionLocalOf<Settings> {
    error("No SettingsStore provided")
}
