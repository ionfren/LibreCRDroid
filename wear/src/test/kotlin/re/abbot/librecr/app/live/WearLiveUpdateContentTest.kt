package re.abbot.librecr.app.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import re.abbot.librecr.app.WEAR_GLUCOSE_DISPLAY_HOLD_MS
import re.abbot.librecr.app.ble.GlucoseUi
import re.abbot.librecr.app.data.GlucoseUnit
import re.abbot.librecr.app.data.SensorStateStore

class WearLiveUpdateContentTest {
    private val receivedAt = 1_000_000L
    private val persisted = SensorStateStore.LastGlucose(100, 120, "RISING", receivedAt, 2.0)

    private fun content(
        live: GlucoseUi? = null,
        reading: SensorStateStore.LastGlucose? = persisted,
        unit: GlucoseUnit = GlucoseUnit.MG_DL,
        nowMs: Long = receivedAt,
        sensorStatus: SensorStateStore.SensorStatusSnapshot? = null,
    ) = WearLiveUpdateContent.from(live, reading, unit, sensorStatus, nowMs = nowMs)

    @Test
    fun `live memory wins before the persisted reading is committed`() {
        val live = GlucoseUi(126, "FALLING", 101, true, receivedAt + 60_000L, -3.0)
        val result = content(live = live, nowMs = live.receivedAtMs)
        assertEquals(126, result.reading?.mgDL)
        assertEquals("↘ 126 Δ -3", result.primaryText)
        assertEquals("126  ·  -3 · 0′", result.chipText(live.receivedAtMs))
    }

    @Test
    fun `phone relayed persisted reading has the same display and elapsed minute age`() {
        val result = content(nowMs = receivedAt + 125_000L)
        assertEquals("↗ 120 Δ +2", result.primaryText)
        assertEquals(2L, result.ageMinutes(receivedAt + 125_000L))
        assertEquals(55_000L, result.nextRefreshDelayMs(receivedAt + 125_000L))
    }

    @Test
    fun `expiry hides glucose and delta even with no connection state change`() {
        val beforeExpiry = content(nowMs = receivedAt + WEAR_GLUCOSE_DISPLAY_HOLD_MS - 1L)
        assertEquals("120", beforeExpiry.value)
        assertEquals(1L, beforeExpiry.nextRefreshDelayMs(receivedAt + WEAR_GLUCOSE_DISPLAY_HOLD_MS - 1L))
        val expired = content(nowMs = receivedAt + WEAR_GLUCOSE_DISPLAY_HOLD_MS)
        assertEquals(WearLiveUpdateContent.Status.UNAVAILABLE, expired.status)
        assertNull(expired.reading)
        assertEquals("--", expired.delta)
        assertNull(expired.nextRefreshDelayMs(receivedAt + WEAR_GLUCOSE_DISPLAY_HOLD_MS))
    }

    @Test
    fun `unavailable live packet never falls back to an earlier valid value`() {
        val unavailable = GlucoseUi(null, "UNKNOWN", 101, false, receivedAt + 60_000L)
        val result = content(live = unavailable, nowMs = unavailable.receivedAtMs)
        assertEquals(WearLiveUpdateContent.Status.VALUE_UNAVAILABLE, result.status)
        assertNull(result.reading)
        assertEquals("NA", result.value)
        assertEquals("", result.arrow)
        assertEquals("--", result.delta)
        assertEquals("NA", result.primaryText)
        assertTrue(result.hasFreshObservation)
        assertEquals(unavailable.receivedAtMs, result.observedAtMs)
        assertEquals("NA · 0′", result.chipText(unavailable.receivedAtMs))
        assertEquals("NA · 2′", result.chipText(unavailable.receivedAtMs + 125_000L))
        assertEquals(55_000L, result.nextRefreshDelayMs(unavailable.receivedAtMs + 125_000L))
    }

    @Test
    fun `NA expires at the same boundary as a numeric packet without reviving persistence`() {
        val unavailable = GlucoseUi(null, "RISING", 101, false, receivedAt)
        val before = content(live = unavailable, nowMs = receivedAt + WEAR_GLUCOSE_DISPLAY_HOLD_MS - 1L)
        assertEquals("NA", before.value)
        assertEquals(1L, before.nextRefreshDelayMs(receivedAt + WEAR_GLUCOSE_DISPLAY_HOLD_MS - 1L))
        val expired = content(live = unavailable, nowMs = receivedAt + WEAR_GLUCOSE_DISPLAY_HOLD_MS)
        assertNull(expired.reading)
        assertFalse(expired.hasFreshObservation)
        assertEquals("--", expired.chipText(receivedAt + WEAR_GLUCOSE_DISPLAY_HOLD_MS))
        assertNull(expired.nextRefreshDelayMs(receivedAt + WEAR_GLUCOSE_DISPLAY_HOLD_MS))
    }

    @Test
    fun `bad quality numeric packets and missing values both display NA in either unit`() {
        listOf(GlucoseUi(125, "RISING", 101, false, receivedAt, 5.0),
            GlucoseUi(null, "RISING", 101, true, receivedAt)).forEach { live ->
            GlucoseUnit.entries.forEach { unit ->
                val result = content(live = live, unit = unit)
                assertEquals("NA", result.value)
                assertEquals("", result.arrow)
                assertEquals("--", result.delta)
            }
        }
    }

    @Test
    fun `a new NA resets elapsed minutes and a usable packet restores numeric display`() {
        val unavailable = GlucoseUi(null, "UNKNOWN", 101, false, receivedAt + 120_000L)
        val result = content(live = unavailable, nowMs = unavailable.receivedAtMs)
        assertEquals("NA · 0′", result.chipText(unavailable.receivedAtMs))
        val recovered = content(live = unavailable.copy(mgDL = 126, usable = true,
            receivedAtMs = receivedAt + 180_000L), nowMs = receivedAt + 180_000L)
        assertEquals(WearLiveUpdateContent.Status.READING, recovered.status)
        assertEquals("126", recovered.value)
    }

    @Test
    fun `warmup and patch errors cannot show a previous glucose`() {
        val warmup = GlucoseUi(null, "UNKNOWN", 1, false, receivedAt)
        assertEquals(WearLiveUpdateContent.Status.WARMUP, content(live = warmup).status)
        val error = SensorStateStore.SensorStatusSnapshot(1, 0, receivedAt)
        val result = content(sensorStatus = error)
        assertEquals(WearLiveUpdateContent.Status.SENSOR_ERROR, result.status)
        assertNull(result.reading)
    }

    @Test
    fun `mmol conversion keeps value delta and elapsed minutes in the chip`() {
        val result = content(reading = persisted.copy(mgDL = 108, deltaMgDlPerMin = 3.6), unit = GlucoseUnit.MMOL_L)
        assertEquals("6.0", result.value)
        assertEquals("+0.2", result.delta)
        assertEquals("6.0  ·  +0.2 · 0′", result.chipText(receivedAt))
        assertEquals("6.0  ·  +0.2 · 2′", result.chipText(receivedAt + 125_000L))
    }

    @Test
    fun `invalid deltas and unknown trends do not invent trend data`() {
        listOf(null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { delta ->
            val result = content(reading = persisted.copy(trend = "UNKNOWN", deltaMgDlPerMin = delta))
            assertEquals("", result.arrow)
            assertEquals("--", result.delta)
        }
        assertEquals("", content(reading = persisted.copy(trend = "UNKNOWN", deltaMgDlPerMin = 5.0)).arrow)
    }

    @Test
    fun `missing timestamp is never shown and clock skew cannot make a negative age`() {
        assertNull(content(reading = persisted.copy(receivedAtMs = 0L)).reading)
        assertNull(content(reading = null).nextRefreshDelayMs(receivedAt))
        assertEquals(0L, content().ageMinutes(receivedAt - 10_000L))
    }

    @Test
    fun `chip age increments at the minute boundary and resets on a new reading`() {
        val result = content()
        assertEquals("120  ·  +2 · 0′", result.chipText(receivedAt + 59_999L))
        assertEquals("120  ·  +2 · 1′", result.chipText(receivedAt + 60_000L))
        assertEquals("120  ·  +2 · 2′", result.chipText(receivedAt + 120_000L))
        val newer = content(reading = persisted.copy(receivedAtMs = receivedAt + 120_000L),
            nowMs = receivedAt + 125_000L)
        assertEquals("120  ·  +2 · 0′", newer.chipText(receivedAt + 125_000L))
        assertEquals("--", content(reading = null).chipText(receivedAt))
    }
}
