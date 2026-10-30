package re.abbot.librecr.protocol.connection

/** Persistent phases describe intent; ownership is confirmed only by a new local sensor packet. */
enum class RecoveryPhase { WATCH_MONITOR, RELEASING_WATCH, PHONE_CONNECTING, PHONE_COLLECTING, WATCH_CONNECTING }

data class RecoveryState(
    val sensor: String,
    val nodeId: String,
    val phase: RecoveryPhase,
    val token: String,
    val sequence: Long,
    val preferWatch: Boolean = true,
    val deadlineMs: Long = 0L,
    val failedWatchTransfers: Int = 0,
    val retryWatchAtMs: Long = 0L,
    val confirmationAfterLifeCount: Int = -1,
    val claimUntilMs: Long = 0L,
)

data class ReceiverHealth(
    val reachable: Boolean = false,
    val active: Boolean = false,
    val packetAgeMs: Long = Long.MAX_VALUE,
    val canStart: Boolean = false,
    val claiming: Boolean = false,
    val claimRemainingMs: Long = 0L,
) {
    val healthy: Boolean get() = reachable && active && packetAgeMs in 0 until RecoveryPolicy.SILENCE_MS
}

object RecoveryPolicy {
    const val SILENCE_MS = 180_000L
    const val FIRST_PACKET_MS = 150_000L
    const val CRITICAL_BATTERY_PERCENT = 5
    const val RETURN_BATTERY_PERCENT = 15
    const val STATUS = "/librecr/automatic_handoff/status"
    const val PREPARE_WATCH = "/librecr/automatic_handoff/prepare_watch"
    const val SESSION_BACKUP = "/librecr/automatic_handoff/session_backup"

    fun shouldAdoptBackup(hasLocalKey: Boolean, localVerifiedAtMs: Long, peerChangedAtMs: Long, nowWallMs: Long): Boolean =
        !hasLocalKey || (peerChangedAtMs > localVerifiedAtMs && peerChangedAtMs <= nowWallMs + SILENCE_MS)

    fun acceptsControl(storedController: String, storedSequence: Long, controller: String, sequence: Long): Boolean =
        sequence >= 0 && (storedController != controller || sequence >= storedSequence)

    fun preparedStartValid(phase: String, preparedUntilMs: Long, nowMs: Long): Boolean =
        phase == "prepared" && nowMs <= preparedUntilMs

    /** Repeating a prepare may acknowledge a reservation, but cannot revive a released transfer. */
    fun acceptsPreparation(sameCommand: Boolean, phase: String): Boolean = !sameCommand || phase == "prepared"

    fun canStartWatch(batteryPercent: Int, charging: Boolean): Boolean =
        batteryPercent >= RETURN_BATTERY_PERCENT || (charging && batteryPercent > CRITICAL_BATTERY_PERCENT)

    fun controllerConnectTimeout(enabled: Boolean, knownSensor: Boolean, failures: Int, autoConnect: Boolean, directMs: Long): Long? =
        if (!autoConnect || (enabled && knownSensor && failures < AutomaticHandoff.FAILED_RECONNECTS)) directMs else null

    fun failedTransferDelay(attempt: Int): Long = (180_000L shl attempt.coerceIn(0, 4)).coerceAtMost(1_800_000L)

    fun retryDelay(attempt: Int): Long = longArrayOf(20_000L, 60_000L, 120_000L, 300_000L)[attempt.coerceIn(0, 3)]

    /** Saved wall-clock deadlines are rebased once per process; clock changes cannot extend a claim forever. */
    fun restoreDeadline(savedWallMs: Long, wallMs: Long, elapsedMs: Long): Long =
        elapsedMs + (savedWallMs - wallMs).coerceIn(0L, SILENCE_MS)
}

/** Android/Wear transport adapter. A false release/start result may also mean a lost acknowledgement. */
interface RecoveryRuntime {
    fun nowMs(): Long
    fun nextToken(): String
    fun nextSequence(previous: Long): Long = previous + 1
    suspend fun save(state: RecoveryState?)
    suspend fun status(nodeId: String): ReceiverHealth
    suspend fun releaseWatch(state: RecoveryState, manual: Boolean): Boolean
    suspend fun prepareWatch(state: RecoveryState): Boolean
    suspend fun startWatch(state: RecoveryState): Boolean
    suspend fun startPhone(): Boolean
    suspend fun stopPhone(): Boolean
    suspend fun watchOwnsSensor()
}

/** Serialized by its caller. Transport failures are represented as unavailable peers by the adapter. */
class PhoneRecoveryCoordinator(private val runtime: RecoveryRuntime, initial: RecoveryState? = null, initialSequence: Long = 0) {
    var state: RecoveryState? = initial
        private set
    private var enabled = false
    private var sequence = maxOf(initial?.sequence ?: 0L, initialSequence)
    private var nextReturnAttemptMs = 0L
    private val readings = ConsecutiveReadingTracker()
    private var lastRemoteLifeCount: Int? = null
    private var lastLocalLifeCount: Int? = null
    private var lastLocalPacketMs: Long? = null

    val ownsPhone: Boolean get() = state?.phase != null && state?.phase != RecoveryPhase.WATCH_MONITOR

    suspend fun configure(enabled: Boolean) {
        this.enabled = enabled
        readings.reset()
    }

    suspend fun cancel() {
        readings.reset()
        lastRemoteLifeCount = null
        lastLocalLifeCount = null
        lastLocalPacketMs = null
        checkpoint(null)
    }

    suspend fun preferWatch(sensor: String, nodeId: String) {
        if (state?.sensor == sensor && state?.phase == RecoveryPhase.WATCH_MONITOR) {
            if (nodeId.isNotBlank() && state?.nodeId != nodeId) checkpoint(state!!.copy(nodeId = nodeId))
            return
        }
        readings.reset()
        lastLocalPacketMs = null
        checkpoint(newState(sensor, nodeId, RecoveryPhase.WATCH_MONITOR,
            deadline = runtime.nowMs() + RecoveryPolicy.SILENCE_MS))
    }

    /** Only fresh, advancing live messages call this; DataItems and historical replay never do. */
    suspend fun remotePacket(sensor: String, nodeId: String, lifeCount: Int) {
        val current = state ?: return
        if (current.sensor != sensor || (current.nodeId.isNotBlank() && current.nodeId != nodeId)) return
        if (lastRemoteLifeCount != null && lifeCount <= lastRemoteLifeCount!!) return
        lastRemoteLifeCount = lifeCount
        when (current.phase) {
            RecoveryPhase.WATCH_MONITOR -> checkpoint(current.copy(nodeId = nodeId,
                deadlineMs = runtime.nowMs() + RecoveryPolicy.SILENCE_MS))
            RecoveryPhase.WATCH_CONNECTING -> if (lifeCount > maxOf(lastLocalLifeCount ?: -1, current.confirmationAfterLifeCount)) finishWatch(current.copy(nodeId = nodeId))
            else -> Unit // A delayed relay cannot stop a confirmed phone collector.
        }
    }

    suspend fun localPacket(lifeCount: Int, usable: Boolean) {
        val current = state ?: return
        if (current.phase != RecoveryPhase.PHONE_CONNECTING && current.phase != RecoveryPhase.PHONE_COLLECTING) return
        if (lastLocalLifeCount != null && lifeCount <= lastLocalLifeCount!!) return
        lastLocalLifeCount = lifeCount
        lastLocalPacketMs = runtime.nowMs()
        if (current.phase == RecoveryPhase.PHONE_CONNECTING) checkpoint(current.copy(
            phase = RecoveryPhase.PHONE_COLLECTING, deadlineMs = runtime.nowMs() + RecoveryPolicy.SILENCE_MS))
        if (readings.reading(lifeCount, usable, runtime.nowMs()) && enabled && current.preferWatch) tryReturn()
    }

    fun interrupted() { readings.reset() }
    fun peerConnected() { nextReturnAttemptMs = 0L }

    suspend fun requestPhone(sensor: String, nodeId: String, manual: Boolean = false, criticalBattery: Boolean = false): Boolean {
        if (!manual && !enabled) return false
        val current = state
        if (current != null && current.sensor != sensor) return false
        if (current?.phase in listOf(RecoveryPhase.PHONE_CONNECTING, RecoveryPhase.PHONE_COLLECTING)) return true
        val health = runtime.status(nodeId)
        if (health.healthy && !manual && !criticalBattery) {
            preferWatch(sensor, nodeId)
            checkpoint(state!!.copy(deadlineMs = runtime.nowMs() + RecoveryPolicy.SILENCE_MS - health.packetAgeMs))
            return false
        }
        val failedReturn = current?.phase == RecoveryPhase.WATCH_CONNECTING
        var next = newState(sensor, nodeId, RecoveryPhase.RELEASING_WATCH,
            preferWatch = !manual, deadline = runtime.nowMs() + AutomaticHandoff.RPC_TIMEOUT_MS).copy(
                failedWatchTransfers = (current?.failedWatchTransfers ?: 0) + if (failedReturn) 1 else 0,
                retryWatchAtMs = if (failedReturn) runtime.nowMs() + RecoveryPolicy.failedTransferDelay(current?.failedWatchTransfers ?: 0)
                    else current?.retryWatchAtMs ?: 0L)
        checkpoint(next) // Recovery after a process death must not depend on the peer finishing an RPC.
        runtime.releaseWatch(next, manual)
        val afterRelease = runtime.status(nodeId)
        if (afterRelease.healthy) {
            // The owner recovered or release was refused. A manual request can be retried explicitly.
            finishWatch(next)
            return false
        }
        next = next.copy(phase = RecoveryPhase.PHONE_CONNECTING,
            deadlineMs = runtime.nowMs() + RecoveryPolicy.FIRST_PACKET_MS,
            claimUntilMs = runtime.nowMs() + RecoveryPolicy.FIRST_PACKET_MS)
        readings.reset()
        lastLocalLifeCount = null
        lastLocalPacketMs = null
        checkpoint(next)
        return runtime.startPhone() // No release ACK is required when the peer cannot answer.
    }

    fun nextCheckMs(): Long? {
        val current = state ?: return null
        val now = runtime.nowMs()
        return when (current.phase) {
            RecoveryPhase.WATCH_MONITOR -> if (enabled) (current.deadlineMs - now).coerceAtLeast(1L) else null
            RecoveryPhase.PHONE_COLLECTING -> if (enabled && current.preferWatch) {
                if (readings.ready(now)) return (maxOf(nextReturnAttemptMs, current.retryWatchAtMs) - now).coerceAtLeast(1L)
                (maxOf(current.deadlineMs, (lastLocalPacketMs ?: 0L) + RecoveryPolicy.SILENCE_MS) - now).coerceAtLeast(1L)
            } else null
            RecoveryPhase.WATCH_CONNECTING -> minOf(20_000L, (current.deadlineMs - now).coerceAtLeast(1L))
            else -> (current.deadlineMs - now).coerceAtLeast(1L)
        }
    }

    suspend fun tick() {
        val current = state ?: return
        val now = runtime.nowMs()
        when (current.phase) {
            RecoveryPhase.WATCH_MONITOR -> if (enabled && now >= current.deadlineMs) {
                val health = runtime.status(current.nodeId)
                if (health.healthy) checkpoint(current.copy(deadlineMs = now + RecoveryPolicy.SILENCE_MS - health.packetAgeMs))
                else if (health.claiming && health.claimRemainingMs > 0) checkpoint(current.copy(
                    deadlineMs = now + health.claimRemainingMs.coerceAtMost(RecoveryPolicy.FIRST_PACKET_MS)))
                else requestPhone(current.sensor, current.nodeId)
            }
            RecoveryPhase.RELEASING_WATCH -> if (now >= current.deadlineMs) {
                // Includes a restart between persisted intent, release, and service launch.
                if (current.preferWatch && runtime.status(current.nodeId).healthy) { finishWatch(current); return }
                runtime.releaseWatch(current, manual = !current.preferWatch)
                if (runtime.status(current.nodeId).healthy) { finishWatch(current); return }
                checkpoint(current.copy(phase = RecoveryPhase.PHONE_CONNECTING,
                    deadlineMs = now + RecoveryPolicy.FIRST_PACKET_MS, claimUntilMs = now + RecoveryPolicy.FIRST_PACKET_MS))
                runtime.startPhone()
            }
            RecoveryPhase.PHONE_CONNECTING -> if (now >= current.deadlineMs) {
                if (current.preferWatch && runtime.status(current.nodeId).healthy) { finishWatch(current); return }
                if (enabled && current.preferWatch && tryReturn()) return
                // Keep the existing economical BLE loop. Do not restart it every timeout.
                checkpoint(current.copy(deadlineMs = now + RecoveryPolicy.SILENCE_MS))
                runtime.startPhone() // Idempotent service start also repairs a failed foreground start.
            }
            RecoveryPhase.PHONE_COLLECTING -> {
                if (enabled && current.preferWatch && readings.ready(now)) tryReturn()
                else if (enabled && current.preferWatch && now >= maxOf(current.deadlineMs, (lastLocalPacketMs ?: 0L) + RecoveryPolicy.SILENCE_MS)) {
                    // The phone lost the sensor too: returning does not require five impossible values.
                    if (!tryReturn()) checkpoint(current.copy(deadlineMs = now + RecoveryPolicy.SILENCE_MS))
                }
            }
            RecoveryPhase.WATCH_CONNECTING -> {
                val health = runtime.status(current.nodeId)
                if (health.healthy) finishWatch(current)
                else if (now >= current.deadlineMs) {
                    // A start ACK only confirms launch, not a working sensor link.
                    runtime.releaseWatch(current, false)
                    val latest = runtime.status(current.nodeId)
                    if (latest.healthy) finishWatch(current)
                    else {
                        checkpoint(current.copy(phase = RecoveryPhase.PHONE_CONNECTING,
                            deadlineMs = now + RecoveryPolicy.FIRST_PACKET_MS,
                            claimUntilMs = now + RecoveryPolicy.FIRST_PACKET_MS,
                            failedWatchTransfers = current.failedWatchTransfers + 1,
                            retryWatchAtMs = now + RecoveryPolicy.failedTransferDelay(current.failedWatchTransfers)))
                        runtime.startPhone()
                    }
                }
            }
        }
    }

    private suspend fun tryReturn(): Boolean {
        val current = state ?: return false
        if (runtime.nowMs() < maxOf(nextReturnAttemptMs, current.retryWatchAtMs)) return false
        nextReturnAttemptMs = runtime.nowMs() + 60_000L
        if (runtime.status(current.nodeId).healthy) { finishWatch(current); return true }
        val next = newState(current.sensor, current.nodeId, RecoveryPhase.WATCH_CONNECTING,
            deadline = runtime.nowMs() + RecoveryPolicy.FIRST_PACKET_MS).copy(
                failedWatchTransfers = current.failedWatchTransfers, retryWatchAtMs = current.retryWatchAtMs,
                confirmationAfterLifeCount = lastLocalLifeCount ?: current.confirmationAfterLifeCount)
        if (!runtime.prepareWatch(next)) return false // Includes peer absence and low battery.
        checkpoint(next)
        if (!runtime.stopPhone()) {
            checkpoint(current)
            runtime.startPhone() // Repair the phone's service/auto-connect flags after a partial stop.
            return false
        }
        runtime.startWatch(next)
        readings.reset()
        lastLocalPacketMs = null
        // Stay in WATCH_CONNECTING even on a missing ACK; a packet/status or deadline settles it.
        return true
    }

    private suspend fun finishWatch(current: RecoveryState) {
        runtime.stopPhone()
        runtime.watchOwnsSensor()
        readings.reset()
        lastLocalLifeCount = null
        lastLocalPacketMs = null
        checkpoint(current.copy(phase = RecoveryPhase.WATCH_MONITOR,
            deadlineMs = runtime.nowMs() + RecoveryPolicy.SILENCE_MS))
    }

    private fun newState(sensor: String, nodeId: String, phase: RecoveryPhase, preferWatch: Boolean = true, deadline: Long) =
        RecoveryState(sensor, nodeId, phase, runtime.nextToken(), runtime.nextSequence(sequence).also { sequence = it }, preferWatch, deadline)

    private suspend fun checkpoint(value: RecoveryState?) {
        runtime.save(value)
        state = value
    }
}
