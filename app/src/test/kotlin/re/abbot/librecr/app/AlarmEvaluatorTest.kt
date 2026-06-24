package re.abbot.librecr.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import re.abbot.librecr.app.alarm.AlarmEvaluator
import re.abbot.librecr.app.alarm.AlarmKind
import re.abbot.librecr.app.data.AlarmSettings
import re.abbot.librecr.app.stats.GlucoseSample

class AlarmEvaluatorTest {
    private val cfg = AlarmSettings(enabled = true)
    private val noSnooze: (AlarmKind) -> Boolean = { false }

    @Test
    fun disabledReturnsNull() {
        assertNull(AlarmEvaluator.evaluate(50, cfg.copy(enabled = false), 600, noSnooze))
    }

    @Test
    fun urgentLowIgnoresActiveHours() {
        val c = cfg.copy(activeHoursEnabled = true, activeStartMinutes = 480, activeEndMinutes = 1320)
        // 01:40 is outside 08:00–22:00, but urgent-low must still fire.
        assertEquals(AlarmKind.URGENT_LOW, AlarmEvaluator.evaluate(50, c, 100, noSnooze)?.kind)
    }

    @Test
    fun lowSuppressedOutsideActiveHours() {
        val c = cfg.copy(activeHoursEnabled = true, activeStartMinutes = 480, activeEndMinutes = 1320)
        assertNull(AlarmEvaluator.evaluate(65, c, 100, noSnooze))
        assertEquals(AlarmKind.LOW, AlarmEvaluator.evaluate(65, c, 600, noSnooze)?.kind)
    }

    @Test
    fun highDetected() {
        assertEquals(AlarmKind.HIGH, AlarmEvaluator.evaluate(260, cfg, 600, noSnooze)?.kind)
    }

    @Test
    fun snoozeSuppressesThatKind() {
        assertNull(AlarmEvaluator.evaluate(65, cfg, 600) { it == AlarmKind.LOW })
    }

    @Test
    fun activeWindowWrapsMidnight() {
        assertTrue(AlarmEvaluator.inWindow(30, 1320, 480))   // 00:30 inside 22:00–08:00
        assertFalse(AlarmEvaluator.inWindow(600, 1320, 480)) // 10:00 outside
    }

    @Test
    fun rawBreachIgnoresEnableAndSnooze() {
        assertEquals(AlarmKind.HIGH, AlarmEvaluator.rawBreach(260, cfg.copy(enabled = false)))
        assertNull(AlarmEvaluator.rawBreach(120, cfg))
    }

    // ---- persistent (sustained) alarms ----

    private val now = 1_700_000_000_000L

    /** One sample per minute spanning the last [minutes], oldest first. */
    private fun series(minutes: Int, mgdl: (Int) -> Int): List<GlucoseSample> =
        (0..minutes).map { i -> GlucoseSample(mgdl(i), now - (minutes - i) * 60_000L) }

    @Test
    fun persistentHighFiresWhenSustainedAcrossWindow() {
        val c = AlarmSettings(enabled = true, persistentHighEnabled = true, persistentHighMgDl = 180, persistentHighMinutes = 60)
        val d = AlarmEvaluator.persistentDecision(series(65) { 200 }, now, 600, c, noSnooze)
        assertEquals(AlarmKind.PERSISTENT_HIGH, d?.kind)
    }

    @Test
    fun persistentHighSkippedIfOneReadingDropsInRange() {
        val c = AlarmSettings(enabled = true, persistentHighEnabled = true, persistentHighMgDl = 180, persistentHighMinutes = 60)
        val samples = series(65) { i -> if (i == 30) 120 else 200 }
        assertNull(AlarmEvaluator.persistentDecision(samples, now, 600, c, noSnooze))
    }

    @Test
    fun persistentLowFiresWhenSustained() {
        val c = AlarmSettings(enabled = true, persistentLowEnabled = true, persistentLowMgDl = 70, persistentLowMinutes = 30)
        val d = AlarmEvaluator.persistentDecision(series(35) { 60 }, now, 600, c, noSnooze)
        assertEquals(AlarmKind.PERSISTENT_LOW, d?.kind)
    }

    @Test
    fun persistentNotFiredWhenWindowUncovered() {
        val c = AlarmSettings(enabled = true, persistentHighEnabled = true, persistentHighMgDl = 180, persistentHighMinutes = 60)
        val sparse = listOf(GlucoseSample(200, now - 60_000L), GlucoseSample(200, now)) // only 2 recent points
        assertNull(AlarmEvaluator.persistentDecision(sparse, now, 600, c, noSnooze))
    }

    @Test
    fun persistentRespectsSnooze() {
        val c = AlarmSettings(enabled = true, persistentHighEnabled = true, persistentHighMgDl = 180, persistentHighMinutes = 60)
        assertNull(AlarmEvaluator.persistentDecision(series(65) { 200 }, now, 600, c) { it == AlarmKind.PERSISTENT_HIGH })
    }

    @Test
    fun persistentRawKindIgnoresMasterAndSnooze() {
        val c = AlarmSettings(persistentHighEnabled = true, persistentHighMgDl = 180, persistentHighMinutes = 60)
        assertEquals(AlarmKind.PERSISTENT_HIGH, AlarmEvaluator.persistentRawKind(series(65) { 200 }, now, c))
    }
}
