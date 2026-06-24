package re.abbot.librecr.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import re.abbot.librecr.app.ble.SensorConnectionManager
import re.abbot.librecr.app.data.SensorStateStore
import re.abbot.librecr.app.data.WearAppearanceStore
import re.abbot.librecr.app.wear.WearDataSync
import re.abbot.librecr.app.wear.complication.LibreComplicationUpdater

/**
 * Minimal process-wide graph so the foreground service (which owns the
 * connection loop) and the UI (which observes it) share one manager + store.
 */
object LibreCR {
    @Volatile private var initialized = false
    lateinit var store: SensorStateStore
        private set
    lateinit var manager: SensorConnectionManager
        private set
    lateinit var appearance: WearAppearanceStore
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        store = SensorStateStore(app)
        manager = SensorConnectionManager(app, store)
        appearance = WearAppearanceStore(app)
        scope.launch {
            appearance.settingsFlow
                .distinctUntilChanged()
                .collect { LibreComplicationUpdater.requestAll(app) }
        }
        WearDataSync.fetchAppearance(app) { settings ->
            scope.launch {
                appearance.save(settings)
                LibreComplicationUpdater.requestAll(app)
            }
        }
        WearDataSync.requestAppearance(app)
        initialized = true
    }
}
