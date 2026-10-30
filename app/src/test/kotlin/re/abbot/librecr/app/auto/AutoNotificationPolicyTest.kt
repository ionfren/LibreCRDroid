package re.abbot.librecr.app.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import re.abbot.librecr.app.GLUCOSE_STALE_AFTER_MS
import re.abbot.librecr.app.data.SensorStateStore
import re.abbot.librecr.app.widget.GlucoseWidgetInput
import re.abbot.librecr.app.widget.WidgetReadingStatus

class AutoNotificationPolicyTest {
    private val measuredAt = 10_000_000L
    private val reading = SensorStateStore.LastGlucose(123, 112, "RISING", measuredAt, 1.5)

    @Test
    fun `notifications require both opt in and projection`() {
        listOf(false to false, false to true, true to false).forEach { (enabled, projected) ->
            val session = autoNotificationSession(AutoNotificationSession(), enabled, projected, "new")
            assertFalse(session.canPost)
            assertNull(session.token)
        }
        assertTrue(autoNotificationSession(AutoNotificationSession(), true, true, "new").canPost)
    }

    @Test
    fun `only the first successful post can show a heads up`() {
        val session = autoNotificationSession(AutoNotificationSession(), true, true, "trip")
        assertTrue(session.mayShowInitialHeadsUp)
        val posted = autoNotificationSession(session.copy(posted = true), true, true, "unused")
        assertTrue(posted.canPost)
        assertFalse(posted.mayShowInitialHeadsUp)
        assertEquals("trip", posted.token)
    }

    @Test
    fun `dismissal remains sticky throughout an enabled connection`() {
        val dismissed = dismissAutoNotification(AutoNotificationSession("trip", posted = true), "trip")
        val afterReadingOrRestart = autoNotificationSession(dismissed.copy(), true, true, "unused")
        assertFalse(afterReadingOrRestart.canPost)
        assertFalse(afterReadingOrRestart.mayShowInitialHeadsUp)
        assertEquals(dismissed, afterReadingOrRestart)
    }

    @Test
    fun `disconnect or off clears dismissal and begins a new session`() {
        val dismissed = AutoNotificationSession("trip", dismissed = true, posted = true)
        listOf(false to true, true to false).forEach { (enabled, projected) ->
            val cleared = autoNotificationSession(dismissed, enabled, projected, "unused")
            assertEquals(AutoNotificationSession(), cleared)
            val reconnected = autoNotificationSession(cleared, true, true, "next-trip")
            assertTrue(reconnected.canPost)
            assertTrue(reconnected.mayShowInitialHeadsUp)
            assertEquals("next-trip", reconnected.token)
        }
    }

    @Test
    fun `an old notification action cannot dismiss a new trip`() {
        val current = AutoNotificationSession("new-trip", posted = true)
        assertEquals(current, dismissAutoNotification(current, "old-trip"))
        assertTrue(dismissAutoNotification(current, "old-trip").canPost)
    }

    @Test
    fun `headline preserves the sensor arrow and fractional mg dl delta rate`() {
        val content = autoGlucoseContent(GlucoseWidgetInput(reading), measuredAt + 60_000L)
        assertEquals("112 mg/dL ↗ · Δ +1.5/min", content.headline)
        assertEquals(WidgetReadingStatus.CURRENT, content.presentation.status)
        assertEquals(60L, content.ageSeconds)
    }

    @Test
    fun `keepalive never makes an unchanged measurement appear new`() {
        val first = autoGlucoseContent(GlucoseWidgetInput(reading), measuredAt + 60_000L)
        val keepalive = autoGlucoseContent(GlucoseWidgetInput(reading), measuredAt + 120_000L)
        assertEquals(first.headline, keepalive.headline)
        assertEquals(measuredAt, keepalive.presentation.measuredAtMs)
        assertEquals(120L, keepalive.ageSeconds)
        assertEquals(measuredAt + 120_000L, keepalive.eventAtMs)
    }

    @Test
    fun `stale readings hide all glucose metrics but retain the original age`() {
        val content = autoGlucoseContent(GlucoseWidgetInput(reading), measuredAt + GLUCOSE_STALE_AFTER_MS)
        assertEquals(WidgetReadingStatus.STALE, content.presentation.status)
        assertEquals("— mg/dL — · Δ —/min", content.headline)
        assertEquals(GLUCOSE_STALE_AFTER_MS / 1_000L, content.ageSeconds)
    }

    @Test
    fun `sensor error and unavailable packets cannot display cached glucose as current`() {
        val error = autoGlucoseContent(GlucoseWidgetInput(reading, sensorError = true), measuredAt + 60_000L)
        assertEquals("— mg/dL — · Δ —/min", error.headline)
        assertEquals(measuredAt, error.presentation.measuredAtMs)

        val unavailableAt = measuredAt + 1L
        val unavailable = autoGlucoseContent(GlucoseWidgetInput(reading, unavailableAtMs = unavailableAt),
            measuredAt + 60_000L)
        assertEquals("NA mg/dL — · Δ —/min", unavailable.headline)
        assertEquals(unavailableAt, unavailable.presentation.measuredAtMs)
    }

    @Test
    fun `no reading and warmup never invent a timestamp or glucose`() {
        val missing = autoGlucoseContent(GlucoseWidgetInput(), measuredAt)
        val warming = autoGlucoseContent(GlucoseWidgetInput(warmupStartedAtMs = measuredAt), measuredAt + 60_000L)
        assertEquals(WidgetReadingStatus.NO_READING, missing.presentation.status)
        assertEquals(WidgetReadingStatus.WARMUP, warming.presentation.status)
        listOf(missing, warming).forEach {
            assertNull(it.ageSeconds)
            assertNull(it.presentation.measuredAtMs)
            assertEquals("— mg/dL — · Δ —/min", it.headline)
        }
    }

    @Test
    fun `unknown sensor trend is never inferred from a positive delta`() {
        val content = autoGlucoseContent(GlucoseWidgetInput(reading.copy(trend = "UNKNOWN")), measuredAt)
        assertEquals("112 mg/dL — · Δ +1.5/min", content.headline)
        assertNull(content.presentation.trend)
    }
}
