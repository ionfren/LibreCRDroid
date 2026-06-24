package re.abbot.librecr.app.log

import re.abbot.librecr.protocol.dataplane.GlucoseTimeline

/**
 * Phone-side accumulator for the watch→phone glucose timeline.
 *
 * The watch ships its own stage timestamps (firstNotify..sentToPhone) in the relay
 * payload; [onPhoneReceived] adds the phone's arrival time and emits the cross-device
 * breakdown immediately (so the DataLayer hop is logged even when no UI is open).
 * [onUiRendered] later completes the line with the phone-UI segment.
 *
 * The delayed-reading verdict uses the **phone's own** inter-arrival gap
 * (`phoneReceivedTs` now − last), which is skew-free because both samples are on the
 * phone clock — unlike a cross-device total. When a consecutive reading lands far more
 * than a minute late, the dominant segment localizes the cause (BLE, decode, store,
 * DataLayer, or UI).
 */
object GlucoseTimelineTracker {
    private const val MAX_TRACKED = 16

    private val lock = Any()
    private val partial = LinkedHashMap<Int, GlucoseTimeline>()
    private var lastLifeCount: Int? = null
    private var lastPhoneReceivedTs: Long = 0L

    /** Wear listener: a relayed reading just arrived. [timeline] carries the watch stamps + phoneReceivedTs. */
    fun onPhoneReceived(timeline: GlucoseTimeline) {
        val line: String
        synchronized(lock) {
            val prevLifeCount = lastLifeCount
            val prevRecv = lastPhoneReceivedTs
            lastLifeCount = timeline.lifeCount
            lastPhoneReceivedTs = timeline.phoneReceivedTs

            partial[timeline.lifeCount] = timeline
            while (partial.size > MAX_TRACKED) {
                val oldest = partial.keys.iterator().next()
                if (oldest == timeline.lifeCount) break
                partial.remove(oldest)
            }

            val verdicts = timeline.classify(prevLifeCount).toMutableList()
            if (prevLifeCount != null && timeline.lifeCount == prevLifeCount + 1 &&
                prevRecv > 0L && timeline.phoneReceivedTs > 0L
            ) {
                val gap = timeline.phoneReceivedTs - prevRecv
                if (gap > GlucoseTimeline.DELAYED_TOTAL_MS) {
                    val dom = timeline.dominantSegment()
                    verdicts += "DELAYED phone inter-arrival=${gap / 1000}s" +
                        (dom?.let { " dominant=${it.first}=${it.second}ms" } ?: "")
                }
            }
            line = "[TIMELINE xdev] lc=${timeline.lifeCount} ${segmentsText(timeline)} | ${verdicts.joinToString("; ")}"
        }
        BleLog.log(line)
    }

    /** Phone UI: a reading was rendered (phone clock). Completes the line for that lifeCount. */
    fun onUiRendered(lifeCount: Int, atMs: Long) {
        val line: String
        synchronized(lock) {
            val base = partial[lifeCount] ?: return
            if (base.uiRenderedTs > 0L) return
            val full = base.copy(uiRenderedTs = atMs)
            partial[lifeCount] = full
            line = "[TIMELINE full] lc=$lifeCount phoneRecv→ui=${full.phoneReceivedToUi ?: -1}ms " +
                "total(firstNotify→ui)=${full.totalToLatest ?: -1}ms | ${full.classify(null).joinToString("; ")}"
        }
        BleLog.log(line)
    }

    private fun segmentsText(t: GlucoseTimeline): String =
        t.segments().joinToString(" ") { "${it.first}=${it.second}ms" }
}
