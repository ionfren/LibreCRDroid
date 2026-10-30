package re.abbot.librecr.app.idrive

import re.abbot.librecr.app.data.GlucoseUnit
import re.abbot.librecr.app.data.SensorStateStore
import re.abbot.librecr.app.stats.GlucoseSample
import re.abbot.librecr.app.widget.GlucoseWidgetInput
import re.abbot.librecr.app.widget.WidgetReadingStatus
import re.abbot.librecr.app.widget.glucoseWidgetPresentation

internal const val IDRIVE_CHART_WINDOW_MS = 3 * 60 * 60_000L

internal data class IDriveGlucoseChart(val startMs: Long, val endMs: Long, val points: List<GlucoseSample>)

/** Same freshness rules as the phone widgets; history stays at its actual minute. */
internal data class IDriveGlucosePresentation(
    val status: WidgetReadingStatus,
    val value: String,
    val unit: String,
    val trend: String?,
    val delta: String,
    val ageMinutes: Long?,
    val measuredAtMs: Long?,
    val mgDl: Int? = null,
    val targetLow: Int = 70,
    val targetHigh: Int = 180,
    val chart: IDriveGlucoseChart = IDriveGlucoseChart(0, IDRIVE_CHART_WINDOW_MS, emptyList()),
)

internal fun idriveGlucosePresentation(
    input: GlucoseWidgetInput,
    unit: GlucoseUnit,
    nowMs: Long,
    targetLow: Int = 70,
    targetHigh: Int = 180,
    history: List<GlucoseSample> = emptyList(),
    recent: List<SensorStateStore.LastGlucose> = emptyList(),
): IDriveGlucosePresentation {
    val base = glucoseWidgetPresentation(input, nowMs)
    val current = input.reading?.takeIf { base.status == WidgetReadingStatus.CURRENT }
    val delta = current?.deltaMgDlPerMin?.takeIf { it.isFinite() }
    // The persisted headline intentionally omits chartMgDL. Do not overwrite the
    // same reading's uncapped history value with its capped headline value.
    val chartTail = input.reading?.takeUnless { latest ->
        recent.any { it.receivedAtMs == latest.receivedAtMs && it.lifeCount == latest.lifeCount }
    }
    return IDriveGlucosePresentation(
        status = base.status,
        value = current?.let { unit.format(it.mgDL) } ?: "—",
        unit = unit.label,
        trend = base.trend,
        delta = delta?.let {
            if (unit == GlucoseUnit.MG_DL) base.deltaPerMinute else unit.formatDelta(it)
        } ?: "—",
        ageMinutes = base.measuredAtMs?.let { (nowMs - it).coerceAtLeast(0L) / 60_000L },
        measuredAtMs = base.measuredAtMs,
        mgDl = current?.mgDL,
        targetLow = targetLow,
        targetHigh = targetHigh,
        chart = idriveGlucoseChart(history, recent + listOfNotNull(chartTail), nowMs),
    )
}

internal fun idriveGlucoseChart(
    history: List<GlucoseSample>,
    recent: List<SensorStateStore.LastGlucose>,
    nowMs: Long,
): IDriveGlucoseChart {
    val end = nowMs / 60_000L * 60_000L
    val start = end - IDRIVE_CHART_WINDOW_MS
    val minutes = linkedMapOf<Long, GlucoseSample>()
    fun add(sample: GlucoseSample) {
        if (sample.mgDl <= 0 || sample.atMs <= 0 || sample.atMs > nowMs) return
        val minute = sample.atMs / 60_000L * 60_000L
        if (minute in start..end) minutes[minute] = sample.copy(atMs = minute)
    }
    history.sortedBy { it.atMs }.forEach(::add)
    recent.sortedBy { it.receivedAtMs }.forEach { add(GlucoseSample(it.chartMgDL ?: it.mgDL, it.receivedAtMs)) }
    return IDriveGlucoseChart(start, end, minutes.values.sortedBy { it.atMs })
}
