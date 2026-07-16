package re.abbot.librecr.app.wear.complication

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import re.abbot.librecr.app.log.BleLog

object LibreComplicationUpdater {
    // Glucose services first: if the CPU re-suspends mid-burst, the safety-relevant repaint won.
    private val services = listOf(
        AgeDeltaComplicationService::class.java,
        TrendAboveValueComplicationService::class.java,
        DateComplicationService::class.java,
        WatchBatteryComplicationService::class.java,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var updateWakeLock: PowerManager.WakeLock? = null

    // The wake source that triggered the update (BLE callback, Data Layer binder, alarm) dies as
    // soon as its callback returns, but the repaint still needs the watch-face host to receive
    // our broadcast and bind back into this process — with no phone connected there is no Data
    // Layer chatter to keep the CPU up, and that round trip is what doze was deferring. The
    // acquire timeout is the release.
    private const val UPDATE_WAKE_LOCK_TIMEOUT_MS = 3_000L

    // Requesting every service at the same instant can drop complication icons/images in ambient
    // mode (GlucoDataHandler field observation), so the burst is slightly staggered.
    private const val REQUEST_STAGGER_MS = 50L

    fun requestAll(context: Context, lifeCount: Int? = null): Job {
        val app = context.applicationContext
        // Acquired synchronously, while the caller's own wake source is still alive.
        acquireUpdateWakeLock(app)
        return scope.launch {
            services.forEachIndexed { index, serviceClass ->
                if (index > 0) delay(REQUEST_STAGGER_MS)
                request(app, serviceClass, lifeCount)
            }
        }
    }

    private fun request(context: Context, serviceClass: Class<*>, lifeCount: Int?) {
        runCatching {
            val component = ComponentName(context, serviceClass)
            val requester = ComplicationDataSourceUpdateRequester.create(
                context = context,
                complicationDataSourceComponent = component,
            )
            val ids = ActiveComplicationRegistry.instanceIdsFor(component)
            if (ids.isEmpty()) requester.requestUpdateAll() else requester.requestUpdate(*ids)
            val route = if (Build.VERSION.SDK_INT >= 36) "WEAR_SDK" else "LEGACY_BROADCAST"
            BleLog.log(
                "COMPLICATION_UPDATE_REQUESTED lc=${lifeCount ?: -1} " +
                    "service=${serviceClass.simpleName} route=$route " +
                    "ids=${if (ids.isEmpty()) "all" else ids.joinToString(",")}",
            )
        }.onFailure {
            BleLog.log("complication: update request failed for ${serviceClass.simpleName}: ${it.message ?: it::class.java.simpleName}")
        }
    }

    private fun acquireUpdateWakeLock(context: Context) {
        runCatching {
            val lock = updateWakeLock ?: run {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    ?: return
                powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LibreCR:ComplicationUpdate")
                    .apply { setReferenceCounted(false) }
                    .also { updateWakeLock = it }
            }
            // Re-acquiring the non-refcounted lock while held just extends the timeout.
            lock.acquire(UPDATE_WAKE_LOCK_TIMEOUT_MS)
        }.onFailure {
            BleLog.log("complication: update wake lock unavailable: ${it.message ?: it::class.java.simpleName}")
        }
    }
}
