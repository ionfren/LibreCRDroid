package re.abbot.librecr.app.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import re.abbot.librecr.app.LibreCR
import re.abbot.librecr.app.ble.ConnectionState
import re.abbot.librecr.app.data.ImportedSession
import re.abbot.librecr.app.log.BleLog
import re.abbot.librecr.app.service.SensorForegroundService
import re.abbot.librecr.app.service.ReceiverRecoveryService
import re.abbot.librecr.app.service.ReceiverRecoveryAlarm
import re.abbot.librecr.protocol.connection.*

/** The watch keeps collecting in isolation and can recover an abandoned release itself. */
object AutomaticSensorHandoff {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val events = Channel<Unit>(Channel.CONFLATED)
    private lateinit var context: Context
    private var requestJob: Job? = null
    private var nextRequestAtMs = 0L
    private var preparedUntilMs = 0L
    private var startUntilMs = 0L
    private var recoveryRetries = 0
    @Volatile private var lastPacketMs: Long? = null
    @Volatile private var lastLifeCount = -1
    @Volatile private var lastRemoteLifeCount = -1
    @Volatile private var remoteDeadlineMs = 0L
    @Volatile var enabled = false
        private set
    @Volatile private var phoneNodeId = ""
    @Volatile private var batteryPercent = 100
    @Volatile private var charging = false
    private val prefs get() = context.getSharedPreferences("librecr_automatic_handoff", Context.MODE_PRIVATE)
    val monitoringRequired: Boolean get() = enabled && prefs.getBoolean("released", false) && prefs.getBoolean("allowRecovery", true)

    fun initialize(context: Context) {
        this.context = context.applicationContext
        enabled = prefs.getBoolean("enabled", false)
        phoneNodeId = prefs.getString("phoneNodeId", "").orEmpty()
        preparedUntilMs = RecoveryPolicy.restoreDeadline(prefs.getLong("prepareUntilWall", 0),
            System.currentTimeMillis(), SystemClock.elapsedRealtime())
        startUntilMs = RecoveryPolicy.restoreDeadline(prefs.getLong("startDeadlineWall", 0),
            System.currentTimeMillis(), SystemClock.elapsedRealtime())
        remoteDeadlineMs = RecoveryPolicy.restoreDeadline(prefs.getLong("releaseDeadlineWall", 0),
            System.currentTimeMillis(), SystemClock.elapsedRealtime())
        this.context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (level >= 0 && scale > 0) batteryPercent = level * 100 / scale
                charging = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1) in
                    listOf(BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL)
                if (batteryPercent <= RecoveryPolicy.CRITICAL_BATTERY_PERCENT && !charging) retryIfNeeded()
            }
        }, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        Wearable.getDataClient(this.context).dataItems.addOnSuccessListener { items ->
            try {
                for (item in items) if (item.uri.path == AutomaticHandoff.SETTINGS) {
                    item.data?.let { receiveSettings(item.uri.host.orEmpty(), it.copyOf()) }
                }
            } finally { items.release() }
        }
        scope.launch {
            while (true) {
                updateMonitor()
                if (!monitoringRequired) {
                    events.receive()
                    recoveryRetries = 0
                    continue
                }
                val remaining = (remoteDeadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
                if (withTimeoutOrNull(remaining) { events.receive(); true } == true) continue
                checkRecovery()
            }
        }
        updateMonitor()
    }

    fun onRecoveryWakeup() { ReceiverRecoveryAlarm.runAwake(context) { checkRecovery() } }

    private suspend fun checkRecovery() = mutex.withLock {
        if (!monitoringRequired || SystemClock.elapsedRealtime() < remoteDeadlineMs) return@withLock
        try {
            val session = LibreCR.store.loadSession() ?: run {
                prefs.edit().putBoolean("released", false).apply()
                return@withLock
            }
            val status = phoneStatus(session)
            val age = status?.optLong("packetAgeMs", Long.MAX_VALUE) ?: Long.MAX_VALUE
            if (status?.optBoolean("active") == true && age < RecoveryPolicy.SILENCE_MS) {
                remoteDeadlineMs = SystemClock.elapsedRealtime() + RecoveryPolicy.SILENCE_MS - age
            } else if ((status?.optLong("claimRemainingMs") ?: 0L) > 0) {
                remoteDeadlineMs = SystemClock.elapsedRealtime() + status!!.optLong("claimRemainingMs")
                    .coerceAtMost(RecoveryPolicy.FIRST_PACKET_MS)
            } else {
                ReceiverSessionBackup.adopt(phoneNodeId)
                LibreCR.store.setAutoConnectEnabled(true)
                prefs.edit().putBoolean("released", false).commit()
                if (!SensorForegroundService.start(context, allowCandidateFirstPair = true, passiveConnect = true)) {
                    prefs.edit().putBoolean("released", true).commit()
                    remoteDeadlineMs = SystemClock.elapsedRealtime() + RecoveryPolicy.retryDelay(recoveryRetries++)
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException && error !is TimeoutCancellationException) throw error
            remoteDeadlineMs = SystemClock.elapsedRealtime() + RecoveryPolicy.retryDelay(recoveryRetries++)
            BleLog.log("receiver recovery: watch resume pending: ${error.javaClass.simpleName}")
        } finally { updateMonitor(); events.trySend(Unit) }
    }

    fun receiveSettings(sourceNodeId: String, payload: ByteArray) {
        scope.launch {
            mutex.withLock {
                try {
                    val json = JSONObject(String(payload, Charsets.UTF_8))
                    val version = json.optLong("syncedAtMs")
                    if (sourceNodeId == phoneNodeId && version < prefs.getLong("settingsVersion", 0)) return@withLock
                    val localId = Wearable.getNodeClient(context).localNode.awaitHandoff().id
                    val selected = json.optString("selectedWatchId")
                    enabled = json.optBoolean("enabled", false) && (selected.isBlank() || selected == localId)
                    val changedPhone = phoneNodeId != sourceNodeId
                    phoneNodeId = sourceNodeId
                    prefs.edit().putBoolean("enabled", enabled).putString("phoneNodeId", sourceNodeId)
                        .putLong("settingsVersion", version).apply()
                    updateMonitor()
                    events.trySend(Unit)
                    if (changedPhone) ReceiverSessionBackup.refresh()
                    if (enabled) retryIfNeeded()
                } catch (error: Exception) { BleLog.log("receiver recovery: settings failed: ${error.javaClass.simpleName}") }
            }
        }
    }

    @Volatile var localSensorAddress = ""
        private set
    fun onLocalSessionStarted(address: String) { localSensorAddress = address; lastPacketMs = null; lastLifeCount = -1 }
    fun onLocalReading(lifeCount: Int) {
        if (lifeCount <= lastLifeCount) return
        lastLifeCount = lifeCount
        lastPacketMs = SystemClock.elapsedRealtime()
    }
    fun onRemotePacket(lifeCount: Int, receivedAtMs: Long, message: Boolean) {
        if (!message || System.currentTimeMillis() - receivedAtMs !in -30_000L..90_000L || lifeCount <= lastRemoteLifeCount) return
        lastRemoteLifeCount = lifeCount
        if (prefs.getBoolean("released", false)) {
            remoteDeadlineMs = SystemClock.elapsedRealtime() + RecoveryPolicy.SILENCE_MS
            events.trySend(Unit)
        }
    }

    @Synchronized fun retryIfNeeded() {
        val critical = batteryPercent <= RecoveryPolicy.CRITICAL_BATTERY_PERCENT && !charging
        if (!enabled || (!critical && LibreCR.manager.failedReconnectCount < AutomaticHandoff.FAILED_RECONNECTS) ||
            requestJob?.isActive == true || SystemClock.elapsedRealtime() < nextRequestAtMs) return
        nextRequestAtMs = SystemClock.elapsedRealtime() + 60_000L
        requestJob = scope.launch {
            try {
                val session = LibreCR.store.loadSession() ?: return@launch
                if (!LibreCR.store.autoConnectEnabled() || !nearbyHandoffNode(context, phoneNodeId)) return@launch
                handoffRpc(context, phoneNodeId, AutomaticHandoff.REQUEST_PHONE,
                    JSONObject().put("bleAddress", session.bleAddress).put("criticalBattery", critical))
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                BleLog.log("receiver recovery: phone unavailable")
            }
        }
    }

    suspend fun acceptsBackupNode(nodeId: String): Boolean = nodeId == phoneNodeId

    /** Boot/update/start events reconcile with a live phone before creating another BLE loop. */
    suspend fun mayStartLocalSession(): Boolean = mutex.withLock {
        if (prefs.getBoolean("released", false)) { events.trySend(Unit); return@withLock false }
        val session = LibreCR.store.loadSession() ?: return@withLock false
        val status = phoneStatus(session)
        if (status?.optBoolean("active") == true || (status?.optLong("claimRemainingMs") ?: 0L) > 0) {
            park(status?.optBoolean("preferWatch") == true && enabled)
            LibreCR.store.setAutoConnectEnabled(false)
            false
        } else true
    }

    fun onRequest(nodeId: String, path: String, data: ByteArray): Task<ByteArray>? {
        if (path !in listOf(RecoveryPolicy.STATUS, RecoveryPolicy.PREPARE_WATCH, AutomaticHandoff.RELEASE_WATCH, AutomaticHandoff.START_WATCH)) return null
        return handoffTask(scope) {
            mutex.withLock {
                if (nodeId != phoneNodeId || !nearbyHandoffNode(context, nodeId)) return@withLock handoffReply(false)
                val session = LibreCR.store.loadSession() ?: return@withLock handoffReply(false)
                val json = JSONObject(String(data, Charsets.UTF_8))
                if (json.optString("bleAddress").isNotBlank() && !session.bleAddress.equals(json.optString("bleAddress"), true)) return@withLock handoffReply(false)
                val age = lastPacketMs?.let { SystemClock.elapsedRealtime() - it } ?: Long.MAX_VALUE
                val healthy = LibreCR.manager.state.value == ConnectionState.STREAMING && age < RecoveryPolicy.SILENCE_MS
                if (path == RecoveryPolicy.STATUS) return@withLock handoffReply(true)
                    .put("bleAddress", session.bleAddress).put("active", healthy).put("packetAgeMs", age)
                    .put("canStart", RecoveryPolicy.canStartWatch(batteryPercent, charging))
                    .put("batteryPercent", batteryPercent)
                    .put("claimRemainingMs", if (!prefs.getBoolean("released", false) && !healthy && LibreCR.store.autoConnectEnabled())
                        (startUntilMs - SystemClock.elapsedRealtime()).coerceIn(0, RecoveryPolicy.FIRST_PACKET_MS) else 0)
                val token = json.getString("token")
                val sequence = json.getLong("sequence")
                val controller = json.getString("controller")
                val sameCommand = controller == prefs.getString("controller", "") && sequence == prefs.getLong("sequence", 0)
                if (sameCommand && token != prefs.getString("token", "")) return@withLock handoffReply(false)
                if (!RecoveryPolicy.acceptsControl(prefs.getString("controller", "").orEmpty(),
                        prefs.getLong("sequence", 0), controller, sequence)) return@withLock handoffReply(false)
                when (path) {
                    RecoveryPolicy.PREPARE_WATCH -> {
                        if (!RecoveryPolicy.canStartWatch(batteryPercent, charging)) return@withLock handoffReply(false)
                        if (!RecoveryPolicy.acceptsPreparation(sameCommand, prefs.getString("phase", "").orEmpty())) return@withLock handoffReply(false)
                        if (sameCommand) return@withLock handoffReply(RecoveryPolicy.preparedStartValid(
                            prefs.getString("phase", "").orEmpty(), preparedUntilMs, SystemClock.elapsedRealtime()))
                        recordCommand(token, sequence, controller)
                        preparedUntilMs = SystemClock.elapsedRealtime() + RecoveryPolicy.FIRST_PACKET_MS
                        prefs.edit().putString("phase", "prepared").putLong("prepareUntilWall", System.currentTimeMillis() + RecoveryPolicy.FIRST_PACKET_MS).commit()
                        handoffReply(true)
                    }
                    AutomaticHandoff.RELEASE_WATCH -> {
                        val cancellingUnconfirmedStart = prefs.getString("token", "") == token &&
                            !prefs.getBoolean("released", false) && !healthy
                        val critical = batteryPercent <= RecoveryPolicy.CRITICAL_BATTERY_PERCENT && !charging
                        if (healthy && !json.optBoolean("manual") && !critical && !cancellingUnconfirmedStart) return@withLock handoffReply(false)
                        recordCommand(token, sequence, controller)
                        prefs.edit().putString("phase", "released").commit()
                        park(json.optBoolean("allowRecovery", true))
                        LibreCR.store.setAutoConnectEnabled(false)
                        val stopped = LibreCR.manager.stopAndJoin()
                        SensorForegroundService.stop(context)
                        val key = LibreCR.store.loadCachedPhase5RawKey()
                        handoffReply(stopped).put("session", session.copy(phase5RawKey = key).toJson())
                    }
                    else -> {
                        if (prefs.getString("token", "") != token) return@withLock handoffReply(false)
                        if (prefs.getString("startedToken", "") == token && LibreCR.store.autoConnectEnabled()) {
                            return@withLock handoffReply(true).put("confirmed", healthy)
                        }
                        if (!RecoveryPolicy.preparedStartValid(prefs.getString("phase", "").orEmpty(), preparedUntilMs, SystemClock.elapsedRealtime()) ||
                            !RecoveryPolicy.canStartWatch(batteryPercent, charging)) return@withLock handoffReply(false)
                        if (!LibreCR.manager.stopAndJoin()) return@withLock handoffReply(false)
                        json.optString("session").takeIf { it.isNotBlank() }?.let { ReceiverSessionBackup.acceptReleasedSession(it) }
                        LibreCR.store.setAutoConnectEnabled(true)
                        startUntilMs = SystemClock.elapsedRealtime() + RecoveryPolicy.FIRST_PACKET_MS
                        prefs.edit().putBoolean("released", false).putString("startedToken", token).putString("phase", "started")
                            .putLong("startDeadlineWall", System.currentTimeMillis() + RecoveryPolicy.FIRST_PACKET_MS).commit()
                        val started = SensorForegroundService.start(context, allowCandidateFirstPair = true)
                        if (!started) {
                            prefs.edit().putString("phase", "prepared").commit()
                            park()
                            LibreCR.store.setAutoConnectEnabled(false)
                        }
                        updateMonitor()
                        events.trySend(Unit)
                        handoffReply(started).put("confirmed", false)
                    }
                }
            }
        }
    }

    private fun recordCommand(token: String, sequence: Long, controller: String) {
        check(prefs.edit().putString("token", token).putLong("sequence", sequence).putString("controller", controller).commit())
    }
    private fun park(allowRecovery: Boolean = true) {
        remoteDeadlineMs = SystemClock.elapsedRealtime() + RecoveryPolicy.SILENCE_MS
        prefs.edit().putBoolean("released", true).putBoolean("allowRecovery", allowRecovery)
            .putLong("releaseDeadlineWall", System.currentTimeMillis() + RecoveryPolicy.SILENCE_MS).commit()
        updateMonitor()
        events.trySend(Unit)
    }
    private fun updateMonitor() {
        ReceiverRecoveryService.ensure(context, monitoringRequired)
        ReceiverRecoveryAlarm.schedule(context, if (monitoringRequired) (remoteDeadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(1) else null)
    }
    private suspend fun phoneStatus(session: ImportedSession): JSONObject? = try {
        if (phoneNodeId.isBlank() || !nearbyHandoffNode(context, phoneNodeId)) null else handoffRpc(
            context, phoneNodeId, RecoveryPolicy.STATUS, JSONObject().put("bleAddress", session.bleAddress))
    } catch (error: Exception) {
        if (error is CancellationException && error !is TimeoutCancellationException) throw error
        null
    }
    fun cancel() {
        lastRemoteLifeCount = -1
        prefs.edit().remove("token").remove("startedToken").remove("prepareUntilWall")
            .putBoolean("released", false).putLong("sequence", prefs.getLong("sequence", 0) + 1).apply()
        updateMonitor()
        events.trySend(Unit)
    }
}
