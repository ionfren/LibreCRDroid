package re.abbot.librecr.app.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import re.abbot.librecr.app.GLUCOSE_STALE_AFTER_MS
import re.abbot.librecr.app.data.GlucoseUnit
import re.abbot.librecr.app.data.SensorStateStore
import re.abbot.librecr.app.stats.GlucoseSample

class GlucoseWidgetUiTest {
    private val now = 50_000_000L

    private fun reading(mgDl: Int, delta: Double, chartMgDl: Int? = null) =
        SensorStateStore.LastGlucose(7, mgDl, "STABLE", now - 30_000L, delta, chartMgDl)

    @Test
    fun `range buckets use the clinical edges around the user target`() {
        assertEquals(WidgetGlucoseRange.VERY_LOW, widgetGlucoseRange(53, 70, 180))
        assertEquals(WidgetGlucoseRange.LOW, widgetGlucoseRange(54, 70, 180))
        assertEquals(WidgetGlucoseRange.LOW, widgetGlucoseRange(69, 70, 180))
        assertEquals(WidgetGlucoseRange.IN_RANGE, widgetGlucoseRange(70, 70, 180))
        assertEquals(WidgetGlucoseRange.IN_RANGE, widgetGlucoseRange(180, 70, 180))
        assertEquals(WidgetGlucoseRange.HIGH, widgetGlucoseRange(181, 70, 180))
        assertEquals(WidgetGlucoseRange.HIGH, widgetGlucoseRange(250, 70, 180))
        assertEquals(WidgetGlucoseRange.VERY_HIGH, widgetGlucoseRange(251, 70, 180))
    }

    @Test
    fun `current reading follows the selected unit and target`() {
        val ui = glucoseWidgetUi(
            GlucoseWidgetModel(GlucoseWidgetInput(reading(126, -1.8)), unit = GlucoseUnit.MMOL_L, targetLow = 130),
            now,
        )
        assertEquals(WidgetReadingStatus.CURRENT, ui.status)
        assertEquals("7.0", ui.value)
        assertEquals("mmol/L", ui.unit)
        assertEquals("-0.1", ui.delta)
        assertEquals(WidgetGlucoseRange.LOW, ui.range)
    }

    @Test
    fun `tiny mmol rates never show negative zero`() {
        val ui = glucoseWidgetUi(GlucoseWidgetModel(GlucoseWidgetInput(reading(126, -0.5)), unit = GlucoseUnit.MMOL_L), now)
        assertEquals("+0.0", ui.delta)
    }

    @Test
    fun `mg per dL keeps the shared presentation rate`() {
        val ui = glucoseWidgetUi(GlucoseWidgetModel(GlucoseWidgetInput(reading(112, 1.5))), now)
        assertEquals("112", ui.value)
        assertEquals("mg/dL", ui.unit)
        assertEquals("+1.5", ui.delta)
        assertEquals(WidgetGlucoseRange.IN_RANGE, ui.range)
    }

    @Test
    fun `stale data hides range and rate but keeps its age and chart`() {
        val old = reading(140, 1.0).copy(receivedAtMs = now - GLUCOSE_STALE_AFTER_MS - 1)
        val ui = glucoseWidgetUi(
            GlucoseWidgetModel(GlucoseWidgetInput(old), history = listOf(GlucoseSample(140, old.receivedAtMs))),
            now,
        )
        assertEquals(WidgetReadingStatus.STALE, ui.status)
        assertEquals("—", ui.value)
        assertNull(ui.range)
        assertNull(ui.delta)
        assertEquals(old.receivedAtMs, ui.measuredAtMs)
        assertEquals(listOf(140), ui.chart.points.map { it.mgDl })
    }

    @Test
    fun `the capped headline does not overwrite the ring's uncapped chart value`() {
        val ring = reading(40, -2.0, chartMgDl = 33)
        val headline = ring.copy(chartMgDL = null)
        val ui = glucoseWidgetUi(GlucoseWidgetModel(GlucoseWidgetInput(headline), recent = listOf(ring)), now)
        assertEquals(listOf(33), ui.chart.points.map { it.mgDl })
    }

    @Test
    fun `warmup reports progress and the minutes left`() {
        val ui = glucoseWidgetUi(
            GlucoseWidgetModel(GlucoseWidgetInput(warmupStartedAtMs = now - 15 * 60_000L, warmupDurationMinutes = 60)),
            now,
        )
        assertEquals(WidgetReadingStatus.WARMUP, ui.status)
        assertEquals(0.25f, ui.warmupProgress!!, 0.0001f)
        assertEquals(45, ui.warmupRemainingMinutes)
    }

    @Test
    fun `picker preview is a current in-range sample inside the chart window`() {
        val ui = previewGlucoseWidgetUi(now)
        assertTrue(ui.preview)
        assertEquals(WidgetReadingStatus.CURRENT, ui.status)
        assertEquals(WidgetGlucoseRange.IN_RANGE, ui.range)
        assertEquals(37, ui.chart.points.size)
        assertTrue(ui.chart.points.all { it.atMs in ui.chart.startMs..ui.chart.endMs })
    }
}
