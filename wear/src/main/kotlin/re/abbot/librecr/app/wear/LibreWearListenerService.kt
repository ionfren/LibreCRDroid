package re.abbot.librecr.app.wear

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import re.abbot.librecr.app.LibreCR
import re.abbot.librecr.app.data.ImportedSession
import re.abbot.librecr.app.log.BleLog
import re.abbot.librecr.app.log.GlucoseLatencyTracer
import re.abbot.librecr.app.service.SensorForegroundService
import re.abbot.librecr.app.wear.complication.LibreComplicationUpdater

class LibreWearListenerService : WearableListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        LibreCR.init(this)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            WearDataSync.PATH_SESSION,
            WearDataSync.PATH_START -> receiveSession(messageEvent)
            WearDataSync.PATH_STOP -> stopLocalConnection()
            WearDataSync.PATH_STOPPED -> WearDataSync.notifyStopAck()
            WearDataSync.PATH_GLUCOSE -> receiveGlucose(messageEvent.data, "message")
            WearDataSync.PATH_GLUCOSE_REPLAY -> receiveGlucose(messageEvent.data, "message replay")
            WearDataSync.PATH_GLUCOSE_ACK -> receiveGlucoseAck(messageEvent.data)
            WearDataSync.PATH_GLUCOSE_REPLAY_REQUEST -> receiveReplayRequest(messageEvent.data)
            WearDataSync.PATH_WEAR_APPEARANCE -> receiveAppearance(messageEvent.data, "message")
            else -> super.onMessageReceived(messageEvent)
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        try {
            for (event in dataEvents) {
                if (event.type == DataEvent.TYPE_CHANGED) {
                    val path = event.dataItem.uri.path ?: continue
                    when {
                        path == WearDataSync.PATH_GLUCOSE || path.startsWith(WearDataSync.PATH_GLUCOSE_REPLAY_PREFIX) ->
                            event.dataItem.data?.let { receiveGlucose(it, "data item") }
                        path == WearDataSync.PATH_WEAR_APPEARANCE ->
                            event.dataItem.data?.let { receiveAppearance(it, "data item") }
                    }
                }
            }
        } finally {
            dataEvents.release()
        }
    }

    private fun receiveSession(messageEvent: MessageEvent) {
        scope.launch {
            runCatching {
                val incoming = ImportedSession.fromJson(String(messageEvent.data)).withoutTransientCrypto()
                val shouldStart = messageEvent.path == WearDataSync.PATH_START
                LibreCR.store.saveSession(incoming)
                if (shouldStart) {
                    LibreCR.store.setAutoConnectEnabled(true)
                }
                BleLog.log("wear: provisioning received for ${incoming.bleAddress} start=$shouldStart")
                if (shouldStart) {
                    SensorForegroundService.start(this@LibreWearListenerService, allowCandidateFirstPair = true)
                }
            }.onFailure {
                BleLog.log("wear: session receive failed: ${it.message ?: it::class.java.simpleName}")
            }
        }
    }

    private fun receiveAppearance(bytes: ByteArray, source: String) {
        scope.launch {
            runCatching {
                val settings = WearDataSync.parseAppearance(bytes)
                LibreCR.appearance.save(settings)
                LibreComplicationUpdater.requestAll(this@LibreWearListenerService)
                BleLog.log("wear: appearance received from phone via $source")
            }.onFailure {
                BleLog.log("wear: appearance receive failed: ${it.message ?: it::class.java.simpleName}")
            }
        }
    }

    private fun receiveGlucose(bytes: ByteArray, source: String) {
        // Relay path: the watch never saw the BLE notify, so the Data Layer arrival here is
        // the equivalent anchor. (receivedAtMs inside the payload is the phone's clock and is
        // kept only for display age, not subtracted across devices.)
        val arrivalMs = System.currentTimeMillis()
        scope.launch {
            runCatching {
                val reading = WearDataSync.parseGlucose(bytes)
                GlucoseLatencyTracer.mark(reading.lifeCount, GlucoseLatencyTracer.Stage.BLE_NOTIFY_RECEIVED, arrivalMs)
                LibreCR.manager.acceptRemoteGlucose(reading)
                GlucoseLatencyTracer.mark(reading.lifeCount, GlucoseLatencyTracer.Stage.STORE_UPDATED)
                BleLog.log("wear: glucose received from phone via $source ${reading.mgDL} mg/dL lc=${reading.lifeCount}")
                LibreComplicationUpdater.requestAll(this@LibreWearListenerService, reading.lifeCount)
            }.onFailure {
                BleLog.log("wear: glucose receive failed: ${it.message ?: it::class.java.simpleName}")
            }
        }
    }

    private fun receiveGlucoseAck(bytes: ByteArray) {
        runCatching { WearDataSync.parseLifeCount(bytes) }
            .onSuccess { WearDataSync.notifyGlucoseAck(it) }
            .onFailure { BleLog.log("wear: glucose ACK parse failed: ${it.message ?: it::class.java.simpleName}") }
    }

    private fun receiveReplayRequest(bytes: ByteArray) {
        runCatching { WearDataSync.parseReplayRequest(bytes) }
            .onSuccess { WearDataSync.replayGlucoseRange(this, it) }
            .onFailure { BleLog.log("wear: replay request parse failed: ${it.message ?: it::class.java.simpleName}") }
    }

    private fun stopLocalConnection() {
        scope.launch {
            LibreCR.store.setAutoConnectEnabled(false)
            val stopped = LibreCR.manager.stopAndJoin()
            SensorForegroundService.stop(this@LibreWearListenerService)
            WearDataSync.sendStopped(this@LibreWearListenerService)
            BleLog.log("wear: local sensor connection stopped by peer stopped=$stopped")
        }
    }
}
