package re.abbot.librecr.app.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlucoseDisplayTest {
    @Test
    fun `warmup is active before configured duration`() {
        val startedAtMs = 1_000_000L

        assertTrue(
            isSensorWarmupActive(
                startedAtMs = startedAtMs,
                warmupDurationMinutes = 60,
                nowMs = startedAtMs + 59 * 60_000L,
            ),
        )
    }

    @Test
    fun `warmup ends at configured duration`() {
        val startedAtMs = 1_000_000L

        assertFalse(
            isSensorWarmupActive(
                startedAtMs = startedAtMs,
                warmupDurationMinutes = 60,
                nowMs = startedAtMs + 60 * 60_000L,
            ),
        )
    }

    @Test
    fun `missing activation marker is not warmup`() {
        assertFalse(isSensorWarmupActive(null, warmupDurationMinutes = 60, nowMs = 1_000_000L))
    }

    @Test
    fun `unusable or missing glucose is a fresh NA observation until expiry`() {
        val at = 1_000_000L
        listOf(GlucoseUi(null, "UNKNOWN", 100, false, at),
            GlucoseUi(120, "RISING", 100, false, at),
            GlucoseUi(null, "UNKNOWN", 100, true, at)).forEach { live ->
            assertTrue(live.isActiveGlucoseUnavailable(at))
            assertFalse(live.isActiveGlucoseUnavailable(at + re.abbot.librecr.app.GLUCOSE_STALE_AFTER_MS))
        }
        assertFalse(GlucoseUi(120, "RISING", 100, true, at).isActiveGlucoseUnavailable(at))
    }
}
