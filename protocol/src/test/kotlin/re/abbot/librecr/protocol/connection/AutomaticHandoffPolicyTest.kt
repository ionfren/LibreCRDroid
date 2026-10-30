package re.abbot.librecr.protocol.connection

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AutomaticHandoffPolicyTest {
    @Test fun `three failures trigger fallback and a recovered reading resets failures`() {
        val tracker = FailedReconnectTracker()
        assertFalse(tracker.failed())
        assertFalse(tracker.failed())
        assertTrue(tracker.failed())
        assertTrue(tracker.failed())
        tracker.reset()
        assertFalse(tracker.failed())
        assertEquals(1, tracker.failures)
    }

    @Test fun `five adjacent live readings qualify and duplicates do not count`() {
        val tracker = ConsecutiveReadingTracker()
        for (minute in 1..4) {
            assertFalse(tracker.reading(minute, true, minute * 60_000L))
            assertFalse(tracker.reading(minute, true, minute * 60_000L + 1))
            assertFalse(tracker.reading(minute - 1, true, minute * 60_000L + 2))
        }
        assertTrue(tracker.reading(5, true, 300_000L))
        assertTrue(tracker.ready(300_000L))
        assertFalse(tracker.ready(390_001L))
    }

    @Test fun `missing minute restarts the consecutive streak`() {
        val tracker = ConsecutiveReadingTracker()
        for (minute in 1..4) tracker.reading(minute, true, minute * 60_000L)
        assertFalse(tracker.reading(6, true, 360_000L))
        assertEquals(1, tracker.count)
        for (minute in 7..9) assertFalse(tracker.reading(minute, true, minute * 60_000L))
        assertTrue(tracker.reading(10, true, 600_000L))
    }

    @Test fun `unusable values disconnects and delayed readings reset the streak`() {
        val tracker = ConsecutiveReadingTracker()
        for (minute in 1..4) tracker.reading(minute, true, minute * 60_000L)
        assertFalse(tracker.reading(5, false, 300_000L))
        assertEquals(0, tracker.count)
        assertFalse(tracker.reading(6, true, 360_000L))
        tracker.reset()
        assertFalse(tracker.reading(7, true, 420_000L))
        assertEquals(1, tracker.count)
        assertFalse(tracker.reading(8, true, 520_000L))
        assertEquals(1, tracker.count)
    }
}
