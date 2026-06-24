package re.abbot.librecr.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import re.abbot.librecr.app.stats.GlucoseSample
import re.abbot.librecr.app.stats.InsightCode
import re.abbot.librecr.app.stats.PgsLabel
import re.abbot.librecr.app.stats.VariabilityLabel
import re.abbot.librecr.app.stats.computeAgp
import re.abbot.librecr.app.stats.computeDaily
import re.abbot.librecr.app.stats.computeStatsReport
import re.abbot.librecr.app.stats.computeVariability
import re.abbot.librecr.app.stats.percentile
import java.util.Calendar

class StatsReportTest {
    /** A sample on calendar day `day` (offset from 2026-01-01) at local `hour`, system time zone. */
    private fun atHour(day: Int, hour: Int, mg: Int): GlucoseSample {
        val c = Calendar.getInstance()
        c.set(2026, Calendar.JANUARY, 1 + day, hour, 0, 0)
        c.set(Calendar.MILLISECOND, 0)
        return GlucoseSample(mg, c.timeInMillis)
    }

    @Test
    fun percentileInterpolatesLinearly() {
        val sorted = listOf(10f, 20f, 30f, 40f)
        assertEquals(10f, percentile(sorted, 0f), 1e-3f)
        assertEquals(40f, percentile(sorted, 1f), 1e-3f)
        // position = 0.5 * 3 = 1.5 → halfway between 20 and 30.
        assertEquals(25f, percentile(sorted, 0.5f), 1e-3f)
        assertEquals(0f, percentile(emptyList(), 0.5f), 1e-3f)
    }

    @Test
    fun agpGroupsByHourOfDay() {
        val samples = listOf(atHour(0, 3, 100), atHour(1, 3, 200), atHour(0, 14, 150))
        val agp = computeAgp(samples)
        assertEquals(24, agp.size)

        val h3 = agp[3]
        assertEquals(2, h3.count)
        assertEquals(150f, h3.median!!, 1e-3f)            // median of [100, 200]
        assertEquals(110f, h3.p10!!, 1e-3f)               // 100 + (200-100)*0.10

        assertEquals(1, agp[14].count)
        assertEquals(0, agp[5].count)                     // no readings this hour
        assertNull(agp[5].median)
    }

    @Test
    fun dailyGroupsByCalendarDay() {
        val samples = listOf(atHour(0, 8, 100), atHour(0, 9, 200), atHour(1, 8, 120))
        val daily = computeDaily(samples, targetLow = 70, targetHigh = 180)
        assertEquals(2, daily.size)

        // Day 0: avg 150; 100 in range, 200 out → 50% in range.
        assertEquals(150f, daily[0].avgMgDl, 1e-3f)
        assertEquals(50f, daily[0].inRangePct, 1e-3f)
        assertEquals(2, daily[0].count)
        // Day 1 (later) sorts after day 0; 120 in range → 100%.
        assertEquals(100f, daily[1].inRangePct, 1e-3f)
    }

    @Test
    fun variabilityIsExcellentAndStableForFlatSeries() {
        val samples = (0..50).map { GlucoseSample(120, it * 60_000L) }
        val v = computeVariability(samples, fullMeanMgDl = 120f, targetLow = 70, targetHigh = 180)
        assertEquals(VariabilityLabel.EXCELLENT, v.gviLabel)
        assertEquals(PgsLabel.STABLE, v.pgsLabel)
        assertTrue("stability should be high for a flat series", v.stabilityPct > 70f)
    }

    @Test
    fun pgsElevatedWhenMeanAboveTarget() {
        val samples = (0..50).map { GlucoseSample(260, it * 60_000L) }
        val v = computeVariability(samples, fullMeanMgDl = 260f, targetLow = 70, targetHigh = 180)
        assertEquals(PgsLabel.ELEVATED, v.pgsLabel)
    }

    @Test
    fun reportEmitsExcellentControlAndStableInsights() {
        val samples = (0..100).map { GlucoseSample(120, it * 60_000L) }
        val report = computeStatsReport(samples, targetLow = 70, targetHigh = 180)!!
        assertEquals(120f, report.medianMgDl, 1e-3f)
        val codes = report.insights.map { it.code }
        assertTrue(codes.contains(InsightCode.EXCELLENT_CONTROL))
        assertTrue(codes.contains(InsightCode.STABLE_GLUCOSE))
    }

    @Test
    fun reportIsNullForEmptyPeriod() {
        assertNull(computeStatsReport(emptyList(), 70, 180))
    }
}
