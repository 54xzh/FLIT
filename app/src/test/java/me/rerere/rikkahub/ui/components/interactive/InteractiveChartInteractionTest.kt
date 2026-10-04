package me.rerere.rikkahub.ui.components.interactive

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractiveChartInteractionTest {
    private val size = Size(320f, 160f)

    private fun model(variant: String, vararg values: List<Double?>): InteractiveChartModel = interactiveChartModel(
        variant, null, listOf("A", "B", "C").take(values.maxOf { it.size }),
        values.mapIndexed { index, points -> mapOf("label" to "S$index", "values" to points) },
    )

    private fun scale(model: InteractiveChartModel) = interactiveChartScale(model.variant, model.series.flatMap { it.values.filterNotNull() })

    private fun positions(model: InteractiveChartModel): List<List<Float?>> {
        val scale = scale(model)
        return model.series.map { item -> item.values.map { it?.let { value -> interactiveChartPosition(value, scale) } } }
    }

    @Test fun `bar taps select the correct grouped series including negative values`() {
        val model = model("bar", listOf(10.0, -5.0), listOf(5.0, -10.0))
        // 第一组的右侧柱子是 S1，第二组的左侧负数柱子是 S0。
        assertEquals(ChartSelection(1, 0), interactiveChartHitTest(model, positions(model), scale(model), size, 1f, Offset(116f, 56f)))
        assertEquals(ChartSelection(0, 1), interactiveChartHitTest(model, positions(model), scale(model), size, 1f, Offset(208f, 88f)))
        assertNull(interactiveChartHitTest(model, positions(model), scale(model), size, 1f, Offset(20f, 56f)))
    }

    @Test fun `line and area taps use animated positions and leave missing points unselectable`() {
        listOf("line", "area").forEach { variant ->
            val model = model(variant, listOf(10.0, null, 30.0))
            val animated = listOf(listOf(0.25f, null, 0.75f))
            assertEquals(ChartSelection(0, 0), interactiveChartHitTest(model, animated, scale(model), size, 1f, Offset(82f, 105.5f)))
            assertNull(interactiveChartHitTest(model, animated, scale(model), size, 1f, Offset(174f, 73f)))
            assertNull(interactiveChartHitTest(model, animated, scale(model), size, 1f, Offset(82f, 20f)))
        }
    }

    @Test fun `overlapping points cycle through series on repeated taps`() {
        val model = model("line", listOf(10.0), listOf(10.0))
        val animated = listOf(listOf(0.5f), listOf(0.5f))
        val tap = Offset(174f, 73f)
        val first = interactiveChartHitTest(model, animated, scale(model), size, 1f, tap)
        assertEquals(ChartSelection(0, 0), first)
        val second = interactiveChartHitTest(model, animated, scale(model), size, 1f, tap, first)
        assertEquals(ChartSelection(1, 0), second)
        assertEquals(first, interactiveChartHitTest(model, animated, scale(model), size, 1f, tap, second))
    }

    @Test fun `pie taps match slice indices across blanks and ignore the hole and gaps`() {
        val model = model("pie", listOf(40.0, null, 60.0))
        val animated = listOf(listOf(0.4f, null, 0.6f))
        assertEquals(ChartSelection(0, 0), interactiveChartHitTest(model, animated, scale(model), size, 1f, Offset(233.6f, 80f)))
        assertEquals(ChartSelection(0, 2), interactiveChartHitTest(model, animated, scale(model), size, 1f, Offset(86.4f, 80f)))
        assertNull(interactiveChartHitTest(model, animated, scale(model), size, 1f, Offset(160f, 80f)))
        assertNull(interactiveChartHitTest(model, animated, scale(model), size, 1f, Offset(160f, 20f)))
        assertNull(interactiveChartHitTest(model, animated, scale(model), size, 1f, Offset(204f, 139f)))
        assertEquals(listOf(0, 2), model.selectablePoints().map { it.pointIndex })
    }

    @Test fun `details retain exact raw values and refresh or disappear with bound updates`() {
        val selected = ChartSelection(0, 0)
        val original = model("line", listOf(100_000_001.00001, 100_000_002.0))
        assertEquals(100_000_001.00001, original.selectionDetails(selected)?.value ?: 0.0, 0.0)
        assertEquals("100000001.00001", interactiveChartSpokenValue(original.selectionDetails(selected)?.value ?: 0.0))
        assertEquals("0.000123456", interactiveChartSpokenValue(0.000123456))
        assertEquals("1.0E100", interactiveChartSpokenValue(1e100))
        assertEquals(25.0, model("line", listOf(25.0)).selectionDetails(selected)?.value ?: 0.0, 0.0)
        assertNull(model("line", listOf(null)).selectionDetails(selected))
        assertNull(original.selectionDetails(ChartSelection(4, 0)))
        assertNull(original.selectionDetails(ChartSelection(0, 8)))
        assertNull(original.selectionDetails(selected)?.share)
    }

    @Test fun `pie shares include only positive first series and cannot overflow the total`() {
        val model = model("pie", listOf(40.0, -30.0, 60.0), listOf(10_000.0))
        assertEquals(0.4, model.selectionDetails(ChartSelection(0, 0))?.share ?: 0.0, 0.000001)
        assertEquals(0.6, model.selectionDetails(ChartSelection(0, 2))?.share ?: 0.0, 0.000001)
        assertNull(model.selectionDetails(ChartSelection(0, 1)))
        assertNull(model.selectionDetails(ChartSelection(1, 0)))
        val huge = model("pie", listOf(Double.MAX_VALUE, Double.MAX_VALUE))
        assertEquals(0.5, huge.selectionDetails(ChartSelection(0, 0))?.share ?: 0.0, 0.0)
    }

    @Test fun `touch targets follow canvas density and zero values are inspectable`() {
        val model = model("bar", listOf(0.0, 10.0))
        val tap = Offset(164f, 275f)
        assertEquals(ChartSelection(0, 0), interactiveChartHitTest(model, positions(model), scale(model), Size(640f, 320f), 2f, tap))
        val detail = model.selectionDetails(ChartSelection(0, 0))
        assertEquals(0.0, detail?.value ?: -1.0, 0.0)
        assertTrue(model.selectablePoints().contains(ChartSelection(0, 0)))
    }

    @Test fun `capsule uses only names values and optional percentage without prefixes or dot separators`() {
        val detail = ChartSelectionDetails("一月", "收入", 120.0, null)
        assertEquals("一月 收入 120", interactiveChartTooltipText(detail))
        val pie = ChartSelectionDetails("食品", "", 120.0, 0.4)
        assertEquals("食品 120（40%）", interactiveChartTooltipText(pie, "120（40%）"))
        val unnamed = interactiveChartModel("line", null, null, listOf(mapOf("values" to listOf(120))))
        val selected = unnamed.selectionDetails(ChartSelection(0, 0))
        assertEquals("120", selected?.let { interactiveChartTooltipText(it) })
    }

    @Test fun `integer source labels survive double precision loss and ordinary decimals remain exact`() {
        val source = Json.parseToJsonElement("""[{"values":[120,9007199254740993,49.999996,49.000004,0.000123456789]}]""")
        val model = interactiveChartModel("line", null, null, source)
        val labels = model.series.single().values.indices.map { model.selectionDetails(ChartSelection(0, it))?.valueLabel }
        assertEquals(listOf("120", "9007199254740993", "50", "49", "0.000123456789"), labels)
    }

    @Test fun `native float values do not expose widened precision or integer boundary noise`() {
        val model = interactiveChartModel("line", null, null, listOf(mapOf("values" to listOf(
            120f, 49.999996f, 49.000004f, -49.000004f, 0.1f, 12.345f, Float.MIN_VALUE,
        ))))
        val labels = model.series.single().values.indices.map { model.selectionDetails(ChartSelection(0, it))?.valueLabel }
        assertEquals(listOf("120", "50", "49", "-49", "0.1", "12.345", "1.4E-45"), labels)
    }

    @Test fun `serialized integer boundary tails are normalized without changing source values`() {
        val source = Json.parseToJsonElement("""[{"values":[49.99996,49.00004,-49.99996,1.99996,12.34567,0.000004,100000001.00001]}]""")
        val chart = interactiveChartModel("line", null, null, source)
        assertEquals(listOf("50", "49", "-50", "2", "12.34567", "0.000004", "100000001.00001"), chart.series.single().valueLabels)
        assertEquals(49.99996, chart.selectionDetails(ChartSelection(0, 0))?.value ?: 0.0, 0.0)
    }

    @Test fun `single series omit legend names while grouped charts retain them`() {
        for (variant in listOf("line", "bar", "area", "pie")) {
            val chart = model(variant, listOf(120.0))
            assertEquals("A 120", interactiveChartTooltipText(chart.selectionDetails(ChartSelection(0, 0)) ?: error("Missing point")))
        }
        val grouped = model("bar", listOf(120.0), listOf(80.0))
        assertEquals("A S1 80", interactiveChartTooltipText(grouped.selectionDetails(ChartSelection(1, 0)) ?: error("Missing point")))
    }

    @Test fun `capsule anchors follow visible line points and correct grouped bars`() {
        val line = model("line", listOf(10.0, 20.0))
        val selected = ChartSelection(0, 0)
        assertEquals(Offset(105f, 105.5f), interactiveChartSelectionAnchor(line, listOf(listOf(0.25f, 0.75f)), scale(line), size, 1f, selected))
        assertEquals(Offset(105f, 73f), interactiveChartSelectionAnchor(line, listOf(listOf(0.5f, 0.75f)), scale(line), size, 1f, selected))
        val bar = model("bar", listOf(10.0), listOf(5.0))
        val anchor = interactiveChartSelectionAnchor(bar, positions(bar), scale(bar), size, 1f, ChartSelection(1, 0))
        assertTrue((anchor?.x ?: 0f) > 174f)
        assertEquals(73f, anchor?.y ?: 0f, 0.001f)
    }

}
