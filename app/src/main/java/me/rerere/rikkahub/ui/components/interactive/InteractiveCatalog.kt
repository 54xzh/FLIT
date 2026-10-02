package me.rerere.rikkahub.ui.components.interactive

import android.icu.text.MessageFormat
import androidx.a2ui.compose.runtime.A2uiComponentScope
import androidx.a2ui.compose.ui.A2uiCatalog
import androidx.a2ui.compose.ui.A2uiComponent
import androidx.a2ui.compose.runtime.A2uiComponentState
import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1
import androidx.a2ui.model.catalog.functions.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.a2ui.catalog.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.hooks.*
import me.rerere.rikkahub.ui.theme.AppShapes

internal data class InteractiveControls(val readOnly: Boolean = true, val canSubmit: Boolean = false)
internal val LocalInteractiveControls = compositionLocalOf { InteractiveControls() }

internal fun interactiveCatalog(localeProvider: A2uiLocaleProvider = A2uiLocaleProvider.Default, openUrl: (String) -> Unit): A2uiCatalog = materialA2uiBasicCatalogV1(
    image = MaterialA2uiBasicCatalogV1Defaults.image { url, description, scale, modifier, onError ->
        ZoomableAsyncImage(model = url, contentDescription = description, contentScale = scale, modifier = modifier)
    },
    video = MaterialA2uiBasicCatalogV1Defaults.video { url, modifier, _ ->
        val context = LocalContext.current
        InteractiveAuxiliaryButton(onClick = { openInteractiveMedia(context, url, "video/*") }, modifier = modifier,
            label = stringResource(R.string.interactive_components_open_media))
    },
    audioPlayer = MaterialA2uiBasicCatalogV1Defaults.audioPlayer { url, description, modifier, _ ->
        val context = LocalContext.current
        InteractiveAuxiliaryButton(onClick = { openInteractiveMedia(context, url, "audio/*") }, modifier = modifier,
            label = description ?: stringResource(R.string.interactive_components_open_media))
    },
    urlOpener = A2uiUrlOpener { openUrl(it) },
    messageFormatter = A2uiMessageFormatter { pattern, locale, arguments -> MessageFormat(pattern, locale).format(arguments) },
    localeProvider = localeProvider,
    button = InteractiveButton, card = InteractiveCard,
    textField = InteractiveTextField, checkBox = InteractiveCheckBox,
    choicePicker = InteractiveChoicePicker, slider = InteractiveSlider,
    dateTimeInput = InteractiveDateTimeInput,
)

private object InteractiveButton : A2uiBasicCatalogV1.Button {
    @Composable
    override fun A2uiComponentScope.TypedContent(
        childId: String, variant: A2uiBasicCatalogV1.Button.Variant, action: Map<String, Any?>,
        accessibility: A2uiBasicCatalogV1.AccessibilityAttributes?, checks: List<A2uiBasicCatalogV1.CheckRule>, modifier: Modifier,
    ) {
        val flags = LocalInteractiveControls.current
        val haptics = rememberPremiumHaptics()
        val interactions = remember { MutableInteractionSource() }
        val pressed by interactions.collectIsPressedAsState()
        val scale by animateFloatAsState(if (pressed) 0.85f else 1f, spring(dampingRatio = 0.6f, stiffness = 300f), label = "InteractiveButton")
        val child = observeA2uiComponentState(childId)
        val failed = checks.firstOrNull { !it.condition }
        val enabled = !flags.readOnly && (!action.containsKey("event") || flags.canSubmit) && child is A2uiComponentState.Success && failed == null
        val click = { haptics.perform(HapticPattern.Pop); dispatchAction(action) }
        val content: @Composable RowScope.() -> Unit = {
            when (val state = child) {
                is A2uiComponentState.Success -> A2uiComponent(component = state.component)
                else -> Text(stringResource(R.string.interactive_components_loading))
            }
        }
        Column(modifier.interactiveAccessibility(accessibility)) {
            when (variant) {
                A2uiBasicCatalogV1.Button.Variant.Primary -> Button(onClick = click, enabled = enabled, shape = AppShapes.ButtonPill,
                    interactionSource = interactions, modifier = Modifier.scale(scale), content = content)
                A2uiBasicCatalogV1.Button.Variant.Secondary -> OutlinedButton(onClick = click, enabled = enabled, shape = AppShapes.ButtonPill,
                    interactionSource = interactions, modifier = Modifier.scale(scale), content = content)
                A2uiBasicCatalogV1.Button.Variant.Borderless -> TextButton(onClick = click, enabled = enabled, shape = AppShapes.ButtonPill,
                    interactionSource = interactions, modifier = Modifier.scale(scale), content = content)
            }
            failed?.let { Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private object InteractiveCard : A2uiBasicCatalogV1.Card {
    @Composable
    override fun A2uiComponentScope.TypedContent(childId: String, accessibility: A2uiBasicCatalogV1.AccessibilityAttributes?, modifier: Modifier) {
        val child = observeA2uiComponentState(childId)
        Card(
            modifier = modifier.interactiveAccessibility(accessibility),
            shape = AppShapes.CardLarge,
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        ) {
            Box(Modifier.padding(16.dp)) {
                when (val state = child) {
                    is A2uiComponentState.Success -> A2uiComponent(component = state.component)
                    is A2uiComponentState.Error -> Text(stringResource(R.string.interactive_components_error))
                    else -> Text(stringResource(R.string.interactive_components_loading))
                }
            }
        }
    }
}

private object InteractiveTextField : A2uiBasicCatalogV1.TextField {
    @Composable
    override fun A2uiComponentScope.TypedContent(
        label: String,
        value: String?,
        variant: A2uiBasicCatalogV1.TextField.Variant,
        validationRegexp: String?,
        onValueChange: (String) -> Unit,
        enabled: Boolean,
        accessibility: A2uiBasicCatalogV1.AccessibilityAttributes?,
        checks: List<A2uiBasicCatalogV1.CheckRule>,
        modifier: Modifier,
    ) {
        val flags = LocalInteractiveControls.current
        val haptics = rememberPremiumHaptics()
        val componentScope = this
        with(MaterialA2uiBasicCatalogV1Defaults.textField) {
            componentScope.TypedContent(
                label = label,
                value = value,
                variant = variant,
                validationRegexp = validationRegexp,
                onValueChange = { value -> if (!flags.readOnly) { haptics.perform(HapticPattern.Pop); onValueChange(value) } },
                enabled = enabled && !flags.readOnly,
                accessibility = accessibility,
                checks = checks,
                modifier = modifier,
            )
        }
    }
}

private object InteractiveCheckBox : A2uiBasicCatalogV1.CheckBox {
    @Composable
    override fun A2uiComponentScope.TypedContent(
        label: String,
        value: Boolean,
        onValueChange: (Boolean) -> Unit,
        enabled: Boolean,
        accessibility: A2uiBasicCatalogV1.AccessibilityAttributes?,
        checks: List<A2uiBasicCatalogV1.CheckRule>,
        modifier: Modifier,
    ) {
        val flags = LocalInteractiveControls.current
        val haptics = rememberPremiumHaptics()
        val componentScope = this
        with(MaterialA2uiBasicCatalogV1Defaults.checkBox) {
            componentScope.TypedContent(
                label = label,
                value = value,
                onValueChange = { value -> if (!flags.readOnly) { haptics.perform(HapticPattern.Pop); onValueChange(value) } },
                enabled = enabled && !flags.readOnly,
                accessibility = accessibility,
                checks = checks,
                modifier = modifier,
            )
        }
    }
}

private object InteractiveChoicePicker : A2uiBasicCatalogV1.ChoicePicker {
    @Composable
    override fun A2uiComponentScope.TypedContent(
        label: String?,
        options: List<A2uiBasicCatalogV1.ChoicePicker.Option>,
        value: List<String>,
        variant: A2uiBasicCatalogV1.ChoicePicker.Variant,
        displayStyle: A2uiBasicCatalogV1.ChoicePicker.DisplayStyle,
        filterable: Boolean,
        onValueChange: (List<String>) -> Unit,
        enabled: Boolean,
        accessibility: A2uiBasicCatalogV1.AccessibilityAttributes?,
        checks: List<A2uiBasicCatalogV1.CheckRule>,
        modifier: Modifier,
    ) {
        val flags = LocalInteractiveControls.current
        val haptics = rememberPremiumHaptics()
        val componentScope = this
        with(MaterialA2uiBasicCatalogV1Defaults.choicePicker) {
            componentScope.TypedContent(
                label = label,
                options = options,
                value = value,
                variant = variant,
                displayStyle = displayStyle,
                filterable = filterable,
                onValueChange = { value -> if (!flags.readOnly) { haptics.perform(HapticPattern.Pop); onValueChange(value) } },
                enabled = enabled && !flags.readOnly,
                accessibility = accessibility,
                checks = checks,
                modifier = modifier,
            )
        }
    }
}

private object InteractiveSlider : A2uiBasicCatalogV1.Slider {
    @Composable
    override fun A2uiComponentScope.TypedContent(
        label: String?,
        min: Float,
        max: Float,
        value: Float,
        onValueChange: (Float) -> Unit,
        enabled: Boolean,
        accessibility: A2uiBasicCatalogV1.AccessibilityAttributes?,
        checks: List<A2uiBasicCatalogV1.CheckRule>,
        modifier: Modifier,
    ) {
        val flags = LocalInteractiveControls.current
        val haptics = rememberPremiumHaptics()
        val componentScope = this
        with(MaterialA2uiBasicCatalogV1Defaults.slider) {
            componentScope.TypedContent(
                label = label,
                min = min,
                max = max,
                value = value,
                onValueChange = { value -> if (!flags.readOnly) { haptics.perform(HapticPattern.Pop); onValueChange(value) } },
                enabled = enabled && !flags.readOnly,
                accessibility = accessibility,
                checks = checks,
                modifier = modifier,
            )
        }
    }
}

private object InteractiveDateTimeInput : A2uiBasicCatalogV1.DateTimeInput {
    @Composable
    override fun A2uiComponentScope.TypedContent(
        value: Long?,
        onValueChange: ((Long?) -> Unit)?,
        enableDate: Boolean,
        enableTime: Boolean,
        min: Long?,
        max: Long?,
        label: String?,
        accessibility: A2uiBasicCatalogV1.AccessibilityAttributes?,
        checks: List<A2uiBasicCatalogV1.CheckRule>,
        modifier: Modifier,
    ) {
        val flags = LocalInteractiveControls.current
        val haptics = rememberPremiumHaptics()
        val componentScope = this
        with(MaterialA2uiBasicCatalogV1Defaults.dateTimeInput) {
            componentScope.TypedContent(
                value = value,
                onValueChange = if (flags.readOnly) null else onValueChange?.let { callback -> { value -> haptics.perform(HapticPattern.Pop); callback(value) } },
                enableDate = enableDate,
                enableTime = enableTime,
                min = min,
                max = max,
                label = label,
                accessibility = accessibility,
                checks = checks,
                modifier = modifier,
            )
        }
    }
}

private fun Modifier.interactiveAccessibility(attributes: A2uiBasicCatalogV1.AccessibilityAttributes?): Modifier =
    if (attributes == null) this else semantics {
        contentDescription = listOfNotNull(attributes.label, attributes.description).joinToString(", ")
    }

/** 官方组件通过 MaterialTheme 读取动画规格，统一覆写为应用的弹簧动画。 */
internal object InteractiveMotionScheme : MotionScheme {
    override fun <T> defaultSpatialSpec() = spring<T>(dampingRatio = 0.5f, stiffness = 400f)
    override fun <T> fastSpatialSpec() = spring<T>(dampingRatio = 0.6f, stiffness = 300f)
    override fun <T> slowSpatialSpec() = spring<T>(dampingRatio = 0.5f, stiffness = 400f)
    override fun <T> defaultEffectsSpec() = spring<T>(dampingRatio = 0.5f, stiffness = 400f)
    override fun <T> fastEffectsSpec() = spring<T>(dampingRatio = 0.6f, stiffness = 300f)
    override fun <T> slowEffectsSpec() = spring<T>(dampingRatio = 0.5f, stiffness = 400f)
}

private fun openInteractiveMedia(context: android.content.Context, url: String, mimeType: String) {
    runCatching {
        var uri = android.net.Uri.parse(url)
        if (uri.scheme == "file" || (uri.scheme == null && url.startsWith('/'))) {
            uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", java.io.File(uri.path ?: url))
        }
        require(uri.scheme in setOf("https", "http", "content"))
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(android.content.Intent.createChooser(intent, null))
    }.onFailure {
        android.widget.Toast.makeText(context, context.getString(R.string.toast_failed_to_open_url, url), android.widget.Toast.LENGTH_SHORT).show()
    }
}

@Composable
internal fun InteractiveAuxiliaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = rememberPremiumHaptics()
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.85f else 1f, spring(dampingRatio = 0.6f, stiffness = 300f), label = "InteractiveAuxiliaryButton")
    TextButton(onClick = { haptics.perform(HapticPattern.Pop); onClick() }, shape = AppShapes.ButtonPill,
        interactionSource = interactions, modifier = modifier.scale(scale)) { Text(label) }
}
