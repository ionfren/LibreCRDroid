package re.abbot.librecr.app.wear.complication

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import re.abbot.librecr.app.data.SensorStateStore

class GlucoseComplicationFreshnessTest {
    @Test
    fun `last glucose becomes stale at exactly 120 seconds`() {
        val now = 1_000_000L
        val reading = SensorStateStore.LastGlucose(
            lifeCount = 123,
            mgDL = 101,
            trend = "STABLE",
            receivedAtMs = now - 119_999L,
            deltaMgDlPerMin = 0.0,
        )

        assertTrue(GlucoseComplicationRenderer.isFresh(reading, now))
        assertFalse(GlucoseComplicationRenderer.isFresh(reading, now + 1L))
    }
}
