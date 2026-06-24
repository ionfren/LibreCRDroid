package re.abbot.librecr.app.stats

import java.util.Calendar
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Rich, multi-card statistics for the Statistics screen, computed in one pass over the period's
 * samples. The formulas are ported from Juggluco's `StatsViewModel` (AGP percentiles, daily
 * breakdown, GVI/PGS variability scores, insights) but kept **pure** — no Android `Context`, so the
 * whole thing is unit-testable on the JVM. Insights are emitted as [InsightCode]s plus numeric args;
 * the UI resolves them to localized text.
 */

/** One hour-of-day bin of the Ambulatory Glucose Profile: percentile curves pooled across all days. */
data class AgpBand(
    val hour: Int,
    val p10: Float?,
    val p25: Float?,
    val median: Float?,
    val p75: Float?,
    val p90: Float?,
    val count: Int,
)

/** One calendar day's summary for the daily-trend chart. */
data class DailyPoint(
    val startOfDayMs: Long,
    val avgMgDl: Float,
    val inRangePct: Float,
    val count: Int,
)

enum class VariabilityLabel { EXCELLENT, GOOD, MODERATE, POOR }

enum class PgsLabel { LOW, ELEVATED, UNSTABLE, STABLE }

/** Glucose Variability Index (GVI) + Patient Glucose Status (PGS), Juggluco's variability scores. */
data class VariabilityScores(
    val gvi: Float,
    val gviLabel: VariabilityLabel,
    val stabilityPct: Float,
    val rocPerMin: Float,
    val pgsLabel: PgsLabel,
    val pgsTrend: Float,
    val pgsConfidence: Float,
)

enum class InsightSeverity { POSITIVE, ATTENTION, CAUTION }

enum class InsightCode {
    EXCELLENT_CONTROL, GOOD_PROGRESS, ROOM_IMPROVEMENT,
    STABLE_GLUCOSE, VARIABILITY_RISING, HIGH_VARIABILITY,
    HYPO_EXPOSURE, PROLONGED_HYPER, OVERNIGHT_DRIFT, UNSTABLE_DAYS, A1C_ESTIMATE,
}

/** A computed insight; the UI maps [code] to localized title/message using [intArg]/[floatArg]. */
data class StatsInsight(
    val code: InsightCode,
    val severity: InsightSeverity,
    val intArg: Int? = null,
    val floatArg: Float? = null,
)

/** Everything the Statistics screen renders for a period. */
data class StatsReport(
    val stats: GlucoseStatistics,
    val p25MgDl: Float,
    val medianMgDl: Float,
    val p75MgDl: Float,
    val agp: List<AgpBand>,
    val daily: List<DailyPoint>,
    val variability: VariabilityScores,
    val insights: List<StatsInsight>,
)

/**
 * Compute the full report from the period's [samples] (oldest first). Returns null for an empty
 * period. Reuses [GlucoseStats.compute] for the headline mean/SD/CV/GMI/TIR so those numbers stay
 * identical to the rest of the app.
 */
fun computeStatsReport(
    samples: List<GlucoseSample>,
    targetLow: Int,
    targetHigh: Int,
): StatsReport? {
    val stats = GlucoseStats.compute(samples, targetLow, targetHigh) ?: return null
    val sortedValues = samples.map { it.mgDl.toFloat() }.sorted()
    val agp = computeAgp(samples)
    val daily = computeDaily(samples, targetLow, targetHigh)
    val variability = computeVariability(samples, stats.meanMgDl.toFloat(), targetLow, targetHigh)
    val insights = buildInsights(
        tir = stats.tir,
        cv = stats.cvPercent.toFloat(),
        gmi = stats.gmiPercent.toFloat(),
        daily = daily,
        agp = agp,
    )
    return StatsReport(
        stats = stats,
        p25MgDl = percentile(sortedValues, 0.25f),
        medianMgDl = percentile(sortedValues, 0.50f),
        p75MgDl = percentile(sortedValues, 0.75f),
        agp = agp,
        daily = daily,
        variability = variability,
        insights = insights,
    )
}

/** Linear-interpolated percentile of an ascending list, matching Juggluco's `percentile`. */
internal fun percentile(sorted: List<Float>, p: Float): Float {
    if (sorted.isEmpty()) return 0f
    if (sorted.size == 1) return sorted.first()
    val clamped = p.coerceIn(0f, 1f)
    val position = clamped * (sorted.size - 1)
    val lower = position.toInt()
    val upper = (lower + 1).coerceAtMost(sorted.lastIndex)
    val weight = position - lower
    return sorted[lower] + (sorted[upper] - sorted[lower]) * weight
}

/** Hour-of-day percentile bands (the AGP curve), pooling every day's readings by local hour. */
internal fun computeAgp(samples: List<GlucoseSample>): List<AgpBand> {
    val byHour = Array(24) { mutableListOf<Float>() }
    val cal = Calendar.getInstance()
    for (s in samples) {
        cal.timeInMillis = s.atMs
        byHour[cal.get(Calendar.HOUR_OF_DAY)].add(s.mgDl.toFloat())
    }
    return (0..23).map { hour ->
        val values = byHour[hour]
        if (values.isEmpty()) {
            AgpBand(hour, null, null, null, null, null, 0)
        } else {
            values.sort()
            AgpBand(
                hour = hour,
                p10 = percentile(values, 0.10f),
                p25 = percentile(values, 0.25f),
                median = percentile(values, 0.50f),
                p75 = percentile(values, 0.75f),
                p90 = percentile(values, 0.90f),
                count = values.size,
            )
        }
    }
}

/** Per-calendar-day average + in-range %, oldest day first. */
internal fun computeDaily(samples: List<GlucoseSample>, targetLow: Int, targetHigh: Int): List<DailyPoint> {
    if (samples.isEmpty()) return emptyList()
    val cal = Calendar.getInstance()
    val groups = LinkedHashMap<Long, MutableList<Int>>()
    for (s in samples) {
        cal.timeInMillis = s.atMs
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        groups.getOrPut(cal.timeInMillis) { mutableListOf() }.add(s.mgDl)
    }
    return groups.entries.sortedBy { it.key }.map { (day, values) ->
        val inRange = values.count { it in targetLow..targetHigh }
        DailyPoint(
            startOfDayMs = day,
            avgMgDl = values.sum().toFloat() / values.size,
            inRangePct = inRange.toFloat() / values.size * 100f,
            count = values.size,
        )
    }
}

// ---- Variability (GVI + PGS) -------------------------------------------------------------------

private const val VARIABILITY_BUCKET_MS = 5L * 60L * 1000L
private const val NOISE_SPIKE_THRESHOLD_MGDL = 18f
private const val NOISE_NEIGHBOR_DISTANCE_MGDL = 9f
private const val MAX_PHYS_ROC_MGDL_PER_MIN = 3.5f
private const val MAX_PSG_CONFIDENCE_SAMPLES = 288

private class VPoint(var v: Float, val t: Long)

/** Sensor-neutral, noise-robust series: 5-min median buckets, de-spiked, rate-of-change capped. */
private fun variabilitySeries(samples: List<GlucoseSample>): List<VPoint> {
    val sorted = samples.sortedBy { it.atMs }
    if (sorted.size <= 8) return sorted.map { VPoint(it.mgDl.toFloat(), it.atMs) }

    val bucketed = sorted
        .groupBy { it.atMs / VARIABILITY_BUCKET_MS }
        .toSortedMap()
        .map { (_, points) ->
            val center = points[points.size / 2]
            val median = percentile(points.map { it.mgDl.toFloat() }.sorted(), 0.5f)
            VPoint(median, center.atMs)
        }
        .toMutableList()
    if (bucketed.size <= 2) return bucketed

    // Flatten single-point spikes that are likely sensor noise.
    for (index in 1 until bucketed.lastIndex) {
        val previous = bucketed[index - 1].v
        val current = bucketed[index].v
        val next = bucketed[index + 1].v
        val mid = (previous + next) / 2f
        if (abs(current - mid) >= NOISE_SPIKE_THRESHOLD_MGDL && abs(previous - next) <= NOISE_NEIGHBOR_DISTANCE_MGDL) {
            bucketed[index].v = mid
        }
    }
    // Cap physiologically implausible jumps to reduce aged-sensor jitter.
    for (index in 1 until bucketed.size) {
        val previous = bucketed[index - 1]
        val current = bucketed[index]
        val minutes = ((current.t - previous.t).toFloat() / 60_000f).coerceAtLeast(1f)
        val maxDelta = MAX_PHYS_ROC_MGDL_PER_MIN * minutes
        val delta = current.v - previous.v
        if (abs(delta) > maxDelta) current.v = previous.v + delta.sign * maxDelta
    }
    return bucketed
}

internal fun computeVariability(
    samples: List<GlucoseSample>,
    fullMeanMgDl: Float,
    targetLow: Int,
    targetHigh: Int,
): VariabilityScores {
    val series = variabilitySeries(samples)
    val values = series.map { it.v }
    val vAvg = if (values.isNotEmpty()) values.average().toFloat() else fullMeanMgDl
    val vVariance = if (values.isNotEmpty()) {
        values.fold(0.0) { acc, v -> val d = v - vAvg; acc + d * d } / values.size
    } else {
        0.0
    }
    val vSd = sqrt(vVariance).toFloat()
    val vCv = if (vAvg > 0f) vSd / vAvg * 100f else 0f

    // --- GVI ---
    var gvi = 1f
    var stability = 100f
    var rateOfChange = 0f
    if (series.size >= 2) {
        var totalDelta = 0f
        var rocAccum = 0f
        var rocSamples = 0
        for (index in 1..series.lastIndex) {
            val delta = abs(series[index].v - series[index - 1].v)
            val minutes = (series[index].t - series[index - 1].t).toFloat() / 60_000f
            totalDelta += delta
            if (minutes > 0f && minutes < 30f) {
                rocAccum += delta / minutes
                rocSamples++
            }
        }
        val meanDelta = totalDelta / series.lastIndex.coerceAtLeast(1)
        val cvFactor = if (vAvg > 0f) (vSd / vAvg).coerceAtLeast(0f) else 0f
        rateOfChange = if (rocSamples > 0) rocAccum / rocSamples else 0f
        val normalizedDelta = if (vAvg > 0f) (meanDelta / vAvg).coerceIn(0f, 1.2f) else 0f
        val normalizedRoc = (rateOfChange / 3.5f).coerceIn(0f, 1f)
        gvi = (1f + cvFactor * 1.1f + normalizedDelta * 0.9f + normalizedRoc * 0.6f).coerceIn(0.8f, 3f)
        stability = (((2.4f - gvi) / 1.6f) * 100f).coerceIn(0f, 100f)
    }
    val gviLabel = when {
        gvi < 1.25f -> VariabilityLabel.EXCELLENT
        gvi < 1.55f -> VariabilityLabel.GOOD
        gvi < 1.90f -> VariabilityLabel.MODERATE
        else -> VariabilityLabel.POOR
    }

    // --- PGS ---
    val half = series.size / 2
    val firstHalfAvg = if (half > 0) series.take(half).map { it.v }.average().toFloat() else fullMeanMgDl
    val secondHalfAvg = if (half < series.size) series.drop(half).map { it.v }.average().toFloat() else fullMeanMgDl
    val pgsTrend = if (secondHalfAvg > 0f) ((firstHalfAvg - secondHalfAvg) / secondHalfAvg).coerceIn(-1f, 1f) else 0f
    val pgsConfidence = (
        (series.size.coerceIn(0, MAX_PSG_CONFIDENCE_SAMPLES).toFloat() / MAX_PSG_CONFIDENCE_SAMPLES) *
            (100f - vCv).coerceIn(0f, 100f)
        ).coerceIn(0f, 100f)
    val pgsLabel = when {
        fullMeanMgDl < targetLow -> PgsLabel.LOW
        fullMeanMgDl > targetHigh -> PgsLabel.ELEVATED
        vCv > 36f -> PgsLabel.UNSTABLE
        else -> PgsLabel.STABLE
    }

    return VariabilityScores(
        gvi = gvi,
        gviLabel = gviLabel,
        stabilityPct = stability,
        rocPerMin = rateOfChange,
        pgsLabel = pgsLabel,
        pgsTrend = pgsTrend,
        pgsConfidence = pgsConfidence,
    )
}

// ---- Insights ----------------------------------------------------------------------------------

private const val MAX_INSIGHTS = 5

internal fun buildInsights(
    tir: TimeInRange,
    cv: Float,
    gmi: Float,
    daily: List<DailyPoint>,
    agp: List<AgpBand>,
): List<StatsInsight> {
    val out = mutableListOf<StatsInsight>()

    out += when {
        tir.inRangePct >= 70f -> StatsInsight(InsightCode.EXCELLENT_CONTROL, InsightSeverity.POSITIVE, intArg = tir.inRangePct.toInt())
        tir.inRangePct >= 55f -> StatsInsight(InsightCode.GOOD_PROGRESS, InsightSeverity.ATTENTION, intArg = tir.inRangePct.toInt())
        else -> StatsInsight(InsightCode.ROOM_IMPROVEMENT, InsightSeverity.CAUTION, intArg = tir.inRangePct.toInt())
    }

    out += when {
        cv <= 36f -> StatsInsight(InsightCode.STABLE_GLUCOSE, InsightSeverity.POSITIVE, intArg = cv.toInt())
        cv <= 45f -> StatsInsight(InsightCode.VARIABILITY_RISING, InsightSeverity.ATTENTION, floatArg = cv)
        else -> StatsInsight(InsightCode.HIGH_VARIABILITY, InsightSeverity.CAUTION, intArg = cv.toInt())
    }

    if (tir.veryLowPct >= 1f) {
        out += StatsInsight(InsightCode.HYPO_EXPOSURE, InsightSeverity.CAUTION, floatArg = tir.veryLowPct)
    }
    if (tir.veryHighPct >= 5f) {
        out += StatsInsight(InsightCode.PROLONGED_HYPER, InsightSeverity.ATTENTION, floatArg = tir.veryHighPct)
    }

    val overnight = agp.filter { it.hour in 0..5 }.mapNotNull { it.median }.takeIf { it.isNotEmpty() }?.average()?.toFloat()
    val daytime = agp.filter { it.hour in 10..18 }.mapNotNull { it.median }.takeIf { it.isNotEmpty() }?.average()?.toFloat()
    if (overnight != null && daytime != null && overnight - daytime > 20f) {
        out += StatsInsight(InsightCode.OVERNIGHT_DRIFT, InsightSeverity.ATTENTION)
    }

    val unstableDays = daily.count { it.inRangePct < 50f }
    if (unstableDays >= 3 && daily.size >= 7) {
        out += StatsInsight(InsightCode.UNSTABLE_DAYS, InsightSeverity.CAUTION, intArg = unstableDays)
    }

    if (gmi >= 7.5f && tir.inRangePct < 60f) {
        out += StatsInsight(InsightCode.A1C_ESTIMATE, InsightSeverity.ATTENTION, floatArg = gmi)
    }

    return out.distinctBy { it.code }.take(MAX_INSIGHTS)
}
