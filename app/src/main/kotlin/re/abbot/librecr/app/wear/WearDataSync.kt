package re.abbot.librecr.app.wear

import android.content.Context
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import re.abbot.librecr.app.data.ImportedSession
import re.abbot.librecr.app.data.SensorStateStore
import re.abbot.librecr.app.data.WearAppearanceSettings
import re.abbot.librecr.app.log.BleLog
import re.abbot.librecr.protocol.dataplane.GlucoseTimeline
import java.util.concurrent.atomic.AtomicReference

object WearDataSync {
    const val PATH_SESSION = "/librecr/session"
    const val PATH_START = "/librecr/start"
    const val PATH_STOP = "/librecr/stop"
    const val PATH_STOPPED = "/librecr/stopped"
    const val PATH_GLUCOSE = "/librecr/glucose"
    const val PATH_GLUCOSE_ACK = "/librecr/glucose/ack"
    const val PATH_GLUCOSE_REPLAY = "/librecr/glucose/replay"
    const val PATH_GLUCOSE_REPLAY_PREFIX = "/librecr/glucose/replay/"
    const val PATH_GLUCOSE_REPLAY_REQUEST = "/librecr/glucose/replay_request"
    const val PATH_WEAR_APPEARANCE = "/librecr/wear_appearance"
    const val PATH_WEAR_APPEARANCE_REQUEST = "/librecr/wear_appearance/request"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingStopAck = AtomicReference<CompletableDeferred<Unit>?>(null)

    fun parseGlucose(bytes: ByteArray): SensorStateStore.LastGlucose {
        val json = JSONObject(String(bytes))
        return SensorStateStore.LastGlucose(
            lifeCount = json.getInt("lifeCount"),
            mgDL = json.getInt("mgDL"),
            trend = json.optString("trend", "UNKNOWN"),
            receivedAtMs = json.optLong("receivedAtMs", System.currentTimeMillis()),
            deltaMgDlPerMin = if (json.has("deltaMgDlPerMin")) json.getDouble("deltaMgDlPerMin") else null,
        )
    }

    /** Reconstruct the watch-side timeline shipped in the payload, stamping the phone's arrival. */
    fun parseTimeline(bytes: ByteArray, phoneReceivedTs: Long): GlucoseTimeline {
        val json = JSONObject(String(bytes))
        return GlucoseTimeline(
            lifeCount = json.getInt("lifeCount"),
            firstNotifyTs = json.optLong("firstNotifyTs", 0L),
            secondNotifyTs = json.optLong("secondNotifyTs", 0L),
            decodedTs = json.optLong("decodedTs", 0L),
            savedTs = json.optLong("savedTs", 0L),
            sentToPhoneTs = json.optLong("sentToPhoneTs", 0L),
            phoneReceivedTs = phoneReceivedTs,
        )
    }

    fun sendSession(context: Context, session: ImportedSession, startOnWatch: Boolean = false) {
        sendToNearbyNodes(
            context = context,
            path = if (startOnWatch) PATH_START else PATH_SESSION,
            payload = session.toProvisioningJson().toByteArray(),
        )
    }

    fun sendStop(context: Context) {
        sendToNearbyNodes(context, PATH_STOP, ByteArray(0))
    }

    suspend fun requestStopAndWait(context: Context, timeoutMs: Long = 7_000L): Boolean {
        val ack = CompletableDeferred<Unit>()
        pendingStopAck.set(ack)
        sendStop(context)
        val stopped = withTimeoutOrNull(timeoutMs) {
            ack.await()
            true
        } ?: false
        pendingStopAck.compareAndSet(ack, null)
        BleLog.log("wear: stop ack received=$stopped")
        return stopped
    }

    fun sendStopped(context: Context) {
        sendToNearbyNodes(context, PATH_STOPPED, ByteArray(0))
    }

    fun notifyStopAck() {
        val ack = pendingStopAck.getAndSet(null)
        if (ack == null) {
            BleLog.log("wear: unsolicited stop ack")
        } else {
            ack.complete(Unit)
        }
    }

    fun sendGlucose(context: Context, reading: SensorStateStore.LastGlucose) {
        val json = JSONObject().apply {
            put("lifeCount", reading.lifeCount)
            put("mgDL", reading.mgDL)
            put("trend", reading.trend)
            put("receivedAtMs", reading.receivedAtMs)
            reading.deltaMgDlPerMin?.let { put("deltaMgDlPerMin", it) }
        }.toString()
        val payload = json.toByteArray()
        sendToNearbyNodes(context, PATH_GLUCOSE, payload)
        putDataItem(context, PATH_GLUCOSE, payload)
    }

    fun sendGlucoseAck(context: Context, lifeCount: Int) {
        val payload = JSONObject()
            .put("lifeCount", lifeCount)
            .put("ackAtMs", System.currentTimeMillis())
            .toString()
            .toByteArray()
        BleLog.log("ACK lc=$lifeCount direction=phone->watch")
        sendToNearbyNodes(context, PATH_GLUCOSE_ACK, payload)
    }

    fun requestGlucoseReplay(context: Context, fromLifeCount: Int, toLifeCount: Int) {
        if (toLifeCount < fromLifeCount) return
        val payload = JSONObject()
            .put("fromLifeCount", fromLifeCount)
            .put("toLifeCount", toLifeCount)
            .put("requestedAtMs", System.currentTimeMillis())
            .toString()
            .toByteArray()
        BleLog.log("wear: phone requested glucose replay lifeCount=$fromLifeCount..$toLifeCount")
        sendToNearbyNodes(context, PATH_GLUCOSE_REPLAY_REQUEST, payload)
    }

    fun sendAppearance(context: Context, settings: WearAppearanceSettings) {
        val payload = JSONObject(settings.toJson())
            .put("syncedAtMs", System.currentTimeMillis())
            .toString()
            .toByteArray()
        sendToNearbyNodes(context, PATH_WEAR_APPEARANCE, payload)
        putDataItem(context, PATH_WEAR_APPEARANCE, payload)
    }

    private fun putDataItem(context: Context, path: String, payload: ByteArray) {
        val app = context.applicationContext
        scope.launch {
            runCatching {
                val request = PutDataRequest.create(path)
                    .setData(payload)
                    .setUrgent()
                Wearable.getDataClient(app).putDataItem(request)
                    .addOnSuccessListener { BleLog.log("wear: put data item $path") }
                    .addOnFailureListener { BleLog.log("wear: put data item $path failed: ${it.message}") }
            }.onFailure {
                BleLog.log("wear: put data item $path unavailable: ${it.message ?: it::class.java.simpleName}")
            }
        }
    }

    private fun sendToNearbyNodes(context: Context, path: String, payload: ByteArray) {
        val app = context.applicationContext
        scope.launch {
            runCatching {
                val nodeClient = Wearable.getNodeClient(app)
                val messageClient = Wearable.getMessageClient(app)
                nodeClient.connectedNodes
                    .addOnSuccessListener { nodes ->
                        if (nodes.isEmpty()) {
                            BleLog.log("wear: no connected Wear node for $path")
                        }
                        nodes.forEach { node ->
                            messageClient.sendMessage(node.id, path, payload)
                                .addOnSuccessListener { BleLog.log("wear: sent $path to ${node.displayName} nearby=${node.isNearby}") }
                                .addOnFailureListener { BleLog.log("wear: send $path to ${node.displayName} failed: ${it.message}") }
                        }
                    }
                    .addOnFailureListener { BleLog.log("wear: connectedNodes failed: ${it.message}") }
            }.onFailure {
                BleLog.log("wear: send $path unavailable: ${it.message ?: it::class.java.simpleName}")
            }
        }
    }
}
