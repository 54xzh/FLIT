package me.rerere.rikkahub.ui.pages.assistant.detail

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
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
import me.rerere.rikkahub.ui.hooks.rememberRecentOverlayColors
import me.rerere.rikkahub.ui.theme.AppShapes
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

    val autoColor = MaterialTheme.colorScheme.background

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColorPreviewCard(
    modifier: Modifier = Modifier,
    label: String,
    colorArgb: Long,
    onColorSelected: (Long) -> Unit,
) {
    val isDarkMode = LocalDarkMode.current
    var showSheet by remember { mutableStateOf(false) }

    val cardColor = if (isDarkMode)
        MaterialTheme.colorScheme.surfaceContainerLow
    else
        MaterialTheme.colorScheme.surfaceContainerHigh

    Row(
        modifier = modifier
            .clip(AppShapes.ListItem)
            .background(cardColor)
            .clickable { showSheet = true }
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

    if (showSheet) {
        ColorPickerBottomSheet(
            initialColorArgb = colorArgb,
            onColorConfirmed = { newColor ->
                onColorSelected(newColor)
                showSheet = false
            },
            onDismiss = { showSheet = false }
        )
    }
}

private val RecommendedColors = listOf(
    0xFF000000L, 0xFF1C1C1EL, 0xFF3A3A3CL, 0xFF636366L,
    0xFFFFFFFFL, 0xFFF5F5F0L, 0xFFFFF8E1L, 0xFFE8EAF6L,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColorPickerBottomSheet(
    initialColorArgb: Long,
    onColorConfirmed: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = rememberPremiumHaptics()
    val controller = rememberColorPickerController()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val recentColorsState = rememberRecentOverlayColors()
    val recentColors by remember { derivedStateOf { recentColorsState.value } }

    var selectedColorArgb by remember { mutableStateOf(initialColorArgb) }
    val selectedColor = remember(selectedColorArgb) {
        Color(selectedColorArgb.toInt())
    }

    var hexInput by remember { mutableStateOf(formatHex(selectedColorArgb)) }

    val dynamicColors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.tertiary,
    ).map { 0xFF000000L or (it.toArgb().toLong() and 0xFFFFFF) }
    val allRecommended = RecommendedColors + dynamicColors

    LaunchedEffect(Unit) {
        controller.selectByColor(
            color = Color(initialColorArgb.toInt()),
            fromUser = false
        )
    }

    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = onDismiss,
        shape = AppShapes.BottomSheet,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(AppShapes.Avatar)
                        .background(selectedColor)
                        .border(
                            width = 2.dp,
                            color = if (selectedColor.luminance() > 0.5f)
                                MaterialTheme.colorScheme.outlineVariant
                            else
                                Color.Transparent,
                            shape = AppShapes.Avatar
                        )
                )
                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { input ->
                        val cleaned = input.trimStart('#').take(6)
                        hexInput = cleaned
                        val parsed = parseHexToArgb(cleaned)
                        if (parsed != null) {
                            selectedColorArgb = parsed
                            controller.selectByColor(
                                color = Color(parsed.toInt()),
                                fromUser = false
                            )
                        }
                    },
                    label = { Text("HEX") },
                    prefix = { Text("#") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = AppShapes.InputField,
                )
            }

            HsvColorPicker(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(AppShapes.CardSmall),
                controller = controller,
                initialColor = Color(initialColorArgb.toInt()),
                onColorChanged = { colorEnvelope: ColorEnvelope ->
                    if (colorEnvelope.fromUser) {
                        haptics.perform(HapticPattern.Pop)
                        val color = colorEnvelope.color
                        val argb = 0xFF000000L or (color.toArgb().toLong() and 0xFFFFFF)
                        selectedColorArgb = argb
                        hexInput = formatHex(argb)
                    }
                }
            )

            HueSlider(
                controller = controller,
                onHueChanged = { hue ->
                    val color = controller.selectedColor.value
                    val argb = 0xFF000000L or (color.toArgb().toLong() and 0xFFFFFF)
                    selectedColorArgb = argb
                    hexInput = formatHex(argb)
                }
            )

            BrightnessSlider(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .clip(AppShapes.Chip),
                controller = controller,
                borderRadius = 12.dp,
                borderSize = 0.dp,
                wheelRadius = 14.dp,
                wheelColor = Color.White,
            )

            HorizontalDivider()

            Text(
                text = stringResource(R.string.color_picker_recommended),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ColorSwatchGrid(
                colors = allRecommended,
                selectedColorArgb = selectedColorArgb,
                onColorClick = { argb ->
                    haptics.perform(HapticPattern.Pop)
                    selectedColorArgb = argb
                    hexInput = formatHex(argb)
                    controller.selectByColor(
                        color = Color(argb.toInt()),
                        fromUser = false
                    )
                }
            )

            if (recentColors.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.color_picker_recent),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                ColorSwatchGrid(
                    colors = recentColors,
                    selectedColorArgb = selectedColorArgb,
                    onColorClick = { argb ->
                        haptics.perform(HapticPattern.Pop)
                        selectedColorArgb = argb
                        hexInput = formatHex(argb)
                        controller.selectByColor(
                            color = Color(argb.toInt()),
                            fromUser = false
                        )
                    }
                )
            }

            Button(
                onClick = {
                    addRecentColor(recentColorsState, selectedColorArgb)
                    onColorConfirmed(selectedColorArgb)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = AppShapes.ButtonPill,
            ) {
                Text(stringResource(android.R.string.ok))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorSwatchGrid(
    colors: List<Long>,
    selectedColorArgb: Long,
    onColorClick: (Long) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        colors.forEach { argb ->
            val isSelected = argb == selectedColorArgb
            val color = Color(argb.toInt())
            val scale by animateFloatAsState(
                targetValue = if (isSelected) 0.85f else 1f,
                animationSpec = spring(
                    dampingRatio = 0.6f,
                    stiffness = 300f
                ),
                label = "swatch_scale"
            )
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .pointerInput(Unit) {
                        detectTapGestures { onColorClick(argb) }
                    }
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp * scale)
                        .align(Alignment.Center)
                        .clip(AppShapes.Avatar)
                        .background(color)
                        .border(
                            width = if (isSelected) 3.dp else 1.dp,
                            color = if (isSelected)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.outlineVariant,
                            shape = AppShapes.Avatar
                        )
                )
            }
        }
    }
}

@Composable
private fun HueSlider(
    controller: com.github.skydoves.colorpicker.compose.ColorPickerController,
    onHueChanged: (Float) -> Unit,
) {
    var hue by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(controller.selectedColor.value) {
        val color = controller.selectedColor.value
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color.toArgb(), hsv)
        hue = hsv[0]
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .clip(AppShapes.Chip)
            .pointerInput(Unit) {
                fun updateHue(x: Float) {
                    val newHue = ((x / size.width) * 360f).coerceIn(0f, 360f)
                    hue = newHue
                    val currentColor = controller.selectedColor.value
                    val hsv = FloatArray(3)
                    android.graphics.Color.colorToHSV(currentColor.toArgb(), hsv)
                    hsv[0] = newHue
                    val newColor = Color(android.graphics.Color.HSVToColor(hsv))
                    controller.selectByColor(newColor, fromUser = true)
                    onHueChanged(newHue)
                }

                detectTapGestures { offset -> updateHue(offset.x) }
            }
            .pointerInput(Unit) {
                fun updateHue(x: Float) {
                    val newHue = ((x / size.width) * 360f).coerceIn(0f, 360f)
                    hue = newHue
                    val currentColor = controller.selectedColor.value
                    val hsv = FloatArray(3)
                    android.graphics.Color.colorToHSV(currentColor.toArgb(), hsv)
                    hsv[0] = newHue
                    val newColor = Color(android.graphics.Color.HSVToColor(hsv))
                    controller.selectByColor(newColor, fromUser = true)
                    onHueChanged(newHue)
                }

                detectDragGestures { change, _ ->
                    change.consume()
                    updateHue(change.position.x)
                }
            }
    ) {
        val rainbowColors = listOf(
            Color.Red,
            Color(0xFFFF7F00),
            Color.Yellow,
            Color.Green,
            Color.Cyan,
            Color.Blue,
            Color.Magenta,
            Color.Red,
        )
        drawRoundRect(
            brush = Brush.horizontalGradient(rainbowColors),
            cornerRadius = CornerRadius(12.dp.toPx()),
        )
        val indicatorX = (hue / 360f) * size.width
        val indicatorRadius = 14.dp.toPx()
        drawCircle(
            color = Color.White,
            radius = indicatorRadius,
            center = Offset(indicatorX, size.height / 2),
        )
        drawCircle(
            color = Color.Black,
            radius = indicatorRadius - 2.dp.toPx(),
            center = Offset(indicatorX, size.height / 2),
        )
        val currentColor = controller.selectedColor.value
        drawCircle(
            color = currentColor,
            radius = indicatorRadius - 4.dp.toPx(),
            center = Offset(indicatorX, size.height / 2),
        )
    }
}

private fun formatHex(argb: Long): String {
    return String.format("%06X", argb and 0xFFFFFF)
}

private fun parseHexToArgb(hex: String): Long? {
    if (hex.length != 6) return null
    return try {
        0xFF000000L or hex.toLong(16)
    } catch (_: NumberFormatException) {
        null
    }
}

private fun addRecentColor(
    recentColorsState: androidx.compose.runtime.MutableState<List<Long>>,
    newColor: Long,
) {
    val current = recentColorsState.value.toMutableList()
    current.remove(newColor)
    current.add(0, newColor)
    recentColorsState.value = current.take(8)
}
