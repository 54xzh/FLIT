package me.rerere.rikkahub.ui.pages.assistant.detail

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.github.skydoves.colorpicker.compose.BrightnessSlider
import com.github.skydoves.colorpicker.compose.ColorEnvelope
import com.github.skydoves.colorpicker.compose.HsvColorPicker
import com.github.skydoves.colorpicker.compose.rememberColorPickerController
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.BackgroundOverlaySettings
import me.rerere.rikkahub.data.model.OverlayColorMode
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.HapticSwitch
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.utils.createChatFilesByContents

@Composable
fun BackgroundPicker(
    background: String?,
    overlaySettings: BackgroundOverlaySettings,
    onUpdateBackground: (String?) -> Unit,
    onUpdateOverlay: (BackgroundOverlaySettings) -> Unit,
) {
    val context = LocalContext.current
    val haptics = rememberPremiumHaptics()
    val isDarkMode = LocalDarkMode.current
    var showPickOption by remember { mutableStateOf(false) }
    var showUrlInput by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf("") }

    val autoColor = if (isDarkMode) Color.Black else Color.White

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val localUris = context.createChatFilesByContents(listOf(it))
            localUris.firstOrNull()?.let { localUri ->
                onUpdateBackground(localUri.toString())
            }
        }
    }

    androidx.compose.material3.Surface(
        color = if (isDarkMode)
            MaterialTheme.colorScheme.surfaceContainerLow
        else
            MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.assistant_page_chat_background),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.assistant_page_chat_background_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(
                onClick = {
                    showPickOption = true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (background != null) {
                        stringResource(R.string.assistant_page_change_background)
                    } else {
                        stringResource(R.string.assistant_page_select_background)
                    }
                )
            }

            if (background != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.assistant_page_background_set),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            onUpdateBackground(null)
                        }
                    ) {
                        Text(stringResource(R.string.assistant_page_remove))
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(9f / 16f)
                        .clip(RoundedCornerShape(10.dp))
                ) {
                    AsyncImage(
                        model = background,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(9f / 16f)
                            .then(
                                if (overlaySettings.blurEnabled && overlaySettings.blurRadius > 0f) {
                                    Modifier.blur(overlaySettings.blurRadius.dp)
                                } else {
                                    Modifier
                                }
                            ),
                        contentScale = ContentScale.Crop
                    )
                    if (overlaySettings.overlayEnabled) {
                        val overlayColor = when (overlaySettings.overlayColorMode) {
                            OverlayColorMode.Auto -> autoColor
                            OverlayColorMode.Manual -> if (isDarkMode) Color(overlaySettings.overlayColorArgb.toInt()) else Color(overlaySettings.overlayColorArgbLight.toInt())
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(9f / 16f)
                                .background(overlayColor.copy(alpha = overlaySettings.overlayOpacity))
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = background != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormItem(
                        label = { Text(stringResource(R.string.assistant_page_background_blur)) },
                        description = { Text(stringResource(R.string.assistant_page_background_blur_desc)) },
                        tail = {
                            HapticSwitch(
                                checked = overlaySettings.blurEnabled,
                                onCheckedChange = {
                                    onUpdateOverlay(overlaySettings.copy(blurEnabled = it))
                                }
                            )
                        }
                    )

                    AnimatedVisibility(
                        visible = overlaySettings.blurEnabled,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        androidx.compose.material3.Surface(
                            color = if (isDarkMode)
                                MaterialTheme.colorScheme.surfaceContainerLow
                            else
                                MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = stringResource(R.string.assistant_page_blur_radius) +
                                            ": ${overlaySettings.blurRadius.toInt()} dp",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Slider(
                                    value = overlaySettings.blurRadius,
                                    onValueChange = {
                                        onUpdateOverlay(
                                            overlaySettings.copy(
                                                blurRadius = (it * 2).toInt().toFloat() / 2
                                            )
                                        )
                                    },
                                    valueRange = 0f..25f,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    FormItem(
                        label = { Text(stringResource(R.string.assistant_page_color_overlay)) },
                        description = { Text(stringResource(R.string.assistant_page_color_overlay_desc)) },
                        tail = {
                            HapticSwitch(
                                checked = overlaySettings.overlayEnabled,
                                onCheckedChange = {
                                    onUpdateOverlay(overlaySettings.copy(overlayEnabled = it))
                                }
                            )
                        }
                    )

                    AnimatedVisibility(
                        visible = overlaySettings.overlayEnabled,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        androidx.compose.material3.Surface(
                            color = if (isDarkMode)
                                MaterialTheme.colorScheme.surfaceContainerLow
                            else
                                MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.assistant_page_overlay_opacity) +
                                            ": ${(overlaySettings.overlayOpacity * 100).toInt()}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Slider(
                                    value = overlaySettings.overlayOpacity,
                                    onValueChange = {
                                        onUpdateOverlay(
                                            overlaySettings.copy(
                                                overlayOpacity = (it * 100).toInt().toFloat() / 100
                                            )
                                        )
                                    },
                                    valueRange = 0f..1f,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(
                                        selected = overlaySettings.overlayColorMode == OverlayColorMode.Auto,
                                        onClick = {
                                            haptics.perform(HapticPattern.Pop)
                                            onUpdateOverlay(overlaySettings.copy(overlayColorMode = OverlayColorMode.Auto))
                                        },
                                        label = { Text(stringResource(R.string.assistant_page_overlay_color_auto)) }
                                    )
                                    FilterChip(
                                        selected = overlaySettings.overlayColorMode == OverlayColorMode.Manual,
                                        onClick = {
                                            haptics.perform(HapticPattern.Pop)
                                            onUpdateOverlay(overlaySettings.copy(overlayColorMode = OverlayColorMode.Manual))
                                        },
                                        label = { Text(stringResource(R.string.assistant_page_overlay_color_manual)) }
                                    )
                                }

                                AnimatedVisibility(
                                    visible = overlaySettings.overlayColorMode == OverlayColorMode.Manual,
                                    enter = fadeIn() + expandVertically(),
                                    exit = fadeOut() + shrinkVertically()
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        ColorPreviewCard(
                                            modifier = Modifier.weight(1f),
                                            label = stringResource(R.string.assistant_page_overlay_dark_color),
                                            colorArgb = overlaySettings.overlayColorArgb,
                                            onColorSelected = { argb ->
                                                onUpdateOverlay(overlaySettings.copy(overlayColorArgb = argb))
                                            }
                                        )
                                        ColorPreviewCard(
                                            modifier = Modifier.weight(1f),
                                            label = stringResource(R.string.assistant_page_overlay_light_color),
                                            colorArgb = overlaySettings.overlayColorArgbLight,
                                            onColorSelected = { argb ->
                                                onUpdateOverlay(overlaySettings.copy(overlayColorArgbLight = argb))
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showPickOption) {
        AlertDialog(
            onDismissRequest = {
                showPickOption = false
            },
            title = {
                Text(stringResource(R.string.assistant_page_select_background))
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            showPickOption = false
                            imagePickerLauncher.launch("image/*")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.assistant_page_select_from_gallery))
                    }
                    Button(
                        onClick = {
                            showPickOption = false
                            urlInput = ""
                            showUrlInput = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.assistant_page_enter_image_url))
                    }
                    if (background != null) {
                        Button(
                            onClick = {
                                showPickOption = false
                                onUpdateBackground(null)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.assistant_page_remove_background))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPickOption = false
                    }
                ) {
                    Text(stringResource(R.string.assistant_page_cancel))
                }
            }
        )
    }

    if (showUrlInput) {
        AlertDialog(
            onDismissRequest = {
                showUrlInput = false
            },
            title = {
                Text(stringResource(R.string.assistant_page_enter_image_url))
            },
            text = {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text(stringResource(R.string.assistant_page_image_url)) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.assistant_page_image_url_placeholder)) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (urlInput.isNotBlank()) {
                            onUpdateBackground(urlInput.trim())
                            showUrlInput = false
                        }
                    }
                ) {
                    Text(stringResource(R.string.assistant_page_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showUrlInput = false
                    }
                ) {
                    Text(stringResource(R.string.assistant_page_cancel))
                }
            }
        )
    }
}

@Composable
private fun ColorPreviewCard(
    modifier: Modifier = Modifier,
    label: String,
    colorArgb: Long,
    onColorSelected: (Long) -> Unit,
) {
    val isDarkMode = LocalDarkMode.current
    var showDialog by remember { mutableStateOf(false) }

    val cardColor = if (isDarkMode)
        MaterialTheme.colorScheme.surfaceContainerLow
    else
        MaterialTheme.colorScheme.surfaceContainerHigh

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(cardColor)
            .clickable { showDialog = true }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Color(colorArgb.toInt()))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape
                )
        )
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "#${String.format("%06X", colorArgb and 0xFFFFFF)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showDialog) {
        ColorPickerDialog(
            initialColorArgb = colorArgb,
            onColorSelected = onColorSelected,
            onDismiss = { showDialog = false }
        )
    }
}

@Composable
private fun ColorPickerDialog(
    initialColorArgb: Long,
    onColorSelected: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = rememberPremiumHaptics()
    val controller = rememberColorPickerController()

    LaunchedEffect(initialColorArgb) {
        controller.selectByColor(
            color = Color(initialColorArgb.toInt()),
            fromUser = false
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.ok))
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HsvColorPicker(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    controller = controller,
                    initialColor = Color(initialColorArgb.toInt()),
                    onColorChanged = { colorEnvelope: ColorEnvelope ->
                        if (colorEnvelope.fromUser) {
                            haptics.perform(HapticPattern.Pop)
                            val color = colorEnvelope.color
                            onColorSelected(0xFF000000L or (color.toArgb().toLong() and 0xFFFFFF))
                        }
                    }
                )

                BrightnessSlider(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    controller = controller,
                    borderRadius = 10.dp,
                    borderSize = 0.dp,
                    wheelRadius = 14.dp,
                    wheelColor = Color.White,
                )
            }
        }
    )
}
