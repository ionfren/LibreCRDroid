package re.abbot.librecr.app.wear

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import re.abbot.librecr.app.LibreCR
import re.abbot.librecr.app.data.ImportedSession
import re.abbot.librecr.app.log.BleLog
import re.abbot.librecr.app.log.GlucoseTimelineTracker
import re.abbot.librecr.app.service.SensorForegroundService

class PhoneWearListenerService : WearableListenerService() {
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
            WearDataSync.PATH_WEAR_APPEARANCE_REQUEST -> sendAppearance()
            else -> super.onMessageReceived(messageEvent)
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        try {
            for (event in dataEvents) {
                if (event.type != DataEvent.TYPE_CHANGED) continue
                val path = event.dataItem.uri.path ?: continue
                if (path == WearDataSync.PATH_GLUCOSE || path.startsWith(WearDataSync.PATH_GLUCOSE_REPLAY_PREFIX)) {
                    event.dataItem.data?.let { receiveGlucose(it, "data item") }
                }
            }
        } finally {
            dataEvents.release()
        }
    }

    private fun receiveGlucose(bytes: ByteArray, source: String) {
        // Stamp arrival on the binder thread, before any coroutine dispatch, so the cross-device
        // hop (sent→phoneReceived) isn't inflated by scheduler latency.
        val phoneReceivedTs = System.currentTimeMillis()
        scope.launch {
            runCatching {
                val reading = WearDataSync.parseGlucose(bytes)
                LibreCR.manager.acceptRemoteGlucose(reading)
                val previous = LibreCR.store.loadLastGlucose()
                if (previous != null && reading.lifeCount > previous.lifeCount + 1) {
                    WearDataSync.requestGlucoseReplay(
                        this@PhoneWearListenerService,
                        fromLifeCount = previous.lifeCount + 1,
                        toLifeCount = reading.lifeCount - 1,
                    )
                }
                val advancesTimeline = previous == null || reading.lifeCount > previous.lifeCount
                if (advancesTimeline) {
                    GlucoseTimelineTracker.onPhoneReceived(WearDataSync.parseTimeline(bytes, phoneReceivedTs))
                }
                val advancedLatest = LibreCR.store.saveRemoteGlucose(reading)
                BleLog.log(
                    "PHONE_RECV lc=${reading.lifeCount} source=$source mgdl=${reading.mgDL} " +
                        "advancedLatest=$advancedLatest timeline=$advancesTimeline previous=${previous?.lifeCount ?: -1}",
                )
                WearDataSync.sendGlucoseAck(this@PhoneWearListenerService, reading.lifeCount)
                // A relayed reading proves the watch is the active receiver. The Libre 3 allows only
                // one connected receiver, so the phone yields its own BLE to avoid the two fighting
                // over the single slot (which alternates readings → every-2-minutes). Acts once;
                // an explicit "return to phone" handoff re-enables auto-connect.
                if (advancedLatest && LibreCR.store.autoConnectEnabled()) {
                    BleLog.log("wear: watch is active receiver → phone yielding BLE")
                    LibreCR.store.setAutoConnectEnabled(false)
                    val stopped = LibreCR.manager.stopAndJoin()
                    SensorForegroundService.stop(this@PhoneWearListenerService)
                    BleLog.log("wear: phone yielded BLE stopped=$stopped")
                }
            }.onFailure {
                BleLog.log("wear: phone glucose receive failed: ${it.message ?: it::class.java.simpleName}")
            }
        }
    }

    private fun receiveSession(messageEvent: MessageEvent) {
        scope.launch {
            runCatching {
                val session = ImportedSession.fromJson(String(messageEvent.data)).withoutTransientCrypto()
                LibreCR.store.saveSession(session)
                BleLog.log("wear: phone saved provisioning update from watch for ${session.bleAddress}")
            }.onFailure {
                BleLog.log("wear: phone session receive failed: ${it.message ?: it::class.java.simpleName}")
            }
        }
    }

    private fun sendAppearance() {
        scope.launch {
            runCatching {
                WearDataSync.sendAppearance(this@PhoneWearListenerService, LibreCR.settings.current().wearAppearance)
                BleLog.log("wear: appearance sent after watch request")
            }.onFailure {
                BleLog.log("wear: appearance request failed: ${it.message ?: it::class.java.simpleName}")
            }
        }
    }

    private fun stopLocalConnection() {
        scope.launch {
            LibreCR.store.setAutoConnectEnabled(false)
            val stopped = LibreCR.manager.stopAndJoin()
            SensorForegroundService.stop(this@PhoneWearListenerService)
            WearDataSync.sendStopped(this@PhoneWearListenerService)
            BleLog.log("wear: phone local sensor connection stopped by peer stopped=$stopped")
        }
    }
}
