package re.abbot.librecr.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import re.abbot.librecr.app.wear.AutomaticSensorHandoff
import re.abbot.librecr.app.LibreCR
import re.abbot.librecr.app.live.WearLiveUpdatesNotifier
import re.abbot.librecr.app.log.BleLog

/**
 * Foreground service (type connectedDevice) that keeps the connection loop alive
 * in the background and surfaces live glucose in its notification — the Android
 * analogue of the iOS background-BLE strategy.
 */
class SensorForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        LibreCR.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        WearLiveUpdatesNotifier.startForeground(this)
        val allowCandidateFirstPair = intent?.getBooleanExtra(EXTRA_ALLOW_CANDIDATE_FIRST_PAIR, false) == true
        scope.launch {
            val session = LibreCR.store.loadSession()
            if (session == null || !LibreCR.store.autoConnectEnabled()) {
                BleLog.log("service: no session or receiver disabled; stopping")
                stopSelf()
                return@launch
            }
            if (!AutomaticSensorHandoff.mayStartLocalSession()) {
                stopSelf()
                return@launch
            }
            BleLog.log(
                "service: starting sensor manager with independent local handshake " +
                    "allowCandidateFirstPair=$allowCandidateFirstPair legacyKeyIgnored=${session.phase5RawKey != null}"
            )
            LibreCR.manager.start(
                scope,
                session.withoutTransientCrypto(),
                allowCandidateFirstPair = allowCandidateFirstPair,
                passiveConnect = intent?.getBooleanExtra(EXTRA_PASSIVE_CONNECT, false) == true,
            )
        }
        return START_STICKY
    }

    override fun onDestroy() {
        LibreCR.manager.stop()
        scope.cancel()
        WearLiveUpdatesNotifier.stopForeground(this)
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_PASSIVE_CONNECT = "librecr.PASSIVE_CONNECT"
        private const val EXTRA_ALLOW_CANDIDATE_FIRST_PAIR =
            "re.abbot.librecr.app.extra.ALLOW_CANDIDATE_FIRST_PAIR"

        fun start(context: Context, allowCandidateFirstPair: Boolean = false, passiveConnect: Boolean = false): Boolean {
            val intent = Intent(context, SensorForegroundService::class.java)
                .putExtra(EXTRA_ALLOW_CANDIDATE_FIRST_PAIR, allowCandidateFirstPair)
                .putExtra(EXTRA_PASSIVE_CONNECT, passiveConnect)
            return runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            }.onFailure {
                BleLog.log("service: startForegroundService failed: ${it.message ?: it::class.java.simpleName}")
            }.isSuccess
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SensorForegroundService::class.java))
        }
    }
}
