package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.OverlayColorMode
import me.rerere.rikkahub.ui.theme.LocalDarkMode

@Composable
fun AssistantBackground(setting: Settings) {
    val assistant = setting.getCurrentAssistant()
    val background = assistant.background ?: return
    val overlay = assistant.backgroundOverlay
    val isDarkMode = LocalDarkMode.current

    Box {
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
                OverlayColorMode.Auto -> if (isDarkMode) Color.Black else Color.White
                OverlayColorMode.Manual -> Color(overlay.overlayColorArgb.toInt())
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(overlayColor.copy(alpha = overlay.overlayOpacity))
            )
        }
    }
}
