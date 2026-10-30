package re.abbot.librecr.app.widget

import re.abbot.librecr.app.GLUCOSE_STALE_AFTER_MS
import re.abbot.librecr.app.ble.isSensorWarmupActive
import re.abbot.librecr.app.data.GlucoseUnit
import re.abbot.librecr.app.data.SensorStateStore
import re.abbot.librecr.app.idrive.IDriveGlucoseChart
import re.abbot.librecr.app.idrive.idriveGlucoseChart
import re.abbot.librecr.app.stats.GlucoseSample
import re.abbot.librecr.protocol.TrendArrowShape
import re.abbot.librecr.protocol.dataplane.SensorLifecycle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

internal enum class WidgetReadingStatus { CURRENT, NO_READING, STALE, UNAVAILABLE, WARMUP, SENSOR_ERROR }

/** The app chart's buckets: fixed clinical edges (54 / 250) around the user's own target. */
internal enum class WidgetGlucoseRange { VERY_LOW, LOW, IN_RANGE, HIGH, VERY_HIGH }

internal fun widgetGlucoseRange(mgDl: Int, targetLow: Int, targetHigh: Int): WidgetGlucoseRange = when {
    mgDl < 54 -> WidgetGlucoseRange.VERY_LOW
    mgDl < targetLow -> WidgetGlucoseRange.LOW
    mgDl <= targetHigh -> WidgetGlucoseRange.IN_RANGE
    mgDl <= 250 -> WidgetGlucoseRange.HIGH
    else -> WidgetGlucoseRange.VERY_HIGH
}

internal data class GlucoseWidgetInput(
    val reading: SensorStateStore.LastGlucose? = null,
    val sensorError: Boolean = false,
    val unavailableAtMs: Long? = null,
    val warmupStartedAtMs: Long? = null,
    val warmupDurationMinutes: Int = SensorLifecycle.DEFAULT_WARMUP_DURATION_MINUTES,
)

internal data class GlucoseWidgetPresentation(
    val status: WidgetReadingStatus,
    val value: String,
    val trend: String?,
    /** The store carries a rate per sensor minute, not an arbitrary difference between packets. */
    val deltaPerMinute: String,
    val measuredAtMs: Long?,
)

internal fun glucoseWidgetPresentation(
    input: GlucoseWidgetInput,
    nowMs: Long,
): GlucoseWidgetPresentation {
    val reading = input.reading?.takeIf {
        it.mgDL in 1..500 && it.receivedAtMs > 0L && it.receivedAtMs <= nowMs
    }
    val age = reading?.let { nowMs - it.receivedAtMs }
    val fresh = age != null && age < GLUCOSE_STALE_AFTER_MS
    val unavailable = input.unavailableAtMs?.let {
        it > 0L && it <= nowMs && nowMs - it < GLUCOSE_STALE_AFTER_MS &&
            (reading == null || it >= reading.receivedAtMs)
    } == true
    val status = when {
        input.sensorError -> WidgetReadingStatus.SENSOR_ERROR
        unavailable -> WidgetReadingStatus.UNAVAILABLE
        fresh -> WidgetReadingStatus.CURRENT
        isSensorWarmupActive(input.warmupStartedAtMs, input.warmupDurationMinutes, nowMs) ->
            WidgetReadingStatus.WARMUP
        reading != null -> WidgetReadingStatus.STALE
        else -> WidgetReadingStatus.NO_READING
    }
    val current = reading?.takeIf { status == WidgetReadingStatus.CURRENT }
    val delta = current?.deltaMgDlPerMin?.takeIf { it.isFinite() }
    val deltaText = delta?.let {
        // Suppress negative zero while keeping fractional rates from multi-minute gaps.
        val rounded = if (abs(it) < 0.05) 0.0 else it
        String.format(Locale.US, "%+.1f", rounded).removeSuffix(".0")
    } ?: "—"
    return GlucoseWidgetPresentation(
        status = status,
        value = current?.mgDL?.toString() ?: if (status == WidgetReadingStatus.UNAVAILABLE) "NA" else "—",
        trend = current?.let { TrendArrowShape.resolvedTrend(it.trend) },
        deltaPerMinute = deltaText,
        measuredAtMs = if (status == WidgetReadingStatus.UNAVAILABLE) input.unavailableAtMs else reading?.receivedAtMs,
    )
}

/** Everything a widget composition observes; time is applied separately when rendering. */
internal data class GlucoseWidgetModel(
    val input: GlucoseWidgetInput = GlucoseWidgetInput(),
    val unit: GlucoseUnit = GlucoseUnit.MG_DL,
    val targetLow: Int = 70,
    val targetHigh: Int = 180,
    /** Persisted SQLite minutes for the chart window. */
    val history: List<GlucoseSample> = emptyList(),
    /** The live DataStore ring; covers readings whose SQLite append has not landed yet. */
    val recent: List<SensorStateStore.LastGlucose> = emptyList(),
)

/** Display-ready widget state in the user's unit; freshness rules come from [glucoseWidgetPresentation]. */
internal data class GlucoseWidgetUi(
    val status: WidgetReadingStatus,
    val value: String,
    val unit: String,
    val trend: String?,
    /** Signed rate per minute in [unit], null whenever the presentation hides the delta. */
    val delta: String?,
    /** Only set for a current reading. */
    val range: WidgetGlucoseRange?,
    val measuredAtMs: Long?,
    val targetLow: Int,
    val targetHigh: Int,
    val chart: IDriveGlucoseChart,
    val warmupProgress: Float? = null,
    val warmupRemainingMinutes: Int? = null,
    /** Picker previews show a static age instead of a host-driven clock. */
    val preview: Boolean = false,
)

internal fun glucoseWidgetUi(model: GlucoseWidgetModel, nowMs: Long): GlucoseWidgetUi {
    val base = glucoseWidgetPresentation(model.input, nowMs)
    val current = model.input.reading?.takeIf { base.status == WidgetReadingStatus.CURRENT }
    val delta = current?.deltaMgDlPerMin?.takeIf { it.isFinite() }
    // Same merge as the car dashboard: the headline reading joins the chart until the ring has it.
    val tail = model.input.reading?.takeUnless { latest ->
        model.recent.any { it.receivedAtMs == latest.receivedAtMs && it.lifeCount == latest.lifeCount }
    }
    val warmupStart = model.input.warmupStartedAtMs?.takeIf { base.status == WidgetReadingStatus.WARMUP }
    val warmupMs = model.input.warmupDurationMinutes.coerceAtLeast(1) * 60_000L
    return GlucoseWidgetUi(
        status = base.status,
        value = current?.let { model.unit.format(it.mgDL) } ?: base.value,
        unit = model.unit.label,
        trend = base.trend,
        delta = delta?.let { if (model.unit == GlucoseUnit.MG_DL) base.deltaPerMinute else mmolDelta(it) },
        range = current?.let { widgetGlucoseRange(it.mgDL, model.targetLow, model.targetHigh) },
        measuredAtMs = base.measuredAtMs,
        targetLow = model.targetLow,
        targetHigh = model.targetHigh,
        chart = idriveGlucoseChart(model.history, model.recent + listOfNotNull(tail), nowMs),
        warmupProgress = warmupStart?.let { ((nowMs - it).toFloat() / warmupMs).coerceIn(0f, 1f) },
        warmupRemainingMinutes = warmupStart?.let {
            ceil((it + warmupMs - nowMs) / 60_000.0).toInt().coerceAtLeast(0)
        },
    )
}

/** One decimal like the mg/dL rate, without a "-0.0" for tiny falls. */
private fun mmolDelta(mgDlPerMin: Double): String {
    val mmol = mgDlPerMin / 18.0
    return String.format(Locale.US, "%+.1f", if (abs(mmol) < 0.05) 0.0 else mmol)
}

/** Picker preview: a plausible in-range afternoon, never the user's own data. */
internal fun previewGlucoseWidgetUi(nowMs: Long): GlucoseWidgetUi {
    val end = nowMs / 60_000L * 60_000L
    val values = intArrayOf(
        146, 151, 158, 166, 174, 181, 186, 188, 185, 178, 168, 157, 146, 136, 128, 121, 115, 110, 106,
        102, 99, 96, 94, 92, 91, 90, 90, 90, 91, 91, 92, 93, 95, 98, 102, 107, 112,
    )
    val points = values.mapIndexed { index, mgDl ->
        GlucoseSample(mgDl, end - (values.size - 1 - index) * 5 * 60_000L)
    }
    return GlucoseWidgetUi(
        status = WidgetReadingStatus.CURRENT,
        value = "112",
        unit = GlucoseUnit.MG_DL.label,
        trend = "RISING",
        delta = "+1.1",
        range = WidgetGlucoseRange.IN_RANGE,
        measuredAtMs = end - 60_000L,
        targetLow = 70,
        targetHigh = 180,
        chart = IDriveGlucoseChart(end - WIDGET_CHART_WINDOW_MS, end, points),
        preview = true,
    )
}
