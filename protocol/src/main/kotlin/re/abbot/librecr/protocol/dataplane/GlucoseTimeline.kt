package re.abbot.librecr.protocol.dataplane

/**
 * End-to-end stopwatch for one glucose reading on the watch→phone path.
 *
 * Each timestamp is a wall-clock millisecond captured at one stage. The watch
 * fills `firstNotify..sentToPhone` (all on the watch clock) and ships them in the
 * relay payload; the phone fills `phoneReceived..uiRendered` (all on the phone
 * clock). Only the `sentToPhone → phoneReceived` hop crosses devices, so only
 * that segment — and any `total` that ends on the phone — is exposed to
 * watch/phone clock skew; every other segment is intra-device and skew-free.
 *
 * A zero timestamp means "stage not reached / not known here". Latency getters
 * return null when either endpoint is missing, so a partial timeline (e.g. logged
 * on the phone before its UI renders) degrades cleanly.
 */
data class GlucoseTimeline(
    val lifeCount: Int,
    val firstNotifyTs: Long = 0L,
    val secondNotifyTs: Long = 0L,
    val decodedTs: Long = 0L,
    val savedTs: Long = 0L,
    val sentToPhoneTs: Long = 0L,
    val phoneReceivedTs: Long = 0L,
    val uiRenderedTs: Long = 0L,
) {
    val firstNotifyToDecoded: Long? get() = delta(firstNotifyTs, decodedTs)
    val decodedToSaved: Long? get() = delta(decodedTs, savedTs)
    val savedToSent: Long? get() = delta(savedTs, sentToPhoneTs)
    val sentToPhoneReceived: Long? get() = delta(sentToPhoneTs, phoneReceivedTs)
    val phoneReceivedToUi: Long? get() = delta(phoneReceivedTs, uiRenderedTs)

    /** firstNotify → the latest stage that has a timestamp (skew-affected once it reaches the phone). */
    val totalToLatest: Long?
        get() {
            if (firstNotifyTs <= 0L) return null
            val last = listOf(uiRenderedTs, phoneReceivedTs, sentToPhoneTs, savedTs, decodedTs, secondNotifyTs)
                .firstOrNull { it > 0L } ?: return null
            return last - firstNotifyTs
        }

    /** All known segments as (name, ms), in pipeline order — for spotting the dominant cost. */
    fun segments(): List<Pair<String, Long>> = buildList {
        firstNotifyToDecoded?.let { add("BLE/decode(firstNotify→decoded)" to it) }
        decodedToSaved?.let { add("watch-store(decoded→saved)" to it) }
        savedToSent?.let { add("watch-send(saved→sent)" to it) }
        sentToPhoneReceived?.let { add("DataLayer(sent→phoneRecv)" to it) }
        phoneReceivedToUi?.let { add("phone-UI/store(phoneRecv→ui)" to it) }
    }

    /** Largest non-negative segment — the stage where the reading spent the most time. */
    fun dominantSegment(): Pair<String, Long>? =
        segments().filter { it.second >= 0L }.maxByOrNull { it.second }

    /**
     * Per-reading verdicts (point 7 of the investigation). [previousLifeCount] is the
     * lifeCount of the prior reading on the *same device* (skew-free), used for the
     * "skipped" / "delayed" checks; pass null for the first reading.
     */
    fun classify(previousLifeCount: Int?): List<String> {
        val out = mutableListOf<String>()

        if (previousLifeCount != null) {
            val gap = lifeCount - previousLifeCount
            if (gap > 1) out += "LOST missing=${gap - 1} (lifeCount ${previousLifeCount + 1}..${lifeCount - 1})"
        }

        firstNotifyToDecoded?.let { if (it > SLOW_SEGMENT_MS) out += "SLOW BLE/decode firstNotify→decoded=${it}ms" }
        decodedToSaved?.let { if (it > SLOW_SEGMENT_MS) out += "SLOW watch-store decoded→saved=${it}ms" }
        savedToSent?.let { if (it > SLOW_SEGMENT_MS) out += "SLOW watch-send saved→sent=${it}ms" }
        sentToPhoneReceived?.let {
            when {
                it < -CLOCK_SKEW_TOLERANCE_MS -> out += "CLOCK-SKEW sent→phoneRecv=${it}ms (watch/phone clocks differ)"
                it > SLOW_SEGMENT_MS -> out += "SLOW DataLayer sent→phoneRecv=${it}ms"
            }
        }
        phoneReceivedToUi?.let { if (it > SLOW_SEGMENT_MS) out += "SLOW phone-UI/store phoneRecv→ui=${it}ms" }

        totalToLatest?.let { total ->
            if (total > DELAYED_TOTAL_MS) {
                val dom = dominantSegment()
                out += "DELAYED total=${total / 1000}s" + (dom?.let { " dominant=${it.first}=${it.second}ms" } ?: "")
            }
        }

        if (out.isEmpty()) out += "OK"
        return out
    }

    /** One greppable line with every known timestamp, latency and verdict. */
    fun format(previousLifeCount: Int?): String {
        val ts = buildList {
            if (firstNotifyTs > 0L) add("firstNotify=$firstNotifyTs")
            if (secondNotifyTs > 0L) add("secondNotify=$secondNotifyTs")
            if (decodedTs > 0L) add("decoded=$decodedTs")
            if (savedTs > 0L) add("saved=$savedTs")
            if (sentToPhoneTs > 0L) add("sent=$sentToPhoneTs")
            if (phoneReceivedTs > 0L) add("phoneRecv=$phoneReceivedTs")
            if (uiRenderedTs > 0L) add("ui=$uiRenderedTs")
        }.joinToString(" ")
        val lat = segments().joinToString(" ") { "${it.first}=${it.second}ms" }
        return "[TIMELINE] lc=$lifeCount $ts | $lat | ${classify(previousLifeCount).joinToString("; ")}"
    }

    private fun delta(from: Long, to: Long): Long? = if (from > 0L && to > 0L) to - from else null

    companion object {
        /** A consecutive reading shown more than this late counts as delayed (investigation §7). */
        const val DELAYED_TOTAL_MS = 75_000L
        /** A single segment slower than this is flagged as the likely culprit. Tunable. */
        const val SLOW_SEGMENT_MS = 5_000L
        /** Cross-device deltas more negative than this are reported as clock skew, not data. */
        const val CLOCK_SKEW_TOLERANCE_MS = 1_000L
    }
}
