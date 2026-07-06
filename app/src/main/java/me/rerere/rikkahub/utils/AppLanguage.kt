package me.rerere.rikkahub.utils

import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import me.rerere.rikkahub.LastChatApp
import me.rerere.rikkahub.data.datastore.AppLanguage

/**
 * 应用界面语言到系统。
 *
 * - [AppLanguage.SYSTEM] 传空 LocaleList，让应用回到跟随系统语言。
 * - 其余项用对应的 BCP-47 语言标签。
 *
 * 实现分两档：
 * - API 33+：直接用平台 `LocaleManager.setApplicationLocales`，最可靠，
 *   不依赖 appcompat delegate 是否已被创建（RouteActivity 是 ComponentActivity，
 *   AppCompatDelegate 的静态路径在它上面不会被自动初始化）。
 * - API 31-32：用 appcompat 的 `AppCompatDelegate.setApplicationLocales` 兜底。
 *
 * RouteActivity 的 configChanges 含 locale|layoutDirection，切换时走就地重配置
 * （Compose 重新组合、资源重新加载），不重建 Activity，避免黑屏。
 *
 * 重要：调用前会先比对「当前已应用的 locale」与目标，一致则直接跳过，
 * 避免冷启动时系统已应用的语言被重复触发一次重配置（表现为启动瞬间的遮罩闪一下）。
 */
fun applyAppLanguage(language: AppLanguage) {
    val tags = language.languageTag
    val systemService = LastChatApp.instance.getSystemService(android.app.LocaleManager::class.java)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // API 33+：平台 LocaleManager
        val current = systemService?.applicationLocales
        val target: LocaleList = if (tags.isNullOrEmpty()) {
            LocaleList.getEmptyLocaleList()
        } else {
            LocaleList.forLanguageTags(tags)
        }
        // 一致则跳过，避免重复触发重配置（启动闪遮罩的根因）
        if (current != null && sameLocaleList(current, target)) return
        systemService?.setApplicationLocales(target)
    } else {
        // API 31-32：appcompat 兜底
        val current = AppCompatDelegate.getApplicationLocales()
        val target = if (tags.isNullOrEmpty()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tags)
        }
        if (current == target) return
        AppCompatDelegate.setApplicationLocales(target)
    }
}

private fun sameLocaleList(a: LocaleList, b: LocaleList): Boolean {
    if (a.isEmpty != b.isEmpty) return false
    if (a.isEmpty) return true
    if (a.size() != b.size()) return false
    for (i in 0 until a.size()) {
        if (a[i] != b[i]) return false
    }
    return true
}