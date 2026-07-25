package re.abbot.librecr.app.ble

import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import re.abbot.librecr.app.data.ImportedSession
import re.abbot.librecr.app.data.SensorStateStore
import re.abbot.librecr.app.log.BleLog
import re.abbot.librecr.app.log.GlucoseLatencyTracer
import re.abbot.librecr.app.alarm.SensorAttentionNotifier
import re.abbot.librecr.app.wear.WearDataSync
import re.abbot.librecr.app.wear.complication.LibreComplicationUpdater
import re.abbot.librecr.app.wear.complication.StaleRepaintScheduler
import re.abbot.librecr.protocol.dataplane.DataFrame
import re.abbot.librecr.protocol.dataplane.DataPlaneChannel
import re.abbot.librecr.protocol.dataplane.DataPlaneCrypto
import re.abbot.librecr.protocol.dataplane.DataPlaneDecodedPayload
import re.abbot.librecr.protocol.dataplane.DataPlaneDecoder
import re.abbot.librecr.protocol.dataplane.DataPlaneNotificationAssembler
import re.abbot.librecr.protocol.dataplane.DataPlanePacketKind
import re.abbot.librecr.protocol.dataplane.ContiguousLifeCountTracker
import re.abbot.librecr.protocol.dataplane.GlucoseTimeline
import re.abbot.librecr.protocol.dataplane.Libre3SensorCondition
import re.abbot.librecr.protocol.dataplane.PatchControlCommand
import re.abbot.librecr.protocol.dataplane.RealtimeGlucoseReading
import re.abbot.librecr.protocol.dataplane.glucoseChartBackfillCommands
import re.abbot.librecr.protocol.crypto.AesCcmException
import re.abbot.librecr.protocol.pairing.PairingFlow
import re.abbot.librecr.protocol.pairing.PairingFlowException
import re.abbot.librecr.protocol.pairing.PhoneCert
import re.abbot.librecr.protocol.pairing.SessionKey
import re.abbot.librecr.protocol.toHex
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

enum class ConnectionState { IDLE, SCANNING, CONNECTING, HANDSHAKING, STREAMING, RECONNECTING, ERROR }

data class GlucoseUi(
    val mgDL: Int?,
    val trend: String,
    val lifeCount: Int,
    val usable: Boolean,
    val receivedAtMs: Long,
    val deltaMgDlPerMin: Double? = null,
    val readingIssue: String? = null,
    val readingIssueDetail: String? = null,
)

private data class BackfillRequest(
    val id: Long,
    val fromLifeCount: Int,
    val reason: String,
)

private data class BackfillCoverage(
    val requestId: Long,
    val fromLifeCount: Int,
    val coveredAtElapsedMs: Long,
    /** Queued or executing requests never expire from coalescing. */
    val active: Boolean,
)

private data class ReconnectTrigger(
    val reason: String,
    val status: Int?,
    val connectionGenerationId: Long,
)

internal const val STATUS8_GATT_CONNECT_WATCHDOG_MS = 8_000L
internal const val STATUS8_SCAN_TIMEOUT_MS = 70_000L
internal const val STATUS8_FAST_WAKE_LOCK_TIMEOUT_MS = 40_000L
internal const val STATUS8_SCAN_WAKE_LOCK_TIMEOUT_MS = 75_000L
internal const val CONNECTION_ESTABLISH_WAKE_LOCK_TIMEOUT_MS = 70_000L

/** A background auto-connect is controller-owned and intentionally has no app-side teardown timer. */
internal fun sensorConnectTimeoutMs(autoConnect: Boolean, directTimeoutMs: Long): Long? =
    if (autoConnect) null else directTimeoutMs

/**
 * The live in-memory reading → the display/persistence model, or null for a non-usable reading.
 * Lets the watch UI and complications read straight from the manager's StateFlow (instant) instead
 * of waiting on the DataStore round-trip.
 */
fun GlucoseUi.toLastGlucose(): SensorStateStore.LastGlucose? {
    if (!usable) return null
    val mg = mgDL ?: return null
    return SensorStateStore.LastGlucose(lifeCount, mg, trend, receivedAtMs, deltaMgDlPerMin)
}

private const val GLUCOSE_UNAVAILABLE_FRESH_MS = 6 * 60_000L

/**
 * True while the latest realtime glucose packet has no displayable value. This is not, by itself,
 * a sensor-error signal; patch-status attention remains the source of truth for real sensor errors.
 */
fun GlucoseUi?.isActiveGlucoseUnavailable(nowMs: Long = System.currentTimeMillis()): Boolean =
    this != null && !usable && receivedAtMs > 0L && nowMs - receivedAtMs < GLUCOSE_UNAVAILABLE_FRESH_MS

/**
 * Owns the full connection lifecycle: scan → connect → security handshake
 * (fresh command-gated local derivation every attempt) → post-handshake CCCD enable →
 * per-minute data loop, with self-healing reconnect and a no-data watchdog.
 * This is where "connects stably + delivers glucose every minute" lives.
 */
class SensorConnectionManager(
    private val context: Context,
    private val store: SensorStateStore,
) {
    private val _state = MutableStateFlow(ConnectionState.IDLE)
    val state: StateFlow<ConnectionState> = _state

    private val _glucose = MutableStateFlow<GlucoseUi?>(null)
    val glucose: StateFlow<GlucoseUi?> = _glucose

    private val _sensorStatus = MutableStateFlow<SensorStateStore.SensorStatusSnapshot?>(null)
    val sensorStatus: StateFlow<SensorStateStore.SensorStatusSnapshot?> = _sensorStatus

    private val _statusLine = MutableStateFlow("idle")
    val statusLine: StateFlow<String> = _statusLine

    private val lastGlucoseAt = AtomicLong(0)
    private var loopJob: Job? = null
    @Volatile private var activeConnection: SensorConnection? = null
    @Volatile private var cachedPhase5RawKey: ByteArray? = null
    /** Consecutive non-mismatch cached-reconnect failures; a correct key survives transient blips. */
    @Volatile private var cachedReconnectFailures: Int = 0
    @Volatile private var lifeCountTracker = ContiguousLifeCountTracker()
    @Volatile private var continuitySession: ImportedSession? = null
    @Volatile private var lastSentReading: SensorStateStore.LastGlucose? = null
    /** Last (errorData, patchState) pair we persisted/relayed, so we act only on real transitions. */
    @Volatile private var lastSensorStatus: Pair<Int, Int>? = null

    // --- Centralized sensor reconnect (the ONLY path that re-establishes the sensor BLE link). Driven
    // exclusively by validated sensor-side signals — GATT disconnect, no-data watchdog, fragment-assembly
    // timeout. The phone / Data Layer NEVER triggers it. See requestSensorReconnect(). ---
    @Volatile private var sensorOnline = false
    @Volatile private var reconnectSignal: CompletableDeferred<ReconnectTrigger>? = null
    @Volatile private var pendingReconnectReason: String = "initial"
    @Volatile private var lastDisconnectStatus: Int? = null
    @Volatile private var reconnectAttempt = 0
    /** One-shot escape from a background auto-connect loop which failed during establishment. */
    @Volatile private var forceDirectConnectNext = false
    @Volatile private var reconnectStartedAtMs = 0L
    @Volatile private var awaitingFirstReadingAfterReconnect = false
    @Volatile private var reconnectLastGoodLifeCount: Int? = null
    @Volatile private var status8RecoveryActive = false
    @Volatile private var recoveryWakeLock: PowerManager.WakeLock? = null
    private val nextConnectionGenerationId = AtomicLong(1L)
    @Volatile private var activeConnectionGenerationId = 0L
    /** Rate-limits shipping the watch log to the phone on reconnect (a ~500-line Data Layer push). */
    @Volatile private var lastLogShipAtMs = 0L
    // Backfill coalescing: clinicalBackfillGreaterEqual(start) already covers every HIGHER start.
    // The lock makes the reconnect and realtime collectors atomic; elapsed time is immune to clock
    // corrections, and active coverage cannot expire during a 90-second sensor procedure.
    private val backfillCoverageLock = Any()
    private val nextBackfillRequestId = AtomicLong(1L)
    private var backfillCoverage: BackfillCoverage? = null
    /** Request id/start, cleared by the first clinical record returned for that request. */
    private var pendingClinicalBackfill: Pair<Long, Int>? = null

    // DataStore persistence runs OFF the decode→ui→send path: SensorStateStore owns the single
    // serialized conflated writer (see queueGlucose/queueSensorStatus there), so a slow write
    // (Wear doze can stall flash I/O for tens of seconds) can never block a CGM reading or pile
    // up a backlog. DataStore is cold-start/persistence only.

    private val adapter by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    @Synchronized
    fun start(
        scope: CoroutineScope,
        session: ImportedSession,
        allowCandidateFirstPair: Boolean = false,
    ) {
        // Never run two loops. The watch is started from several uncoordinated triggers (boot
        // receiver, BT-state-change receiver, activity, phone session relay). This whole method is
        // synchronized: two service start coroutines previously both observed loopJob == null before
        // either assigned it, leaving one unreferenced loop alive. The resulting twin GATT handshakes
        // made the sensor terminate both links with status=19 forever.
        if (loopJob?.isActive == true) {
            BleLog.log("manager: start ignored; connection loop already active")
            return
        }
        loopJob?.cancel()
        cachedReconnectFailures = 0
        lastSensorStatus = null
        lastSentReading = null
        lifeCountTracker = ContiguousLifeCountTracker()
        continuitySession = null
        reconnectAttempt = 0
        forceDirectConnectNext = false
        sensorOnline = false
        reconnectSignal = null
        awaitingFirstReadingAfterReconnect = false
        status8RecoveryActive = false
        synchronized(backfillCoverageLock) {
            backfillCoverage = null
            pendingClinicalBackfill = null
        }
        releaseRecoveryWakeLock("manager_start")
        cachedPhase5RawKey = session.phase5RawKey?.copyOf()
        val localProvisioning = session.withoutTransientCrypto()
        logDeviceDiagnostics()
        logSystemDiagnostics("manager_start")
        BleLog.log(
            "manager: starting independent BLE loop; cachedResumeKey=${cachedPhase5RawKey != null} " +
                "allowCandidateFirstPair=$allowCandidateFirstPair transientKeyPersisted=false"
        )
        loopJob = scope.launch {
            // The loop must never end silently: it is the only thing keeping readings alive, and a
            // dead loop looks exactly like "stale value, no reconnect, empty log" in the field.
            try {
                runLoop(localProvisioning)
                reconLog("SENSOR_LOOP_EXITED normally (unexpected — loop should only end by cancel)")
            } catch (e: CancellationException) {
                reconLog("SENSOR_LOOP_EXITED cancelled (stop/restart)")
                throw e
            } catch (e: Throwable) {
                reconLog("SENSOR_LOOP_CRASHED ${e::class.simpleName}: ${e.message}")
                _statusLine.value = "loop crashed: ${e.message}"
                throw e
            }
        }
    }

    fun stop() {
        detachLoopForStop()
    }

    @Synchronized
    private fun detachLoopForStop(): Job? {
        val job = loopJob
        loopJob = null
        job?.cancel()
        activeConnection?.let { connection ->
            connection.disconnect()
            closeAndInvalidateConnection(connection, "manager_stop")
        }
        releaseRecoveryWakeLock("manager_stop")
        status8RecoveryActive = false
        _state.value = ConnectionState.IDLE
        _statusLine.value = "stopped"
        BleLog.log("manager: stop requested; active GATT closed")
        return job
    }

    /**
     * The single, centralized sensor-reconnect funnel. Every validated sensor-side disconnect signal
     * routes here; nothing else — and never the phone / Data Layer — may restart the sensor link. Safe
     * to call from any thread (GATT callback, watchdog, collector). It does NOT touch the GATT itself:
     * it only records the cause and signals the running loop, which performs the close + scan + reconnect.
     */
    private fun requestSensorReconnect(
        reason: String,
        status: Int? = null,
        linkSnapshot: SensorLinkSnapshot? = activeConnection?.diagnosticSnapshot(),
    ) {
        val callbackGenerationId = activeConnection?.connectionGenerationId ?: -1L
        reconLog(
            "SENSOR_RECONNECT_REQUEST reason=$reason status=${status ?: -1} attempt=$reconnectAttempt " +
                "generation=$callbackGenerationId",
        )
        val signal = reconnectSignal
        if (!sensorOnline || signal == null || signal.isCompleted) {
            reconLog("SENSOR_RECONNECT_SUPPRESSED_ALREADY_RUNNING reason=$reason")
            return
        }
        val connection = activeConnection
        val generationId = connection?.connectionGenerationId ?: -1L
        if (generationId <= 0L || generationId != activeConnectionGenerationId) {
            reconLog(
                "SENSOR_RECONNECT_SUPPRESSED_STALE_GENERATION reason=$reason " +
                    "callbackGeneration=$generationId activeGeneration=$activeConnectionGenerationId",
            )
            return
        }
        sensorOnline = false
        pendingReconnectReason = reason
        lastDisconnectStatus = status
        reconnectLastGoodLifeCount = lastSentReading?.lifeCount ?: lifeCountTracker.lastContiguousLifeCount
        reconnectStartedAtMs = System.currentTimeMillis()
        awaitingFirstReadingAfterReconnect = true
        if (status == GATT_CONNECTION_TIMEOUT_STATUS) {
            status8RecoveryActive = true
            acquireRecoveryWakeLock(
                timeoutMs = STATUS8_FAST_WAKE_LOCK_TIMEOUT_MS,
                phase = "fast_gatt",
            )
            reconLog(
                "SENSOR_STATUS_8_RECOVERY_START generation=$generationId " +
                    "watchdogMs=$STATUS8_GATT_CONNECT_WATCHDOG_MS",
            )
        }
        val age = if (lastGlucoseAt.get() > 0) System.currentTimeMillis() - lastGlucoseAt.get() else -1

        reconLog(
            "SENSOR_DISCONNECTED status=${describeGattStatus(status)} lastPacketAgeMs=$age attempt=$reconnectAttempt " +
                "reason=$reason lastGoodLifeCount=${reconnectLastGoodLifeCount ?: -1}",
        )
        // Wake the serialized owner before collecting any ancillary diagnostics below. This is the
        // immediate status=8 recovery edge; no delay/backoff is inserted ahead of gatt.connect().
        signal.complete(ReconnectTrigger(reason, status, generationId))
        // Deliberately NO log ship here: pushing ~500 log lines to the phone rides Bluetooth
        // Classic when the Wear link has no Wi-Fi (typically outdoors), contending for the shared
        // combo antenna exactly while the BLE reconnect needs it. The ship on reconnect success
        // carries the whole drop narrative anyway, and the phone can pull the buffer on demand.
        logTransportSnapshot("disconnect", status)
        if (status != null || reason != "gatt_disconnect") {
            logDisconnectDiagnostics(reason, status, linkSnapshot)
        }
    }

    private fun newSensorConnection(device: BluetoothDevice): SensorConnection {
        val generationId = nextConnectionGenerationId.getAndIncrement()
        activeConnectionGenerationId = generationId
        return SensorConnection(
            context = context,
            device = device,
            connectionGenerationId = generationId,
            isConnectionGenerationCurrent = { it == activeConnectionGenerationId },
        ).also { activeConnection = it }
    }

    private fun closeAndInvalidateConnection(connection: SensorConnection, reason: String) {
        val generationId = connection.connectionGenerationId
        if (activeConnection === connection) activeConnection = null
        if (activeConnectionGenerationId == generationId) activeConnectionGenerationId = 0L
        connection.close()
        reconLog("SENSOR_GATT_INVALIDATED generation=$generationId reason=$reason")
    }

    @Synchronized
    private fun acquireRecoveryWakeLock(timeoutMs: Long, phase: String) {
        releaseRecoveryWakeLock("replace_$phase")
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        recoveryWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LibreCR:BleRecovery").also {
            it.setReferenceCounted(false)
            it.acquire(timeoutMs)
        }
        reconLog("SENSOR_RECOVERY_WAKE_LOCK_ACQUIRED phase=$phase timeoutMs=$timeoutMs")
    }

    @Synchronized
    private fun releaseRecoveryWakeLock(reason: String) {
        val wakeLock = recoveryWakeLock
        recoveryWakeLock = null
        if (wakeLock?.isHeld == true) wakeLock.release()
        if (wakeLock != null) reconLog("SENSOR_RECOVERY_WAKE_LOCK_RELEASED reason=$reason")
    }

    private fun reconLog(message: String) = BleLog.log("$WEAR_BLE_TAG $message")

    /** Free-text exception messages must not fork the key=value log grammar (a space reads as a new key). */
    private fun logToken(value: String): String =
        value.replace(Regex("[^A-Za-z0-9_.:/-]+"), "_").take(96).ifBlank { "unknown" }

    /**
     * Keeps the numeric token greppable (`status=8/...`) while making the shipped narrative
     * self-explanatory — the reader shouldn't need the HCI error-code table on their phone.
     */
    private fun describeGattStatus(status: Int?): String = when (status) {
        null -> "-1"
        0 -> "0/OK_LOCAL_DISCONNECT"
        8 -> "8/LINK_TIMEOUT_SIGNAL_LOST"
        19 -> "19/TERMINATED_BY_SENSOR"
        22 -> "22/TERMINATED_BY_WATCH"
        62 -> "62/FAILED_TO_ESTABLISH"
        133 -> "133/GATT_ERROR"
        147 -> "147/CONNECT_TIMEOUT"
        else -> status.toString()
    }

    /**
     * Ship the connection narrative to the phone, at most once per [LOG_SHIP_MIN_INTERVAL_MS] —
     * a reconnect storm must not push the Data Layer every few seconds. Called ONLY on reconnect
     * success (first reading), never at disconnect time: without Wi-Fi the push rides Bluetooth
     * Classic and steals the shared antenna from the BLE reconnect itself. Ships events-only
     * (the timestamped `[WEAR-BLE]` lines: drop time/status/reason, transport, recovery) — the
     * phone's log screen can always pull the full verbose buffer on demand.
     */
    private fun shipLogRateLimited() {
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastLogShipAtMs >= LOG_SHIP_MIN_INTERVAL_MS) {
            lastLogShipAtMs = nowMs
            WearDataSync.sendLog(context, eventsOnly = true)
        }
    }

    /**
     * One log line correlating a link event with the watch's current connectivity: the active
     * network's transports (Wi-Fi vs the Bluetooth companion proxy) and the Wear node state.
     * This is what separates "drops happen when the phone link rides Bluetooth (no Wi-Fi)" from
     * "drops happen anywhere" — both lookups are local Play Services/system state, zero radio.
     */
    private fun logTransportSnapshot(trigger: String, status: Int? = null) {
        val statusLabel = describeGattStatus(status)
        val net = runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return@runCatching "none"
            buildList {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("WIFI")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("BT_PROXY")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("CELL")
            }.ifEmpty { listOf("OTHER") }.joinToString("|")
        }.getOrElse { "unavailable" }
        runCatching {
            Wearable.getNodeClient(context).connectedNodes
                .addOnSuccessListener { nodes ->
                    reconLog(
                        "WATCH_LINK_TRANSPORT trigger=$trigger status=$statusLabel net=$net " +
                            "nodes=${nodes.size} nearby=${nodes.count { it.isNearby }}",
                    )
                }
                .addOnFailureListener {
                    reconLog("WATCH_LINK_TRANSPORT trigger=$trigger status=$statusLabel net=$net nodes=? (${it.message})")
                }
        }.onFailure {
            reconLog("WATCH_LINK_TRANSPORT trigger=$trigger status=$statusLabel net=$net nodes=unavailable")
        }
    }

    /** Static context needed when comparing failures across watch models / Wear OS builds. */
    private fun logDeviceDiagnostics() {
        val appVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
        val capabilities = runCatching {
            "enabled=${adapter.isEnabled},2m=${adapter.isLe2MPhySupported},coded=${adapter.isLeCodedPhySupported}," +
                "extendedAdv=${adapter.isLeExtendedAdvertisingSupported}"
        }.getOrElse { "unavailable:${diagnosticToken(it.message)}" }
        reconLog(
            "SENSOR_DIAG_DEVICE manufacturer=${diagnosticToken(Build.MANUFACTURER)} " +
                "model=${diagnosticToken(Build.MODEL)} device=${diagnosticToken(Build.DEVICE)} " +
                "sdk=${Build.VERSION.SDK_INT} wear=${diagnosticToken(Build.VERSION.RELEASE)} " +
                "build=${diagnosticToken(Build.DISPLAY)} app=$appVersion capabilities=$capabilities",
        )
    }

    /** Runtime state which can explain a local-radio or power transition at the failure instant. */
    private fun logSystemDiagnostics(trigger: String) {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val processInfo = ActivityManager.RunningAppProcessInfo().also {
            runCatching { ActivityManager.getMyMemoryState(it) }
        }
        val capacity = runCatching {
            battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                ?.takeUnless { it == Int.MIN_VALUE }
        }.getOrNull()
        val adapterState = runCatching { adapter.state }.getOrNull()
        val enabled = runCatching { adapter.isEnabled }.getOrNull()
        reconLog(
            "SENSOR_DIAG_SYSTEM trigger=$trigger uptimeMs=${SystemClock.elapsedRealtime()} " +
                "processAgeMs=${(SystemClock.uptimeMillis() - Process.getStartUptimeMillis()).coerceAtLeast(0L)} " +
                "btEnabled=${enabled ?: "unknown"} btState=${adapterStateName(adapterState)} " +
                "interactive=${power?.isInteractive ?: "unknown"} idle=${power?.isDeviceIdleMode ?: "unknown"} " +
                "powerSave=${power?.isPowerSaveMode ?: "unknown"} thermal=${power?.currentThermalStatus ?: -1} " +
                "battery=${capacity ?: -1} charging=${battery?.isCharging ?: "unknown"} " +
                "importance=${processInfo.importance} importanceReason=${processInfo.importanceReasonCode}",
        )
    }

    /**
     * Complete app-visible post-mortem. A status=8 cannot expose whether the missing link-layer
     * replies were caused by distance, RF collision, the sensor radio, or the watch controller;
     * the assessment line makes that limitation explicit and ranks the likely family from RSSI.
     */
    private fun logDisconnectDiagnostics(reason: String, status: Int?, snapshot: SensorLinkSnapshot?) {
        val statusValue = status ?: -1
        val enabled = runCatching { adapter.isEnabled }.getOrNull()
        if (snapshot == null) {
            reconLog("SENSOR_DIAG_LINK reason=$reason status=$statusValue snapshot=unavailable")
        } else {
            reconLog(
                "SENSOR_DIAG_LINK reason=$reason status=$statusValue session=${snapshot.sessionId} " +
                    "sessionAgeMs=${snapshot.sessionAgeMs} connectedForMs=${snapshot.connectedForMs ?: -1} " +
                    "streamingForMs=${snapshot.streamingForMs ?: -1} lastNotifyAgeMs=${snapshot.lastNotifyAgeMs ?: -1} " +
                    "lastNotify=${snapshot.lastNotifyCharacteristic ?: "none"} notifications=${snapshot.notificationCount} " +
                    "bytes=${snapshot.notificationBytes}",
            )
            reconLog("SENSOR_DIAG_NOTIFY session=${snapshot.sessionId} channels=${snapshot.notificationChannels}")
            reconLog(
                "SENSOR_DIAG_RADIO session=${snapshot.sessionId} rssiDbm=${snapshot.rssiDbm ?: "unknown"} " +
                    "rssiAgeMs=${snapshot.rssiAgeMs ?: -1} txPhy=${snapshot.txPhy ?: "unknown"} " +
                    "rxPhy=${snapshot.rxPhy ?: "unknown"} intervalMs=${snapshot.connectionIntervalMs ?: "unknown"} " +
                    "latency=${snapshot.connectionLatency ?: -1} supervisionTimeoutMs=${snapshot.supervisionTimeoutMs ?: -1} " +
                    "paramsAgeMs=${snapshot.connectionParamsAgeMs ?: -1} localDisconnect=${snapshot.disconnectRequested} " +
                    "closeRequested=${snapshot.closeRequested}",
            )
            reconLog(
                "SENSOR_DIAG_GATT session=${snapshot.sessionId} currentOp=${diagnosticToken(snapshot.currentOperation)} " +
                    "currentOpAgeMs=${snapshot.currentOperationAgeMs ?: -1} lastOp=${diagnosticToken(snapshot.lastOperation)} " +
                    "lastOpResult=${diagnosticToken(snapshot.lastOperationResult)} lastOpAgeMs=${snapshot.lastOperationAgeMs ?: -1} " +
                    "lastCallback=${diagnosticToken(snapshot.lastCallback)} callbackAgeMs=${snapshot.lastCallbackAgeMs ?: -1}",
            )
        }

        val assessment = if (status != null) {
            assessBleDisconnect(
                BleDisconnectEvidence(
                    status = status,
                    localDisconnectRequested = snapshot?.disconnectRequested == true,
                    bluetoothEnabled = enabled,
                    rssiDbm = snapshot?.rssiDbm,
                    rssiAgeMs = snapshot?.rssiAgeMs,
                    lastNotifyAgeMs = snapshot?.lastNotifyAgeMs,
                ),
            )
        } else {
            BleDisconnectAssessment(
                meaning = "application_reconnect_trigger",
                probableCause = reason,
                confidence = "high",
                evidence = "status=none",
            )
        }
        reconLog(
            "SENSOR_DIAG_CAUSE status=$statusValue meaning=${assessment.meaning} " +
                "probable=${assessment.probableCause} confidence=${assessment.confidence} " +
                "evidence=${assessment.evidence} limitation=exact_hci_root_cause_not_exposed_by_android",
        )
        logSystemDiagnostics("disconnect_$statusValue")
    }

    /** Keep one recent RSSI sample in the snapshot without changing PHY or connection parameters. */
    private suspend fun monitorLinkDiagnostics(conn: SensorConnection) {
        delay(RSSI_INITIAL_DELAY_MS)
        while (kotlin.coroutines.coroutineContext.isActive) {
            conn.sampleRemoteRssi()
            val snapshot = conn.diagnosticSnapshot()
            reconLog(
                "SENSOR_LINK_HEALTH session=${snapshot.sessionId} rssiDbm=${snapshot.rssiDbm ?: "unknown"} " +
                    "rssiAgeMs=${snapshot.rssiAgeMs ?: -1} lastNotifyAgeMs=${snapshot.lastNotifyAgeMs ?: -1} " +
                    "notifications=${snapshot.notificationCount} txPhy=${snapshot.txPhy ?: "unknown"} " +
                    "rxPhy=${snapshot.rxPhy ?: "unknown"}",
            )
            delay(RSSI_SAMPLE_INTERVAL_MS)
        }
    }

    private fun adapterStateName(state: Int?): String = when (state) {
        BluetoothAdapter.STATE_OFF -> "OFF"
        BluetoothAdapter.STATE_TURNING_ON -> "TURNING_ON"
        BluetoothAdapter.STATE_ON -> "ON"
        BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF"
        null -> "unknown"
        else -> state.toString()
    }

    private fun diagnosticToken(value: String?): String = value
        ?.replace(Regex("[^A-Za-z0-9_.:,/()=-]+"), "_")
        ?.take(160)
        ?.ifBlank { "none" }
        ?: "none"

    /**
     * Backoff keyed to the attempt number (1-based): fast first retries, then a bounded climb. Capped at
     * [RECONNECT_BACKOFF_MAX_MS] — never the old 60s. The sensor re-advertises within seconds of a real
     * drop, so attempt 1 fires almost immediately.
     */
    private fun reconnectDelayMs(attempt: Int): Long {
        val idx = (attempt - 1).coerceIn(0, RECONNECT_BACKOFF_MS.size - 1)
        return RECONNECT_BACKOFF_MS[idx].coerceAtMost(RECONNECT_BACKOFF_MAX_MS)
    }

    fun acceptRemoteGlucose(reading: SensorStateStore.LastGlucose) {
        val current = _glucose.value
        if (current != null && current.lifeCount > reading.lifeCount) {
            BleLog.log("WATCH_STATE_UPDATED skipped stale remote lc=${reading.lifeCount} current=${current.lifeCount}")
            return
        }
        if (current != null && current.lifeCount == reading.lifeCount) {
            BleLog.log(
                "WATCH_STATE_UPDATED skipped duplicate remote lc=${reading.lifeCount} " +
                    "currentTrend=${current.trend} incomingTrend=${reading.trend}",
            )
            return
        }
        _glucose.value = GlucoseUi(
            mgDL = reading.mgDL,
            trend = reading.trend,
            lifeCount = reading.lifeCount,
            usable = true,
            receivedAtMs = reading.receivedAtMs,
            deltaMgDlPerMin = reading.deltaMgDlPerMin,
        )
        lastSentReading = reading
        store.queueGlucose(reading)
        StaleRepaintScheduler.armFrom(context, reading.receivedAtMs)
        BleLog.log("WATCH_STATE_UPDATED lc=${reading.lifeCount} source=remote")
    }

    /** Publish relayed patch status in memory before its conflated persistence write. */
    fun acceptRemoteSensorStatus(status: SensorStateStore.SensorStatusSnapshot) {
        _sensorStatus.value = status
        store.queueSensorStatus(status.errorData, status.patchState, status.observedAtMs)
    }

    /** Cold-start seed; never overwrites a newer status already received on the live path. */
    fun seedPersistedSensorStatus(status: SensorStateStore.SensorStatusSnapshot?) {
        if (status == null) return
        _sensorStatus.update { current ->
            if (current == null || status.observedAtMs > current.observedAtMs) status else current
        }
    }

    /** Session replacement invalidates patch status from the previous provisioning. */
    fun clearSensorStatus() {
        _sensorStatus.value = null
    }

    /** Publish a non-numeric realtime sensor reading relayed by the phone. */
    fun acceptRemoteGlucoseUnavailable(event: WearDataSync.GlucoseUnavailable) {
        val current = _glucose.value
        if (current != null && current.lifeCount > event.lifeCount) {
            BleLog.log("WATCH_STATE_UPDATED skipped stale remote unavailable lc=${event.lifeCount} current=${current.lifeCount}")
            return
        }
        if (current != null && current.lifeCount == event.lifeCount && !current.usable) {
            BleLog.log("WATCH_STATE_UPDATED skipped duplicate remote unavailable lc=${event.lifeCount}")
            return
        }
        _glucose.value = GlucoseUi(
            mgDL = null,
            trend = event.trend,
            lifeCount = event.lifeCount,
            usable = false,
            receivedAtMs = event.receivedAtMs,
            readingIssue = event.reason,
            readingIssueDetail = event.detail,
        )
        LibreComplicationUpdater.requestAll(context, event.lifeCount)
        BleLog.log(
            "WATCH_STATE_UPDATED lc=${event.lifeCount} source=remote usable=false " +
                "issue=${event.reason} ${event.detail}",
        )
    }

    suspend fun stopAndJoin(timeoutMs: Long = STOP_JOIN_TIMEOUT_MS): Boolean {
        val job = detachLoopForStop()
        if (job == null) return true
        return withTimeoutOrNull(timeoutMs) {
            job.join()
            true
        } ?: false
    }

    private suspend fun runLoop(session: ImportedSession) {
        val scanner = SensorScanner(adapter)
        var currentSession = session
        var retainedGattForFastReconnect: SensorConnection? = null
        var status8RecoveryState: Status8RecoveryState? = null
        loadCachedPhase5RawKeyIfNeeded()
        seedContinuityState(currentSession)

        while (kotlin.coroutines.coroutineContext.isActive) {
            var conn: SensorConnection? = null
            var attemptFailure: String? = null
            var attemptFailureStatus: Int? = null
            var keepGattForFastReconnect = false
            var continueImmediately = false
            var fastGattAttempt = false
            var recoveryScanAttempt = false
            var controllerHandoffAttempt = false
            var connectionAutoConnect = false
            try {
                if (!adapter.isEnabled) {
                    _state.value = ConnectionState.RECONNECTING
                    _statusLine.value = "Bluetooth oprit; aștept repornirea"
                    BleLog.log("manager: Bluetooth disabled; waiting before scan")
                    waitForBluetoothEnabled()
                    reconnectAttempt = 0
                    continue
                }

                val canResume = cachedPhase5RawKey != null
                // A status=8 recovery always uses the existing cached session (resume=true). The
                // stale-key full-handshake probe remains available to unrelated/ordinary attempts.
                val probeFullHandshake = !status8RecoveryActive && canResume &&
                    cachedReconnectFailures >= CACHED_RECONNECT_PROBE_AFTER_FAILURES
                _statusLine.value = when {
                    probeFullHandshake -> "preparing full-handshake probe"
                    canResume -> "preparing cached reconnect"
                    else -> "preparing local handshake"
                }
                BleLog.log(
                    when {
                        probeFullHandshake ->
                            "manager: probing full first-pair after $cachedReconnectFailures cached failures (cached key kept as fallback)"
                        canResume -> "manager: reconnect attempt will try cached session resume"
                        else -> "manager: reconnect attempt will use fresh local handshake material"
                    },
                )
                val firstPairEphemeral =
                    if (!canResume || probeFullHandshake) prepareFirstPairEphemeral() else null
                kotlin.coroutines.coroutineContext.ensureActive()

                val disconnected = CompletableDeferred<ReconnectTrigger>()
                reconnectSignal = disconnected
                fun installDisconnectCallback(connection: SensorConnection, autoConnect: Boolean) {
                    connection.onDisconnected = { status ->
                        if (
                            shouldRetryWithDirectConnect(
                                autoConnect = autoConnect,
                                status = status,
                                connectionWasStreaming = sensorOnline,
                            ) &&
                            !status8RecoveryActive
                        ) {
                            forceDirectConnectNext = true
                            reconLog(
                                "SENSOR_DIRECT_CONNECT_ARMED status=${describeGattStatus(status)} " +
                                    "reason=auto_connect_establishment_failure attempt=$reconnectAttempt",
                            )
                        }
                        requestSensorReconnect("gatt_disconnect", status, connection.diagnosticSnapshot())
                    }
                }

                val c: SensorConnection
                val deviceAddress: String
                val recoveryDecision = status8RecoveryState
                    ?.next(retainedGattAvailable = retainedGattForFastReconnect != null)
                if (recoveryDecision != null) {
                    status8RecoveryState = recoveryDecision.nextState
                    reconLog(
                        "SENSOR_STATUS_8_RECOVERY_PHASE phase=${recoveryDecision.phase} " +
                            "fastConsumed=${recoveryDecision.nextState.fastGattConsumed} " +
                            "scanConsumed=${recoveryDecision.nextState.activeScanConsumed}",
                    )
                }
                if (recoveryDecision?.phase == Status8RecoveryPhase.RETRY_RETAINED_GATT) {
                    val retained = checkNotNull(retainedGattForFastReconnect) {
                        "status=8 retained-GATT phase selected without a GATT"
                    }
                    retainedGattForFastReconnect = null
                    fastGattAttempt = true
                    c = retained
                    conn = c
                    activeConnection = c
                    deviceAddress = c.deviceAddress
                    installDisconnectCallback(c, autoConnect = false)
                    _state.value = ConnectionState.RECONNECTING
                    _statusLine.value = "fast reconnect $deviceAddress"
                    // Reset the callback-acquired bridge lock at actual dispatch. This phase can
                    // consume ~35s at its configured bounds, so scheduler latency must not eat it.
                    acquireRecoveryWakeLock(
                        timeoutMs = STATUS8_FAST_WAKE_LOCK_TIMEOUT_MS,
                        phase = "fast_gatt_retry",
                    )
                    reconLog(
                        "SENSOR_GATT_CONNECT_RETRY_DISPATCH generation=${c.connectionGenerationId} " +
                            "device=$deviceAddress attempt=$reconnectAttempt",
                    )
                    c.reconnectAndDiscoverExisting(
                        connectTimeoutMs = STATUS8_GATT_CONNECT_WATCHDOG_MS,
                        discoverTimeoutMs = RECOVERY_DISCOVER_TIMEOUT_MS,
                    )
                } else if (recoveryDecision?.phase == Status8RecoveryPhase.ACTIVE_SCAN_ONCE) {
                    recoveryScanAttempt = true
                    _state.value = ConnectionState.SCANNING
                    _statusLine.value = "recovery scan for ${currentSession.bleAddress}"
                    acquireRecoveryWakeLock(
                        timeoutMs = STATUS8_SCAN_WAKE_LOCK_TIMEOUT_MS,
                        phase = "active_scan",
                    )
                    val scanGenerationId = nextConnectionGenerationId.get()
                    val scan = scanner.findSensorForRecovery(
                        targetAddress = currentSession.bleAddress,
                        targetDeviceName = currentSession.bleDeviceName,
                        timeoutMs = STATUS8_SCAN_TIMEOUT_MS,
                        connectionGenerationId = scanGenerationId,
                    ) ?: throw IllegalStateException("recovery scan did not find sensor")
                    c = newSensorConnection(scan.device)
                    conn = c
                    deviceAddress = scan.device.address
                    installDisconnectCallback(c, autoConnect = false)
                    _state.value = ConnectionState.CONNECTING
                    _statusLine.value = "connecting $deviceAddress"
                    acquireRecoveryWakeLock(
                        timeoutMs = CONNECTION_ESTABLISH_WAKE_LOCK_TIMEOUT_MS,
                        phase = "recovery_connect_discover",
                    )
                    reconLog(
                        "SENSOR_CONNECT_GATT_NEW generation=${c.connectionGenerationId} " +
                            "device=$deviceAddress autoConnect=false transport=LE",
                    )
                    c.connectAndDiscover(
                        connectTimeoutMs = NEW_GATT_CONNECT_TIMEOUT_MS,
                        discoverTimeoutMs = RECOVERY_DISCOVER_TIMEOUT_MS,
                        autoConnect = false,
                    )
                } else {
                    if (recoveryDecision?.phase == Status8RecoveryPhase.CONTROLLER_AUTO_CONNECT) {
                        controllerHandoffAttempt = true
                        status8RecoveryState = null
                        status8RecoveryActive = false
                        reconnectAttempt = maxOf(reconnectAttempt, 1)
                        forceDirectConnectNext = false
                        releaseRecoveryWakeLock("controller_handoff")
                        reconLog(
                            "SENSOR_STATUS_8_RECOVERY_HANDOFF reason=one_scan_consumed " +
                                "next=controller_auto_connect delayMs=0",
                        )
                    }
                    // Initial/NFC and non-status=8 behavior stays on the established path.
                    val knownAddress = currentSession.bleAddress
                        .takeIf { BluetoothAdapter.checkBluetoothAddress(it.uppercase()) }
                        ?.uppercase()
                    val device: BluetoothDevice
                    if (knownAddress != null) {
                        device = adapter.getRemoteDevice(knownAddress)
                        val directFallback = forceDirectConnectNext
                        forceDirectConnectNext = false
                        connectionAutoConnect = shouldUseControllerAutoConnect(
                            controllerHandoff = controllerHandoffAttempt,
                            reconnectAttempt = reconnectAttempt,
                            directFallbackArmed = directFallback,
                        )
                        if (directFallback) {
                            reconLog(
                                "SENSOR_CONNECT_MODE_FALLBACK mode=direct " +
                                    "reason=previous_auto_connect_establishment_failure attempt=$reconnectAttempt",
                            )
                        }
                    } else {
                        _state.value = ConnectionState.SCANNING
                        _statusLine.value = "scanning for ${currentSession.bleAddress}"
                        val scanTimeoutMs = if (reconnectAttempt == 0) COLD_SCAN_TIMEOUT_MS else RECONNECT_SCAN_TIMEOUT_MS
                        reconLog(
                            "SENSOR_SCAN_START target=${currentSession.bleAddress} timeoutMs=$scanTimeoutMs " +
                                "attempt=$reconnectAttempt",
                        )
                        val scan = scanner.findSensor(
                            currentSession.bleAddress,
                            currentSession.bleDeviceName,
                            timeoutMs = scanTimeoutMs,
                        ) ?: throw IllegalStateException("sensor not found")
                        reconLog("SENSOR_SCAN_FOUND device=${scan.device.address}")
                        currentSession = rememberObservedIdentity(currentSession, scan)
                        device = scan.device
                        connectionAutoConnect = false
                    }
                    c = newSensorConnection(device)
                    conn = c
                    deviceAddress = device.address
                    installDisconnectCallback(c, autoConnect = connectionAutoConnect)
                    _state.value = ConnectionState.CONNECTING
                    _statusLine.value = "connecting $deviceAddress"
                    reconLog(
                        "SENSOR_CONNECT_START generation=${c.connectionGenerationId} " +
                            "device=$deviceAddress autoConnect=$connectionAutoConnect " +
                            "persistent=${connectionAutoConnect}",
                    )
                    if (!connectionAutoConnect) {
                        acquireRecoveryWakeLock(
                            timeoutMs = CONNECTION_ESTABLISH_WAKE_LOCK_TIMEOUT_MS,
                            phase = "direct_connect",
                        )
                    }
                    c.connectAndDiscover(
                        connectTimeoutMs = sensorConnectTimeoutMs(
                            autoConnect = connectionAutoConnect,
                            directTimeoutMs = DIRECT_GATT_CONNECT_TIMEOUT_MS,
                        ),
                        discoverTimeoutMs = STANDARD_DISCOVER_TIMEOUT_MS,
                        autoConnect = connectionAutoConnect,
                        onConnected = {
                            // Do not hold an app wake lock during a passive controller wait. The
                            // callback wakes the process; from this edge keep discovery + handshake
                            // notification setup alive through Doze.
                            acquireRecoveryWakeLock(
                                timeoutMs = CONNECTION_ESTABLISH_WAKE_LOCK_TIMEOUT_MS,
                                phase = "connected_discover",
                            )
                        },
                    )
                }

                _state.value = ConnectionState.HANDSHAKING
                _statusLine.value = if (cachedPhase5RawKey != null) {
                    "handshake: cached reconnect"
                } else {
                    "handshake: full local derivation"
                }
                val authorizeAndEnable: suspend () -> AuthorizationOutcome = {
                    authorize(c, currentSession, firstPairEphemeral, probeFullHandshake).also {
                        c.refreshDataPlaneNotifications(forceRearm = fastGattAttempt)
                    }
                }
                // Establishment and authorization are separate awake phases. A scan/connect lock
                // may have nearly expired here, so every handshake gets a fresh lock; a retained
                // GATT gets the longer bound needed for seven serialized off→on CCCD cycles.
                releaseRecoveryWakeLock("handshake_start")
                val auth = withHandshakeWakeLock(
                    timeoutMs = if (fastGattAttempt) {
                        DATA_PLANE_REARM_WAKE_LOCK_TIMEOUT_MS
                    } else {
                        HANDSHAKE_WAKE_LOCK_TIMEOUT_MS
                    },
                    block = authorizeAndEnable,
                )
                val material = auth.result.sessionMaterial
                BleLog.log(
                    "manager: ${auth.mode} authorized kEnc=${material.kEnc.toHex().take(8)}… " +
                        "ivEnc=${material.ivEnc.toHex().take(8)}…",
                )

                // Every re-authorization yields fresh session material: never carry the old
                // DataPlaneCrypto/sequence state across a reconnected link.
                val crypto = DataPlaneCrypto(material.kEnc, material.ivEnc)
                val decoder = DataPlaneDecoder(crypto)
                val patchControl = PatchControlBackfillWriter(
                    conn = c,
                    crypto = crypto,
                    writeTimeoutMs = POST_AUTH_PATCH_CONTROL_TIMEOUT_MS,
                    rearmDataPlane = {
                        withHandshakeWakeLock(DATA_PLANE_REARM_WAKE_LOCK_TIMEOUT_MS) {
                            c.refreshDataPlaneNotifications(forceRearm = true)
                        }
                    },
                )

                _state.value = ConnectionState.STREAMING
                _statusLine.value = "streaming"
                lastGlucoseAt.set(System.currentTimeMillis())
                c.markStreaming()
                sensorOnline = true
                status8RecoveryState = null
                status8RecoveryActive = false
                synchronized(backfillCoverageLock) {
                    backfillCoverage = null
                    pendingClinicalBackfill = null
                }
                reconLog(
                    "SENSOR_RECONNECTED generation=${c.connectionGenerationId} device=$deviceAddress " +
                        "attempt=$reconnectAttempt resume=${cachedPhase5RawKey != null}",
                )
                logTransportSnapshot("streaming_start")
                c.readPhy()

                val trigger = coroutineScope {
                    val backfillRequests = Channel<BackfillRequest>(Channel.UNLIMITED)
                    val backfill = launch {
                        for (request in backfillRequests) {
                            sendBackfillFrom(patchControl, request)
                        }
                    }
                    val collectors = listOf(
                        launch { collectGlucose(c, decoder, backfillRequests) },
                        launch {
                            collectChannel(
                                c,
                                decoder,
                                LibreSensorGatt.PATCH_STATUS,
                                DataPlaneChannel.PATCH_STATUS,
                                backfillRequests,
                            )
                        },
                        launch { collectChannel(c, decoder, LibreSensorGatt.HISTORIC_DATA, DataPlaneChannel.HISTORIC_DATA) },
                        launch { collectChannel(c, decoder, LibreSensorGatt.CLINICAL_DATA, DataPlaneChannel.CLINICAL_DATA) },
                    )
                    val immediateBackfill = launch { enqueueImmediateReconnectBackfill(backfillRequests) }
                    val watchdog = launch { watchdog(c) }
                    val linkDiagnostics = launch { monitorLinkDiagnostics(c) }

                    val disconnectTrigger = disconnected.await()
                    collectors.forEach { it.cancel() }
                    immediateBackfill.cancel()
                    backfill.cancel()
                    backfillRequests.close()
                    watchdog.cancel()
                    linkDiagnostics.cancel()
                    disconnectTrigger
                }

                if (
                    trigger.status == GATT_CONNECTION_TIMEOUT_STATUS &&
                    trigger.connectionGenerationId == c.connectionGenerationId
                ) {
                    // Preserve precisely this GATT for one immediate BluetoothGatt.connect().
                    status8RecoveryState = Status8RecoveryState()
                    retainedGattForFastReconnect = c
                    keepGattForFastReconnect = true
                    continueImmediately = true
                    reconLog(
                        "SENSOR_GATT_RETAINED_FOR_SINGLE_RETRY generation=${c.connectionGenerationId}",
                    )
                }
            } catch (e: TimeoutCancellationException) {
                attemptFailure = "op_timeout: ${e.message}"
                reconLog("SENSOR_RECONNECT_FAILED reason=op_timeout ${e.message}")
                _statusLine.value = "error: ${e.message}"
                if (status8RecoveryState != null && (fastGattAttempt || recoveryScanAttempt)) {
                    continueImmediately = true
                }
            } catch (e: CancellationException) {
                attemptFailure = "cancelled"
                conn?.disconnect()
                throw e
            } catch (e: Exception) {
                attemptFailure = e.message ?: e::class.java.simpleName
                attemptFailureStatus = (e as? GattDisconnectedException)?.gattStatus
                reconLog("SENSOR_RECONNECT_FAILED reason=${e.message}")
                _statusLine.value = "error: ${e.message}"
                if (status8RecoveryState != null && (fastGattAttempt || recoveryScanAttempt)) {
                    continueImmediately = true
                }
            } finally {
                sensorOnline = false
                reconnectSignal = null
                if (conn != null && !keepGattForFastReconnect) {
                    closeAndInvalidateConnection(conn, attemptFailure ?: pendingReconnectReason)
                    reconLog("SENSOR_GATT_CLOSED reason=${attemptFailure ?: pendingReconnectReason}")
                }
                if (attemptFailure != null && !keepGattForFastReconnect) {
                    releaseRecoveryWakeLock("attempt_failed")
                }
            }

            if (continueImmediately) {
                if (fastGattAttempt) {
                    reconLog("SENSOR_FAST_GATT_FAILED_FALLBACK_SCAN delayMs=0")
                } else if (recoveryScanAttempt) {
                    reconLog("SENSOR_RECOVERY_SCAN_FAILED_HANDOFF_CONTROLLER delayMs=0")
                }
                continue
            }

            if (status8RecoveryState != null) {
                reconLog(
                    "SENSOR_STATUS_8_RECOVERY_NEXT delayMs=0 " +
                        "reason=${attemptFailure?.let(::logToken) ?: pendingReconnectReason}",
                )
                continue
            }

            reconnectAttempt += 1
            val delayMs = reconnectDelayMs(reconnectAttempt)
            _state.value = ConnectionState.RECONNECTING
            _statusLine.value = "reconnecting in ${delayMs / 1000}s"
            val delayReason = attemptFailure?.let(::logToken) ?: pendingReconnectReason
            val delayStatus = if (attemptFailure != null) attemptFailureStatus else lastDisconnectStatus
            reconLog(
                "SENSOR_RECONNECT_DELAY attempt=$reconnectAttempt ms=$delayMs reason=$delayReason " +
                    "status=${delayStatus ?: -1} resume=${cachedPhase5RawKey != null}",
            )
            delay(delayMs)
            reconLog("SENSOR_BACKOFF_NEXT delayMs=${reconnectDelayMs(reconnectAttempt + 1)}")
        }
    }

    private suspend fun prepareFirstPairEphemeral(): SessionKey.FirstPairNativeEphemeral {
        _statusLine.value = "deriving first-pair key (one-time)"
        BleLog.log("manager: deriving fresh first-pair native ephemeral locally...")
        val random = SecureRandom()
        val ephemeral = SessionKey.makeFirstPairNativeEphemeral { count ->
            ByteArray(count).also { random.nextBytes(it) }
        }
        BleLog.log("manager: first-pair ephemeral ready attempts=${ephemeral.attempts} pub=${ephemeral.phoneEphemeralPub65.toHex()}")
        return ephemeral
    }

    private suspend fun waitForBluetoothEnabled() {
        while (kotlin.coroutines.coroutineContext.isActive && !adapter.isEnabled) {
            delay(1_000L)
        }
        if (kotlin.coroutines.coroutineContext.isActive) {
            BleLog.log("manager: Bluetooth enabled; settling before reconnect")
            delay(1_000L)
        }
    }

    private data class AuthorizationOutcome(
        val result: PairingFlow.AuthorizationResult,
        val mode: String,
    )

    private suspend fun authorize(
        conn: SensorConnection,
        session: ImportedSession,
        firstPairEphemeral: SessionKey.FirstPairNativeEphemeral?,
        probeFullHandshake: Boolean = false,
    ): AuthorizationOutcome {
        val transport = AndroidGattTransport(conn)
        val phoneCert = PhoneCert.bundled162b()
        BleLog.log("manager: phone cert prefix=${phoneCert.raw.copyOfRange(0, 4).toHex()}")
        val flow = PairingFlow(transport, phoneCert = phoneCert, logger = { BleLog.log(it) })
        val resumeKey = cachedPhase5RawKey
        if (resumeKey != null && !probeFullHandshake) {
            BleLog.log("manager: cached reconnect authorization (StartAuthorization only)")
            reconLog(
                "SENSOR_CACHED_HANDSHAKE_START generation=${conn.connectionGenerationId} resume=true",
            )
            try {
                val result = flow.runCachedReconnectHandshake(session.blePin, resumeKey)
                cachedReconnectFailures = 0
                rememberPhase5RawKey(result.phase5RawKey)
                reconLog(
                    "SENSOR_CACHED_HANDSHAKE_SUCCESS generation=${conn.connectionGenerationId} resume=true",
                )
                return AuthorizationOutcome(result, "cached reconnect handshake")
            } catch (e: Exception) {
                // Discard the cached key ONLY when it is *provably* wrong (Phase 6 CCM/echo failure).
                // Discarding it forces a full first-pair handshake, which an already-provisioned Libre 3
                // can refuse to honor without a fresh NFC switch-receiver — i.e. an over-eager discard can
                // strand the session ("sensor stopped, fixed only by New Sensor"). So a transient drop
                // (status=19/8), a timeout, or a failure count must NEVER throw the key away; keep it and
                // retry the cached path. A stale key (sensor re-paired by another receiver) also fails
                // this way — that case is recovered by the periodic full-handshake PROBE, which keeps
                // this key as fallback and only replaces it after a *successful* full handshake.
                cachedReconnectFailures += 1
                if (isKeyMismatch(e)) {
                    forgetPhase5RawKey()
                    cachedReconnectFailures = 0
                    BleLog.log("manager: cached reconnect failed (${e.message}); cached key DISCARDED (provable key mismatch); next attempt full handshake")
                } else {
                    BleLog.log(
                        "manager: cached reconnect failed (${e.message}); cached key KEPT " +
                            "(#$cachedReconnectFailures — full-handshake probe after $CACHED_RECONNECT_PROBE_AFTER_FAILURES)",
                    )
                }
                reconLog(
                    "SENSOR_CACHED_HANDSHAKE_FAILED generation=${conn.connectionGenerationId} " +
                        "resume=true reason=${logToken(e.message ?: e::class.java.simpleName)}",
                )
                throw IllegalStateException("cached reconnect failed; retry on next attempt", e)
            }
        }

        if (resumeKey != null && probeFullHandshake) {
            // Stale-key recovery probe: run the full first-pair WITHOUT discarding the cached key.
            // Success ⇒ the sensor accepted a fresh derivation, the new key replaces the stale one.
            // Failure ⇒ nothing lost; reset the counter so the cached path gets retried first.
            BleLog.log("manager: FULL-HANDSHAKE PROBE (stale-key recovery); cached key kept as fallback")
            val ephemeral = firstPairEphemeral ?: prepareFirstPairEphemeral()
            try {
                val result = flow.runCommandGatedFirstPairHandshake(session.blePin, ephemeral)
                cachedReconnectFailures = 0
                rememberPhase5RawKey(result.phase5RawKey)
                BleLog.log("manager: PROBE SUCCEEDED — stale cached key replaced by fresh full-handshake key")
                return AuthorizationOutcome(result, "full handshake probe (stale-key recovery)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                cachedReconnectFailures = 0
                BleLog.log("manager: probe full handshake failed (${e.message}); cached key kept; returning to cached path")
                throw IllegalStateException("full-handshake probe failed; retry cached on next attempt", e)
            }
        }

        BleLog.log("manager: full command-gated first-pair authorization (local key derivation)")
        val ephemeral = firstPairEphemeral ?: prepareFirstPairEphemeral()
        val result = flow.runCommandGatedFirstPairHandshake(session.blePin, ephemeral)
        rememberPhase5RawKey(result.phase5RawKey)
        return AuthorizationOutcome(result, "full local handshake")
    }

    private suspend fun loadCachedPhase5RawKeyIfNeeded() {
        if (cachedPhase5RawKey != null) return
        runCatching { store.loadCachedPhase5RawKey() }
            .onSuccess { key ->
                if (key != null) {
                    cachedPhase5RawKey = key.copyOf()
                    BleLog.log("manager: loaded cached Phase 5 resume key from local store")
                }
            }
            .onFailure { BleLog.log("manager: cached Phase 5 resume key unavailable: ${it.message}") }
    }

    /** Seed both timestamp/delta anchoring and the gap-free cursor before any data collector starts. */
    private suspend fun seedContinuityState(session: ImportedSession) {
        continuitySession = session
        runCatching { store.loadLastGlucose() }
            .onSuccess { reading ->
                if (lastSentReading == null && reading != null) {
                    lastSentReading = reading
                    BleLog.log("[BACKFILL] latest reading anchor seeded lc=${reading.lifeCount}")
                }
            }
            .onFailure { BleLog.log("[BACKFILL] latest reading anchor unavailable: ${it.message}") }

        runCatching { store.loadLastContiguousLifeCount() }
            .onSuccess { seed ->
                lifeCountTracker = ContiguousLifeCountTracker(seed)
                // Materialize the fallback before collectors can update last_glucose beyond a gap;
                // otherwise a process restart could mistake that later display value for contiguous.
                seed?.let { store.queueLastContiguousLifeCount(it, session) }
                BleLog.log("[BACKFILL] contiguous cursor seeded last=${seed ?: -1}")
            }
            .onFailure {
                lifeCountTracker = ContiguousLifeCountTracker()
                BleLog.log("[BACKFILL] contiguous cursor unavailable: ${it.message}")
            }
    }

    private suspend fun rememberPhase5RawKey(key: ByteArray) {
        cachedPhase5RawKey = key.copyOf()
        runCatching { store.saveCachedPhase5RawKey(key) }
            .onSuccess { BleLog.log("manager: cached Phase 5 resume key saved locally") }
            .onFailure { BleLog.log("manager: cached Phase 5 resume key save failed: ${it.message}") }
    }

    private suspend fun forgetPhase5RawKey() {
        cachedPhase5RawKey = null
        runCatching { store.clearCachedPhase5RawKey() }
            .onSuccess { BleLog.log("manager: cached Phase 5 resume key cleared") }
            .onFailure { BleLog.log("manager: cached Phase 5 resume key clear failed: ${it.message}") }
    }

    /**
     * True only when the cached key is *provably* wrong: a Phase 6 CCM MAC / R1-R2 echo failure
     * (AesCcmException or Phase6VerificationFailed) during the handshake — that only happens when the
     * derived session material can't validate the sensor's Phase 6, i.e. the key is stale.
     *
     * Deliberately does NOT include GATT status=19 (peer-terminated). status=19 is a transient link
     * drop (RF, Wi-Fi/BT coexistence, the sensor closing a slow handshake) — NOT proof of a bad key.
     * Treating it as a mismatch discarded a perfectly good key and stranded the session until a manual
     * NFC re-provision, which is exactly the failure being fixed here.
     */
    private fun isKeyMismatch(error: Throwable): Boolean {
        var t: Throwable? = error
        while (t != null) {
            if (t is AesCcmException || t is PairingFlowException.Phase6VerificationFailed) return true
            t = t.cause
        }
        return false
    }

    private suspend fun <T> withHandshakeWakeLock(
        timeoutMs: Long = HANDSHAKE_WAKE_LOCK_TIMEOUT_MS,
        block: suspend () -> T,
    ): T {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LibreCR:BleHandshake")
        if (wakeLock == null) {
            BleLog.log("power: handshake wake lock unavailable")
            return block()
        }
        wakeLock.setReferenceCounted(false)
        wakeLock.acquire(timeoutMs)
        BleLog.log("power: handshake wake lock acquired timeout=${timeoutMs}ms")
        return try {
            block()
        } finally {
            if (wakeLock.isHeld) {
                wakeLock.release()
                BleLog.log("power: handshake wake lock released")
            }
        }
    }

    private suspend fun rememberObservedIdentity(session: ImportedSession, scan: SensorScanResult): ImportedSession {
        val observedName = scan.advertisedName?.takeIf { it.isNotBlank() } ?: return session
        if (normalizeIdentity(session.bleDeviceName) == normalizeIdentity(observedName)) return session
        val updated = session.copy(bleDeviceName = observedName)
        store.saveSession(updated, preserveCachedKeyWhenKeyless = true)
        clearSensorStatus()
        BleLog.log("manager: cached permanent BLE device name=$observedName for ${session.bleAddress}")
        return updated
    }

    private fun normalizeIdentity(value: String?): String? =
        value?.filter { it.isLetterOrDigit() }?.uppercase()?.takeIf { it.isNotEmpty() }

    private suspend fun collectGlucose(
        conn: SensorConnection,
        decoder: DataPlaneDecoder,
        backfillRequests: Channel<BackfillRequest>,
    ) {
        val channel = conn.notifyChannel(LibreSensorGatt.GLUCOSE_DATA)
        val assembler = DataPlaneNotificationAssembler()
        // Single-coroutine loop, so these per-reading locals need no synchronization. A glucose
        // reading is a 15B prefix + suffix → two 177a notifies; we time both and the decode.
        var pendingFirstNotifyTs = 0L
        while (true) {
            // A glucose reading is prefix + suffix. Once the prefix is buffered, the suffix should follow
            // within ~1s; if it never does, the sensor channel is half-stuck → a validated reconnect trigger.
            val frag = if (pendingFirstNotifyTs > 0L) {
                withTimeoutOrNull(FRAGMENT_ASSEMBLY_TIMEOUT_MS) { channel.receive() } ?: run {
                    reconLog("SENSOR_FRAGMENT_TIMEOUT channel=glucose awaitedMs=$FRAGMENT_ASSEMBLY_TIMEOUT_MS (prefix without suffix)")
                    conn.disconnect()
                    requestSensorReconnect("stale_fragment_timeout")
                    return
                }
            } else {
                channel.receive()
            }
            val notifyAtMs = System.currentTimeMillis()
            val result = assembler.feedDetailed(frag, DataPlaneChannel.GLUCOSE_DATA)
            result.flushedOrphanAgeMs?.let { age ->
                BleLog.log("[ANOMALY] REASSEMBLY: flushed orphan 177a prefix age=${age}ms (suffix never arrived → that reading lost)")
                pendingFirstNotifyTs = 0L
            }
            result.replacedPrefixAgeMs?.let { age ->
                BleLog.log("[ANOMALY] REASSEMBLY: replaced orphan 177a prefix age=${age}ms with fresh prefix")
                pendingFirstNotifyTs = notifyAtMs
            }
            result.orphanSuffixSize?.let { size ->
                BleLog.log("[ANOMALY] REASSEMBLY: orphan 177a suffix len=${size}B arrived without prefix")
            }
            val combined = result.combined
            if (combined == null) {
                // Buffered the 15B prefix; remember when it landed to time first→second notify.
                pendingFirstNotifyTs = notifyAtMs
                BleLog.log("[LATENCY] 177a reassembly: buffered ${frag.size}B prefix t=$notifyAtMs, awaiting suffix")
                continue
            }
            val firstNotifyTs = if (pendingFirstNotifyTs > 0L) pendingFirstNotifyTs else notifyAtMs
            val secondNotifyTs = notifyAtMs
            pendingFirstNotifyTs = 0L
            runCatching {
                val packet = decoder.decrypt(DataFrame.parse(combined), DataPlaneChannel.GLUCOSE_DATA)
                (packet.payload as? DataPlaneDecodedPayload.RealtimeGlucose)?.reading?.let { r ->
                    val decodedTs = System.currentTimeMillis()
                    GlucoseLatencyTracer.mark(r.lifeCount, GlucoseLatencyTracer.Stage.BLE_NOTIFY_RECEIVED, secondNotifyTs)
                    GlucoseLatencyTracer.mark(r.lifeCount, GlucoseLatencyTracer.Stage.GLUCOSE_DECODED, decodedTs)
                    BleLog.log(
                        "WATCH_DECODED lc=${r.lifeCount} mgdl=${r.currentGlucoseMgDL ?: "NA"} " +
                            "trend=${r.trendKind} usable=${r.isCurrentGlucoseUsable}",
                    )
                    BleLog.log(
                        "[LATENCY] 177a→decoded lc=${r.lifeCount} firstNotify=$firstNotifyTs secondNotify=$secondNotifyTs " +
                            "decoded=$decodedTs firstNotify→decoded=${decodedTs - firstNotifyTs}ms " +
                            "interFragment=${secondNotifyTs - firstNotifyTs}ms",
                    )
                    lastGlucoseAt.set(decodedTs)
                    // No connection-priority manipulation: the link keeps the default Android/sensor-
                    // negotiated parameters for the whole session (HIGH/BALANCED → status=8, LOW_POWER →
                    // slow handshake, any mid-session change → status=19).
                    val mg = r.currentGlucoseMgDL
                    val usable = r.isCurrentGlucoseUsable
                    val firstRecoveredGlucose = awaitingFirstReadingAfterReconnect && usable && mg != null
                    val issue = glucoseReadingIssue(r)
                    val issueDetail = glucoseReadingIssueDetail(r)
                    // Delta from the previous in-memory reading — never re-reads DataStore.
                    val delta = if (usable && mg != null) deltaPerMin(lastSentReading, mg, r.lifeCount, decodedTs) else null
                    // 1) In-memory StateFlow first: the watch UI, notification and complications all
                    //    read this instantly, independent of any DataStore write.
                    _glucose.value = GlucoseUi(
                        mgDL = mg,
                        trend = r.trendKind.name,
                        lifeCount = r.lifeCount,
                        usable = usable,
                        receivedAtMs = decodedTs,
                        deltaMgDlPerMin = delta,
                        readingIssue = issue,
                        readingIssueDetail = issueDetail,
                    )
                    GlucoseLatencyTracer.mark(r.lifeCount, GlucoseLatencyTracer.Stage.VIEWMODEL_UPDATED)
                    BleLog.log("WATCH_STATE_UPDATED lc=${r.lifeCount} source=ble usable=$usable")
                    BleLog.log(
                        "glucose lifeCount=${r.lifeCount} mgdl=${mg ?: "NA"} trend=${r.trendKind} " +
                            "usable=$usable issue=${issue ?: "none"} $issueDetail",
                    )
                    if (usable && mg != null) {
                        val reading = SensorStateStore.LastGlucose(
                            r.lifeCount, mg, r.trendKind.name, decodedTs, delta,
                            chartMgDL = r.currentGlucoseChartMgDL,
                        )
                        lastSentReading = reading
                        val timeline = GlucoseTimeline(
                            lifeCount = r.lifeCount,
                            firstNotifyTs = firstNotifyTs,
                            secondNotifyTs = secondNotifyTs,
                            decodedTs = decodedTs,
                        )
                        // 2) Send to the phone straight from the decoded object (no DataStore reload),
                        //    and 3) refresh complications from the in-memory value.
                        WearDataSync.sendGlucose(context, reading, timeline)
                        LibreComplicationUpdater.requestAll(context, r.lifeCount)
                        if (firstRecoveredGlucose) {
                            awaitingFirstReadingAfterReconnect = false
                            val durationMs = decodedTs - reconnectStartedAtMs
                            reconLog(
                                "SENSOR_FIRST_GLUCOSE generation=${conn.connectionGenerationId} " +
                                    "lifeCount=${r.lifeCount} mgdl=$mg",
                            )
                            reconLog(
                                "SENSOR_COMPLICATION_UPDATE_IMMEDIATE lifeCount=${r.lifeCount}",
                            )
                            reconLog(
                                "SENSOR_RECONNECT_SUCCESS durationMs=$durationMs attempt=$reconnectAttempt",
                            )
                            val lastGood = reconnectLastGoodLifeCount
                            if (lastGood != null && r.lifeCount > lastGood + 1) {
                                reconLog(
                                    "SENSOR_LIFECOUNT_GAP_AFTER_RECONNECT lastGood=$lastGood now=${r.lifeCount} " +
                                        "missing=${r.lifeCount - lastGood - 1}",
                                )
                            }
                            status8RecoveryActive = false
                            releaseRecoveryWakeLock("first_glucose")
                            reconLog("SENSOR_RECOVERY_JOBS_CLEAN generation=${conn.connectionGenerationId}")
                            shipLogRateLimited()
                        }
                        // Backoff resets only on a real numeric reading, never merely on GATT connect.
                        if (reconnectAttempt != 0) {
                            reconLog("SENSOR_BACKOFF_RESET reason=first_valid_reading")
                            reconnectAttempt = 0
                        }
                        StaleRepaintScheduler.armFrom(context, decodedTs)
                        // 4) Persist last — fire-and-forget via the store's single serialized writer.
                        store.queueGlucose(reading)
                    } else {
                        WearDataSync.sendGlucoseUnavailable(
                            context,
                            WearDataSync.GlucoseUnavailable(
                                lifeCount = r.lifeCount,
                                trend = r.trendKind.name,
                                receivedAtMs = decodedTs,
                                reason = issue ?: GLUCOSE_ISSUE_NOT_USABLE,
                                detail = issueDetail,
                            ),
                        )
                        LibreComplicationUpdater.requestAll(context, r.lifeCount)
                    }
                    // The value/unavailable event is buffered for the phone first and a usable value
                    // is queued locally before the contiguous cursor can be persisted.
                    val missingStart = recordReceivedLifeCount(r.lifeCount, "177a realtime")
                    if (missingStart != null) {
                        queueBackfill(
                            backfillRequests,
                            missingStart,
                            "missing realtime before lc=${r.lifeCount}",
                        )
                    }
                }
            }.onFailure { BleLog.log("glucose decode failed len=${combined.size}: ${it.message}") }
        }
    }

    private fun glucoseReadingIssue(r: RealtimeGlucoseReading): String? = when {
        r.currentGlucoseMgDL == null -> GLUCOSE_ISSUE_VALUE_UNAVAILABLE
        !r.dqError.isGood -> GLUCOSE_ISSUE_DATA_QUALITY
        r.sensorCondition != Libre3SensorCondition.OK -> GLUCOSE_ISSUE_SENSOR_CONDITION
        !r.isCurrentGlucoseUsable -> GLUCOSE_ISSUE_NOT_USABLE
        else -> null
    }

    private fun glucoseReadingIssueDetail(r: RealtimeGlucoseReading): String =
        "raw=${r.uncappedCurrentMgDL} dq=0x${"%04x".format(r.dqErrorRaw)} " +
            "condition=${r.sensorConditionRaw}/${r.sensorCondition} action=${r.actionability}"

    private suspend fun collectChannel(
        conn: SensorConnection,
        decoder: DataPlaneDecoder,
        uuid: UUID,
        channel: DataPlaneChannel,
        backfillRequests: Channel<BackfillRequest>? = null,
    ) {
        val ch = conn.notifyChannel(uuid)
        while (true) {
            val frag = ch.receive()
            runCatching {
                val packet = decoder.decrypt(DataFrame.parse(frag), channel)
                when (val payload = packet.payload) {
                    is DataPlaneDecodedPayload.PatchStatusPayload -> {
                        val s = payload.status
                        BleLog.log(
                            "decoded $channel kind=${packet.kind} lifeCount=${s.lifeCount} " +
                                "currentLifeCount=${s.currentLifeCount} patchState=${s.patchState} " +
                                "sensorError=${s.sensorError} sensorAttention=${s.sensorAttention} " +
                                "notifyUser=${s.shouldNotifyUser} replaceSensor=${s.shouldNotifyReplaceSensor} " +
                                "stackDisconnectReason=${s.stackDisconnectReason} appDisconnectReason=${s.appDisconnectReason} " +
                                "plaintext=${packet.plaintext.toHex()}",
                        )
                        // Surface sensor errors (insertion failure / ended / replace / unknown) once per
                        // transition: persist for complications, relay to the phone, notify the user, repaint.
                        val statusKey = s.errorData to s.patchState
                        if (statusKey != lastSensorStatus) {
                            lastSensorStatus = statusKey
                            val observedAtMs = System.currentTimeMillis()
                            _sensorStatus.value = SensorStateStore.SensorStatusSnapshot(
                                s.errorData,
                                s.patchState,
                                observedAtMs,
                            )
                            // Fire-and-forget persist: a stalled DataStore commit must not delay the
                            // phone relay, the user notification, or the complication repaint below.
                            store.queueSensorStatus(s.errorData, s.patchState, observedAtMs)
                            WearDataSync.sendSensorStatus(context, s.errorData, s.patchState, observedAtMs)
                            SensorAttentionNotifier.onAttentionChanged(context, s.sensorAttention)
                            LibreComplicationUpdater.requestAll(context)
                        }
                        val missingStart = lifeCountTracker.backfillStart(s.currentLifeCount)
                        if (missingStart != null && backfillRequests != null) {
                            queueBackfill(
                                backfillRequests,
                                missingStart,
                                "patch status current lc=${s.currentLifeCount}",
                            )
                        }
                    }
                    is DataPlaneDecodedPayload.HistoricalReadingPagePayload -> {
                        val p = payload.page
                        BleLog.log(
                            "[BACKFILL] historical page lc=${p.startLifeCount}..${p.endLifeCount} " +
                                "samples=${p.samples.joinToString { "${it.lifeCount}:${it.glucoseMgDL ?: "NA"}" }}",
                        )
                        // This is a separately finalized five-minute series, not the per-minute
                        // realtime series. Relaying it into the phone chart creates a false spike
                        // every five minutes, so keep it diagnostic-only on Wear.
                        for (sample in p.samples) {
                            val mg = sample.glucoseMgDL ?: continue
                            BleLog.log(
                                "[BACKFILL] historical recovered lc=${sample.lifeCount} mgdl=$mg " +
                                    "chartMergeSkipped=noncanonical_five_minute_series",
                            )
                        }
                    }
                    is DataPlaneDecodedPayload.ClinicalReadingRecordPayload -> {
                        val r = payload.record
                        skipUnavailableClinicalPrefixIfNeeded(r.lifeCount)
                        val mg = r.currentGlucoseMgDL
                        if (mg != null) {
                            // Recovered a minute the watch missed live (e.g. a brief BLE blip when the
                            // phone's Wear link migrates WiFi→BT). The watch keeps no history of its own,
                            // so relay it to the phone on the replay path — stamped at its real minute —
                            // to fill the one-point gap instead of dropping it.
                            val reading = backfilledReading(
                                lifeCount = r.lifeCount,
                                mgDL = mg,
                                trend = "BACKFILL_CLINICAL",
                                chartMgDL = r.currentGlucose.chartMgDL,
                            )
                            WearDataSync.sendBackfilledGlucose(context, reading)
                            continuitySession?.let { store.queueBackfilledGlucose(reading, it) }
                            // Queue the recovered point before advancing/persisting the contiguous
                            // cursor. The store's single writer preserves that ordering.
                            recordReceivedLifeCount(r.lifeCount, "clinical backfill")
                            BleLog.log(
                                "[BACKFILL] clinical relayed lc=${r.lifeCount} mgdl=$mg " +
                                    "estimatedMs=${reading.receivedAtMs} → phone; localMergeQueued=true",
                            )
                        } else {
                            BleLog.log(
                                "[BACKFILL] clinical lc=${r.lifeCount} mgdl=NA " +
                                    "raw=${r.currentGlucoseRaw} historicEstimate=${r.historicLifeCountEstimate} skipped",
                            )
                            // The minute was decoded but has no recoverable glucose. Mark it received
                            // so PATCH_STATUS does not request the same irrecoverable record forever.
                            recordReceivedLifeCount(r.lifeCount, "clinical backfill unavailable")
                        }
                    }
                    else -> BleLog.log("decoded $channel kind=${packet.kind} plaintext=${packet.plaintext.toHex()}")
                }
            }.onFailure { BleLog.log("$channel decode failed: ${it.message}") }
        }
    }

    private suspend fun enqueueImmediateReconnectBackfill(backfillRequests: Channel<BackfillRequest>) {
        val fromLifeCount = lifeCountTracker.nextMissingLifeCount
        if (fromLifeCount == null) {
            BleLog.log("[BACKFILL] reconnect skipped; no contiguous lifeCount seed")
            return
        }
        BleLog.log(
            "[BACKFILL] reconnect immediate request from missing lifeCount=$fromLifeCount " +
                "lastContiguous=${lifeCountTracker.lastContiguousLifeCount ?: -1}",
        )
        queueBackfill(backfillRequests, fromLifeCount, "reconnect")
    }

    private fun queueBackfill(backfillRequests: Channel<BackfillRequest>, fromLifeCount: Int, reason: String) {
        val normalizedFrom = maxOf(fromLifeCount, 0)
        val nowElapsedMs = SystemClock.elapsedRealtime()
        // Skip a request already covered by one just sent on this link: the sensor command is
        // "everything ≥ start", so a recent request with an equal-or-lower start subsumes this one.
        val request = synchronized(backfillCoverageLock) {
            val coverage = backfillCoverage
            val coverageAgeMs = coverage
                ?.let { (nowElapsedMs - it.coveredAtElapsedMs).coerceAtLeast(0L) }
                ?: Long.MAX_VALUE
            if (
                coverage != null &&
                shouldCoalesceBackfill(
                    coveredFromLifeCount = coverage.fromLifeCount,
                    requestedFromLifeCount = normalizedFrom,
                    coverageActive = coverage.active,
                    coverageAgeMs = coverageAgeMs,
                    cooldownMs = BACKFILL_COALESCE_WINDOW_MS,
                )
            ) {
                BleLog.log(
                    "[BACKFILL] coalesced reason=$reason from lifeCount=$normalizedFrom " +
                        "(covered by ≥${coverage.fromLifeCount} active=${coverage.active} " +
                        "ageMs=$coverageAgeMs)",
                )
                return
            }
            BackfillRequest(
                id = nextBackfillRequestId.getAndIncrement(),
                fromLifeCount = normalizedFrom,
                reason = reason,
            ).also {
                backfillCoverage = BackfillCoverage(
                    requestId = it.id,
                    fromLifeCount = it.fromLifeCount,
                    coveredAtElapsedMs = nowElapsedMs,
                    active = true,
                )
            }
        }
        val result = backfillRequests.trySend(request)
        if (result.isSuccess) {
            BleLog.log(
                "[BACKFILL] queued id=${request.id} reason=$reason " +
                    "from lifeCount=${request.fromLifeCount}",
            )
        } else {
            clearBackfillCoverage(request, "queue_failed")
            BleLog.log("[BACKFILL] queue failed reason=$reason from lifeCount=${request.fromLifeCount}: ${result.exceptionOrNull()?.message}")
        }
    }

    private suspend fun sendBackfillFrom(
        patchControl: PatchControlBackfillWriter,
        request: BackfillRequest,
    ) {
        val fromLifeCount = request.fromLifeCount
        val reason = request.reason
        val clinicalStart = maxOf(fromLifeCount, 0)
        BleLog.log(
            "[BACKFILL] request reason=$reason missing=$clinicalStart " +
                "clinicalStart=$clinicalStart stream=clinical",
        )
        val commands = glucoseChartBackfillCommands(clinicalStart)
            .map { it to PATCH_CONTROL_CLINICAL_COMPLETION_TIMEOUT_MS }
        for ((command, completionTimeoutMs) in commands) {
            synchronized(backfillCoverageLock) {
                pendingClinicalBackfill = request.id to clinicalStart
            }
            when (patchControl.write(command, reason, completionTimeoutMs)) {
                PatchControlWriteResult.COMPLETED -> Unit
                PatchControlWriteResult.DEFINITELY_REJECTED -> {
                    clearBackfillCoverage(request, "command_rejected_${command.label}")
                    BleLog.log(
                        "[BACKFILL] request rejected reason=$reason ${command.label}; " +
                            "a later sensor trigger may retry",
                    )
                    return
                }
                PatchControlWriteResult.INDETERMINATE_OR_ACTIVE -> {
                    coolDownBackfillCoverage(request, "command_indeterminate_${command.label}")
                    BleLog.log(
                        "[BACKFILL] request indeterminate reason=$reason ${command.label}; " +
                            "cooldownMs=$BACKFILL_COALESCE_WINDOW_MS",
                    )
                    return
                }
            }
        }
        coolDownBackfillCoverage(request, "commands_completed")
        BleLog.log("[BACKFILL] request completed reason=$reason missing=$clinicalStart")
    }

    private fun skipUnavailableClinicalPrefixIfNeeded(firstReturnedLifeCount: Int) {
        val pending = synchronized(backfillCoverageLock) {
            pendingClinicalBackfill.also { pendingClinicalBackfill = null }
        } ?: return
        val requestedStart = pending.second
        if (firstReturnedLifeCount <= requestedStart) return
        if (lifeCountTracker.skipUnavailableBefore(firstReturnedLifeCount)) {
            BleLog.log(
                "[BACKFILL] sensor replay starts at lc=$firstReturnedLifeCount; " +
                    "skipped unavailable clinical prefix $requestedStart..${firstReturnedLifeCount - 1}",
            )
        }
    }

    private fun clearBackfillCoverage(request: BackfillRequest, reason: String) {
        val cleared = synchronized(backfillCoverageLock) {
            if (backfillCoverage?.requestId != request.id) {
                false
            } else {
                backfillCoverage = null
                if (pendingClinicalBackfill?.first == request.id) {
                    pendingClinicalBackfill = null
                }
                true
            }
        }
        if (cleared) {
            BleLog.log(
                "[BACKFILL] coalescing coverage cleared reason=$reason " +
                    "id=${request.id} from lifeCount=${request.fromLifeCount}",
            )
        }
    }

    private fun coolDownBackfillCoverage(request: BackfillRequest, reason: String) {
        val cooledAtMs = SystemClock.elapsedRealtime()
        val updated = synchronized(backfillCoverageLock) {
            val coverage = backfillCoverage
            if (coverage?.requestId != request.id) {
                false
            } else {
                backfillCoverage = coverage.copy(
                    coveredAtElapsedMs = cooledAtMs,
                    active = false,
                )
                true
            }
        }
        if (updated) {
            BleLog.log(
                "[BACKFILL] coalescing cooldown reason=$reason id=${request.id} " +
                    "from lifeCount=${request.fromLifeCount} windowMs=$BACKFILL_COALESCE_WINDOW_MS",
            )
        }
    }

    private fun recordReceivedLifeCount(lifeCount: Int, source: String): Int? {
        val tracker = lifeCountTracker
        val previous = tracker.lastContiguousLifeCount
        val advanced = tracker.record(lifeCount)
        val contiguous = tracker.lastContiguousLifeCount
        if (advanced && contiguous != null) {
            continuitySession?.let { store.queueLastContiguousLifeCount(contiguous, it) }
            BleLog.log(
                "[BACKFILL] contiguous cursor advanced source=$source received=$lifeCount " +
                    "previous=${previous ?: -1} now=$contiguous",
            )
        }

        val missingStart = tracker.backfillStart(lifeCount)
        if (missingStart != null) {
            BleLog.log(
                "[MISSING] first missing lifeCount=$missingStart before $source lc=$lifeCount " +
                    "lastContiguous=${contiguous ?: -1}",
            )
            return missingStart
        }
        if (!advanced && previous != null && lifeCount <= previous) {
            val order = if (lifeCount == previous) "duplicate" else "out-of-order"
            BleLog.log("[MISSING] $order lifeCount=$lifeCount from $source lastContiguous=$previous")
        }
        return null
    }

    private fun backfilledReading(
        lifeCount: Int,
        mgDL: Int,
        trend: String,
        chartMgDL: Int?,
    ): SensorStateStore.LastGlucose {
        val fallbackReceivedAtMs = System.currentTimeMillis()
        val anchor = lastSentReading
        val estimatedReceivedAtMs = anchor
            ?.let { it.receivedAtMs + (lifeCount - it.lifeCount).toLong() * 60_000L }
            ?.takeIf { it > 0L }
            ?: fallbackReceivedAtMs
        return SensorStateStore.LastGlucose(
            lifeCount = lifeCount,
            mgDL = mgDL,
            trend = trend,
            receivedAtMs = estimatedReceivedAtMs,
            deltaMgDlPerMin = null,
            chartMgDL = chartMgDL,
        )
    }

    private class PatchControlBackfillWriter(
        private val conn: SensorConnection,
        private val crypto: DataPlaneCrypto,
        private val writeTimeoutMs: Long,
        private val rearmDataPlane: suspend () -> Unit,
    ) {
        private val responses = conn.notifyChannel(LibreSensorGatt.PATCH_CONTROL)
        private val decoder = DataPlaneDecoder(crypto)
        private var nextSequence: Int? = 1

        suspend fun write(
            command: PatchControlCommand,
            reason: String,
            completionTimeoutMs: Long,
        ): PatchControlWriteResult {
            val sequence = nextSequence ?: throw PatchControlSequenceExhaustedException()
            // Reserve once per logical command. A failed command must never let a different
            // plaintext reuse this CCM nonce; the one 0xFD retransmission reuses this exact frame.
            nextSequence = nextPatchControlSequence(sequence)
            val frame = crypto.encrypt(command.plaintext, sequence, DataPlanePacketKind.patchControlWrite)
            var cccdRetryUsed = false

            while (true) {
                BleLog.log(
                    "[BACKFILL] patchControl reason=$reason ${command.label} " +
                        "seq=${"%04x".format(sequence)} retry=$cccdRetryUsed " +
                        "pt=${command.plaintext.toHex()} raw=${frame.raw.toHex()}",
                )
                try {
                    conn.writeCharacteristic(
                        LibreSensorGatt.PATCH_CONTROL,
                        frame.raw,
                        withResponse = true,
                        timeoutMs = writeTimeoutMs,
                    )
                } catch (e: TimeoutCancellationException) {
                    BleLog.log(
                        "[BACKFILL] patchControl ${command.label} reason=$reason " +
                            "ATT timeout after ${writeTimeoutMs}ms",
                    )
                    throw GattOperationTimedOutException(
                        "PATCH_CONTROL ${command.label} ATT write",
                        e,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: GattOperationException) {
                    when (patchControlWriteRecovery(e.gattStatus, cccdRetryUsed)) {
                        PatchControlWriteRecovery.REARM_CCCDS_AND_RETRY -> {
                            cccdRetryUsed = true
                            BleLog.log(
                                "[BACKFILL] patchControl ${command.label} reason=$reason " +
                                    "status=${e.gattStatus} rearm=full retry=same_frame",
                            )
                            try {
                                rearmDataPlane()
                            } catch (rearmTimeout: TimeoutCancellationException) {
                                BleLog.log(
                                    "[BACKFILL] patchControl ${command.label} CCCD rearm timed out: " +
                                        rearmTimeout.message,
                                )
                                throw GattOperationTimedOutException(
                                    "PATCH_CONTROL ${command.label} CCCD rearm",
                                    rearmTimeout,
                                )
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (rearmFailure: Exception) {
                                BleLog.log(
                                    "[BACKFILL] patchControl ${command.label} CCCD rearm failed: " +
                                        rearmFailure.message,
                                )
                                throw rearmFailure
                            }
                            continue
                        }

                        PatchControlWriteRecovery.GIVE_UP -> {
                            val detail = if (
                                e.gattStatus == GATT_PROCEDURE_ALREADY_IN_PROGRESS_STATUS
                            ) {
                                "procedure_already_in_progress_no_blind_retry"
                            } else {
                                "not_accepted"
                            }
                            BleLog.log(
                                "[BACKFILL] patchControl ${command.label} reason=$reason " +
                                    "status=${e.gattStatus} result=$detail",
                            )
                            return if (
                                e.gattStatus == GATT_PROCEDURE_ALREADY_IN_PROGRESS_STATUS
                            ) {
                                PatchControlWriteResult.INDETERMINATE_OR_ACTIVE
                            } else {
                                PatchControlWriteResult.DEFINITELY_REJECTED
                            }
                        }
                    }
                } catch (e: Exception) {
                    BleLog.log(
                        "[BACKFILL] patchControl ${command.label} reason=$reason write failed: ${e.message}",
                    )
                    throw e
                }

                BleLog.log(
                    "[BACKFILL] patchControl ATT_ACK reason=$reason ${command.label} " +
                        "seq=${"%04x".format(sequence)}",
                )
                return awaitCompletion(command, reason, sequence, completionTimeoutMs)
            }
        }

        private suspend fun awaitCompletion(
            command: PatchControlCommand,
            reason: String,
            expectedSequence: Int,
            timeoutMs: Long,
        ): PatchControlWriteResult {
            val completed = withTimeoutOrNull<PatchControlWriteResult>(timeoutMs) {
                while (true) {
                    val raw = responses.receiveCatching().getOrNull()
                    if (raw == null) {
                        throw IllegalStateException(
                            "PATCH_CONTROL completion channel closed for ${command.label}",
                        )
                    }
                    val frame = try {
                        DataFrame.parse(raw)
                    } catch (e: Exception) {
                        BleLog.log(
                            "[BACKFILL] patchControl completion malformed len=${raw.size}: ${e.message}",
                        )
                        continue
                    }
                    if (frame.sequenceNumber != expectedSequence) {
                        BleLog.log(
                            "[BACKFILL] patchControl completion stale expected=" +
                                "${"%04x".format(expectedSequence)} actual=" +
                                "${"%04x".format(frame.sequenceNumber)}",
                        )
                        continue
                    }
                    val decoded = try {
                        decoder.decrypt(frame, DataPlaneChannel.PATCH_CONTROL)
                    } catch (e: Exception) {
                        BleLog.log(
                            "[BACKFILL] patchControl completion auth failed " +
                                "seq=${"%04x".format(expectedSequence)}: ${e.message}",
                        )
                        continue
                    }
                    BleLog.log(
                        "[BACKFILL] patchControl COMPLETED reason=$reason ${command.label} " +
                            "seq=${"%04x".format(expectedSequence)} kind=${decoded.kind} " +
                            "pt=${decoded.plaintext.toHex()}",
                    )
                    return@withTimeoutOrNull PatchControlWriteResult.COMPLETED
                }
                @Suppress("UNREACHABLE_CODE")
                PatchControlWriteResult.INDETERMINATE_OR_ACTIVE
            }
            if (completed == null) {
                BleLog.log(
                    "[BACKFILL] patchControl completion timeout reason=$reason ${command.label} " +
                        "seq=${"%04x".format(expectedSequence)} timeoutMs=$timeoutMs no_immediate_retry=true",
                )
            }
            return completed ?: PatchControlWriteResult.INDETERMINATE_OR_ACTIVE
        }
    }

    private suspend fun watchdog(conn: SensorConnection) {
        while (true) {
            delay(WATCHDOG_CHECK_MS)
            val since = System.currentTimeMillis() - lastGlucoseAt.get()
            if (since > NO_DATA_TIMEOUT_MS) {
                reconLog("SENSOR_NO_DATA_WATCHDOG noDataMs=$since → reconnect")
                conn.disconnect()
                requestSensorReconnect("no_data_watchdog")
                return
            }
        }
    }

    /**
     * Per-minute delta from the previous in-memory reading (no I/O). The denominator is the
     * sensor's own minute counter (lifeCount), NOT wall-clock time: after a reconnect the sensor
     * delivers two readings seconds apart, and a wall-clock denominator of a few seconds exploded
     * the delta to ±99. lifeCount difference is the true elapsed sensor minutes regardless of
     * when the packets happen to arrive.
     */
    private fun deltaPerMin(prev: SensorStateStore.LastGlucose?, mgDL: Int, lifeCount: Int, atMs: Long): Double? {
        val p = prev ?: return null
        if (p.receivedAtMs !in 1 until atMs) return null
        val minutes = (lifeCount - p.lifeCount).toDouble()
        if (minutes <= 0.0) return null
        return ((mgDL - p.mgDL) / minutes).takeIf { it.isFinite() }
    }

    companion object {
        private const val STOP_JOIN_TIMEOUT_MS = 5_000L
        private const val WATCHDOG_CHECK_MS = 15_000L
        /**
         * No-data watchdog: two missed minutes + margin. A live BLE link cannot lose a notification
         * (link-layer retransmission), so one missing minute means the sensor skipped a send — the
         * old 75s ceiling tore down that healthy link at ~75–90s even though the next reading lands
         * at ~120s. A genuinely dead link is reported by the controller itself (status=8) within
         * seconds; the watchdog only backstops rare half-open states, so slower detection is cheap.
         * NOTE: anything between ~90s and ~120s is the worst of both — still fires before the ~120s
         * post-skip reading, yet detects real death later. Keep this ≥135s (120s + jitter margin).
         */
        private const val NO_DATA_TIMEOUT_MS = 135_000L
        private const val POST_AUTH_PATCH_CONTROL_TIMEOUT_MS = 10_000L
        private const val PATCH_CONTROL_CLINICAL_COMPLETION_TIMEOUT_MS = 30_000L
        /** A backfill request with an equal-or-lower start within this window subsumes a new one. */
        private const val BACKFILL_COALESCE_WINDOW_MS = 60_000L
        private const val HANDSHAKE_WAKE_LOCK_TIMEOUT_MS = 90_000L
        /** Seven CCCDs × off/on × 20s operation ceiling, plus authorization overhead. */
        private const val DATA_PLANE_REARM_WAKE_LOCK_TIMEOUT_MS = 6 * 60_000L
        private const val GATT_CONNECTION_TIMEOUT_STATUS = 8
        private const val NEW_GATT_CONNECT_TIMEOUT_MS = 20_000L
        private const val RECOVERY_DISCOVER_TIMEOUT_MS = 12_000L
        private const val DIRECT_GATT_CONNECT_TIMEOUT_MS = 20_000L
        private const val STANDARD_DISCOVER_TIMEOUT_MS = 45_000L

        /** Greppable tag for the whole sensor reconnect / reading-gap narrative on Wear OS. */
        private const val WEAR_BLE_TAG = "[WEAR-BLE]"
        /** Suffix must follow a buffered glucose prefix within this window, else the channel is stale. */
        private const val FRAGMENT_ASSEMBLY_TIMEOUT_MS = 8_000L
        /** Read-only HCI RSSI sample: frequent enough to remain useful for a status=8 post-mortem. */
        private const val RSSI_INITIAL_DELAY_MS = 3_000L
        private const val RSSI_SAMPLE_INTERVAL_MS = 30_000L
        /** Minimum spacing between watch-log pushes to the phone on reconnect. */
        private const val LOG_SHIP_MIN_INTERVAL_MS = 5 * 60_000L
        /**
         * After this many consecutive cached-reconnect failures, probe a full first-pair handshake
         * (keeping the cached key as fallback). Recovers a stale key — sensor re-paired by another
         * app/device — which fails exactly like a transient drop (status=19 after Phase 5) and would
         * otherwise retry the cached path forever.
         */
        private const val CACHED_RECONNECT_PROBE_AFTER_FAILURES = 4
        /** Cold/first attempt: long window for the sensor's ~minute advertising cadence. */
        private const val COLD_SCAN_TIMEOUT_MS = 60_000L
        /** Reconnect: the sensor re-advertises within seconds of a real drop, so fail fast into backoff. */
        private const val RECONNECT_SCAN_TIMEOUT_MS = 12_000L
        /** Per-attempt reconnect delay (1-based). Fast first retries, bounded climb — never the old 60s. */
        private val RECONNECT_BACKOFF_MS = longArrayOf(
            500L,     // attempt 1
            2_000L,   // attempt 2
            2_000L,   // attempt 3
            5_000L,   // attempt 4
            10_000L,  // attempt 5
            20_000L,  // attempt 6
            30_000L,  // attempt 7+
        )
        private const val RECONNECT_BACKOFF_MAX_MS = 30_000L

        private const val GLUCOSE_ISSUE_VALUE_UNAVAILABLE = "VALUE_UNAVAILABLE"
        private const val GLUCOSE_ISSUE_DATA_QUALITY = "DATA_QUALITY"
        private const val GLUCOSE_ISSUE_SENSOR_CONDITION = "SENSOR_CONDITION"
        private const val GLUCOSE_ISSUE_NOT_USABLE = "NOT_USABLE"
    }
}
