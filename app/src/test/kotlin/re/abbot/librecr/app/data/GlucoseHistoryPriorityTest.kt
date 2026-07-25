package re.abbot.librecr.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class GlucoseHistoryPriorityTest {
    @Test
    fun historicalPointCannotReplaceClinicalPointRegardlessOfArrivalOrder() {
        val clinical = reading(lifeCount = 15_545, mgDL = 74, trend = "BACKFILL_CLINICAL")
        val historical = reading(lifeCount = 15_545, mgDL = 91, trend = "BACKFILL_HISTORICAL")

        val clinicalThenHistorical = mergeGlucoseHistory(listOf(clinical), historical, maxPoints = 64)
        val historicalThenClinical = mergeGlucoseHistory(listOf(historical), clinical, maxPoints = 64)

        assertEquals(listOf(clinical), clinicalThenHistorical)
        assertEquals(listOf(clinical), historicalThenClinical)
    }

    @Test
    fun realtimePointWinsOverBothBackfillSources() {
        val historical = reading(lifeCount = 15_550, mgDL = 89, trend = "BACKFILL_HISTORICAL")
        val clinical = reading(lifeCount = 15_550, mgDL = 75, trend = "BACKFILL_CLINICAL")
        val realtime = reading(lifeCount = 15_550, mgDL = 74, trend = "STABLE")

        val merged = listOf(historical, clinical, realtime).fold(
            emptyList<SensorStateStore.LastGlucose>(),
        ) { history, incoming ->
            mergeGlucoseHistory(history, incoming, maxPoints = 64)
        }
        val lateHistorical = mergeGlucoseHistory(merged, historical, maxPoints = 64)

        assertEquals(listOf(realtime), lateHistorical)
    }

    @Test
    fun historicalPointStillFillsAnActuallyMissingMinute() {
        val historical = reading(lifeCount = 15_555, mgDL = 91, trend = "BACKFILL_HISTORICAL")

        assertEquals(
            listOf(historical),
            mergeGlucoseHistory(emptyList(), historical, maxPoints = 64),
        )
    }

    @Test
    fun sameWallClockMinuteAlsoUsesSourcePriority() {
        val clinical = reading(
            lifeCount = 15_560,
            mgDL = 74,
            trend = "BACKFILL_CLINICAL",
            receivedAtMs = 1_000_001L,
        )
        val historicalWithDifferentLifeCount = reading(
            lifeCount = 15_555,
            mgDL = 91,
            trend = "BACKFILL_HISTORICAL",
            receivedAtMs = 1_000_999L,
        )

        assertEquals(
            listOf(clinical),
            mergeGlucoseHistory(listOf(clinical), historicalWithDifferentLifeCount, maxPoints = 64),
        )
    }

    private fun reading(
        lifeCount: Int,
        mgDL: Int,
        trend: String,
        receivedAtMs: Long = 1_000_000L,
    ) = SensorStateStore.LastGlucose(
        lifeCount = lifeCount,
        mgDL = mgDL,
        trend = trend,
        receivedAtMs = receivedAtMs,
        deltaMgDlPerMin = null,
    )
}
