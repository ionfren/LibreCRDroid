package re.abbot.librecr.app.log

/**
 * Per-reading stopwatch across the glucose display pipeline.
 *
 * Each stage of the chain calls [mark] with the reading's `lifeCount` (the
 * sensor's own minute counter, the only identifier that is stable end-to-end
 * across BLE → store → UI → complication) and the stage it just reached. The
 * first timestamp seen for each (lifeCount, stage) pair is kept; later duplicates
 * are ignored, so two complication services both refreshing for the same reading
 * record the earliest AOD paint, not the second one.
 *
 * Every mark emits one greppable `[LATENCY]` line through [BleLog] (which already
 * timestamps to the millisecond and feeds the in-app log viewer), annotated with:
 *   - `Δprev` — ms since the previous stage that actually fired for this reading,
 *   - `Δnotify` — ms since [Stage.BLE_NOTIFY_RECEIVED] (the end-to-end age).
 *
 * Reading the deltas tells you exactly where a minute went: a large `Δprev` on
 * STORE_UPDATED is DataStore disk latency; a large one on UI_RENDERED is the
 * DataStore→Compose round-trip; a large one on AOD_UPDATED is the OS complication
 * refresh throttle. The two anchors (BLE notify vs. relay arrival) are described
 * per call site.
 */
object GlucoseLatencyTracer {
    enum class Stage(val label: String) {
        /** Local path: completing BLE fragment dequeued. Relay path: Data Layer message arrived on the watch. */
        BLE_NOTIFY_RECEIVED("BLE_NOTIFY_RECEIVED"),
        GLUCOSE_DECODED("GLUCOSE_DECODED"),
        VIEWMODEL_UPDATED("VIEWMODEL_UPDATED"),
        STORE_UPDATED("STORE_UPDATED"),
        UI_RENDERED("UI_RENDERED"),
        AOD_UPDATED("AOD_UPDATED"),
    }

    private const val MAX_TRACKED = 16

    private val lock = Any()
    // lifeCount -> (stage -> first timestamp ms). LinkedHashMap keeps insertion order
    // so the oldest tracked reading is the first key (cheap LRU eviction) and the last
    // inserted stage is the immediately-preceding one (for Δprev).
    private val traces = LinkedHashMap<Int, LinkedHashMap<Stage, Long>>()

    fun mark(lifeCount: Int, stage: Stage, atMs: Long = System.currentTimeMillis()) {
        var anchorMs: Long? = null
        var prevStage: Stage? = null
        var prevMs: Long? = null
        var isNew = false
        synchronized(lock) {
            val trace = traces.getOrPut(lifeCount) { LinkedHashMap() }
            // At most one new lifeCount per call, so a single eviction keeps the cap.
            while (traces.size > MAX_TRACKED) {
                val oldest = traces.keys.iterator().next()
                if (oldest == lifeCount) break
                traces.remove(oldest)
            }
            if (!trace.containsKey(stage)) {
                anchorMs = trace[Stage.BLE_NOTIFY_RECEIVED]
                trace.entries.lastOrNull()?.let { prevStage = it.key; prevMs = it.value }
                trace[stage] = atMs
                isNew = true
            }
        }
        if (!isNew) return

        val sb = StringBuilder("[LATENCY] ${stage.label} lc=$lifeCount t=$atMs")
        prevStage?.let { ps -> prevMs?.let { sb.append(" Δprev(${ps.label})=+${atMs - it}ms") } }
        if (stage != Stage.BLE_NOTIFY_RECEIVED) {
            anchorMs?.let { sb.append(" Δnotify=+${atMs - it}ms") }
        }
        BleLog.log(sb.toString())
    }
}
