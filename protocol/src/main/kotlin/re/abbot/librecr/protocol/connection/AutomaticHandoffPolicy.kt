package re.abbot.librecr.protocol.connection

/** Shared thresholds and RPC paths; control commands are never persisted as Wear DataItems. */
object AutomaticHandoff {
    const val FAILED_RECONNECTS = 3
    const val CONSECUTIVE_READINGS = 5
    const val BLE_RELEASE_DELAY_MS = 3_000L
    const val RPC_TIMEOUT_MS = 20_000L
    const val SETTINGS = "/librecr/automatic_handoff/settings"
    const val REQUEST_PHONE = "/librecr/automatic_handoff/request_phone"
    const val RELEASE_WATCH = "/librecr/automatic_handoff/release_watch"
    const val START_WATCH = "/librecr/automatic_handoff/start_watch"
}

class FailedReconnectTracker {
    var failures: Int = 0
        private set

    fun failed(): Boolean {
        failures = (failures + 1).coerceAtMost(AutomaticHandoff.FAILED_RECONNECTS)
        return failures >= AutomaticHandoff.FAILED_RECONNECTS
    }

    fun reset() { failures = 0 }
}

/** Only new, usable realtime readings in adjacent sensor minutes count toward return to the watch. */
class ConsecutiveReadingTracker {
    var count: Int = 0
        private set
    private var lastLifeCount: Int? = null
    private var lastAtMs: Long = 0L

    fun reading(lifeCount: Int, usable: Boolean, elapsedMs: Long): Boolean {
        if (!usable) {
            reset()
            return false
        }
        val previous = lastLifeCount
        if (previous != null && lifeCount <= previous) return count >= AutomaticHandoff.CONSECUTIVE_READINGS
        count = if (previous != null && lifeCount == previous + 1 &&
            elapsedMs - lastAtMs in 1..90_000L
        ) (count + 1).coerceAtMost(AutomaticHandoff.CONSECUTIVE_READINGS) else 1
        lastLifeCount = lifeCount
        lastAtMs = elapsedMs
        return count >= AutomaticHandoff.CONSECUTIVE_READINGS
    }

    fun reset() {
        count = 0
        lastLifeCount = null
        lastAtMs = 0L
    }

    fun ready(elapsedMs: Long): Boolean =
        count >= AutomaticHandoff.CONSECUTIVE_READINGS && elapsedMs - lastAtMs in 0..90_000L
}
