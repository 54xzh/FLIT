package me.rerere.rikkahub.ui.components.interactive

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

internal data class ChartSelection(val seriesIndex: Int, val pointIndex: Int)

internal data class ChartSelectionDetails(
    val category: String,
    val series: String,
    val value: Double,
    val share: Double?,
    val valueLabel: String = interactiveChartSpokenValue(value),
)

internal fun InteractiveChartModel.selectionDetails(selection: ChartSelection?): ChartSelectionDetails? {
    selection ?: return null
    val item = series.getOrNull(selection.seriesIndex) ?: return null
    val value = item.values.getOrNull(selection.pointIndex)?.takeIf { it.isFinite() } ?: return null
    val category = categories.getOrNull(selection.pointIndex) ?: return null
    if (variant == "pie" && (selection.seriesIndex != 0 || value <= 0.0)) return null
    val share = if (variant == "pie") {
        val positive = item.values.filterNotNull().filter { it > 0.0 && it.isFinite() }
        val largest = positive.maxOrNull() ?: return null
        (value / largest) / positive.sumOf { it / largest }
    } else null
    return ChartSelectionDetails(
        category = if (hasCategoryLabels) category else "",
        series = if (variant != "pie" && series.size > 1) item.label else "",
        value = value,
        share = share,
        valueLabel = item.valueLabels.getOrNull(selection.pointIndex) ?: interactiveChartSpokenValue(value),
    )
}

internal fun interactiveChartTooltipText(detail: ChartSelectionDetails, valueText: String = detail.valueLabel): String =
    listOf(detail.category, detail.series, valueText).filter { it.isNotBlank() }.joinToString(" ")

internal fun interactiveChartSelectionAnchor(
    model: InteractiveChartModel,
    positions: List<List<Float?>>,
    scale: InteractiveChartScale,
    size: Size,
    density: Float,
    selection: ChartSelection,
): Offset? {
    if (model.selectionDetails(selection) == null || size.width <= 0f || size.height <= 0f) return null
    if (model.variant == "pie") {
        val slice = interactiveChartPieSlices(positions.firstOrNull().orEmpty())
            .firstOrNull { it.pointIndex == selection.pointIndex } ?: return Offset(size.width / 2f, size.height / 2f)
        val angle = (slice.startAngle + slice.sweepAngle / 2f) * Math.PI.toFloat() / 180f
        val radius = min(size.width, size.height) * 0.46f
        return Offset(size.width / 2f + cos(angle) * radius, size.height / 2f + sin(angle) * radius)
    }
    val value = positions.getOrNull(selection.seriesIndex)?.getOrNull(selection.pointIndex) ?: return null
    val plot = interactiveChartPlot(size, density)
    val point = if (model.variant == "bar") {
        val bar = interactiveChartBars(model, positions, scale, plot).firstOrNull { it.selection == selection } ?: return null
        Offset(bar.bounds.center.x, plot.y(value))
    } else plot.point(selection.pointIndex, model.categories.size, value)
    return Offset(point.x.coerceIn(0f, size.width), point.y.coerceIn(0f, size.height))
}

internal fun InteractiveChartModel.selectablePoints(): List<ChartSelection> =
    series.flatMapIndexed { seriesIndex, item ->
        item.values.indices.mapNotNull { pointIndex ->
            ChartSelection(seriesIndex, pointIndex).takeIf { selectionDetails(it) != null }
        }
    }

internal data class ChartPlot(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = (right - left).coerceAtLeast(1f)
    val height get() = (bottom - top).coerceAtLeast(1f)
    fun y(position: Float): Float = bottom - position * height
    fun y(value: Double, scale: InteractiveChartScale): Float = y(interactiveChartPosition(value, scale))
    fun baseline(scale: InteractiveChartScale) = y(0.0, scale)
    fun point(index: Int, count: Int, position: Float): Offset =
        Offset(left + width / count.coerceAtLeast(1) * (index + 0.5f), y(position))
}

internal fun interactiveChartPlot(size: Size, density: Float): ChartPlot =
    ChartPlot(36f * density, 8f * density, size.width - 8f * density, size.height - 22f * density)

internal data class ChartBar(val selection: ChartSelection, val bounds: Rect)

/** 绘制与点击共用柱子位置，包含负数、零值和动画中的位置。 */
internal fun interactiveChartBars(
    model: InteractiveChartModel,
    positions: List<List<Float?>>,
    scale: InteractiveChartScale,
    plot: ChartPlot,
): List<ChartBar> {
    val slot = plot.width / model.categories.size.coerceAtLeast(1)
    val groupWidth = slot * 0.72f
    val barWidth = groupWidth / positions.size.coerceAtLeast(1)
    val baseline = plot.baseline(scale)
    return model.categories.indices.flatMap { pointIndex ->
        positions.mapIndexedNotNull { seriesIndex, values ->
            val position = values.getOrNull(pointIndex) ?: return@mapIndexedNotNull null
            val y = plot.y(position)
            val left = plot.left + pointIndex * slot + (slot - groupWidth) / 2 + seriesIndex * barWidth
            val top = min(baseline, y)
            ChartBar(ChartSelection(seriesIndex, pointIndex), Rect(left, top, left + barWidth * 0.86f, top + abs(y - baseline).coerceAtLeast(1f)))
        }
    }
}

internal data class ChartPieSlice(val pointIndex: Int, val startAngle: Float, val sweepAngle: Float)

internal fun interactiveChartPieSlices(values: List<Float?>): List<ChartPieSlice> {
    val positive = values.mapIndexedNotNull { index, value ->
        value?.takeIf { it.isFinite() && it > 0f }?.let { index to it }
    }
    val sum = positive.sumOf { it.second.toDouble() }
    if (sum <= 0.0) return emptyList()
    var start = -90f
    return positive.map { (index, value) ->
        val sweep = (value / sum * 360.0).toFloat()
        ChartPieSlice(index, start, (sweep - 1.5f).coerceAtLeast(0.5f)).also { start += sweep }
    }
}

/** 点击使用当时的动画位置；空白和缺失数据不会产生详情。重合节点可连续点击切换。 */
internal fun interactiveChartHitTest(
    model: InteractiveChartModel,
    positions: List<List<Float?>>,
    scale: InteractiveChartScale,
    size: Size,
    density: Float,
    tap: Offset,
    previous: ChartSelection? = null,
): ChartSelection? {
    if (size.width <= 0f || size.height <= 0f) return null
    if (model.variant == "pie") {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = min(size.width, size.height) * 0.46f
        val distance = hypot(tap.x - center.x, tap.y - center.y)
        val tolerance = max(radius * 0.18f, 12f * density)
        if (distance == 0f || abs(distance - radius) > tolerance) return null
        val angle = ((atan2(tap.y - center.y, tap.x - center.x) * 180f / Math.PI.toFloat()) + 450f) % 360f
        return interactiveChartPieSlices(positions.firstOrNull().orEmpty()).firstOrNull { slice ->
            val start = slice.startAngle + 90f
            angle >= start && angle < start + slice.sweepAngle
        }?.let { ChartSelection(0, it.pointIndex) }?.takeIf { model.selectionDetails(it) != null }
    }
    val plot = interactiveChartPlot(size, density)
    if (tap.x !in plot.left..plot.right || tap.y !in plot.top..plot.bottom) return null
    val radius = if (model.variant == "bar") 12f * density else 24f * density
    val candidates = if (model.variant == "bar") {
        interactiveChartBars(model, positions, scale, plot).map { bar ->
            val rect = bar.bounds
            val nearest = Offset(tap.x.coerceIn(rect.left, rect.right), tap.y.coerceIn(rect.top, rect.bottom))
            bar.selection to (nearest - tap).getDistanceSquared()
        }
    } else {
        positions.flatMapIndexed { seriesIndex, values ->
            values.mapIndexedNotNull { pointIndex, value ->
                value ?: return@mapIndexedNotNull null
                val point = plot.point(pointIndex, model.categories.size, value)
                if (point.y !in plot.top..plot.bottom) return@mapIndexedNotNull null
                ChartSelection(seriesIndex, pointIndex) to (point - tap).getDistanceSquared()
            }
        }
    }.filter { (selection, distance) -> distance <= radius * radius && model.selectionDetails(selection) != null }
        .sortedBy { it.second }
    val closest = candidates.firstOrNull()?.second ?: return null
    val overlapping = candidates.takeWhile { abs(it.second - closest) <= 0.25f }.map { it.first }
    val previousIndex = overlapping.indexOf(previous)
    return overlapping[(previousIndex + 1) % overlapping.size]
}
