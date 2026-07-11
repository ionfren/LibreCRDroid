package re.abbot.librecr.app.data

import android.content.Context
import android.os.SystemClock
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import re.abbot.librecr.app.log.BleLog
import re.abbot.librecr.app.log.GlucoseLatencyTracer
import re.abbot.librecr.protocol.dataplane.Libre3SensorAttention
import re.abbot.librecr.protocol.dataplane.Libre3SensorError
import re.abbot.librecr.protocol.hexToBytes
import re.abbot.librecr.protocol.toHex

private val Context.dataStore by preferencesDataStore(name = "librecr_wear_session")

class SensorStateStore(private val context: Context) {
    private val keySession = stringPreferencesKey("session_json")
    private val keyAutoConnect = booleanPreferencesKey("auto_connect")
    private val keyLastLifeCount = intPreferencesKey("last_glucose_lifecount")
    private val keyLastMgDL = intPreferencesKey("last_glucose_mgdl")
    private val keyLastTrend = stringPreferencesKey("last_glucose_trend")
    private val keyLastReceivedAtMs = longPreferencesKey("last_glucose_received_at_ms")
    private val keyLastDeltaMgDlPerMin = doublePreferencesKey("last_glucose_delta_mgdl_per_min")
    private val keyCachedPhase5RawKey = stringPreferencesKey("cached_phase5_raw_key")
    private val keySensorErrorData = intPreferencesKey("sensor_error_data")
    private val keySensorPatchState = intPreferencesKey("sensor_patch_state")
    private val keySensorStatusObservedAtMs = longPreferencesKey("sensor_status_observed_at_ms")

    data class LastGlucose(
        val lifeCount: Int,
        val mgDL: Int,
        val trend: String,
        val receivedAtMs: Long,
        val deltaMgDlPerMin: Double?,
        /** Uncapped-below-floor value carried to the phone for its history chart; null ⇒ same as [mgDL]. */
        val chartMgDL: Int? = null,
    )

    /**
     * Last patch-status error/state, kept so the watch complications can surface sensor errors
     * (insertion failure, ended, replace, or an unknown "sensor error" code). Raw bytes are stored
     * and the product-facing [attention]/[error] are derived from the protocol.
     */
    data class SensorStatusSnapshot(
        val errorData: Int,
        val patchState: Int,
        val observedAtMs: Long,
    ) {
        val attention: Libre3SensorAttention get() = Libre3SensorAttention.from(errorData, patchState)
        val error: Libre3SensorError get() = Libre3SensorError.fromCode(errorData)
    }

    // ---- Single serialized DataStore writer -----------------------------------------------------
    // Wear flash I/O under doze can stall one DataStore commit for tens of seconds (observed ~48s).
    // Every librecr_wear_session mutation therefore enters this ordered queue and exactly ONE
    // consumer coroutine is the only call site allowed to invoke DataStore.edit. Per-minute glucose
    // and sensor status are conflated independently (the store only retains the latest value); rare
    // control-plane writes are never conflated and await their own commit. The conflated wake-up
    // channel cannot lose work because the guarded queue is the source of truth.

    private enum class ConflationKey { GLUCOSE, SENSOR_STATUS }

    private data class WriteRequest(
        val id: Long,
        val label: String,
        val queuedAtMs: Long,
        val conflationKey: ConflationKey?,
        val transform: (MutablePreferences) -> Unit,
        val completion: CompletableDeferred<Result<Unit>>?,
        val onCommitted: (() -> Unit)?,
    )

    private val writeQueueLock = Any()
    private val pendingWrites = mutableListOf<WriteRequest>()
    private var nextWriteId = 0L
    private val writeSignal = Channel<Unit>(Channel.CONFLATED)
    private val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        writerScope.launch {
            var terminalFailure: Throwable = CancellationException("DataStore writer stopped")
            try {
                for (ignored in writeSignal) {
                    while (true) {
                        val request = synchronized(writeQueueLock) {
                            if (pendingWrites.isEmpty()) null else pendingWrites.removeAt(0)
                        } ?: break
                        commit(request)
                    }
                }
            } catch (failure: Throwable) {
                terminalFailure = failure
                throw failure
            } finally {
                writeSignal.close(terminalFailure)
                failPendingWrites(terminalFailure)
            }
        }
    }

    /**
     * Hot path (per-minute reading): hand the latest value to the single writer and return
     * immediately — never suspends, never blocks the decode → UI → phone-send chain.
     */
    fun queueGlucose(reading: LastGlucose) {
        enqueue(
            label = "glucose lc=${reading.lifeCount}",
            conflationKey = ConflationKey.GLUCOSE,
            transform = { it.applyGlucose(reading) },
            onCommitted = {
                GlucoseLatencyTracer.mark(reading.lifeCount, GlucoseLatencyTracer.Stage.STORE_UPDATED)
            },
        )
    }

    /**
     * Hot path (patch status transition): same fire-and-forget contract as [queueGlucose], so the
     * phone relay, the user notification and the complication repaint never wait on flash I/O.
     */
    fun queueSensorStatus(errorData: Int, patchState: Int, observedAtMs: Long = System.currentTimeMillis()) {
        enqueue(
            label = "sensorStatus err=$errorData patch=$patchState",
            conflationKey = ConflationKey.SENSOR_STATUS,
            transform = { it.applySensorStatus(errorData, patchState, observedAtMs) },
        )
    }

    /**
     * Enqueues a write without doing any persistence on the caller. Replacing a pending request with
     * the same [conflationKey] moves the newest value to the back of the ordered queue; non-conflated
     * requests are retained verbatim. The short monitor below is never held during DataStore.edit.
     */
    private fun enqueue(
        label: String,
        conflationKey: ConflationKey? = null,
        transform: (MutablePreferences) -> Unit,
        completion: CompletableDeferred<Result<Unit>>? = null,
        onCommitted: (() -> Unit)? = null,
    ): WriteRequest {
        val queuedAtMs = SystemClock.elapsedRealtime()
        var replaced: WriteRequest? = null
        val request = synchronized(writeQueueLock) {
            val next = WriteRequest(
                id = ++nextWriteId,
                label = label,
                queuedAtMs = queuedAtMs,
                conflationKey = conflationKey,
                transform = transform,
                completion = completion,
                onCommitted = onCommitted,
            )
            if (conflationKey != null) {
                val pendingIndex = pendingWrites.indexOfFirst { it.conflationKey == conflationKey }
                if (pendingIndex >= 0) replaced = pendingWrites.removeAt(pendingIndex)
            }
            pendingWrites += next
            // Log before releasing the queue monitor, so no existing wake-up can commit this
            // request before its queued event appears in the diagnostic timeline.
            BleLog.log("[PERSIST] queued id=${next.id} ${next.label}")
            replaced?.let {
                BleLog.log("[PERSIST] conflated id=${it.id}→${next.id} ${next.conflationKey}")
            }
            next
        }
        if (writeSignal.trySend(Unit).isFailure) {
            val failure = IllegalStateException("DataStore writer is unavailable")
            val removed = synchronized(writeQueueLock) {
                val index = pendingWrites.indexOfFirst { it.id == request.id }
                if (index < 0) null else pendingWrites.removeAt(index)
            }
            removed?.completion?.complete(Result.failure(failure))
            BleLog.log("[PERSIST] queue signal FAILED id=${request.id} ${request.label}: ${failure.message}")
        }
        return request
    }

    private fun failPendingWrites(failure: Throwable) {
        val abandoned = synchronized(writeQueueLock) {
            pendingWrites.toList().also { pendingWrites.clear() }
        }
        abandoned.forEach { it.completion?.complete(Result.failure(failure)) }
        if (abandoned.isNotEmpty()) {
            BleLog.log("[PERSIST] writer stopped; abandoned=${abandoned.size}: ${failure.message}")
        }
    }

    /** Ordered, lossless control-plane write that still executes on the one writer coroutine. */
    private suspend fun serializedEdit(label: String, transform: (MutablePreferences) -> Unit) {
        val completion = CompletableDeferred<Result<Unit>>()
        enqueue(label = label, transform = transform, completion = completion)
        completion.await().getOrThrow()
    }

    /**
     * The only DataStore.edit call in this store. The transform type is deliberately non-suspending
     * and contains no application lock or I/O. We only capture the entered timestamp inside edit;
     * BleLog is synchronized, so all logging stays outside the transform.
     */
    private suspend fun commit(request: WriteRequest) {
        var enteredAtMs: Long? = null
        var cancellation: CancellationException? = null
        val result = try {
            context.dataStore.edit { prefs ->
                enteredAtMs = SystemClock.elapsedRealtime()
                request.transform(prefs)
            }
            Result.success(Unit)
        } catch (failure: CancellationException) {
            cancellation = failure
            Result.failure(failure)
        } catch (failure: Throwable) {
            Result.failure(failure)
        }
        val finishedAtMs = SystemClock.elapsedRealtime()
        val entered = enteredAtMs
        if (result.isSuccess && entered != null) {
            val totalMs = finishedAtMs - request.queuedAtMs
            val timing = "id=${request.id} ${request.label} " +
                "queued→enteredEdit=${entered - request.queuedAtMs}ms " +
                "enteredEdit→committed=${finishedAtMs - entered}ms total=${totalMs}ms"
            if (totalMs > SLOW_EDIT_WARN_MS) {
                BleLog.log("[ANOMALY] [PERSIST] committed $timing (persistence only; live paths continue)")
            } else {
                BleLog.log("[PERSIST] committed $timing")
            }
            request.onCommitted?.let { callback ->
                runCatching(callback).onFailure {
                    BleLog.log("[PERSIST] post-commit callback FAILED id=${request.id}: ${it.message}")
                }
            }
        } else {
            val phase = if (entered == null) {
                "queued→enteredEdit=not-entered"
            } else {
                "queued→enteredEdit=${entered - request.queuedAtMs}ms " +
                    "enteredEdit→failed=${finishedAtMs - entered}ms"
            }
            val error = result.exceptionOrNull()
            BleLog.log(
                "[PERSIST] FAILED id=${request.id} ${request.label} $phase " +
                    "error=${error?.message ?: error?.javaClass?.simpleName ?: "unknown"}",
            )
        }
        request.completion?.complete(result)
        cancellation?.let { throw it }
    }

    private fun MutablePreferences.applyGlucose(reading: LastGlucose) {
        this[keyLastLifeCount] = reading.lifeCount
        this[keyLastMgDL] = reading.mgDL
        this[keyLastTrend] = reading.trend
        this[keyLastReceivedAtMs] = reading.receivedAtMs
        val delta = reading.deltaMgDlPerMin
        if (delta == null || !delta.isFinite()) {
            remove(keyLastDeltaMgDlPerMin)
        } else {
            this[keyLastDeltaMgDlPerMin] = delta
        }
    }

    private fun MutablePreferences.applySensorStatus(errorData: Int, patchState: Int, observedAtMs: Long) {
        this[keySensorErrorData] = errorData
        this[keySensorPatchState] = patchState
        this[keySensorStatusObservedAtMs] = observedAtMs
    }

    // ---------------------------------------------------------------------------------------------

    val sessionFlow: Flow<ImportedSession?> = context.dataStore.data.map { prefs ->
        prefs[keySession]?.let { runCatching { ImportedSession.fromJson(it) }.getOrNull() }
    }

    val autoConnectFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[keyAutoConnect] ?: false
    }

    val lastGlucoseFlow: Flow<LastGlucose?> = context.dataStore.data.map { prefs ->
        val lifeCount = prefs[keyLastLifeCount] ?: return@map null
        val mgDL = prefs[keyLastMgDL] ?: return@map null
        LastGlucose(
            lifeCount = lifeCount,
            mgDL = mgDL,
            trend = prefs[keyLastTrend] ?: "UNKNOWN",
            receivedAtMs = prefs[keyLastReceivedAtMs] ?: 0L,
            deltaMgDlPerMin = prefs[keyLastDeltaMgDlPerMin],
        )
    }

    val sensorStatusFlow: Flow<SensorStatusSnapshot?> = context.dataStore.data.map { prefs ->
        val errorData = prefs[keySensorErrorData] ?: return@map null
        val patchState = prefs[keySensorPatchState] ?: return@map null
        SensorStatusSnapshot(errorData, patchState, prefs[keySensorStatusObservedAtMs] ?: 0L)
    }

    suspend fun loadSession(): ImportedSession? = sessionFlow.first()

    suspend fun autoConnectEnabled(): Boolean = autoConnectFlow.first()

    suspend fun loadSensorStatus(): SensorStatusSnapshot? = sensorStatusFlow.first()

    private fun MutablePreferences.clearSensorStatus() {
        remove(keySensorErrorData)
        remove(keySensorPatchState)
        remove(keySensorStatusObservedAtMs)
    }

    suspend fun saveSession(
        session: ImportedSession,
        preserveCachedKeyWhenKeyless: Boolean = false,
    ) {
        val persistedSessionJson = session.withoutTransientCrypto().toJson()
        serializedEdit("session addr=${session.bleAddress}") { prefs ->
            applyCachedKeyOnSessionChange(prefs, session, preserveCachedKeyWhenKeyless)
            prefs[keySession] = persistedSessionJson
            prefs.clearSensorStatus()
        }
    }

    /**
     * Reconcile the separate cached first-pair key with an incoming session:
     *  - a session carrying a 16-byte key (imported from external JSON / Swift `phase5RawKey`) sets it;
     *  - a key-less session drops any cached key, because the caller is replacing provisioning;
     *  - a metadata-only same-address update can explicitly preserve the locally-derived key.
     */
    private fun applyCachedKeyOnSessionChange(
        prefs: MutablePreferences,
        session: ImportedSession,
        preserveCachedKeyWhenKeyless: Boolean = false,
    ) {
        val previousAddress = prefs[keySession]
            ?.let { runCatching { ImportedSession.fromJson(it).bleAddress }.getOrNull() }
        val importedKey = session.phase5RawKey?.takeIf { it.size == 16 }
        when {
            importedKey != null -> prefs[keyCachedPhase5RawKey] = importedKey.toHex()
            preserveCachedKeyWhenKeyless &&
                previousAddress != null &&
                previousAddress.equals(session.bleAddress, ignoreCase = true) -> Unit
            else -> prefs.remove(keyCachedPhase5RawKey)
        }
    }

    suspend fun setAutoConnectEnabled(enabled: Boolean) {
        serializedEdit("autoConnect=$enabled") { it[keyAutoConnect] = enabled }
    }

    suspend fun clearSession() {
        serializedEdit("clearSession") {
            it.remove(keySession)
            it.remove(keyCachedPhase5RawKey)
            it.clearSensorStatus()
        }
    }

    suspend fun loadCachedPhase5RawKey(): ByteArray? {
        val raw = context.dataStore.data.first()[keyCachedPhase5RawKey] ?: return null
        return runCatching { hexToBytes(raw).takeIf { it.size == 16 } }.getOrNull()
    }

    suspend fun saveCachedPhase5RawKey(key: ByteArray) {
        require(key.size == 16) { "phase5 raw key must be 16 bytes" }
        val encodedKey = key.toHex()
        serializedEdit("cachedKey save") { it[keyCachedPhase5RawKey] = encodedKey }
    }

    suspend fun clearCachedPhase5RawKey() {
        serializedEdit("cachedKey clear") { it.remove(keyCachedPhase5RawKey) }
    }

    suspend fun lastGlucose(): Pair<Int, Int>? {
        val prefs = context.dataStore.data.first()
        val lc = prefs[keyLastLifeCount] ?: return null
        val mg = prefs[keyLastMgDL] ?: return null
        return lc to mg
    }

    suspend fun loadLastGlucose(): LastGlucose? = lastGlucoseFlow.first()

    suspend fun saveLastGlucose(lifeCount: Int, mgDL: Int) {
        saveGlucoseReading(lifeCount, mgDL, "UNKNOWN", System.currentTimeMillis())
    }

    suspend fun saveGlucoseReading(
        lifeCount: Int,
        mgDL: Int,
        trend: String,
        receivedAtMs: Long,
    ) {
        val previous = loadLastGlucose()
        // lifeCount (the sensor's minute counter) is the denominator, not wall-clock: post-reconnect
        // bursts deliver readings seconds apart and a seconds-based denominator exploded delta to ±99.
        val delta = previous
            ?.takeIf { lifeCount > it.lifeCount && it.receivedAtMs in 1 until receivedAtMs }
            ?.let { (mgDL - it.mgDL).toDouble() / (lifeCount - it.lifeCount).toDouble() }
        val reading = LastGlucose(lifeCount, mgDL, trend, receivedAtMs, delta)
        serializedEdit("glucoseReading lc=$lifeCount") { it.applyGlucose(reading) }
    }

    /**
     * Merge a point recovered from clinical/historical backfill. The watch has no long history
     * store, so older recovered points are logged by the caller but do not move the displayed value
     * backwards.
     */
    suspend fun saveBackfilledGlucoseReading(
        lifeCount: Int,
        mgDL: Int,
        trend: String,
        fallbackReceivedAtMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val current = loadLastGlucose()
        if (current != null && lifeCount <= current.lifeCount) return false
        val receivedAtMs = estimateBackfillTime(current, lifeCount, fallbackReceivedAtMs)
        saveGlucoseReading(lifeCount, mgDL, trend, receivedAtMs)
        return true
    }

    private fun estimateBackfillTime(current: LastGlucose?, lifeCount: Int, fallbackReceivedAtMs: Long): Long {
        val anchor = current ?: return fallbackReceivedAtMs
        val estimated = anchor.receivedAtMs + (lifeCount - anchor.lifeCount) * 60_000L
        return estimated.takeIf { it > 0L } ?: fallbackReceivedAtMs
    }

    private companion object {
        /** Above this end-to-end write duration the [PERSIST] line is promoted to [ANOMALY]. */
        const val SLOW_EDIT_WARN_MS = 1_000L
    }
}
