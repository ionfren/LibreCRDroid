package re.abbot.librecr.app.stats

import kotlin.math.sqrt

/** A single glucose sample in the sensor's native unit (mg/dL) at a wall-clock time. */
data class GlucoseSample(val mgDl: Int, val atMs: Long)

/** Time-in-range split as percentages of all samples; the five buckets sum to ~100. */
data class TimeInRange(
    val veryLowPct: Float,
    val lowPct: Float,
    val inRangePct: Float,
    val highPct: Float,
    val veryHighPct: Float,
)

data class GlucoseStatistics(
    val count: Int,
    val meanMgDl: Double,
    val sdMgDl: Double,
    val cvPercent: Double,
    /** Glucose Management Indicator (%), ADA formula. */
    val gmiPercent: Double,
    /** Estimated A1c (%), ADAG regression. */
    val estimatedA1cPercent: Double,
    val tir: TimeInRange,
    val lowestMgDl: Int,
    val highestMgDl: Int,
    val firstAtMs: Long,
    val lastAtMs: Long,
)

/**
 * Pure glucose statistics used by the Statistics screen. Buckets follow the clinical AGP
 * convention (very-low <54, very-high >250); the inner low/high edges are the user's target.
 */
object GlucoseStats {
    fun compute(
        samples: List<GlucoseSample>,
        targetLow: Int = 70,
        targetHigh: Int = 180,
    ): GlucoseStatistics? {
        if (samples.isEmpty()) return null
        var sum = 0.0
        var sumSq = 0.0
        var veryLow = 0; var low = 0; var inRange = 0; var high = 0; var veryHigh = 0
        for (s in samples) {
            val v = s.mgDl
            sum += v
            sumSq += v.toDouble() * v
            when {
                v < 54 -> veryLow++
                v < targetLow -> low++
                v <= targetHigh -> inRange++
                v <= 250 -> high++
                else -> veryHigh++
            }
        }
        return fromAggregates(
            count = samples.size,
            sum = sum,
            sumSq = sumSq,
            lowestMgDl = samples.minOf { it.mgDl },
            highestMgDl = samples.maxOf { it.mgDl },
            veryLow = veryLow, low = low, inRange = inRange, high = high, veryHigh = veryHigh,
            firstAtMs = samples.minOf { it.atMs },
            lastAtMs = samples.maxOf { it.atMs },
        )
    }

    /**
     * Build the statistics from pre-reduced aggregates — the single source of truth for the
     * mean/SD/CV/GMI/A1c/TIR formulas, shared by [compute] and the SQL-aggregate path in the
     * history database. Population variance via `E[x²] − E[x]²` (equivalent to `Σ(x−μ)²/n`).
     */
    fun fromAggregates(
        count: Int,
        sum: Double,
        sumSq: Double,
        lowestMgDl: Int,
        highestMgDl: Int,
        veryLow: Int,
        low: Int,
        inRange: Int,
        high: Int,
        veryHigh: Int,
        firstAtMs: Long,
        lastAtMs: Long,
    ): GlucoseStatistics? {
        if (count <= 0) return null
        val mean = sum / count
        val variance = (sumSq / count - mean * mean).coerceAtLeast(0.0)
        val sd = sqrt(variance)
        val cv = if (mean != 0.0) sd / mean * 100.0 else 0.0
        fun pct(c: Int): Float = c.toFloat() / count * 100f
        return GlucoseStatistics(
            count = count,
            meanMgDl = mean,
            sdMgDl = sd,
            cvPercent = cv,
            gmiPercent = 3.31 + 0.02392 * mean,
            estimatedA1cPercent = (mean + 46.7) / 28.7,
            tir = TimeInRange(
                veryLowPct = pct(veryLow),
                lowPct = pct(low),
                inRangePct = pct(inRange),
                highPct = pct(high),
                veryHighPct = pct(veryHigh),
            ),
            lowestMgDl = lowestMgDl,
            highestMgDl = highestMgDl,
            firstAtMs = firstAtMs,
            lastAtMs = lastAtMs,
        )
    }
}
