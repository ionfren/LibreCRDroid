package re.abbot.librecr.app.libreview

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import re.abbot.librecr.app.data.CloudSettings
import re.abbot.librecr.app.data.SensorStateStore
import re.abbot.librecr.app.data.SettingsStore
import re.abbot.librecr.app.data.deviceUniqueId
import re.abbot.librecr.app.log.BleLog
import java.util.Locale

sealed interface CloudStatus {
    data object Idle : CloudStatus
    data object Uploading : CloudStatus
    data class Ok(val atMs: Long, val mgDl: Int) : CloudStatus
    data class Error(val message: String) : CloudStatus
}

/**
 * Uploads each live reading to LibreView (`/api/measurements`). Keeps a cached session, dedups on
 * lifeCount, serializes uploads with a mutex, and re-authenticates once if the token is rejected
 * (`status 20 / wrongDeviceInToken`, as Juggluco does). Never throws to the caller.
 */
class LibreViewUploader(
    private val context: Context,
    private val settings: SettingsStore,
    private val store: SensorStateStore,
) {
    private val client = LibreViewAccountClient()
    private val mutex = Mutex()

    @Volatile private var session: LibreViewSession? = null
    @Volatile private var lastUploadedLifeCount = -1

    private val _status = MutableStateFlow<CloudStatus>(CloudStatus.Idle)
    val status: StateFlow<CloudStatus> = _status

    /** Upload one reading if cloud upload is enabled. Dedups on lifeCount; never throws. */
    suspend fun uploadReading(mgDl: Int, trend: String, lifeCount: Int, atMs: Long) {
        val cloud = settings.current().cloud
        if (!cloud.uploadEnabled || cloud.email.isBlank() || cloud.password.isBlank()) return
        if (lifeCount == lastUploadedLifeCount) return
        mutex.withLock {
            if (lifeCount == lastUploadedLifeCount) return
            runCatching { send(cloud, mgDl, trend, lifeCount, atMs) }
                .onFailure {
                    _status.value = CloudStatus.Error(it.message ?: it.toString())
                    BleLog.log("libreview upload error: ${it.message}")
                }
        }
    }

    /** Force a fresh login and upload the latest stored reading (the "Test now" button). */
    suspend fun testNow(): Result<Unit> = mutex.withLock {
        val cloud = settings.current().cloud
        if (cloud.email.isBlank() || cloud.password.isBlank()) {
            return Result.failure(LibreViewAccountException("Introdu email și parolă LibreView."))
        }
        val last = store.loadLastGlucose()
            ?: return Result.failure(LibreViewAccountException("Nu există încă o citire de trimis."))
        session = null // force re-login
        runCatching { send(cloud, last.mgDL, last.trend, last.lifeCount, last.receivedAtMs) }
            .onFailure { _status.value = CloudStatus.Error(it.message ?: it.toString()) }
    }

    private suspend fun send(cloud: CloudSettings, mgDl: Int, trend: String, lifeCount: Int, atMs: Long) {
        _status.value = CloudStatus.Uploading
        var result = post(cloud, mgDl, trend, lifeCount, atMs)
        if (result.status == 20 && result.reason.contains("wrongDeviceInToken", ignoreCase = true)) {
            session = null
            result = post(cloud, mgDl, trend, lifeCount, atMs)
        }
        if (result.status == 0) {
            lastUploadedLifeCount = lifeCount
            _status.value = CloudStatus.Ok(System.currentTimeMillis(), mgDl)
            BleLog.log("libreview upload ok mgdl=$mgDl lifeCount=$lifeCount")
        } else {
            throw LibreViewAccountException("status=${result.status} ${result.reason}".trim())
        }
    }

    private suspend fun post(cloud: CloudSettings, mgDl: Int, trend: String, lifeCount: Int, atMs: Long): MeasurementUploadResult {
        val s = session ?: client.login(cloud.email, cloud.password, deviceUniqueId(context)).also { session = it }
        val current = settings.current()
        val device = MeasurementPayload.Device(
            hardwareDescriptor = Build.MODEL,
            hardwareName = Build.MANUFACTURER,
            modelName = "com.freestylelibre.app.gb",
            osVersion = Build.VERSION.SDK_INT.toString(),
            uniqueIdentifier = deviceUniqueId(context),
        )
        val recordNumber = lifeCount.toLong()
        val body = MeasurementPayload.build(
            device = device,
            unitLabel = "mg/dL",
            isStreaming = true,
            language = languageTag(),
            targetLow = current.targetLow,
            targetHigh = current.targetHigh,
            hour24 = true,
            nowMs = System.currentTimeMillis(),
            userToken = s.userToken,
            currentEntries = listOf(MeasurementPayload.currentEntry(mgDl, trend, atMs, recordNumber)),
            scheduledEntries = listOf(MeasurementPayload.scheduledEntry(mgDl, atMs, recordNumber)),
        )
        return client.postMeasurements(s, body)
    }

    private fun languageTag(): String {
        val loc = Locale.getDefault()
        return if (loc.country.isBlank()) loc.language else "${loc.language}-${loc.country}"
    }
}
