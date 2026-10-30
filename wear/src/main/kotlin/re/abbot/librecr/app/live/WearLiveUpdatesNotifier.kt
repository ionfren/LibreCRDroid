package re.abbot.librecr.app.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.SystemClock
import java.lang.ref.WeakReference
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import re.abbot.librecr.app.LibreCR
import re.abbot.librecr.app.MainActivity
import re.abbot.librecr.app.R
import re.abbot.librecr.app.WEAR_GLUCOSE_DISPLAY_HOLD_MS
import re.abbot.librecr.app.ble.GlucoseUi
import re.abbot.librecr.app.data.GlucoseUnit
import re.abbot.librecr.app.data.SensorStateStore
import re.abbot.librecr.app.log.BleLog
import re.abbot.librecr.protocol.TrendArrowShape
import re.abbot.librecr.protocol.dataplane.SensorLifecycle

/**
 * One notification for direct BLE and phone-relayed readings. The foreground service owns the
 * same ID while receiving locally, so handoff never leaves two glucose cards on the watch.
 * In-memory glucose drives updates; flash I/O is only a cold-start fallback.
 */
object WearLiveUpdatesNotifier {
    private const val CHANNEL_ID = "librecr_sensor"
    private const val NOTIFICATION_ID = 1
    private const val PREFERENCES = "wear_live_updates"
    private const val ENABLED = "enabled"
    internal const val ACTION_UNPIN = "re.abbot.librecr.app.live.UNPIN"
    internal const val ACTION_PIN = "re.abbot.librecr.app.live.PIN"

    private val mutableEnabled = MutableStateFlow(true)
    val enabled = mutableEnabled.asStateFlow()
    private var initialized = false
    private var foregroundRunning = false
    private var foregroundService: WeakReference<Service>? = null
    private var maybePosted = true
    private var latest = Snapshot()
    private val observationMode = MutableStateFlow(WearLiveUpdateMode.HIDDEN)
    private var lastNotificationKey: WearLiveUpdateNotificationKey? = null
    private var channelCreated = false
    private var openAppAction: PendingIntent? = null
    private val actionIntents = mutableMapOf<String, PendingIntent>()

    private data class Snapshot(
        val live: GlucoseUi? = null,
        val persisted: SensorStateStore.LastGlucose? = null,
        val unit: GlucoseUnit = GlucoseUnit.MG_DL,
        val sensorStatus: SensorStateStore.SensorStatusSnapshot? = null,
        val warmupMinutes: Int = SensorLifecycle.DEFAULT_WARMUP_DURATION_MINUTES,
    ) {
        fun content(nowMs: Long) = WearLiveUpdateContent.from(
            live, persisted, unit, sensorStatus, warmupMinutes, nowMs,
        )
    }

    @Synchronized
    fun initialize(context: Context, scope: CoroutineScope) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        mutableEnabled.value = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(ENABLED, true)
        updateObservationMode()
        scope.launch {
            collectWearLiveUpdates(observationMode, ::contentFlow, { refresh(app) })
        }
    }

    private fun contentFlow(): Flow<WearLiveUpdateContent> = combine(
        LibreCR.manager.glucose,
        LibreCR.store.lastGlucoseFlow.onStart { emit(synchronized(this@WearLiveUpdatesNotifier) { latest.persisted }) },
        LibreCR.appearance.settingsFlow.map { it.unit }.distinctUntilChanged()
            .onStart { emit(LibreCR.appearance.cachedOrDefault().unit) },
        LibreCR.manager.sensorStatus,
        LibreCR.store.sessionFlow.map { it?.warmupMinutes ?: SensorLifecycle.DEFAULT_WARMUP_DURATION_MINUTES }
            .distinctUntilChanged().onStart { emit(synchronized(this@WearLiveUpdatesNotifier) { latest.warmupMinutes }) },
    ) { live, persisted, unit, status, warmupMinutes ->
        Snapshot(live, persisted, unit, status, warmupMinutes)
    }.map { snapshot ->
        synchronized(this@WearLiveUpdatesNotifier) { latest = snapshot }
        // A queued DataStore write normally changes only persisted, while live already has the
        // same reading. Distinct effective content keeps that write from restarting the timer.
        snapshot.content(System.currentTimeMillis())
    }

    private fun updateObservationMode() {
        observationMode.value = WearLiveUpdateMode.from(enabled.value, foregroundRunning)
    }

    private fun currentContent(nowMs: Long): WearLiveUpdateContent = latest.copy(
        live = LibreCR.manager.glucose.value,
        unit = LibreCR.appearance.cachedOrDefault().unit,
        sensorStatus = LibreCR.manager.sensorStatus.value,
    ).content(nowMs)

    @Synchronized
    fun setEnabled(context: Context, enabled: Boolean) {
        val wasEnabled = mutableEnabled.value
        mutableEnabled.value = enabled
        updateObservationMode()
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putBoolean(ENABLED, enabled).apply()
        if (wasEnabled && !enabled && foregroundRunning) {
            // Wear can retain an OngoingActivity after its extras disappear from an updated FGS
            // notification. Remove that notification before registering the ordinary one; the
            // service/connection itself keeps running throughout this synchronous handoff.
            foregroundService?.get()?.let { service ->
                service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
                maybePosted = false
                lastNotificationKey = null
                startForeground(service)
            }
        }
        refresh(context)
    }

    /** Registration and updates share the monitor so no relay update can race startForeground. */
    @Synchronized
    fun startForeground(service: Service) {
        ensureChannel(service)
        foregroundRunning = true
        foregroundService = WeakReference(service)
        updateObservationMode()
        val nowMs = System.currentTimeMillis()
        val content = currentContent(nowMs)
        val notification = buildNotification(service, content, nowMs)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            service.startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            service.startForeground(NOTIFICATION_ID, notification)
        }
        maybePosted = true
        lastNotificationKey = content.notificationKey(enabled.value, foregroundRunning, nowMs)
    }

    @Synchronized
    fun stopForeground(service: Service) {
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
        foregroundRunning = false
        foregroundService = null
        maybePosted = false
        lastNotificationKey = null
        updateObservationMode()
        // A fresh relayed reading can still be displayed when the phone takes over BLE.
        refresh(service)
    }

    /** Also called by the existing stale-complication alarm, including after a process restart. */
    @Synchronized
    fun refresh(context: Context) {
        val app = context.applicationContext
        // The disabled relay case does not even derive display content or touch notification IPC
        // after the existing card has been removed once.
        if (!foregroundRunning && !enabled.value) {
            cancelIfPosted(app)
            return
        }
        val nowMs = System.currentTimeMillis()
        val content = currentContent(nowMs)
        if (!foregroundRunning && !content.hasFreshObservation) {
            cancelIfPosted(app)
            return
        }
        val key = content.notificationKey(enabled.value, foregroundRunning, nowMs)
        if (maybePosted && key == lastNotificationKey) return
        ensureChannel(app)
        runCatching { manager(app).notify(NOTIFICATION_ID, buildNotification(app, content, nowMs)) }
            .onSuccess {
                maybePosted = true
                lastNotificationKey = key
            }
            .onFailure { BleLog.log("wear live update failed: ${it.message}") }
    }

    private fun cancelIfPosted(context: Context) {
        if (maybePosted) {
            manager(context).cancel(NOTIFICATION_ID)
            maybePosted = false
            lastNotificationKey = null
        }
    }

    private fun buildNotification(context: Context, content: WearLiveUpdateContent, nowMs: Long): Notification {
        val fresh = content.hasFreshObservation
        val pinned = enabled.value
        val openApp = openAppAction ?: PendingIntent.getActivity(
            context, 120, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ).also { openAppAction = it }
        val statusText = when (content.status) {
            WearLiveUpdateContent.Status.READING -> content.primaryText
            WearLiveUpdateContent.Status.VALUE_UNAVAILABLE -> content.value
            WearLiveUpdateContent.Status.WARMUP -> context.getString(R.string.sensor_warming_up)
            WearLiveUpdateContent.Status.SENSOR_ERROR -> context.getString(R.string.sensor_error)
            WearLiveUpdateContent.Status.UNAVAILABLE -> context.getString(R.string.sensor_unavailable)
        }
        val age = context.getString(R.string.wear_live_age, content.ageMinutes(nowMs))
        val detail = if (!pinned && fresh) {
            // The ordinary service notification uses the OS timestamp for age. It needs only
            // reading/status updates and one expiry check, not an app-owned minute ticker.
            if (content.status == WearLiveUpdateContent.Status.VALUE_UNAVAILABLE) context.getString(R.string.sensor_unavailable)
            else "${content.unit.label} · ${context.getString(R.string.wear_live_delta, content.delta, content.unit.label)}"
        } else if (content.status == WearLiveUpdateContent.Status.VALUE_UNAVAILABLE) {
            "${context.getString(R.string.sensor_unavailable)} · $age"
        } else if (fresh) {
            "${content.unit.label} · ${context.getString(R.string.wear_live_delta, content.delta, content.unit.label)} · $age"
        } else statusText
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(trendIcon(content))
            .setContentTitle(if (fresh) statusText else context.getString(R.string.wear_live_title))
            .setContentText(detail)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setLocalOnly(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setShowWhen(fresh)
            .setContentIntent(openApp)
            .setDeleteIntent(actionIntent(context, ACTION_UNPIN))
            .addAction(
                0,
                context.getString(if (pinned) R.string.wear_live_unpin else R.string.wear_live_pin),
                actionIntent(context, if (pinned) ACTION_UNPIN else ACTION_PIN),
            )

        if (fresh) {
            builder.setWhen(content.observedAtMs)
            if (!foregroundRunning) {
                // The OS removes a relayed card even if the process is killed or stays asleep.
                builder.setTimeoutAfter((content.observedAtMs + WEAR_GLUCOSE_DISPLAY_HOLD_MS - nowMs).coerceAtLeast(1L))
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && fresh && pinned) {
            // Wear OS 7 uses the same API. Standard style is supported; MetricStyle is not.
            builder.setRequestPromotedOngoing(true)
                .setShortCriticalText(content.chipText(nowMs))
        }
        if (pinned) {
            val icon = WearLiveUpdateIcon.create(content, nowMs)
            val ongoingStatus = Status.Builder().addPart("reading", Status.TextPart(statusText))
            if (fresh) {
                // Wear evaluates this timer itself, even when our process is asleep. Status timers
                // use elapsedRealtime, while readings arriving over the Data Layer use wall time.
                val readingElapsedRealtime = SystemClock.elapsedRealtime() -
                    (nowMs - content.observedAtMs).coerceAtLeast(0L)
                ongoingStatus.addTemplate("#reading# · #age#")
                    .addPart("age", Status.StopwatchPart(readingElapsedRealtime))
            } else {
                ongoingStatus.addTemplate("#reading#")
            }
            builder.setLargeIcon(icon)
            OngoingActivity.Builder(context, NOTIFICATION_ID, builder)
                .setStaticIcon(Icon.createWithBitmap(icon))
                .setTouchIntent(openApp)
                .setTitle(context.getString(R.string.wear_live_title))
                .setContentDescription("$statusText · $detail")
                .setStatus(ongoingStatus.build())
                .build()
                .apply(context)
        }
        return builder.build()
    }

    private fun trendIcon(content: WearLiveUpdateContent): Int = when (TrendArrowShape.resolvedTrend(content.reading?.trend)) {
        "FALLING_QUICKLY" -> R.drawable.ic_notification_trend_falling_quickly
        "FALLING" -> R.drawable.ic_notification_trend_falling
        "STABLE" -> R.drawable.ic_notification_trend_stable
        "RISING" -> R.drawable.ic_notification_trend_rising
        "RISING_QUICKLY" -> R.drawable.ic_notification_trend_rising_quickly
        else -> R.drawable.ic_launcher_monochrome
    }

    private fun actionIntent(context: Context, action: String): PendingIntent = actionIntents.getOrPut(action) {
        PendingIntent.getBroadcast(
            context, 120, Intent(context, WearLiveUpdatesReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel(context: Context) {
        if (channelCreated) return
        manager(context).createNotificationChannel(NotificationChannel(
            CHANNEL_ID, context.getString(R.string.wear_live_channel), NotificationManager.IMPORTANCE_LOW,
        ))
        channelCreated = true
    }

    private fun manager(context: Context): NotificationManager =
        context.getSystemService(NotificationManager::class.java)
}

/** Dismissal/unpin is remembered across new readings and process restarts until explicitly enabled. */
class WearLiveUpdatesReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WearLiveUpdatesNotifier.ACTION_UNPIN && intent.action != WearLiveUpdatesNotifier.ACTION_PIN) return
        LibreCR.init(context)
        WearLiveUpdatesNotifier.setEnabled(context, intent.action == WearLiveUpdatesNotifier.ACTION_PIN)
    }
}
