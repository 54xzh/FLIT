package me.rerere.rikkahub.ui.components.interactive

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractiveChartTest {
    @Test fun `series line up with categories and drop invalid numbers without shifting later points`() {
        val model = interactiveChartModel(
            variant = "line",
            title = "Spend",
            categories = listOf("Jan", "Feb", "Mar"),
            series = listOf(
                mapOf("label" to "Food", "values" to listOf(12, Double.NaN, 18)),
                mapOf("label" to "Transit", "values" to listOf(4)),
            ),
        )
        assertEquals("line", model.variant)
        assertEquals(listOf("Jan", "Feb", "Mar"), model.categories)
        assertEquals(listOf(12.0, null, 18.0), model.series[0].values)
        assertEquals(listOf(4.0, null, null), model.series[1].values)
    }

    @Test fun `display keeps at most four series and twelve points`() {
        val series = List(6) { index -> mapOf("label" to "S$index", "values" to List(20) { it.toDouble() }) }
        val model = interactiveChartModel("radar", null, null, series)
        assertEquals("bar", model.variant)
        assertEquals(InteractiveChartLimits.MaxSeries, model.series.size)
        assertEquals(InteractiveChartLimits.MaxPoints, model.series.first().values.size)
        assertEquals("1", model.categories.first())
        assertNull(model.title)
    }

    @Test fun `json series and bar scale include a zero baseline`() {
        val parsed = Json.parseToJsonElement("""[{"label":"Food","values":[5,8]}]""")
        val model = interactiveChartModel("bar", " ", listOf("A", "B"), parsed)
        assertNull(model.title)
        assertEquals(listOf(5.0, 8.0), model.series.single().values)
        assertEquals(InteractiveChartScale(0.0, 8.0), interactiveChartScale("bar", listOf(5.0, 8.0)))
        val trend = interactiveChartScale("line", listOf(100.0, 110.0))
        assertEquals(98.8, trend.min, 0.001)
        assertEquals(111.2, trend.max, 0.001)
    }

    @Test fun `axis labels stay short for large magnitudes`() {
        assertEquals("1.2k", interactiveChartAxisLabel(1200.0))
        assertEquals("-2M", interactiveChartAxisLabel(-2_000_000.0))
        assertEquals("0", interactiveChartAxisLabel(0.0))
        assertEquals("999.9", interactiveChartAxisLabel(999.9))
        assertEquals("1.0E25", interactiveChartAxisLabel(1.0e25))
        assertEquals("1200", interactiveChartSpokenValue(1200.0))
    }

    @Test fun `large values with small differences retain distinct positions`() {
        val values = listOf(100_000_001.0, 100_000_002.0)
        val scale = interactiveChartScale("line", values)
        val positions = values.map { interactiveChartPosition(it, scale) }
        assertEquals(0.09677419f, positions[0], 0.00001f)
        assertEquals(0.9032258f, positions[1], 0.00001f)
        assertTrue(positions[1] - positions[0] > 0.8f)
    }

    @Test fun `animation baselines remain in the plot for positive negative and updated ranges`() {
        listOf(listOf(100.0, 110.0), listOf(-110.0, -100.0), listOf(-10.0, 10.0), listOf(1_000_000.0, 2_000_000.0))
            .forEach { values ->
                val scale = interactiveChartScale("line", values)
                val baseline = interactiveChartPosition(0.0, scale)
                assertTrue(baseline in 0f..1f)
                values.forEach { assertTrue(interactiveChartPosition(it, scale) in 0f..1f) }
            }
        assertEquals(0f, interactiveChartPosition(0.0, interactiveChartScale("line", listOf(100.0, 110.0))), 0f)
        assertEquals(1f, interactiveChartPosition(0.0, interactiveChartScale("line", listOf(-110.0, -100.0))), 0f)
    }

    @Test fun `finite values beyond float range and spanning double range retain finite positions`() {
        listOf(listOf(1e100, 2e100), listOf(-Double.MAX_VALUE, Double.MAX_VALUE), listOf(Double.MAX_VALUE))
            .forEach { values ->
                val scale = interactiveChartScale("line", values)
                assertTrue(scale.min.isFinite())
                assertTrue(scale.max.isFinite())
                values.forEach { assertTrue(interactiveChartPosition(it, scale) in 0f..1f) }
            }
        val scale = interactiveChartScale("line", listOf(-Double.MAX_VALUE, Double.MAX_VALUE))
        assertEquals(0.5f, interactiveChartPosition(0.0, scale), 0f)
    }

    @Test fun `decimal axis ticks remain distinct instead of rounding to zero`() {
        assertEquals(listOf("0.02", "0.01", "0"), listOf(0.02, 0.01, 0.0).map { interactiveChartAxisLabel(it, 0.01) })
        assertEquals("-0.002", interactiveChartAxisLabel(-0.002, 0.001))
        assertEquals("0.012", interactiveChartAxisLabel(0.012, 0.001))
        assertEquals("1.0E-8", interactiveChartAxisLabel(1e-8, 1e-8))
        assertEquals("0.01", interactiveChartAxisLabel(0.01))
    }
}
