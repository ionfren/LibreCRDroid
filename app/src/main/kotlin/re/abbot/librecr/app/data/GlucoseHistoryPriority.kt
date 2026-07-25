package re.abbot.librecr.app.data

/**
 * Conflict priority for samples that describe the same sensor minute.
 *
 * Historical backfill is a separately finalized five-minute series. It is useful only as a
 * fallback for an absent minute and must not replace the canonical per-minute clinical/realtime
 * value.
 */
internal enum class GlucoseHistorySource(val priority: Int) {
    HISTORICAL_BACKFILL(0),
    CLINICAL_BACKFILL(1),
    REALTIME_OR_IMPORT(2),
}

internal fun glucoseHistorySource(trend: String): GlucoseHistorySource =
    when (trend) {
        "BACKFILL_HISTORICAL" -> GlucoseHistorySource.HISTORICAL_BACKFILL
        "BACKFILL_CLINICAL" -> GlucoseHistorySource.CLINICAL_BACKFILL
        else -> GlucoseHistorySource.REALTIME_OR_IMPORT
    }

/**
 * Order-independent merge used by the short DataStore history ring.
 *
 * A conflict is either the same sensor lifeCount or the same wall-clock minute. Lower-priority
 * historical samples are ignored; equal or higher-priority samples replace the existing point.
 */
internal fun mergeGlucoseHistory(
    history: List<SensorStateStore.LastGlucose>,
    reading: SensorStateStore.LastGlucose,
    maxPoints: Int,
): List<SensorStateStore.LastGlucose> {
    val incomingPriority = glucoseHistorySource(reading.trend).priority
    val incomingMinute = reading.receivedAtMs / 60_000L
    val conflicts = history.filter {
        it.lifeCount == reading.lifeCount || it.receivedAtMs / 60_000L == incomingMinute
    }
    if (conflicts.any { glucoseHistorySource(it.trend).priority > incomingPriority }) {
        return history.sortedBy { it.receivedAtMs }.takeLast(maxPoints)
    }
    return (history - conflicts.toSet() + reading)
        .sortedBy { it.receivedAtMs }
        .takeLast(maxPoints)
}
