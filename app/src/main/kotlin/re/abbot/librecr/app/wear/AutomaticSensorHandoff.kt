package re.abbot.librecr.app.wear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import re.abbot.librecr.app.LibreCR
import re.abbot.librecr.app.ble.ConnectionState
import re.abbot.librecr.app.data.ImportedSession
import re.abbot.librecr.app.log.BleLog
import re.abbot.librecr.app.service.ReceiverRecoveryService
import re.abbot.librecr.app.service.ReceiverRecoveryAlarm
import re.abbot.librecr.app.service.SensorForegroundService
import re.abbot.librecr.protocol.connection.*
import java.util.UUID

/** Phone-side authority, with a persistent monitor independent of the watch process. */
object AutomaticSensorHandoff {
    private lateinit var context: Context
    private lateinit var coordinator: PhoneRecoveryCoordinator
    private val mutex = Mutex()
    private val events = Channel<Unit>(Channel.CONFLATED)
    private val prefs get() = context.getSharedPreferences("librecr_automatic_handoff", Context.MODE_PRIVATE)
    @Volatile private var snapshot: RecoveryState? = null
    @Volatile private var enabled = false
    @Volatile private var lastLocalPacketMs: Long? = null
    @Volatile private var lastLocalLifeCount = -1
    private var retryAfterMs = 0L
    val ownsPhoneConnection: Boolean get() = snapshot?.phase?.let { it != RecoveryPhase.WATCH_MONITOR } == true
    val monitoringRequired: Boolean get() = snapshot?.let {
        (enabled && it.preferWatch && it.phase == RecoveryPhase.WATCH_MONITOR) ||
            it.phase == RecoveryPhase.WATCH_CONNECTING || it.phase == RecoveryPhase.RELEASING_WATCH ||
            (it.phase == RecoveryPhase.PHONE_CONNECTING && !SensorForegroundService.running)
    } == true

    fun initialize(context: Context) {
        this.context = context.applicationContext
        snapshot = loadState()
        coordinator = PhoneRecoveryCoordinator(runtime, snapshot, prefs.getLong("last_sequence", 0L))
        LibreCR.appScope.launch {
            while (true) {
                val waitMs = mutex.withLock {
                    coordinator.nextCheckMs()?.coerceAtLeast((retryAfterMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
                        .also { ReceiverRecoveryAlarm.schedule(context, it) }
                }
                if (waitMs == null) events.receive()
                else if (withTimeoutOrNull(waitMs) { events.receive(); true } != true) {
                    safely { coordinator.tick() }
                }
            }
        }
    }

    @Volatile var localSensorAddress = ""
        private set
    fun onLocalSessionStarted(address: String) { localSensorAddress = address; lastLocalLifeCount = -1; lastLocalPacketMs = null }

    fun onLocalReading(lifeCount: Int, usable: Boolean) {
        if (lifeCount <= lastLocalLifeCount) return
        lastLocalLifeCount = lifeCount
        lastLocalPacketMs = SystemClock.elapsedRealtime()
        launch { coordinator.localPacket(lifeCount, usable) }
    }

    fun onConnectionInterrupted() {
        lastLocalPacketMs = null
        launch { coordinator.interrupted() }
    }

    /** Only a direct live message with a recent origin timestamp can renew the watch's liveness. */
    fun onRemotePacket(nodeId: String, sensorAddress: String, lifeCount: Int, receivedAtMs: Long, message: Boolean) {
        val age = System.currentTimeMillis() - receivedAtMs
        if (!message || age !in -30_000L..90_000L) return
        launch {
            val session = LibreCR.store.loadSession() ?: return@launch
            if (!session.bleAddress.equals(sensorAddress, true)) return@launch
            if (coordinator.state == null && !LibreCR.store.autoConnectEnabled()) {
                coordinator.preferWatch(sensorId(session), nodeId)
            }
            coordinator.remotePacket(sensorId(session), nodeId, lifeCount)
        }
    }

    fun onPeerConnected() { launch { coordinator.peerConnected(); coordinator.tick() } }

    fun onRecoveryWakeup() { ReceiverRecoveryAlarm.runAwake(context) { safely { coordinator.tick() } } }

    fun onSettingsChanged(enabled: Boolean) {
        this.enabled = enabled
        launch {
            coordinator.configure(enabled)
            val settings = LibreCR.settings.current()
            val state = coordinator.state
            if (state != null && settings.selectedWearNodeId.isNotBlank() && settings.selectedWearNodeId != state.nodeId) {
                coordinator.cancel()
            }
            if (coordinator.state == null && LibreCR.store.watchTakeoverFlow.first()) {
                val session = LibreCR.store.loadSession() ?: return@launch
                coordinator.preferWatch(sensorId(session), settings.selectedWearNodeId)
            }
            coordinator.tick()
        }
    }

    suspend fun watchHandoffStarted() = mutex.withLock {
        val session = LibreCR.store.loadSession() ?: return@withLock
        coordinator.preferWatch(sensorId(session), resolveWatchNode())
        updateMonitor()
        events.trySend(Unit)
    }

    suspend fun cancel() = mutex.withLock {
        coordinator.cancel()
        updateMonitor()
        events.trySend(Unit)
    }

    /** Explicit user override works with the toggle off and with a dead/unreachable watch. */
    suspend fun takePhoneManually(): Boolean = mutex.withLock {
        if (!hasBluetoothPermissions()) return@withLock false
        val session = LibreCR.store.loadSession() ?: return@withLock false
        val node = resolveWatchNode()
        coordinator.cancel()
        val started = coordinator.requestPhone(sensorId(session), node, manual = true)
        updateMonitor()
        events.trySend(Unit)
        started
    }

    suspend fun acceptsBackupNode(nodeId: String): Boolean = WearDataSync.acceptsSourceNode(nodeId)

    fun onRequest(nodeId: String, path: String, data: ByteArray): Task<ByteArray>? {
        if (path != AutomaticHandoff.REQUEST_PHONE && path != RecoveryPolicy.STATUS) return null
        return handoffTask(LibreCR.appScope) {
            if (path == RecoveryPolicy.STATUS) {
                if (!WearDataSync.acceptsSourceNode(nodeId)) return@handoffTask handoffReply(false)
                val session = LibreCR.store.loadSession() ?: return@handoffTask handoffReply(false)
                val request = JSONObject(String(data, Charsets.UTF_8))
                if (!session.bleAddress.equals(request.optString("bleAddress"), true)) return@handoffTask handoffReply(false)
                val current = snapshot
                val age = lastLocalPacketMs?.let { SystemClock.elapsedRealtime() - it } ?: Long.MAX_VALUE
                return@handoffTask handoffReply(true).put("bleAddress", session.bleAddress)
                    .put("active", LibreCR.manager.state.value == ConnectionState.STREAMING && age < RecoveryPolicy.SILENCE_MS)
                    .put("packetAgeMs", age).put("preferWatch", current?.preferWatch == true).put("claimRemainingMs", if (current?.phase == RecoveryPhase.PHONE_CONNECTING)
                        (current.claimUntilMs - SystemClock.elapsedRealtime()).coerceIn(0, RecoveryPolicy.FIRST_PACKET_MS) else 0L)
            }
            mutex.withLock {
                if (!WearDataSync.acceptsSourceNode(nodeId)) return@withLock handoffReply(false)
                val session = LibreCR.store.loadSession() ?: return@withLock handoffReply(false)
                val json = JSONObject(String(data, Charsets.UTF_8))
                if (json.optString("bleAddress").isNotBlank() && !session.bleAddress.equals(json.optString("bleAddress"), true)) {
                    return@withLock handoffReply(false)
                }
                if (!enabled || !hasBluetoothPermissions() || !nearbyHandoffNode(context, nodeId)) return@withLock handoffReply(false)
                val accepted = coordinator.requestPhone(sensorId(session), nodeId, criticalBattery = json.optBoolean("criticalBattery"))
                updateMonitor()
                events.trySend(Unit)
                handoffReply(accepted).put("phase", coordinator.state?.phase?.name)
            }
        }
    }

    private fun launch(block: suspend () -> Unit) { LibreCR.appScope.launch { safely(block) } }
    private suspend fun safely(block: suspend () -> Unit) {
        try { mutex.withLock {
            val session = LibreCR.store.loadSession()
            if (coordinator.state != null && (session == null || coordinator.state?.sensor != sensorId(session))) coordinator.cancel()
            block()
            retryAfterMs = 0L
            updateMonitor()
        } }
        catch (error: Exception) {
            if (error is CancellationException && error !is TimeoutCancellationException) throw error
            retryAfterMs = SystemClock.elapsedRealtime() + RecoveryPolicy.retryDelay(0)
            BleLog.log("receiver recovery: ${error.javaClass.simpleName}: ${error.message}")
        } finally { events.trySend(Unit) }
    }

    private fun updateMonitor() { ReceiverRecoveryService.ensure(context, monitoringRequired) }

    private suspend fun resolveWatchNode(): String {
        val selected = LibreCR.settings.current().selectedWearNodeId
        if (selected.isNotBlank()) return selected
        snapshot?.nodeId?.takeIf { it.isNotBlank() }?.let { return it }
        return runCatching { WearDataSync.connectedWearNodes(context).singleOrNull { it.isNearby }?.id.orEmpty() }.getOrDefault("")
    }

    private fun sensorId(session: ImportedSession): String = session.bleAddress.uppercase()
    private fun command(state: RecoveryState) = JSONObject().put("token", state.token).put("sequence", state.sequence)
        .put("bleAddress", state.sensor).put("controller", controllerId())
    private fun controllerId(): String = prefs.getString("controller", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("controller", it).commit()
    }

    private suspend fun rpc(nodeId: String, path: String, payload: JSONObject): JSONObject? {
        if (nodeId.isBlank()) return null
        return try {
            if (!nearbyHandoffNode(context, nodeId)) null else handoffRpc(context, nodeId, path, payload)
        } catch (error: Exception) {
            if (error is CancellationException && error !is TimeoutCancellationException) throw error
            null
        }
    }

    private val runtime = object : RecoveryRuntime {
        override fun nowMs() = SystemClock.elapsedRealtime()
        override fun nextToken() = UUID.randomUUID().toString()
        override fun nextSequence(previous: Long) = maxOf(previous + 1, System.currentTimeMillis())
        override suspend fun save(state: RecoveryState?) {
            val editor = prefs.edit()
            if (state == null) editor.remove("recovery_v2") else editor.putString("recovery_v2", JSONObject()
                .put("sensor", state.sensor).put("node", state.nodeId).put("phase", state.phase.name)
                .put("token", state.token).put("sequence", state.sequence).put("preferWatch", state.preferWatch)
                .put("deadlineWall", System.currentTimeMillis() + (state.deadlineMs - nowMs()).coerceAtLeast(0))
                .put("failedWatchTransfers", state.failedWatchTransfers)
                .put("confirmationAfterLifeCount", state.confirmationAfterLifeCount)
                .put("claimUntilWall", System.currentTimeMillis() + (state.claimUntilMs - nowMs()).coerceAtLeast(0))
                .put("retryWatchWall", System.currentTimeMillis() + (state.retryWatchAtMs - nowMs()).coerceAtLeast(0)).toString())
            if (state != null) editor.putLong("last_sequence", maxOf(state.sequence, prefs.getLong("last_sequence", 0L)))
            check(editor.remove("cycle").commit()) { "Cannot persist receiver recovery" }
            snapshot = state
            updateMonitor()
        }
        override suspend fun status(nodeId: String): ReceiverHealth {
            val response = rpc(nodeId, RecoveryPolicy.STATUS, JSONObject().put("bleAddress", snapshot?.sensor)) ?: return ReceiverHealth()
            val session = LibreCR.store.loadSession() ?: return ReceiverHealth()
            if (!session.bleAddress.equals(response.optString("bleAddress"), true)) return ReceiverHealth()
            return ReceiverHealth(reachable = response.optBoolean("accepted"), active = response.optBoolean("active"),
                packetAgeMs = response.optLong("packetAgeMs", Long.MAX_VALUE), canStart = response.optBoolean("canStart"),
                claiming = response.optLong("claimRemainingMs") > 0, claimRemainingMs = response.optLong("claimRemainingMs"))
        }
        override suspend fun releaseWatch(state: RecoveryState, manual: Boolean): Boolean {
            val response = rpc(state.nodeId, AutomaticHandoff.RELEASE_WATCH, command(state).put("manual", manual).put("allowRecovery", state.preferWatch)) ?: return false
            response.optString("session").takeIf { it.isNotBlank() }?.let { ReceiverSessionBackup.acceptReleasedSession(it) }
            if (response.optBoolean("accepted")) delay(AutomaticHandoff.BLE_RELEASE_DELAY_MS)
            return response.optBoolean("accepted")
        }
        override suspend fun prepareWatch(state: RecoveryState): Boolean =
            rpc(state.nodeId, RecoveryPolicy.PREPARE_WATCH, command(state))?.optBoolean("accepted") == true
        override suspend fun startWatch(state: RecoveryState): Boolean {
            val session = LibreCR.store.loadSession() ?: return false
            val key = LibreCR.store.loadCachedPhase5RawKey()
            delay(AutomaticHandoff.BLE_RELEASE_DELAY_MS)
            return rpc(state.nodeId, AutomaticHandoff.START_WATCH, command(state)
                .put("session", session.copy(phase5RawKey = key).toJson()))?.optBoolean("accepted") == true
        }
        override suspend fun startPhone(): Boolean {
            if (LibreCR.manager.state.value == ConnectionState.IDLE) {
                ReceiverSessionBackup.adopt(snapshot?.nodeId.orEmpty())
            }
            LibreCR.store.setWatchTakeoverEnabled(false)
            LibreCR.store.setAutoConnectEnabled(true)
            return SensorForegroundService.start(context, allowCandidateFirstPair = true, passiveConnect = snapshot?.preferWatch == true)
        }
        override suspend fun stopPhone(): Boolean {
            LibreCR.store.setAutoConnectEnabled(false)
            val stopped = LibreCR.manager.stopAndJoin()
            SensorForegroundService.stop(context)
            return stopped
        }
        override suspend fun watchOwnsSensor() { LibreCR.store.setWatchTakeoverEnabled(true) }
    }

    private fun loadState(): RecoveryState? = runCatching {
        val raw = prefs.getString("recovery_v2", null)
        if (raw == null) {
            val legacy = JSONObject(prefs.getString("cycle", null) ?: return@runCatching null)
            return@runCatching RecoveryState(legacy.getString("address").uppercase(), legacy.getString("nodeId"),
                if (legacy.optBoolean("returning")) RecoveryPhase.WATCH_CONNECTING else RecoveryPhase.RELEASING_WATCH,
                legacy.getString("token"), maxOf(prefs.getLong("last_sequence", 0), System.currentTimeMillis()),
                deadlineMs = SystemClock.elapsedRealtime() + AutomaticHandoff.RPC_TIMEOUT_MS)
        }
        val json = JSONObject(raw)
        RecoveryState(json.getString("sensor"), json.getString("node"), RecoveryPhase.valueOf(json.getString("phase")),
            json.getString("token"), maxOf(json.getLong("sequence"), prefs.getLong("last_sequence", 0L)),
            json.optBoolean("preferWatch", true), RecoveryPolicy.restoreDeadline(json.optLong("deadlineWall"),
                System.currentTimeMillis(), SystemClock.elapsedRealtime()), json.optInt("failedWatchTransfers"),
            SystemClock.elapsedRealtime() + (json.optLong("retryWatchWall") - System.currentTimeMillis()).coerceIn(0, 1_800_000L),
            json.optInt("confirmationAfterLifeCount", -1), RecoveryPolicy.restoreDeadline(json.optLong("claimUntilWall"),
                System.currentTimeMillis(), SystemClock.elapsedRealtime()))
    }.getOrNull()

    private fun hasBluetoothPermissions(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN).all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
}
