package re.abbot.librecr.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import re.abbot.librecr.app.stats.GlucoseSample
import re.abbot.librecr.app.stats.GlucoseStats

class GlucoseStatsTest {
    private fun samples(vararg v: Int) = v.mapIndexed { i, mg -> GlucoseSample(mg, i * 60_000L) }

    @Test
    fun emptyReturnsNull() {
        assertNull(GlucoseStats.compute(emptyList()))
    }

    @Test
    fun meanGmiAndInRange() {
        val r = GlucoseStats.compute(samples(100, 100, 100, 100))!!
        assertEquals(100.0, r.meanMgDl, 1e-6)
        assertEquals(3.31 + 0.02392 * 100, r.gmiPercent, 1e-6)
        assertEquals(100f, r.tir.inRangePct, 1e-3f)
        assertEquals(0.0, r.cvPercent, 1e-6)
        assertEquals(4, r.count)
    }

    @Test
    fun tirBucketsAcrossRanges() {
        // very-low(40), low(60), in-range(120), high(220), very-high(300)
        val r = GlucoseStats.compute(samples(40, 60, 120, 220, 300))!!
        assertEquals(20f, r.tir.veryLowPct, 1e-2f)
        assertEquals(20f, r.tir.lowPct, 1e-2f)
        assertEquals(20f, r.tir.inRangePct, 1e-2f)
        assertEquals(20f, r.tir.highPct, 1e-2f)
        assertEquals(20f, r.tir.veryHighPct, 1e-2f)
        assertEquals(40, r.lowestMgDl)
        assertEquals(300, r.highestMgDl)
    }

    @Test
    fun customTargetMovesBoundaries() {
        // With target 80..140, a 75 is "low" and a 150 is "high".
        val r = GlucoseStats.compute(samples(75, 100, 150), targetLow = 80, targetHigh = 140)!!
        assertEquals(33.333f, r.tir.lowPct, 1e-2f)
        assertEquals(33.333f, r.tir.inRangePct, 1e-2f)
        assertEquals(33.333f, r.tir.highPct, 1e-2f)
    }
}
