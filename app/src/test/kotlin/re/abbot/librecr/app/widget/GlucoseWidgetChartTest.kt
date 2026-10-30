package re.abbot.librecr.app.widget

import org.junit.Assert.assertEquals
import org.junit.Test
import re.abbot.librecr.app.stats.GlucoseSample

class GlucoseWidgetChartTest {
    private val start = 1_000_000_000L
    private val end = start + 10 * 60_000L

    @Test
    fun `a segment crossing the target edge changes color exactly at the edge`() {
        val runs = widgetChartRuns(
            listOf(GlucoseSample(160, start), GlucoseSample(200, start + 5 * 60_000L)), start, end, 70, 180,
        )
        assertEquals(listOf(WidgetChartBand.IN_RANGE, WidgetChartBand.ABOVE), runs.map { it.band })
        assertEquals(WidgetChartPoint(0.25f, 180f), runs[0].points.last())
        assertEquals(runs[0].points.last(), runs[1].points.first())
        assertEquals(WidgetChartPoint(0.5f, 200f), runs[1].points.last())
    }

    @Test
    fun `a dip below the target and back is cut into three runs`() {
        val runs = widgetChartRuns(
            listOf(GlucoseSample(80, start), GlucoseSample(60, start + 60_000L), GlucoseSample(80, start + 120_000L)),
            start, end, 70, 180,
        )
        assertEquals(
            listOf(WidgetChartBand.IN_RANGE, WidgetChartBand.BELOW, WidgetChartBand.IN_RANGE),
            runs.map { it.band },
        )
    }

    @Test
    fun `same-band samples stay one polyline`() {
        val runs = widgetChartRuns((0..4).map { GlucoseSample(120 + it, start + it * 60_000L) }, start, end, 70, 180)
        assertEquals(1, runs.size)
        assertEquals(5, runs[0].points.size)
    }

    @Test
    fun `long gaps break the line and an isolated reading becomes a dot`() {
        val runs = widgetChartRuns(
            listOf(GlucoseSample(100, start), GlucoseSample(105, start + 60_000L), GlucoseSample(110, start + 9 * 60_000L)),
            start, end, 70, 180, gapMs = 5 * 60_000L,
        )
        assertEquals(2, runs.size)
        assertEquals(2, runs[0].points.size)
        assertEquals(listOf(WidgetChartPoint(0.9f, 110f)), runs[1].points)
    }

    @Test
    fun `samples outside the window are ignored`() {
        val runs = widgetChartRuns(
            listOf(GlucoseSample(100, start - 60_000L), GlucoseSample(110, start), GlucoseSample(120, end + 60_000L)),
            start, end, 70, 180,
        )
        assertEquals(listOf(listOf(WidgetChartPoint(0f, 110f))), runs.map { it.points })
    }

    @Test
    fun `scale keeps the home chart band and widens for extremes`() {
        assertEquals(32f..224f, widgetChartScale(listOf(GlucoseSample(120, start)), 70, 180))
        assertEquals(22f..308f, widgetChartScale(listOf(GlucoseSample(30, start), GlucoseSample(300, start)), 70, 180))
    }
}
