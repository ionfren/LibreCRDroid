package re.abbot.librecr.app.live

import re.abbot.librecr.app.WEAR_GLUCOSE_DISPLAY_HOLD_MS
import re.abbot.librecr.app.ble.GlucoseUi
import re.abbot.librecr.app.ble.toLastGlucose
import re.abbot.librecr.app.data.GlucoseUnit
import re.abbot.librecr.app.data.SensorStateStore
import re.abbot.librecr.app.isWearGlucoseFresh
import re.abbot.librecr.protocol.TrendArrowShape
import re.abbot.librecr.protocol.dataplane.Libre3SensorAttention
import re.abbot.librecr.protocol.dataplane.SensorLifecycle

/** Pure display policy: a non-numeric live packet must never resurrect a persisted value. */
internal data class WearLiveUpdateContent(
    val reading: SensorStateStore.LastGlucose?,
    val unit: GlucoseUnit,
    val status: Status,
    val observedAtMs: Long,
) {
    enum class Status { READING, VALUE_UNAVAILABLE, UNAVAILABLE, WARMUP, SENSOR_ERROR }

    val hasFreshObservation: Boolean get() = status == Status.READING || status == Status.VALUE_UNAVAILABLE

    val arrow: String get() = when (TrendArrowShape.resolvedTrend(reading?.trend)) {
        "FALLING_QUICKLY" -> "↓"
        "FALLING" -> "↘"
        "STABLE" -> "→"
        "RISING" -> "↗"
        "RISING_QUICKLY" -> "↑"
        else -> ""
    }

    val value: String get() = reading?.let { unit.format(it.mgDL) }
        ?: if (status == Status.VALUE_UNAVAILABLE) "NA" else "--"
    val delta: String get() = reading?.deltaMgDlPerMin?.takeIf { it.isFinite() }
        ?.let(unit::formatDelta) ?: "--"
    val primaryText: String get() = if (reading == null) value else
        listOf(arrow, value, "Δ $delta").filter { it.isNotBlank() }.joinToString(" ")

    // Four ordinary spaces surround the middle dot between glucose and delta (two per side).
    // The trend arrow remains the separate notification icon, without a leading separator.
    fun chipText(nowMs: Long): String =
        when {
            status == Status.VALUE_UNAVAILABLE -> "NA · ${ageMinutes(nowMs)}′"
            reading == null -> "--"
            else -> "$value  ·  $delta · ${ageMinutes(nowMs)}′"
        }

    fun ageMinutes(nowMs: Long): Long = ((nowMs - observedAtMs).coerceAtLeast(0L) / 60_000L)

    /** Only finite minute updates while fresh; no perpetual ticker after the reading expires. */
    fun nextRefreshDelayMs(nowMs: Long, showAge: Boolean = true): Long? {
        if (!hasFreshObservation || observedAtMs <= 0L) return null
        val age = (nowMs - observedAtMs).coerceAtLeast(0L)
        val remaining = WEAR_GLUCOSE_DISPLAY_HOLD_MS - age
        if (remaining <= 0L) return null
        return if (showAge) minOf(60_000L - age % 60_000L, remaining) else remaining
    }

    companion object {
        fun from(
            live: GlucoseUi?,
            persisted: SensorStateStore.LastGlucose?,
            unit: GlucoseUnit,
            sensorStatus: SensorStateStore.SensorStatusSnapshot?,
            warmupMinutes: Int = SensorLifecycle.DEFAULT_WARMUP_DURATION_MINUTES,
            nowMs: Long,
        ): WearLiveUpdateContent {
            val candidate = if (live != null) live.toLastGlucose() else persisted
            val status = when {
                sensorStatus?.attention?.let { it != Libre3SensorAttention.None } == true -> Status.SENSOR_ERROR
                live != null && !live.usable && live.lifeCount < warmupMinutes -> Status.WARMUP
                live != null && (!live.usable || live.mgDL == null) &&
                    isWearGlucoseFresh(live.receivedAtMs, nowMs) -> Status.VALUE_UNAVAILABLE
                candidate != null && isWearGlucoseFresh(candidate.receivedAtMs, nowMs) -> Status.READING
                else -> Status.UNAVAILABLE
            }
            return WearLiveUpdateContent(
                reading = candidate.takeIf { status == Status.READING },
                unit = unit,
                status = status,
                observedAtMs = live?.receivedAtMs ?: persisted?.receivedAtMs ?: 0L,
            )
        }
    }
}
