package re.abbot.librecr.app.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackfillRequestPolicyTest {
    @Test
    fun recentEqualOrHigherStartIsCoveredByEarlierRequest() {
        assertTrue(
            shouldCoalesceBackfillRequest(
                previousFromLifeCount = 100,
                previousAtMs = 1_000,
                requestedFromLifeCount = 100,
                nowMs = 60_999,
                windowMs = 60_000,
            ),
        )
        assertTrue(
            shouldCoalesceBackfillRequest(
                previousFromLifeCount = 100,
                previousAtMs = 1_000,
                requestedFromLifeCount = 105,
                nowMs = 2_000,
                windowMs = 60_000,
            ),
        )
    }

    @Test
    fun earlierStartOrExpiredWindowIsNotCoalesced() {
        assertFalse(
            shouldCoalesceBackfillRequest(
                previousFromLifeCount = 100,
                previousAtMs = 1_000,
                requestedFromLifeCount = 99,
                nowMs = 2_000,
                windowMs = 60_000,
            ),
        )
        assertFalse(
            shouldCoalesceBackfillRequest(
                previousFromLifeCount = 100,
                previousAtMs = 1_000,
                requestedFromLifeCount = 100,
                nowMs = 61_000,
                windowMs = 60_000,
            ),
        )
    }

    @Test
    fun absentPreviousRequestAndClockRollbackAreNotCoalesced() {
        assertFalse(
            shouldCoalesceBackfillRequest(
                previousFromLifeCount = null,
                previousAtMs = 0,
                requestedFromLifeCount = 100,
                nowMs = 1_000,
                windowMs = 60_000,
            ),
        )
        assertFalse(
            shouldCoalesceBackfillRequest(
                previousFromLifeCount = 100,
                previousAtMs = 2_000,
                requestedFromLifeCount = 100,
                nowMs = 1_000,
                windowMs = 60_000,
            ),
        )
    }
}
