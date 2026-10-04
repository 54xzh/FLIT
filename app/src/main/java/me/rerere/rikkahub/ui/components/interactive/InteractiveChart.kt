package me.rerere.rikkahub.ui.components.interactive

import androidx.a2ui.compose.runtime.A2uiComponentProperties
import androidx.a2ui.compose.runtime.A2uiComponentScope
import androidx.a2ui.compose.runtime.A2uiProperty
import androidx.a2ui.compose.ui.A2uiComponent
import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1
import androidx.a2ui.model.schema.A2uiAnySchema
import androidx.a2ui.model.schema.A2uiArraySchema
import androidx.a2ui.model.schema.A2uiObjectSchema
import androidx.a2ui.model.schema.A2uiSchemaKeyword
import androidx.a2ui.model.schema.A2uiStringSchema
import androidx.a2ui.model.schema.commontypes.A2uiDataBindingSchema
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.drawText
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonElement
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.interactive.toInteractiveValue
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.round

internal object InteractiveChartLimits {
    const val MaxSeries = 4
    const val MaxPoints = 12
}

internal data class InteractiveChartSeries(
    val label: String,
    val values: List<Double?>,
    val valueLabels: List<String?> = values.map { it?.let(::interactiveChartSpokenValue) },
)
internal data class InteractiveChartModel(
    val variant: String,
    val title: String?,
    val categories: List<String>,
    val series: List<InteractiveChartSeries>,
    val hasCategoryLabels: Boolean = true,
)
internal data class InteractiveChartScale(val min: Double, val max: Double)

private val ChartVariants = listOf("bar", "line", "area", "pie")

private val seriesSchema = A2uiAnySchema(
    "Named numeric series or a data binding",
    listOf(
        A2uiSchemaKeyword.OneOf(
            listOf(
                A2uiArraySchema(
                    A2uiObjectSchema(
                        properties = mapOf(
                            "label" to A2uiStringSchema("Series name"),
                            "values" to A2uiArraySchema(A2uiAnySchema("A number, or a blank point")),
                        ),
                        required = setOf("values"),
                        isAdditionalPropertiesAllowed = true,
                    ),
                    "Chart series",
                ),
                A2uiDataBindingSchema.DEFAULT_INSTANCE,
            ),
        ),
    ),
)

private val SeriesProperty = A2uiProperty.dynamicCustom("series", seriesSchema, { it }, true)
private val CategoriesProperty = A2uiProperty.dynamicStringList("categories", false, "Labels lined up with series values")
private val TitleProperty = A2uiProperty.dynamicString("title", false, "Optional chart title")
private val VariantProperty = A2uiProperty.stringEnum("variant", ChartVariants, "bar", "bar, line, area, or pie")
private val AccessibilityProperty = A2uiProperty.dynamicCustom(
    "accessibility",
    A2uiObjectSchema(
        properties = mapOf("label" to A2uiStringSchema(), "description" to A2uiStringSchema()),
        isAdditionalPropertiesAllowed = true,
    ),
    { value ->
        val map = value.asChartMap()
        A2uiBasicCatalogV1.AccessibilityAttributes(
            label = map?.get("label") as? String ?: "",
            description = map?.get("description") as? String ?: "",
        )
    },
    false,
)

/** 柱状、折线、面积和饼图。数据可绑定，脚本更新后直接重绘。 */
internal object InteractiveChart : A2uiComponent {
    override val name = "Chart"
    override val description = "Displays a bar, line, area, or pie chart. Tap a point, bar, or slice to inspect its values."
    override val properties = listOf(
        VariantProperty, TitleProperty, CategoriesProperty, SeriesProperty, AccessibilityProperty,
        A2uiBasicCatalogV1.WeightProperty,
    )

    @Composable
    override fun A2uiComponentScope.Content(properties: A2uiComponentProperties, modifier: Modifier) {
        val model = interactiveChartModel(
            variant = properties.get(VariantProperty),
            title = properties.bind(TitleProperty),
            categories = properties.bind(CategoriesProperty),
            series = properties.bind(SeriesProperty),
        )
        InteractiveChartView(model, properties.bind(AccessibilityProperty), modifier)
    }
}

internal fun interactiveChartModel(variant: Any?, title: Any?, categories: Any?, series: Any?): InteractiveChartModel {
    val resolvedVariant = (variant as? String)?.takeIf { it in ChartVariants } ?: "bar"
    val parsed = series.asChartList().orEmpty().mapNotNull { item ->
        val map = item.asChartMap() ?: return@mapNotNull null
        val sourceValues = map["values"].asChartList() ?: return@mapNotNull null
        val values = sourceValues.map { it.asChartNumber() }
        if (values.isEmpty()) return@mapNotNull null
        InteractiveChartSeries(
            label = (map["label"] as? String).orEmpty(),
            values = values,
            valueLabels = sourceValues.mapIndexed { index, value ->
                values[index]?.let { interactiveChartSourceLabel(unwrapChartValue(value), it) }
            },
        )
    }.take(InteractiveChartLimits.MaxSeries)
    val pointCount = if (parsed.isEmpty()) 0 else min(
        InteractiveChartLimits.MaxPoints,
        if (categories.asChartList().isNullOrEmpty()) parsed.maxOf { it.values.size } else categories.asChartList().orEmpty().size,
    )
    val labels = List(pointCount) { index ->
        categories.asChartList()?.getOrNull(index) as? String ?: "${index + 1}"
    }
    return InteractiveChartModel(
        variant = resolvedVariant,
        title = (title as? String)?.takeIf { it.isNotBlank() },
        categories = labels,
        series = parsed.map { item ->
            InteractiveChartSeries(
                item.label,
                List(pointCount) { index -> item.values.getOrNull(index) },
                List(pointCount) { index -> item.valueLabels.getOrNull(index) },
            )
        },
        hasCategoryLabels = !categories.asChartList().isNullOrEmpty(),
    )
}

internal fun interactiveChartScale(variant: String, values: List<Double>): InteractiveChartScale {
    if (values.isEmpty()) return InteractiveChartScale(0.0, 1.0)
    var minValue = values.min()
    var maxValue = values.max()
    if (variant == "bar") {
        if (minValue > 0.0) minValue = 0.0
        if (maxValue < 0.0) maxValue = 0.0
    }
    if (maxValue == minValue) {
        val delta = if (maxValue == 0.0) 1.0 else abs(maxValue) * 0.5
        if (variant == "bar") maxValue += delta else {
            minValue -= delta
            maxValue += delta
        }
    } else if (variant != "bar") {
        val pad = (maxValue / 2.0 - minValue / 2.0) * 0.24
        minValue -= pad
        maxValue += pad
    }
    return InteractiveChartScale(
        minValue.coerceAtLeast(-Double.MAX_VALUE),
        maxValue.coerceAtMost(Double.MAX_VALUE),
    )
}

/** 先用原始精度计算相对位置，再交给浮点动画，避免大数值的小幅变化丢失。 */
internal fun interactiveChartPosition(value: Double, scale: InteractiveChartScale): Float {
    val span = scale.max - scale.min
    val position = when {
        scale.max <= scale.min -> 0.0
        span.isFinite() -> (value - scale.min) / span
        else -> (value / 2.0 - scale.min / 2.0) / (scale.max / 2.0 - scale.min / 2.0)
    }
    return position.coerceIn(0.0, 1.0).toFloat()
}

internal fun interactiveChartAxisLabel(value: Double, tickStep: Double? = null): String {
    if (!value.isFinite()) return "0"
    val absolute = abs(value)
    if (absolute >= 1e15) return value.toString()
    val sign = if (value < 0) "-" else ""
    if (absolute in Double.MIN_VALUE..<0.0001) return value.toString()
    fun trim(divisor: Double): String {
        val step = (tickStep?.takeIf { it.isFinite() && it > 0.0 } ?: absolute) / divisor
        val decimals = if (step > 0.0) ceil(-log10(step)).toInt().coerceIn(1, 12) else 1
        return BigDecimal.valueOf(absolute / divisor).setScale(decimals, RoundingMode.HALF_EVEN)
            .stripTrailingZeros().toPlainString()
    }
    return when {
        absolute >= 1_000_000_000 -> sign + trim(1_000_000_000.0) + "B"
        absolute >= 1_000_000 -> sign + trim(1_000_000.0) + "M"
        absolute >= 1_000 -> sign + trim(1_000.0) + "k"
        else -> sign + trim(1.0)
    }
}

internal fun interactiveChartSpokenValue(value: Double): String {
    if (!value.isFinite()) return "—"
    if (abs(value) >= 1e15 || (value != 0.0 && abs(value) < 1e-6)) return value.toString()
    return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
}

/** 只修正显示值的整数边界尾差，绘制、比例和原始数据不变。 */
private fun interactiveChartSourceLabel(source: Any?, value: Double): String {
    // JSON 转换会把 Float 变成 Double，因此不能根据运行时类型判断尾差。
    // 仅吸收距非零整数不超过 0.00005的尾差，保留小数值和大整数原始精度。
    val nearestInteger = round(value)
    val integerNoise = nearestInteger != 0.0 && value != nearestInteger &&
        nearestInteger.toFloat().toDouble() == nearestInteger && abs(value - nearestInteger) <= 0.00005
    val decimal = (if (integerNoise) BigDecimal.valueOf(nearestInteger) else source?.toString()?.toBigDecimalOrNull())?.stripTrailingZeros()
        ?: return interactiveChartSpokenValue(value)
    return if (abs(value) >= 1e15 || (value != 0.0 && abs(value) < 1e-6)) {
        if (decimal.scale() <= 0 && source is Number && source !is Float && source !is Double) decimal.toPlainString()
        else decimal.toString()
    } else decimal.toPlainString()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InteractiveChartView(
    model: InteractiveChartModel,
    accessibility: A2uiBasicCatalogV1.AccessibilityAttributes?,
    modifier: Modifier,
) {
    val tooltipController = LocalChartTooltipController.current
    val tooltipOwner = remember { Any() }
    DisposableEffect(tooltipController, tooltipOwner) {
        onDispose { tooltipController?.release(tooltipOwner) }
    }
    val palette = chartPalette()
    val haptics = rememberPremiumHaptics()
    var selection by remember(model.variant, model.categories, model.series.map { it.label }) {
        mutableStateOf<ChartSelection?>(null)
    }
    val selectedDetails = model.selectionDetails(selection)
    LaunchedEffect(model, selection) {
        if (selection != null && selectedDetails == null) selection = null
    }
    val highlight = remember { Animatable(0f) }
    LaunchedEffect(selection) {
        highlight.snapTo(0f)
        if (selection != null) highlight.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 300f))
    }
    val locale = LocalConfiguration.current.locales[0]
    val percentFormat = remember(locale) { NumberFormat.getPercentInstance(locale).apply { maximumFractionDigits = 2 } }
    val tinyShare = stringResource(R.string.interactive_components_chart_tiny_share)
    fun shareText(share: Double): String = if (share > 0.0 && share < 0.0001) tinyShare else percentFormat.format(share)
    fun select(point: ChartSelection?) {
        val updated = point.takeUnless { it == selection }
        if (updated != selection) haptics.perform(HapticPattern.Pop)
        selection = updated
        if (updated == null) tooltipController?.release(tooltipOwner)
        else tooltipController?.select(tooltipOwner) { selection = null }
    }
    val details = model.summary()
    val spoken = accessibility?.let { item ->
        listOfNotNull(item.label, item.description).filter { it.isNotBlank() }.joinToString(", ").takeIf { it.isNotBlank() }
    } ?: details.takeIf { it.isNotBlank() }?.let { stringResource(R.string.interactive_components_chart_summary, it) }
        ?: stringResource(R.string.interactive_components_chart_empty)
    val pieHasSlice = model.variant != "pie" || model.series.firstOrNull()?.values?.any { it != null && it > 0.0 } == true
    val hasPoint = model.series.any { series -> series.values.any { it != null } }
    Column(modifier.semantics { contentDescription = spoken }.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        model.title?.let {
            Text(it, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (!hasPoint || !pieHasSlice) {
            Text(stringResource(R.string.interactive_components_chart_empty), color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium)
        } else {
            val scale = interactiveChartScale(model.variant, model.series.flatMap { it.values.filterNotNull() })
            val baseline = interactiveChartPosition(0.0, scale)
            val pieMax = model.series.firstOrNull()?.values?.filterNotNull()?.maxOrNull()?.takeIf { it > 0.0 } ?: 1.0
            val displayedSeries = if (model.variant == "pie") model.series.take(1) else model.series
            val animations = displayedSeries.mapIndexed { index, series ->
                val positions = series.values.map { value ->
                    value?.let {
                        if (model.variant == "pie") (it.coerceAtLeast(0.0) / pieMax).toFloat()
                        else interactiveChartPosition(it, scale)
                    }
                }
                key(model.variant, index, series.label) {
                    rememberPointAnimations(positions, if (model.variant == "pie") 0f else baseline)
                }
            }
            val measurer = rememberTextMeasurer()
            val axisStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
            val grid = MaterialTheme.colorScheme.outlineVariant
            fun animatedPositions(): List<List<Float?>> = animations.mapIndexed { seriesIndex, points ->
                points.mapIndexed { index, animation ->
                    if (displayedSeries[seriesIndex].values[index] == null) null else animation.value
                }
            }
            val accessibleActions = model.selectablePoints().mapNotNull { point ->
                val detail = model.selectionDetails(point) ?: return@mapNotNull null
                val valueText = detail.share?.let {
                    stringResource(R.string.interactive_components_chart_value_share, detail.valueLabel, shareText(it))
                } ?: detail.valueLabel
                val label = interactiveChartTooltipText(detail, valueText)
                CustomAccessibilityAction(label) { select(point); true }
            }
            var chartSize by remember { mutableStateOf(IntSize.Zero) }
            var chartOrigin by remember { mutableStateOf(Offset.Zero) }
            Box(Modifier.onSizeChanged { chartSize = it }.onGloballyPositioned { chartOrigin = it.positionInWindow() }) {
                Canvas(
                    Modifier.fillMaxWidth().height(if (model.variant == "pie") 196.dp else 176.dp)
                        .clipToBounds()
                        .padding(8.dp)
                        .pointerInput(model, animations, scale) {
                            detectTapGestures { tap ->
                                select(interactiveChartHitTest(
                                    model, animatedPositions(), scale, Size(size.width.toFloat(), size.height.toFloat()), density, tap, selection,
                                ))
                            }
                        }
                        .semantics { customActions = accessibleActions },
                ) {
                    val animated = animatedPositions()
                    when (model.variant) {
                        "pie" -> drawPie(animated.firstOrNull().orEmpty(), palette)
                        "line" -> drawTrend(model, animated, scale, palette, grid, measurer, axisStyle, fill = false)
                        "area" -> drawTrend(model, animated, scale, palette, grid, measurer, axisStyle, fill = true)
                        else -> drawBars(model, animated, scale, palette, grid, measurer, axisStyle)
                    }
                    if (selectedDetails != null) selection?.let { selected ->
                        drawChartSelection(model, animated, scale, palette, selected, highlight.value.coerceAtLeast(0f))
                    }
                }
                if (selectedDetails != null) selection?.let { selected ->
                    InteractiveChartTooltip(
                        owner = tooltipOwner,
                        selection = selected,
                        detail = selectedDetails,
                        valueText = selectedDetails.share?.let {
                            stringResource(R.string.interactive_components_chart_value_share, selectedDetails.valueLabel, shareText(it))
                        } ?: selectedDetails.valueLabel,
                        anchorBounds = Rect(chartOrigin, Size(chartSize.width.toFloat(), chartSize.height.toFloat())),
                        anchor = { contentSize, density ->
                            val padding = 8f * density
                            val chartSize = Size((contentSize.width - padding * 2f).coerceAtLeast(0f), (contentSize.height - padding * 2f).coerceAtLeast(0f))
                            interactiveChartSelectionAnchor(model, animatedPositions(), scale, chartSize, density, selected)
                                ?.plus(Offset(padding, padding))
                        },
                        onDismiss = { select(null) },
                    )
                }
            }
            ChartLegend(model, palette)
        }
    }
}

@Composable
private fun rememberPointAnimations(values: List<Float?>, baseline: Float): List<Animatable<Float, AnimationVector1D>> =
    values.mapIndexed { index, target ->
        key(index) {
            val animation = remember { Animatable(baseline) }
            LaunchedEffect(target) {
                if (target == null) animation.snapTo(baseline)
                else animation.animateTo(target, spring(dampingRatio = 0.6f, stiffness = 300f))
            }
            animation
        }
    }

@Composable
private fun chartPalette(): List<Color> {
    val scheme = MaterialTheme.colorScheme
    val light = scheme.surface.luminance() > 0.5f
    return List(InteractiveChartLimits.MaxPoints) { index ->
        if (index == 0) scheme.primary
        else Color.hsl((index * 47f) % 360f, 0.58f, if (light) 0.42f else 0.68f)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChartLegend(model: InteractiveChartModel, palette: List<Color>) {
    val items = if (model.variant == "pie") {
        model.categories.mapIndexedNotNull { index, label ->
            val value = model.series.firstOrNull()?.values?.getOrNull(index)
            if (value == null || value <= 0.0) null else label.ifBlank { "${index + 1}" } to palette[index % palette.size]
        }
    } else model.series.mapIndexedNotNull { index, series ->
        val label = series.label.ifBlank { if (model.series.size > 1) "${index + 1}" else "" }
        label.takeIf { it.isNotBlank() }?.let { it to palette[index % palette.size] }
    }
    if (items.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(color, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBars(
    model: InteractiveChartModel,
    series: List<List<Float?>>,
    scale: InteractiveChartScale,
    palette: List<Color>,
    grid: Color,
    measurer: TextMeasurer,
    axisStyle: TextStyle,
) {
    val plot = plotBounds()
    drawGrid(plot, scale, grid)
    drawAxisLabels(model.categories, plot, scale, measurer, axisStyle)
    clipRect(plot.left, plot.top, plot.right, plot.bottom) {
        interactiveChartBars(model, series, scale, plot).forEach { bar ->
            drawRoundRect(
                color = palette[bar.selection.seriesIndex % palette.size],
                topLeft = bar.bounds.topLeft,
                size = bar.bounds.size,
                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTrend(
    model: InteractiveChartModel,
    series: List<List<Float?>>,
    scale: InteractiveChartScale,
    palette: List<Color>,
    grid: Color,
    measurer: TextMeasurer,
    axisStyle: TextStyle,
    fill: Boolean,
) {
    val plot = plotBounds()
    drawGrid(plot, scale, grid)
    drawAxisLabels(model.categories, plot, scale, measurer, axisStyle)
    clipRect(plot.left, plot.top, plot.right, plot.bottom) {
        val slot = plot.width / model.categories.size.coerceAtLeast(1)
        series.forEachIndexed { seriesIndex, values ->
            val color = palette[seriesIndex % palette.size]
            val stroke = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            var run = mutableListOf<Int>()
            fun drawRun() {
                if (run.isEmpty()) return
                val path = Path()
                run.forEachIndexed { step, index ->
                    val amount = values.getOrNull(index) ?: return@forEachIndexed
                    val point = plot.point(index, model.categories.size, amount)
                    if (step == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                }
                if (fill && run.size >= 2) {
                    val area = Path().apply {
                        addPath(path)
                        lineTo(plot.left + slot * run.last() + slot / 2, plot.baseline(scale))
                        lineTo(plot.left + slot * run.first() + slot / 2, plot.baseline(scale))
                        close()
                    }
                    drawPath(area, color.copy(alpha = 0.22f))
                }
                drawPath(path, color, style = stroke)
                run.forEach { index ->
                    val amount = values.getOrNull(index) ?: return@forEach
                    drawCircle(color, radius = 3.5.dp.toPx(), center = plot.point(index, model.categories.size, amount))
                }
                run = mutableListOf()
            }
            values.forEachIndexed { index, value ->
                if (value == null) drawRun() else run += index
            }
            drawRun()
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPie(
    values: List<Float?>,
    palette: List<Color>,
) {
    val slices = interactiveChartPieSlices(values)
    val diameter = min(size.width, size.height) * 0.92f
    val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
    slices.forEach { slice ->
        drawArc(
            color = palette[slice.pointIndex % palette.size],
            startAngle = slice.startAngle,
            sweepAngle = slice.sweepAngle,
            useCenter = false,
            topLeft = topLeft,
            size = Size(diameter, diameter),
            style = Stroke(width = diameter * 0.18f, cap = StrokeCap.Butt),
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.plotBounds(): ChartPlot = interactiveChartPlot(size, density)

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawChartSelection(
    model: InteractiveChartModel,
    positions: List<List<Float?>>,
    scale: InteractiveChartScale,
    palette: List<Color>,
    selected: ChartSelection,
    progress: Float,
) {
    val opacity = progress.coerceIn(0f, 1f)
    if (model.variant == "pie") {
        val slice = interactiveChartPieSlices(positions.firstOrNull().orEmpty())
            .firstOrNull { it.pointIndex == selected.pointIndex } ?: return
        val diameter = min(size.width, size.height) * 0.92f
        drawArc(
            color = palette[slice.pointIndex % palette.size].copy(alpha = opacity),
            startAngle = slice.startAngle,
            sweepAngle = slice.sweepAngle,
            useCenter = false,
            topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f),
            size = Size(diameter, diameter),
            style = Stroke(width = diameter * 0.18f + 4.dp.toPx() * progress),
        )
        return
    }
    val position = positions.getOrNull(selected.seriesIndex)?.getOrNull(selected.pointIndex) ?: return
    val plot = plotBounds()
    val color = palette[selected.seriesIndex % palette.size]
    val bar = if (model.variant == "bar") interactiveChartBars(model, positions, scale, plot)
        .firstOrNull { it.selection == selected } else null
    val point = if (bar != null) Offset(bar.bounds.center.x, plot.y(position))
        else plot.point(selected.pointIndex, model.categories.size, position)
    clipRect(plot.left, plot.top, plot.right, plot.bottom) {
        val guide = color.copy(alpha = 0.55f * opacity)
        val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
        drawLine(guide, Offset(plot.left, point.y), Offset(plot.right, point.y), 1.dp.toPx(), pathEffect = dash)
        drawLine(guide, Offset(point.x, plot.top), Offset(point.x, plot.bottom), 1.dp.toPx(), pathEffect = dash)
        if (bar != null) {
            drawRoundRect(color.copy(alpha = 0.2f * opacity), bar.bounds.topLeft, bar.bounds.size,
                CornerRadius(4.dp.toPx(), 4.dp.toPx()), style = Stroke(3.dp.toPx() * progress))
        }
        drawCircle(color.copy(alpha = 0.22f * opacity), (7.dp.toPx() + 3.dp.toPx() * progress), point)
        drawCircle(color.copy(alpha = opacity), 3.5.dp.toPx() + 1.5.dp.toPx() * progress, point)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGrid(plot: ChartPlot, scale: InteractiveChartScale, grid: Color) {
    interactiveChartTicks(scale).forEach { value ->
        val y = plot.y(value, scale)
        drawLine(grid.copy(alpha = 0.55f), Offset(plot.left, y), Offset(plot.right, y), strokeWidth = 1.dp.toPx())
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAxisLabels(
    labels: List<String>,
    plot: ChartPlot,
    scale: InteractiveChartScale,
    measurer: TextMeasurer,
    style: TextStyle,
) {
    val gutter = (plot.left - 4.dp.toPx()).toInt().coerceAtLeast(1)
    val ticks = interactiveChartTicks(scale)
    val tickStep = ticks.zipWithNext { first, second -> abs(first - second) }.minOrNull()
    ticks.forEach { value ->
        val layout = measurer.measure(
            interactiveChartAxisLabel(value, tickStep), style = style, overflow = TextOverflow.Ellipsis, maxLines = 1,
            constraints = Constraints(maxWidth = gutter),
        )
        drawText(layout, topLeft = Offset(plot.left - layout.size.width - 4.dp.toPx(), plot.y(value, scale) - layout.size.height / 2f))
    }
    val step = ((labels.size + 5) / 6).coerceAtLeast(1)
    val slot = plot.width / labels.size.coerceAtLeast(1)
    labels.forEachIndexed { index, label ->
        if (label.isBlank() || (index % step != 0 && index != labels.lastIndex)) return@forEachIndexed
        val layout = measurer.measure(
            label, style = style, overflow = TextOverflow.Ellipsis, maxLines = 1,
            constraints = Constraints(maxWidth = slot.toInt().coerceAtLeast(1)),
        )
        drawText(layout, topLeft = Offset(plot.left + slot * index + (slot - layout.size.width) / 2f, plot.bottom + 4.dp.toPx()))
    }
}

private fun interactiveChartTicks(scale: InteractiveChartScale): List<Double> =
    listOf(scale.max, scale.min / 2.0 + scale.max / 2.0, scale.min).distinct()

private fun InteractiveChartModel.summary(): String = buildString {
    title?.let { append(it).append(". ") }
    series.forEach { item ->
        if (item.label.isNotBlank()) append(item.label).append(' ')
        append(categories.indices.joinToString { index ->
            val label = categories[index]
            val value = item.valueLabels.getOrNull(index) ?: "—"
            if (label.isBlank()) value else "$label $value"
        })
        append(". ")
    }
}.trim()

private fun Any?.asChartList(): List<Any?>? = when (val value = unwrapChartValue(this)) {
    is List<*> -> value
    else -> null
}

private fun Any?.asChartMap(): Map<*, *>? = unwrapChartValue(this) as? Map<*, *>

private fun Any?.asChartNumber(): Double? = when (val value = unwrapChartValue(this)) {
    is Number -> value.toDouble().takeIf { it.isFinite() }
    is String -> value.toDoubleOrNull()?.takeIf { it.isFinite() }
    else -> null
}

private fun unwrapChartValue(value: Any?): Any? = if (value is JsonElement) value.toInteractiveValue() else value
