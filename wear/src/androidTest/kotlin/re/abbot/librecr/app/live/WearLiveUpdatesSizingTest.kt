package re.abbot.librecr.app.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import androidx.annotation.RequiresApi
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import re.abbot.librecr.app.MainActivity
import re.abbot.librecr.app.R
import java.io.File

/** Sizing probe for an emulator only; never inject simulated readings into the sensor manager. */
@RequiresApi(37)
class WearLiveUpdatesSizingTest {
    @Test
    fun compareRequestedFontSizesWithNativeChipAndPreRenderedChip() {
        assumeTrue("Sizing probes with sample data must run on an emulator", Build.FINGERPRINT.contains("generic") ||
            Build.MODEL.startsWith("sdk_") || Build.MODEL.contains("Emulator"))
        assumeTrue(Build.VERSION.SDK_INT >= 37)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val ui = instrumentation.uiAutomation
        fun shell(command: String) {
            ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command)).use { it.readBytes() }
        }
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        shell("input keyevent KEYCODE_WAKEUP")

        val text = "→ 120 +2 0′"
        val forcedSizes = SpannableString(text).apply {
            setSpan(AbsoluteSizeSpan(28, true), 2, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(AbsoluteSizeSpan(14, true), 6, 8, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(AbsoluteSizeSpan(11, true), 9, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(1.5f), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), 2, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val channel = "wear_size_probe"
        val id = 1977
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(channel, "Sizing probe", NotificationManager.IMPORTANCE_LOW))
        val openApp = PendingIntent.getActivity(context, id, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun builder() = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification_trend_stable)
            .setContentTitle(forcedSizes)
            .setContentText("Sizing probe: glucose 28dp, delta 14dp, age 11dp")
            .setOngoing(true).setOnlyAlertOnce(true).setLocalOnly(true)
            .setCategory(Notification.CATEGORY_STATUS).setShowWhen(false)
            .setContentIntent(openApp).setRequestPromotedOngoing(true)

        val normal = builder().setShortCriticalText("120 +2 0′").build()
        val sanitizedTitle = normal.extras.getCharSequence(Notification.EXTRA_TITLE) as Spanned
        assertEquals(text, sanitizedTitle.toString())
        assertEquals(0, sanitizedTitle.getSpans(0, sanitizedTitle.length, AbsoluteSizeSpan::class.java).size)
        assertEquals(0, sanitizedTitle.getSpans(0, sanitizedTitle.length, RelativeSizeSpan::class.java).size)
        assertTrue(sanitizedTitle.getSpans(0, sanitizedTitle.length, StyleSpan::class.java).isNotEmpty())

        // The new compact-metric API accepts CharSequence, but deliberately converts it to String.
        val metric = Notification.Metric.FixedText(forcedSizes)
        assertFalse((metric.value as CharSequence) is Spanned)
        assertEquals(text, metric.value)

        // Bypassing the String setter is not a workaround: the framework cannot read this value.
        val invalid = builder().addExtras(Bundle().apply {
            putCharSequence("android.shortCriticalText", forcedSizes)
        }).build()
        assertNull(invalid.shortCriticalText)

        fun save(bitmap: Bitmap, name: String) {
            File(context.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val bitmap = Bitmap.createBitmap(480, 96, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        }
        paint.textSize = 70f
        canvas.drawText("→", 0f, 74f, paint)
        paint.textSize = 86f
        canvas.drawText("120", 85f, 78f, paint)
        paint.textSize = 52f
        canvas.drawText("+2", 270f, 76f, paint)
        paint.textSize = 40f
        canvas.drawText("0′", 390f, 76f, paint)
        save(bitmap, "wear-size-requested-image.png")
        val forcedBitmap = builder().setSmallIcon(Icon.createWithBitmap(bitmap))
            .setShortCriticalText("").build()
        assertTrue(normal.hasPromotableCharacteristics())
        assertTrue(forcedBitmap.hasPromotableCharacteristics())
        try {
            manager.notify(id, normal)
            Thread.sleep(1_500L)
            save(requireNotNull(ui.takeScreenshot()), "wear-size-native-screen.png")
            manager.notify(id, forcedBitmap)
            Thread.sleep(1_500L)
            save(requireNotNull(ui.takeScreenshot()), "wear-size-bitmap-screen.png")
        } finally {
            manager.cancel(id)
        }
    }
}
