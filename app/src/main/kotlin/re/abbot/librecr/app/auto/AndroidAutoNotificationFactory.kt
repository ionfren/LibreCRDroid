package re.abbot.librecr.app.auto

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.text.format.DateFormat
import androidx.car.app.notification.CarAppExtender
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import re.abbot.librecr.app.MainActivity
import re.abbot.librecr.app.R
import re.abbot.librecr.app.widget.WidgetReadingStatus
import re.abbot.librecr.protocol.TrendArrowShape
import java.util.Date

/** Uses a normal incoming-message envelope, rather than an ongoing service notification. */
internal object AndroidAutoNotificationFactory {
    const val CHANNEL_ID = "librecr_android_auto_glucose_v2"
    internal const val LEGACY_CHANNEL_ID = "librecr_android_auto_glucose"

    fun ensureChannel(context: Context): NotificationChannel {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.getNotificationChannel(CHANNEL_ID)?.let { return it }
        val old = manager.getNotificationChannel(LEGACY_CHANNEL_ID)
        // Channel importance cannot be raised in place. Preserve explicit user choices, including
        // blocking, while migrating the original app-created LOW channel to the car-compatible one.
        val importance = when {
            old?.importance == NotificationManager.IMPORTANCE_NONE -> NotificationManager.IMPORTANCE_NONE
            old?.hasUserSetImportance() == true -> old.importance
            else -> NotificationManager.IMPORTANCE_HIGH
        }
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.auto_notification_channel), importance)
        channel.description = context.getString(R.string.auto_notification_channel_description)
        channel.setSound(old?.sound, old?.audioAttributes)
        channel.enableVibration(old?.shouldVibrate() == true)
        old?.vibrationPattern?.let { channel.vibrationPattern = it }
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
        return manager.getNotificationChannel(CHANNEL_ID) ?: channel
    }

    fun notificationsAllowed(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(CHANNEL_ID)
            ?: manager.getNotificationChannel(LEGACY_CHANNEL_ID)
        return NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            channel?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun build(context: Context, content: AutoGlucoseContent, session: AutoNotificationSession): NotificationCompat.Builder {
        val token = checkNotNull(session.token)
        val art = trendArtwork(content.presentation.trend)
        val sender = Person.Builder().setKey("librecr_cgm")
            .setName(content.headline).setImportant(true).setIcon(IconCompat.createWithBitmap(art)).build()
        val self = Person.Builder().setKey("librecr_user").setName(context.getString(R.string.widget_brand)).build()
        val body = contentText(context, content)
        val style = NotificationCompat.MessagingStyle(self)
            .setConversationTitle(content.headline).setGroupConversation(false)
            .addMessage(body, content.eventAtMs, sender)
        val refresh = NotificationCompat.Action.Builder(
            R.drawable.ic_notification_glucose, context.getString(R.string.auto_notification_refresh),
            actionIntent(context, AndroidAutoGlucoseNotifications.ACTION_REFRESH, token, mutable = true),
        ).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false).setAllowGeneratedReplies(false)
            .addRemoteInput(RemoteInput.Builder("auto_glucose_refresh")
                .setLabel(context.getString(R.string.auto_notification_refresh)).build()).build()
        val dismiss = NotificationCompat.Action.Builder(
            R.drawable.ic_notification_glucose, context.getString(R.string.auto_notification_dismiss),
            actionIntent(context, AndroidAutoGlucoseNotifications.ACTION_DISMISS, token),
        ).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false).build()
        val importance = if (session.mayShowInitialHeadsUp) NotificationManager.IMPORTANCE_HIGH
            else NotificationManager.IMPORTANCE_DEFAULT
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_glucose)
            .setContentTitle(context.getString(R.string.widget_brand))
            .setContentText(context.getString(R.string.auto_notification_private))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setSilent(true).build()
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_glucose).setLargeIcon(art)
            .setContentTitle(content.headline).setContentText(body).setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(publicVersion)
            // Both transport timestamps must belong to this drive. Measurement age/time is shown
            // explicitly in body, never replaced with the delivery timestamp.
            .setWhen(content.eventAtMs).setShowWhen(false)
            .setOngoing(false).setAutoCancel(false).setOnlyAlertOnce(true).setSilent(true)
            .setContentIntent(PendingIntent.getActivity(context, 130,
                Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setDeleteIntent(actionIntent(context, AndroidAutoGlucoseNotifications.ACTION_DISMISS, token))
            .extend(CarAppExtender.Builder().setImportance(importance).build())
            .addInvisibleAction(refresh).addInvisibleAction(dismiss)
            .addAction(R.drawable.ic_notification_glucose, context.getString(R.string.auto_notification_disable),
                actionIntent(context, AndroidAutoGlucoseNotifications.ACTION_DISABLE, token))
    }

    private fun actionIntent(context: Context, action: String, token: String, mutable: Boolean = false): PendingIntent {
        val intent = Intent(context, AndroidAutoNotificationActionService::class.java)
            .setAction(action).putExtra(AndroidAutoGlucoseNotifications.EXTRA_SESSION, token)
            .setData(Uri.Builder().scheme("librecr").authority("android-auto")
                .appendPath(token).appendPath(action).build())
        val mutability = if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getService(context, 130, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutability)
    }

    private fun contentText(context: Context, content: AutoGlucoseContent): String {
        val status = when (content.presentation.status) {
            WidgetReadingStatus.CURRENT -> null
            WidgetReadingStatus.STALE -> R.string.widget_stale
            WidgetReadingStatus.WARMUP -> R.string.sensor_warming_up
            WidgetReadingStatus.SENSOR_ERROR -> R.string.sensor_error
            else -> R.string.standby_no_reading
        }?.let(context::getString)
        val seconds = content.ageSeconds
        val age = when {
            seconds == null -> context.getString(R.string.age_no_reading)
            seconds < 5L -> context.getString(R.string.age_now)
            seconds < 60L -> context.getString(R.string.age_seconds, seconds.toInt())
            seconds < 3600L -> context.getString(R.string.age_minutes, (seconds / 60L).toInt())
            else -> context.getString(R.string.age_hours, (seconds / 3600L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
        val measuredTime = content.presentation.measuredAtMs?.let { DateFormat.getTimeFormat(context).format(Date(it)) }
        return listOfNotNull(status, age, measuredTime).joinToString(" · ")
    }

    private fun trendArtwork(trend: String?): Bitmap = createBitmap(128, 128).also { bitmap ->
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(11, 73, 107) }
        canvas.drawRoundRect(0f, 0f, 128f, 128f, 36f, 36f, paint)
        paint.color = Color.WHITE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 128 * TrendArrowShape.STROKE_FRACTION
        val rotation = TrendArrowShape.rotationDegrees(trend)
        if (rotation == null) canvas.drawLine(40f, 64f, 88f, 64f, paint)
        else {
            canvas.scale(TrendArrowShape.SCALE, TrendArrowShape.SCALE, 64f, 64f)
            canvas.rotate(rotation, 64f, 64f)
            TrendArrowShape.SEGMENTS.forEach { canvas.drawLine(it.x0 * 128, it.y0 * 128, it.x1 * 128, it.y1 * 128, paint) }
        }
    }
}
