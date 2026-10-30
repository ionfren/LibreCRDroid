package re.abbot.librecr.app.live

import android.annotation.TargetApi
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import re.abbot.librecr.app.MainActivity
import re.abbot.librecr.app.R
import re.abbot.librecr.app.GLUCOSE_STALE_AFTER_MS
import re.abbot.librecr.app.isFreshGlucose
import re.abbot.librecr.app.data.GlucoseUnit
import re.abbot.librecr.app.data.ImportedSession
import re.abbot.librecr.app.data.LiveUpdateSettings
import re.abbot.librecr.app.data.SensorStateStore
import re.abbot.librecr.protocol.TrendArrowShape
import re.abbot.librecr.protocol.dataplane.SensorLifecycle

object LiveUpdatesNotifier {
    const val CHANNEL_ID = "librecr_live_updates"
    private const val NOTIFICATION_ID = 120
    private const val REQUEST_PROMOTED_ONGOING_EXTRA = "android.requestPromotedOngoing"

    data class State(
        val settings: LiveUpdateSettings,
        val unit: GlucoseUnit,
        val reading: SensorStateStore.LastGlucose?,
        val lifecycle: SensorStateStore.SensorLifecycleSnapshot?,
        val session: ImportedSession?,
        /** When the live glucose state is unavailable: its timestamp; null when readings are good. */
        val unavailableAtMs: Long? = null,
    )

    /**
     * True when our notification may currently be posted. Starts true so a notification that
     * survived a process restart still gets one cancel; afterwards, the 30s ticker stops issuing
     * a redundant cancel() binder call on every tick while disabled/stale (e.g., all night).
     */
    @Volatile private var maybePosted = true

    fun update(
        context: Context,
        state: State,
    ) {
        val app = context.applicationContext
        val settings = state.settings
        // A non-numeric packet is still a new observation, with its own timestamp and live card.
        val unavailable = settings.enabled && state.unavailableAtMs?.let { isFreshGlucose(it) } == true
        if (unavailable) {
            ensureChannel(app)
            maybePosted = true
            notificationManager(app).notify(NOTIFICATION_ID, buildNotification(app, state.copy(reading = null)))
            return
        }
        val reading = state.reading?.takeIf { state.unavailableAtMs == null && isFreshGlucose(it.receivedAtMs) }
        if (!settings.enabled || reading == null) {
            if (maybePosted) {
                maybePosted = false
                notificationManager(app).cancel(NOTIFICATION_ID)
            }
            return
        }
        val displayState = state.copy(reading = reading)
        ensureChannel(app)
        maybePosted = true
        notificationManager(app).notify(
            NOTIFICATION_ID,
            buildNotification(app, displayState),
        )
    }

    fun canPostPromotedNotifications(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return null
        return notificationManager(context.applicationContext).canPostPromotedNotifications()
    }

    fun notificationSettingsIntent(context: Context): Intent {
        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS
        } else {
            Settings.ACTION_APP_NOTIFICATION_SETTINGS
        }
        return notificationSettingsIntent(context, action)
    }

    fun openNotificationSettings(context: Context) {
        runCatching { context.startActivity(notificationSettingsIntent(context)) }
            .onFailure {
                context.startActivity(notificationSettingsIntent(context, Settings.ACTION_APP_NOTIFICATION_SETTINGS))
            }
    }

    private fun notificationSettingsIntent(context: Context, action: String): Intent {
        return Intent(action)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun buildNotification(
        context: Context,
        state: State,
    ): Notification {
        val settings = state.settings
        val reading = state.reading
        val sensorProgress = sensorProgress(state)
        val contentIntent = PendingIntent.getActivity(
            context,
            120,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val usesMetricStyle = Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN
        val observedAtMs = state.unavailableAtMs ?: requireNotNull(reading).receivedAtMs
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(
                if (usesMetricStyle) null else primaryText(state),
            )
            .setContentText(
                if (usesMetricStyle) metricSecondaryText(state) else secondaryText(context, state),
            )
            .setCategory(Notification.CATEGORY_STATUS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .setWhen(observedAtMs)
            .setShowWhen(true)
            .setTimeoutAfter((observedAtMs + GLUCOSE_STALE_AFTER_MS - System.currentTimeMillis()).coerceAtLeast(1L))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(SENSOR_ACTIVE_COLOR)
            .setContentIntent(contentIntent)

        if (!usesMetricStyle) {
            val sensorLabel = sensorProgress.label(context)
            builder
                .setSubText(sensorLabel)
                .setSettingsText(sensorLabel)
        } else {
            // Match Google Clock: make the MetricStyle card use the notification's dynamic
            // small icon instead of the application's launcher icon.
            builder.addExtras(
                Bundle().apply {
                    putBoolean(NotificationCompat.EXTRA_PREFER_SMALL_ICON, true)
                },
            )
        }

        val trendIconRes = trendNotificationIconRes(reading?.trend)
        val useTrendNotificationIcon = trendIconRes != null &&
            (
                usesMetricStyle ||
                    (settings.statusChipEnabled && settings.showTrendInChip)
            )
        if (useTrendNotificationIcon) {
            builder.setSmallIcon(requireNotNull(trendIconRes))
            if (!usesMetricStyle) {
                builder.setLargeIcon(
                    Icon.createWithBitmap(trendBadgeBitmap(context, reading?.trend)),
                )
            }
        } else {
            builder.setSmallIcon(R.drawable.ic_launcher_monochrome)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            applyLiveUpdateStyle(context, builder, state, sensorProgress)
        }
        return builder.build()
    }

    @TargetApi(Build.VERSION_CODES.BAKLAVA)
    private fun applyLiveUpdateStyle(
        context: Context,
        builder: NotificationCompat.Builder,
        state: State,
        sensorProgress: SensorProgress,
    ) {
        val settings = state.settings
        val reading = state.reading
        if (settings.statusChipEnabled) {
            builder.setShortCriticalText(reading?.let { LiveUpdateFormatter.chipText(it, state.unit, settings) } ?: "NA")
        }
        if (settings.showOnHomeScreen) {
            builder.addExtras(Bundle().apply { putBoolean(REQUEST_PROMOTED_ONGOING_EXTRA, true) })
            requestPromotedOngoing(builder)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            builder.setStyle(metricStyle(context, state))
        } else {
            val max = sensorProgress.totalMinutes
            val elapsed = sensorProgress.elapsedMinutes
            if (max != null) {
                builder.setProgress(max, elapsed.coerceIn(0, max), false)
            } else {
                builder.setProgress(0, 0, true)
            }

            val style = NotificationCompat.ProgressStyle()
                .setStyledByProgress(false)

            if (max != null) {
                style
                    .setProgress(elapsed.coerceIn(0, max))
                    .setProgressSegments(sensorProgressSegments(sensorProgress))
                    .setProgressTrackerIcon(
                        IconCompat.createWithBitmap(sensorProgressMarkerBitmap(context, sensorProgress)),
                    )
            } else {
                style.setProgressIndeterminate(true)
            }
            builder.setStyle(style)
        }
    }

    @TargetApi(Build.VERSION_CODES.CINNAMON_BUN)
    private fun metricStyle(
        context: Context,
        state: State,
    ): NotificationCompat.MetricStyle {
        val reading = state.reading
        val glucoseValue: NotificationCompat.Metric.MetricValue = when {
            reading == null -> NotificationCompat.Metric.FixedText("NA")
            state.unit == GlucoseUnit.MG_DL -> NotificationCompat.Metric.FixedInt(reading.mgDL)
            else -> NotificationCompat.Metric.FixedFloat(
                reading.mgDL / 18f,
                null,
                1,
                1,
            )
        }
        return NotificationCompat.MetricStyle()
            .addMetric(
                NotificationCompat.Metric(
                    glucoseValue,
                    (if (reading == null) secondaryText(context, state) else metricSecondaryText(state)).ifBlank {
                        context.getString(R.string.title_app)
                    },
                ),
            )
            .setCriticalMetric(GLUCOSE_METRIC_INDEX)
    }

    private fun requestPromotedOngoing(builder: NotificationCompat.Builder) {
        builder.setRequestPromotedOngoing(true)
    }

    private fun primaryText(state: State): CharSequence {
        val trend = state.reading
            ?.let { LiveUpdateFormatter.trendSymbol(it.trend) }
            .takeIf {
                state.settings.statusChipEnabled && state.settings.showTrendInChip
            }
        val value = state.reading?.let { state.unit.formatWithUnit(it.mgDL) } ?: "NA"
        val text = listOfNotNull(trend, value.takeIf { it.isNotBlank() }).joinToString(" ")
        if (text.isBlank()) return text

        return SpannableString(text).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(PRIMARY_TEXT_SCALE), 0, length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
            if (trend != null) {
                setSpan(
                    RelativeSizeSpan(PRIMARY_TREND_SCALE),
                    0,
                    trend.length,
                    Spanned.SPAN_INCLUSIVE_EXCLUSIVE,
                )
            }
        }
    }

    private fun metricSecondaryText(state: State): String {
        return state.reading?.let { LiveUpdateFormatter.deltaText(it, state.unit) }
            ?.let { "$it ${state.unit.label}" }
            .orEmpty()
    }

    private fun secondaryText(
        context: Context,
        state: State,
    ): String = if (state.reading == null) {
        context.getString(if (sensorProgress(state).isWarmingUp) R.string.sensor_warming_up else R.string.standby_no_reading)
    } else listOfNotNull(
        state.reading
            ?.let { trendLabel(context, it.trend) }
            .takeIf { state.settings.showTrendOnLockScreen },
        state.reading?.let { LiveUpdateFormatter.deltaText(it, state.unit) }.takeIf { state.settings.showDeltaOnLockScreen },
    ).joinToString(" • ")

    private fun sensorProgress(state: State): SensorProgress {
        val now = System.currentTimeMillis()
        val reading = state.reading
        val lifecycle = state.lifecycle ?: reading?.let {
            SensorStateStore.SensorLifecycleSnapshot(it.lifeCount, it.receivedAtMs)
        }
        val elapsed = lifecycle?.let { extrapolatedLifeCountMinutes(it, now) } ?: 0
        val warmup = state.session?.warmupMinutes ?: SensorLifecycle.DEFAULT_WARMUP_DURATION_MINUTES
        return SensorProgress(
            elapsedMinutes = elapsed,
            totalMinutes = state.session?.wearMinutes?.takeIf { it > 0 },
            warmupMinutes = warmup.coerceAtLeast(0),
        )
    }

    private fun extrapolatedLifeCountMinutes(
        snapshot: SensorStateStore.SensorLifecycleSnapshot,
        nowMs: Long,
    ): Int {
        val observedAt = snapshot.observedAtMs.takeIf { it > 0L } ?: return snapshot.lifeCountMinutes
        val extraMinutes = ((nowMs - observedAt).coerceAtLeast(0L) / 60_000L).toInt()
        return (snapshot.lifeCountMinutes + extraMinutes).coerceAtLeast(0)
    }

    @TargetApi(Build.VERSION_CODES.BAKLAVA)
    private fun sensorProgressSegments(progress: SensorProgress): List<NotificationCompat.ProgressStyle.Segment> {
        val max = progress.totalMinutes ?: return emptyList()
        return listOf(NotificationCompat.ProgressStyle.Segment(max).setColor(progress.color))
    }

    private data class SensorProgress(
        val elapsedMinutes: Int,
        val totalMinutes: Int?,
        val warmupMinutes: Int,
    ) {
        val isWarmingUp: Boolean get() = elapsedMinutes < warmupMinutes

        val color: Int
            get() = when {
                totalMinutes != null && elapsedMinutes >= totalMinutes -> SENSOR_EXPIRED_COLOR
                isWarmingUp -> SENSOR_WARMUP_COLOR
                else -> SENSOR_ACTIVE_COLOR
            }

        fun label(context: Context): String? {
            val total = totalMinutes ?: return null
            val remaining = (total - elapsedMinutes).coerceAtLeast(0)
            return if (elapsedMinutes >= total) {
                context.getString(R.string.sensor_expired)
            } else {
                context.getString(R.string.sensor_until_finish, formatDuration(context, remaining))
            }
        }
    }

    private fun formatDuration(context: Context, minutes: Int): String {
        val safe = minutes.coerceAtLeast(0)
        val days = safe / (24 * 60)
        val hours = (safe % (24 * 60)) / 60
        val mins = safe % 60
        return when {
            days > 0 && hours > 0 -> context.getString(R.string.duration_days_hours, days, hours)
            days > 0 -> context.getString(R.string.duration_days, days)
            hours > 0 && mins > 0 -> context.getString(R.string.duration_hours_minutes, hours, mins)
            hours > 0 -> context.getString(R.string.duration_hours, hours)
            else -> context.getString(R.string.duration_minutes, mins)
        }
    }

    private fun trendLabel(context: Context, trend: String?): String = context.getString(
        when (TrendArrowShape.resolvedTrend(trend)) {
            "FALLING_QUICKLY" -> R.string.trend_falling_quickly
            "FALLING" -> R.string.trend_falling
            "STABLE" -> R.string.trend_stable
            "RISING" -> R.string.trend_rising
            "RISING_QUICKLY" -> R.string.trend_rising_quickly
            else -> R.string.trend_unknown
        },
    )

    // Cache the drawn bitmaps: buildNotification runs every ~30s (live "age"/progress tick) but the
    // trend badge only changes with the trend, and the progress marker only with its color. Keys are
    // small and bounded (≤6 trends, ≤3 colors). update() is called from a single collector.
    private val trendBadgeCache = HashMap<String, Bitmap>()
    private val progressMarkerCache = HashMap<Int, Bitmap>()

    private fun trendBadgeBitmap(context: Context, trend: String?): Bitmap =
        trendBadgeCache.getOrPut(trend.orEmpty()) { createTrendBadgeBitmap(context, trend) }

    private fun sensorProgressMarkerBitmap(context: Context, progress: SensorProgress): Bitmap =
        progressMarkerCache.getOrPut(progress.color) { createSensorProgressMarkerBitmap(context, progress) }

    private fun trendNotificationIconRes(trend: String?): Int? =
        when (TrendArrowShape.resolvedTrend(trend)) {
            "FALLING_QUICKLY" -> R.drawable.ic_notification_trend_falling_quickly
            "FALLING" -> R.drawable.ic_notification_trend_falling
            "STABLE" -> R.drawable.ic_notification_trend_stable
            "RISING" -> R.drawable.ic_notification_trend_rising
            "RISING_QUICKLY" -> R.drawable.ic_notification_trend_rising_quickly
            else -> null
        }

    private fun createTrendBadgeBitmap(context: Context, trend: String?): Bitmap {
        val size = (72f * context.resources.displayMetrics.density).toInt().coerceAtLeast(72)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1E9EAA.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawCircle(size / 2f, size / 2f, size * 0.47f, circlePaint)
        val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = size * 0.115f
        }
        val rotation = TrendArrowShape.rotationDegrees(trend) ?: 0f
        canvas.save()
        canvas.scale(TrendArrowShape.SCALE * 1.12f, TrendArrowShape.SCALE * 1.12f, size / 2f, size / 2f)
        canvas.rotate(rotation, size / 2f, size / 2f)
        TrendArrowShape.SEGMENTS.forEach { segment ->
            canvas.drawLine(
                segment.x0 * size,
                segment.y0 * size,
                segment.x1 * size,
                segment.y1 * size,
                arrowPaint,
            )
        }
        canvas.restore()
        return bitmap
    }

    private fun createSensorProgressMarkerBitmap(context: Context, progress: SensorProgress): Bitmap {
        val size = (20f * context.resources.displayMetrics.density).toInt().coerceAtLeast(24)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = progress.color
            style = Paint.Style.FILL
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        canvas.drawCircle(size / 2f, size / 2f, size * 0.28f, fill)
        canvas.drawCircle(size / 2f, size / 2f, size * 0.28f, stroke)
        return bitmap
    }

    private fun notificationManager(context: Context): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureChannel(context: Context) {
        val manager = notificationManager(context)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.live_updates_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.live_updates_channel_desc)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            },
        )
    }

    private val SENSOR_WARMUP_COLOR = 0xFF64B5F6.toInt()
    private val SENSOR_ACTIVE_COLOR = 0xFF00A676.toInt()
    private val SENSOR_EXPIRED_COLOR = 0xFFFFB454.toInt()
    private const val GLUCOSE_METRIC_INDEX = 0
    private const val PRIMARY_TEXT_SCALE = 1.28f
    private const val PRIMARY_TREND_SCALE = 1.48f
}
