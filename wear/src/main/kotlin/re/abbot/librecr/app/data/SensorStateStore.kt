package re.abbot.librecr.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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

    data class LastGlucose(
        val lifeCount: Int,
        val mgDL: Int,
        val trend: String,
        val receivedAtMs: Long,
        val deltaMgDlPerMin: Double?,
    )

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

    suspend fun loadSession(): ImportedSession? = sessionFlow.first()

    suspend fun autoConnectEnabled(): Boolean = autoConnectFlow.first()

    suspend fun saveSession(session: ImportedSession) {
        context.dataStore.edit { it[keySession] = session.withoutTransientCrypto().toJson() }
    }

    suspend fun setAutoConnectEnabled(enabled: Boolean) {
        context.dataStore.edit { it[keyAutoConnect] = enabled }
    }

    suspend fun clearSession() {
        context.dataStore.edit {
            it.remove(keySession)
            it.remove(keyCachedPhase5RawKey)
        }
    }

    suspend fun loadCachedPhase5RawKey(): ByteArray? {
        val raw = context.dataStore.data.first()[keyCachedPhase5RawKey] ?: return null
        return runCatching { hexToBytes(raw).takeIf { it.size == 16 } }.getOrNull()
    }

    suspend fun saveCachedPhase5RawKey(key: ByteArray) {
        require(key.size == 16) { "phase5 raw key must be 16 bytes" }
        context.dataStore.edit { it[keyCachedPhase5RawKey] = key.toHex() }
    }

    suspend fun clearCachedPhase5RawKey() {
        context.dataStore.edit { it.remove(keyCachedPhase5RawKey) }
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
        val delta = previous
            ?.takeIf { it.lifeCount != lifeCount && it.receivedAtMs in 1 until receivedAtMs }
            ?.let { (mgDL - it.mgDL).toDouble() / ((receivedAtMs - it.receivedAtMs).toDouble() / 60_000.0) }
        context.dataStore.edit {
            it[keyLastLifeCount] = lifeCount
            it[keyLastMgDL] = mgDL
            it[keyLastTrend] = trend
            it[keyLastReceivedAtMs] = receivedAtMs
            if (delta == null || !delta.isFinite()) {
                it.remove(keyLastDeltaMgDlPerMin)
            } else {
                it[keyLastDeltaMgDlPerMin] = delta
            }
        }
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

    suspend fun saveRemoteGlucose(reading: LastGlucose) {
        context.dataStore.edit {
            it[keyLastLifeCount] = reading.lifeCount
            it[keyLastMgDL] = reading.mgDL
            it[keyLastTrend] = reading.trend
            it[keyLastReceivedAtMs] = reading.receivedAtMs
            val delta = reading.deltaMgDlPerMin
            if (delta == null || !delta.isFinite()) it.remove(keyLastDeltaMgDlPerMin)
            else it[keyLastDeltaMgDlPerMin] = delta
        }
    }

    private fun estimateBackfillTime(current: LastGlucose?, lifeCount: Int, fallbackReceivedAtMs: Long): Long {
        val anchor = current ?: return fallbackReceivedAtMs
        val estimated = anchor.receivedAtMs + (lifeCount - anchor.lifeCount) * 60_000L
        return estimated.takeIf { it > 0L } ?: fallbackReceivedAtMs
    }
}
