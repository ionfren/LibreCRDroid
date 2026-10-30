package re.abbot.librecr.app.idrive

import org.junit.Assert.*
import org.junit.Test
import re.abbot.librecr.app.GLUCOSE_STALE_AFTER_MS
import re.abbot.librecr.app.data.GlucoseUnit
import re.abbot.librecr.app.data.SensorStateStore
import re.abbot.librecr.app.stats.GlucoseSample
import re.abbot.librecr.app.widget.GlucoseWidgetInput
import re.abbot.librecr.app.widget.WidgetReadingStatus

class IDriveGlucosePresentationTest {
    private val at = 10_000_000L
    private val reading = SensorStateStore.LastGlucose(123, 126, "RISING", at, -1.8)

    @Test fun `uses the selected unit for both glucose and delta and the sensor trend`() {
        val result = idriveGlucosePresentation(GlucoseWidgetInput(reading), GlucoseUnit.MMOL_L, at + 61_000)
        assertEquals("7.0", result.value)
        assertEquals("mmol/L", result.unit)
        assertEquals("-0.1", result.delta)
        assertEquals("RISING", result.trend) // Never infer the trend from delta.
        assertEquals(1L, result.ageMinutes)
        assertEquals(at, result.measuredAtMs)
        assertEquals("-1.8", idriveGlucosePresentation(GlucoseWidgetInput(reading), GlucoseUnit.MG_DL, at).delta)
    }

    @Test fun `time alone expires a reading and preserves its real timestamp`() {
        val input = GlucoseWidgetInput(reading)
        assertEquals("126", idriveGlucosePresentation(input, GlucoseUnit.MG_DL, at + GLUCOSE_STALE_AFTER_MS - 1).value)
        val stale = idriveGlucosePresentation(input, GlucoseUnit.MG_DL, at + GLUCOSE_STALE_AFTER_MS)
        assertEquals(WidgetReadingStatus.STALE, stale.status)
        assertEquals("—", stale.value)
        assertEquals("—", stale.delta)
        assertNull(stale.trend)
        assertEquals(6L, stale.ageMinutes)
        assertEquals(at, stale.measuredAtMs)
    }

    @Test fun `sensor error and unavailable packets suppress a fresh value`() {
        listOf(
            GlucoseWidgetInput(reading, sensorError = true),
            GlucoseWidgetInput(reading, unavailableAtMs = at + 1),
        ).forEach { input ->
            val result = idriveGlucosePresentation(input, GlucoseUnit.MG_DL, at + 1000)
            assertEquals("—", result.value)
            assertEquals("—", result.delta)
            assertNull(result.trend)
        }
    }

    @Test fun `no data warmup and invalid timestamps have explicit noncurrent states`() {
        assertEquals(WidgetReadingStatus.NO_READING,
            idriveGlucosePresentation(GlucoseWidgetInput(), GlucoseUnit.MG_DL, at).status)
        assertEquals(WidgetReadingStatus.WARMUP,
            idriveGlucosePresentation(GlucoseWidgetInput(warmupStartedAtMs = at), GlucoseUnit.MG_DL, at).status)
        val future = idriveGlucosePresentation(GlucoseWidgetInput(reading.copy(receivedAtMs = at + 1)), GlucoseUnit.MG_DL, at)
        assertEquals("—", future.value)
        assertNull(future.ageMinutes)
        assertNull(future.measuredAtMs)
    }

    @Test fun `unknown trends and nonfinite deltas are hidden`() {
        val result = idriveGlucosePresentation(GlucoseWidgetInput(reading.copy(trend = "?", deltaMgDlPerMin = Double.NaN)),
            GlucoseUnit.MG_DL, at)
        assertNull(result.trend)
        assertEquals("—", result.delta)
    }

    @Test fun `glucose coloring retains the selected targets`() {
        val result = idriveGlucosePresentation(GlucoseWidgetInput(reading), GlucoseUnit.MG_DL, at, 80, 160)
        assertEquals(126, result.mgDl)
        assertEquals(80, result.targetLow)
        assertEquals(160, result.targetHigh)
        assertNull(idriveGlucosePresentation(GlucoseWidgetInput(reading), GlucoseUnit.MG_DL,
            at + GLUCOSE_STALE_AFTER_MS).mgDl)
    }

    @Test fun `seconds alone do not change transmitted content within the same minute`() {
        val minute = at / 60_000L * 60_000L
        val input = GlucoseWidgetInput(reading.copy(receivedAtMs = minute))
        assertEquals(idriveGlucosePresentation(input, GlucoseUnit.MG_DL, minute + 1_000),
            idriveGlucosePresentation(input, GlucoseUnit.MG_DL, minute + 59_000))
    }

    @Test fun `chart merges history and live uncapped samples by minute within three hours`() {
        val now = 20_000_000L / 60_000 * 60_000
        val recent = reading.copy(mgDL = 40, chartMgDL = 32, receivedAtMs = now - 30_000)
        val chart = idriveGlucoseChart(listOf(
            GlucoseSample(110, now - 60_000), GlucoseSample(90, now - 120_000),
            GlucoseSample(80, now - IDRIVE_CHART_WINDOW_MS - 60_000),
            GlucoseSample(100, now + 1), GlucoseSample(0, now), GlucoseSample(120, 0),
        ), listOf(recent), now)
        assertEquals(IDRIVE_CHART_WINDOW_MS, chart.endMs - chart.startMs)
        assertEquals(listOf(90, 32), chart.points.map { it.mgDl })
        assertEquals(listOf(now - 120_000, now - 60_000), chart.points.map { it.atMs })
    }

    @Test fun `expired glucose leaves history at its timestamp without extending the graph`() {
        val now = at + GLUCOSE_STALE_AFTER_MS
        val result = idriveGlucosePresentation(GlucoseWidgetInput(reading), GlucoseUnit.MG_DL, now)
        assertEquals("—", result.value)
        assertEquals(126, result.chart.points.single().mgDl)
        assertEquals(at / 60_000 * 60_000, result.chart.points.single().atMs)
        assertTrue(result.chart.points.last().atMs < result.chart.endMs)
    }

    @Test fun `capped headline does not replace the same reading uncapped chart value`() {
        val uncapped = reading.copy(mgDL = 40, chartMgDL = 32)
        val result = idriveGlucosePresentation(GlucoseWidgetInput(uncapped.copy(chartMgDL = null)),
            GlucoseUnit.MG_DL, at, recent = listOf(uncapped))
        assertEquals("40", result.value)
        assertEquals(32, result.chart.points.single().mgDl)
    }
}
