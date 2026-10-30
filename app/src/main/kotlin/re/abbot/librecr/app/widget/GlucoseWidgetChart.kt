package re.abbot.librecr.app.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import re.abbot.librecr.app.idrive.IDRIVE_CHART_WINDOW_MS
import re.abbot.librecr.app.stats.GlucoseSample

/** Same three-hour window as the car dashboard. */
internal const val WIDGET_CHART_WINDOW_MS = IDRIVE_CHART_WINDOW_MS

/** Longer gaps break the line instead of drawing a straight guess across missing data. */
private const val CHART_GAP_MS = 15 * 60_000L

// The app chart's default scale, so a widget sparkline reads like the Home chart.
private const val CHART_FLOOR_MGDL = 40f
private const val CHART_CEILING_MGDL = 216f
private const val CHART_EDGE_PADDING_MGDL = 8f

internal enum class WidgetChartBand { BELOW, IN_RANGE, ABOVE }

/** x is the 0..1 position inside the window; y stays in mg/dL until rendering. */
internal data class WidgetChartPoint(val x: Float, val mgDl: Float)

/** A polyline drawn in one color; a single point is an isolated reading drawn as a dot. */
internal data class WidgetChartRun(val band: WidgetChartBand, val points: List<WidgetChartPoint>)

internal fun widgetChartBand(mgDl: Float, targetLow: Int, targetHigh: Int): WidgetChartBand = when {
    mgDl < targetLow -> WidgetChartBand.BELOW
    mgDl > targetHigh -> WidgetChartBand.ABOVE
    else -> WidgetChartBand.IN_RANGE
}

/**
 * Splits the series into single-color runs. Segments crossing a target edge are cut exactly at
 * the edge, so the color changes where the line leaves the target band, not at the next sample.
 */
internal fun widgetChartRuns(
    samples: List<GlucoseSample>,
    startMs: Long,
    endMs: Long,
    targetLow: Int,
    targetHigh: Int,
    gapMs: Long = CHART_GAP_MS,
): List<WidgetChartRun> {
    val span = (endMs - startMs).coerceAtLeast(1L).toFloat()
    val sorted = samples.filter { it.mgDl > 0 && it.atMs in startMs..endMs }.sortedBy { it.atMs }
    fun point(sample: GlucoseSample) = WidgetChartPoint((sample.atMs - startMs) / span, sample.mgDl.toFloat())
    val edges = listOf(targetLow.toFloat(), targetHigh.toFloat())
    val runs = mutableListOf<WidgetChartRun>()
    var open: MutableList<WidgetChartPoint>? = null
    var openBand: WidgetChartBand? = null

    fun add(band: WidgetChartBand, from: WidgetChartPoint, to: WidgetChartPoint) {
        val run = open
        if (run != null && openBand == band && run.last() == from) {
            run += to
        } else {
            val created = mutableListOf(from, to)
            runs += WidgetChartRun(band, created)
            open = created
            openBand = band
        }
    }

    sorted.forEachIndexed { index, sample ->
        val previous = sorted.getOrNull(index - 1)
        if (previous == null || sample.atMs - previous.atMs > gapMs) {
            open = null
            val next = sorted.getOrNull(index + 1)
            if (next == null || next.atMs - sample.atMs > gapMs) {
                runs += WidgetChartRun(widgetChartBand(sample.mgDl.toFloat(), targetLow, targetHigh), listOf(point(sample)))
            }
            return@forEachIndexed
        }
        val a = point(previous)
        val b = point(sample)
        val cuts = edges
            .filter { edge -> (a.mgDl - edge) * (b.mgDl - edge) < 0f }
            .map { edge -> (edge - a.mgDl) / (b.mgDl - a.mgDl) }
            .sorted()
        var from = a
        (cuts + 1f).forEach { t ->
            val to = if (t >= 1f) b else WidgetChartPoint(a.x + (b.x - a.x) * t, a.mgDl + (b.mgDl - a.mgDl) * t)
            add(widgetChartBand((from.mgDl + to.mgDl) / 2f, targetLow, targetHigh), from, to)
            from = to
        }
    }
    return runs
}

/** Vertical scale: the Home chart's default band, widened for the data and the user's target. */
internal fun widgetChartScale(samples: List<GlucoseSample>, targetLow: Int, targetHigh: Int): ClosedFloatingPointRange<Float> {
    val minimum = minOf(CHART_FLOOR_MGDL, targetLow.toFloat(), samples.minOfOrNull { it.mgDl }?.toFloat() ?: CHART_FLOOR_MGDL)
    val maximum = maxOf(CHART_CEILING_MGDL, targetHigh.toFloat(), samples.maxOfOrNull { it.mgDl }?.toFloat() ?: CHART_CEILING_MGDL)
    return (minimum - CHART_EDGE_PADDING_MGDL)..(maximum + CHART_EDGE_PADDING_MGDL)
}

/** Runs and vertical scale of one chart window, shared by the widget bitmaps and the AOD canvas. */
internal class WidgetChartGeometry(val runs: List<WidgetChartRun>, val scale: ClosedFloatingPointRange<Float>)

/** Null when the window holds no reading to draw. */
internal fun widgetChartGeometry(
    samples: List<GlucoseSample>,
    startMs: Long,
    endMs: Long,
    targetLow: Int,
    targetHigh: Int,
): WidgetChartGeometry? {
    val runs = widgetChartRuns(samples, startMs, endMs, targetLow, targetHigh)
    if (runs.isEmpty()) return null
    return WidgetChartGeometry(runs, widgetChartScale(samples.filter { it.atMs in startMs..endMs }, targetLow, targetHigh))
}

/**
 * Draws the sparkline into [bounds]. [colorOf] gives each band's color, or null to leave that band
 * out: the widget renders one alpha mask per band, the AOD overlay draws every band in color.
 */
internal fun drawWidgetChart(
    canvas: Canvas,
    bounds: RectF,
    geometry: WidgetChartGeometry,
    targetLow: Int,
    targetHigh: Int,
    density: Float,
    colorOf: (WidgetChartBand) -> Int?,
) {
    val scale = geometry.scale
    val stroke = 2.4f * density
    val dot = 3.6f * density
    val halo = 7.5f * density
    // Room for the latest reading's halo at the right edge and for round caps elsewhere.
    val plot = RectF(bounds.left + stroke, bounds.top + halo, bounds.right - halo, bounds.bottom - halo)
    fun x(point: WidgetChartPoint) = plot.left + point.x * plot.width()
    fun y(mgDl: Float) = plot.bottom - (mgDl - scale.start) / (scale.endInclusive - scale.start) * plot.height()
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    fun Paint.tinted(color: Int, alpha: Int): Paint = apply {
        this.color = color
        this.alpha = Color.alpha(color) * alpha / 255
    }

    colorOf(WidgetChartBand.IN_RANGE)?.let { color ->
        val radius = 6f * density
        canvas.drawRoundRect(bounds.left, y(targetHigh.toFloat()), bounds.right, y(targetLow.toFloat()), radius, radius,
            fill.tinted(color, 40))
    }
    geometry.runs.forEach { run ->
        val color = colorOf(run.band) ?: return@forEach
        if (run.points.size == 1) {
            canvas.drawCircle(x(run.points[0]), y(run.points[0].mgDl), stroke * 0.8f, fill.tinted(color, 255))
        } else {
            val path = Path()
            run.points.forEachIndexed { index, point ->
                if (index == 0) path.moveTo(x(point), y(point.mgDl)) else path.lineTo(x(point), y(point.mgDl))
            }
            canvas.drawPath(path, line.tinted(color, 255))
        }
    }
    geometry.runs.flatMap { run -> run.points.map { run.band to it } }.maxByOrNull { it.second.x }?.let { (band, point) ->
        val color = colorOf(band) ?: return@let
        canvas.drawCircle(x(point), y(point.mgDl), halo, fill.tinted(color, 64))
        canvas.drawCircle(x(point), y(point.mgDl), dot, fill.tinted(color, 255))
    }
}

/**
 * Alpha-only layers, one per color band. RemoteViews cannot pick a bitmap per day/night mode, so
 * each layer is tinted by the host with a theme-aware color instead of baking colors in.
 */
internal class WidgetChartLayers(val inRange: Bitmap, val below: Bitmap?, val above: Bitmap?)

internal fun renderWidgetChart(
    samples: List<GlucoseSample>,
    startMs: Long,
    endMs: Long,
    targetLow: Int,
    targetHigh: Int,
    widthPx: Int,
    heightPx: Int,
    density: Float,
): WidgetChartLayers? {
    if (widthPx <= 0 || heightPx <= 0) return null
    val geometry = widgetChartGeometry(samples, startMs, endMs, targetLow, targetHigh) ?: return null
    val bounds = RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat())
    fun layer(band: WidgetChartBand): Bitmap? {
        if (band != WidgetChartBand.IN_RANGE && geometry.runs.none { it.band == band }) return null
        return createBitmap(widthPx, heightPx, Bitmap.Config.ALPHA_8).also { bitmap ->
            drawWidgetChart(Canvas(bitmap), bounds, geometry, targetLow, targetHigh, density) {
                if (it == band) Color.BLACK else null
            }
        }
    }
    return WidgetChartLayers(
        inRange = layer(WidgetChartBand.IN_RANGE) ?: return null,
        below = layer(WidgetChartBand.BELOW),
        above = layer(WidgetChartBand.ABOVE),
    )
}
