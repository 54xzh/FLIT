package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.HazeState
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.OverlayColorMode
import me.rerere.rikkahub.ui.theme.LocalDarkMode

@Composable
fun AssistantBackground(setting: Settings, hazeState: HazeState? = null) {
    val assistant = setting.getCurrentAssistant()
    val background = assistant.background
    if (background == null) {
        if (hazeState != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(state = hazeState)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                )
            }
        }
        return
    }
    val overlay = assistant.backgroundOverlay
    val isDarkMode = LocalDarkMode.current

    Box(
        modifier = if (hazeState != null) {
            Modifier
                .fillMaxSize()
                .hazeSource(state = hazeState)
        } else {
            Modifier.fillMaxSize()
        }
    ) {
        AsyncImage(
            model = background,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (overlay.blurEnabled && overlay.blurRadius > 0f) {
                        Modifier.blur(overlay.blurRadius.dp)
                    } else {
                        Modifier
                    }
                )
        )

        if (overlay.overlayEnabled) {
            val overlayColor = when (overlay.overlayColorMode) {
                OverlayColorMode.Auto -> MaterialTheme.colorScheme.background
                OverlayColorMode.Manual -> if (isDarkMode) Color(overlay.overlayColorArgb.toInt()) else Color(overlay.overlayColorArgbLight.toInt())
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(overlayColor.copy(alpha = overlay.overlayOpacity))
            )
        }
    }
}
