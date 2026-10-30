package re.abbot.librecr.app.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import re.abbot.librecr.app.GLUCOSE_STALE_AFTER_MS
import re.abbot.librecr.app.data.SensorStateStore

class GlucoseWidgetStateTest {
    private val measuredAt = 10_000_000L
    private val reading = SensorStateStore.LastGlucose(123, 112, "RISING", measuredAt, 1.5)

    @Test
    fun `reading keeps measurement timestamp and fractional delta rate`() {
        val result = glucoseWidgetPresentation(GlucoseWidgetInput(reading), measuredAt + 60_000L)
        assertEquals(WidgetReadingStatus.CURRENT, result.status)
        assertEquals("112", result.value)
        assertEquals("RISING", result.trend)
        assertEquals("+1.5", result.deltaPerMinute)
        assertEquals(measuredAt, result.measuredAtMs)
    }

    @Test
    fun `stale threshold hides glucose trend and delta without resetting the age`() {
        val input = GlucoseWidgetInput(reading)
        assertEquals(WidgetReadingStatus.CURRENT, glucoseWidgetPresentation(input, measuredAt + GLUCOSE_STALE_AFTER_MS - 1).status)
        val result = glucoseWidgetPresentation(input, measuredAt + GLUCOSE_STALE_AFTER_MS)
        assertEquals(WidgetReadingStatus.STALE, result.status)
        assertEquals("—", result.value)
        assertNull(result.trend)
        assertEquals("—", result.deltaPerMinute)
        assertEquals(measuredAt, result.measuredAtMs)
    }

    @Test
    fun `new unavailable packet suppresses the previous glucose`() {
        val result = glucoseWidgetPresentation(
            GlucoseWidgetInput(reading, unavailableAtMs = measuredAt + 30_000L),
            measuredAt + 60_000L,
        )
        assertEquals(WidgetReadingStatus.UNAVAILABLE, result.status)
        assertEquals("NA", result.value)
        assertNull(result.trend)
        assertEquals("—", result.deltaPerMinute)
        assertEquals(measuredAt + 30_000L, result.measuredAtMs)
    }

    @Test
    fun `NA without any prior numeric reading keeps its own timestamp`() {
        val result = glucoseWidgetPresentation(GlucoseWidgetInput(unavailableAtMs = measuredAt), measuredAt + 60_000L)
        assertEquals("NA", result.value)
        assertEquals(measuredAt, result.measuredAtMs)
        assertNull(result.trend)
    }

    @Test
    fun `an unavailable packet older than the accepted reading does not hide it`() {
        val result = glucoseWidgetPresentation(
            GlucoseWidgetInput(reading, unavailableAtMs = measuredAt - 1), measuredAt,
        )
        assertEquals(WidgetReadingStatus.CURRENT, result.status)
    }

    @Test
    fun `sensor attention suppresses even a fresh cached value`() {
        val result = glucoseWidgetPresentation(GlucoseWidgetInput(reading, sensorError = true), measuredAt)
        assertEquals(WidgetReadingStatus.SENSOR_ERROR, result.status)
        assertEquals("—", result.value)
        assertEquals("—", result.deltaPerMinute)
    }

    @Test
    fun `warmup expires and a real reading overrides the activation marker`() {
        val input = GlucoseWidgetInput(warmupStartedAtMs = measuredAt, warmupDurationMinutes = 60)
        assertEquals(WidgetReadingStatus.WARMUP, glucoseWidgetPresentation(input, measuredAt + 59 * 60_000L).status)
        assertEquals(WidgetReadingStatus.NO_READING, glucoseWidgetPresentation(input, measuredAt + 60 * 60_000L).status)
        assertEquals(WidgetReadingStatus.CURRENT, glucoseWidgetPresentation(input.copy(reading = reading), measuredAt).status)
    }

    @Test
    fun `missing or invalid readings cannot become current`() {
        listOf(null, reading.copy(mgDL = 0), reading.copy(receivedAtMs = 0), reading.copy(receivedAtMs = measuredAt + 1)).forEach {
            val result = glucoseWidgetPresentation(GlucoseWidgetInput(it), measuredAt)
            assertEquals(WidgetReadingStatus.NO_READING, result.status)
            assertEquals("—", result.value)
            assertNull(result.measuredAtMs)
        }
    }

    @Test
    fun `unknown trend is never inferred from delta and nonfinite deltas are hidden`() {
        val result = glucoseWidgetPresentation(GlucoseWidgetInput(reading.copy(trend = "UNKNOWN", deltaMgDlPerMin = Double.NaN)), measuredAt)
        assertNull(result.trend)
        assertEquals("—", result.deltaPerMinute)
        assertEquals("+0", glucoseWidgetPresentation(GlucoseWidgetInput(reading.copy(deltaMgDlPerMin = -0.001)), measuredAt).deltaPerMinute)
    }
}
