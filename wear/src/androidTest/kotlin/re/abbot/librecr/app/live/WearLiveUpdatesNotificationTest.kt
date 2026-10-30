package re.abbot.librecr.app.live

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.core.graphics.drawable.toBitmap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.wear.ongoing.OngoingActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import re.abbot.librecr.app.LibreCR
import re.abbot.librecr.app.data.SensorStateStore
import re.abbot.librecr.app.data.WearAppearanceSettings
import re.abbot.librecr.app.wear.WearDataSync
import java.io.File

/** Runs against the actual notification manager, including promotion flags and dismissal. */
class WearLiveUpdatesNotificationTest {
    @Test
    fun relayedReadingsUpdateOnePromotableCardAndRespectUnpinAndExpiry() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS",
            ).close()
        }
        LibreCR.init(context)
        runBlocking {
            LibreCR.store.clearSession()
            LibreCR.appearance.save(WearAppearanceSettings())
        }
        LibreCR.manager.clearSensorStatus()
        WearLiveUpdatesNotifier.setEnabled(context, true)
        val manager = context.getSystemService(NotificationManager::class.java)
        val now = System.currentTimeMillis()
        // The next elapsed minute arrives in two seconds, without sending another sensor packet.
        val first = SensorStateStore.LastGlucose(20_000, 120, "RISING", now - 58_000L, 2.0)
        LibreCR.manager.acceptRemoteGlucose(first)
        await { manager.activeNotifications.any { it.id == 1 && it.notification.title() == "↗ 120 Δ +2" } }
        val notification = manager.activeNotifications.single { it.id == 1 }.notification
        assertNotNull(notification.smallIcon)
        assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("0 min"))
        assertTrue(notification.timeoutAfter in 1L..330_000L)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        if (Build.VERSION.SDK_INT >= 36) assertTrue(notification.hasPromotableCharacteristics())
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
        if (Build.VERSION.SDK_INT >= 36) {
            assertEquals("120  ·  +2 · 0′", notification.extras.getString("android.shortCriticalText"))
        }
        val status = requireNotNull(OngoingActivity.recoverOngoingActivity(context)?.status)
        val elapsedNow = SystemClock.elapsedRealtime()
        val timeNow = status.getText(context, elapsedNow).toString()
        val timeLater = status.getText(context, elapsedNow + 65_000L).toString()
        assertTrue(timeNow.startsWith("↗ 120 Δ +2 · "))
        assertFalse("Wear must advance the age without the app posting again", timeNow == timeLater)
        assertTrue(status.getNextChangeTimeMillis(elapsedNow) < Long.MAX_VALUE)
        val bitmap = requireNotNull(notification.getLargeIcon().loadDrawable(context)).toBitmap()
        assertEquals(96, bitmap.width)
        assertEquals(96, bitmap.height)
        File(context.cacheDir, "wear-live-icon.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        await {
            manager.activeNotifications.any {
                it.id == 1 && it.notification.extras.getCharSequence(Notification.EXTRA_TEXT)
                    .toString().endsWith("1 min")
            }
        }
        val aged = manager.activeNotifications.single { it.id == 1 }.notification
        assertEquals(first.receivedAtMs, aged.`when`)
        if (Build.VERSION.SDK_INT >= 36) {
            assertEquals("120  ·  +2 · 1′", aged.extras.getString("android.shortCriticalText"))
        }

        // The action's real PendingIntent must persist opt-out; subsequent readings cannot repin it.
        notification.actions.single().actionIntent.send()
        await { !WearLiveUpdatesNotifier.enabled.value && manager.activeNotifications.none { it.id == 1 } }
        assertFalse(context.getSharedPreferences("wear_live_updates", Context.MODE_PRIVATE).getBoolean("enabled", true))
        LibreCR.manager.acceptRemoteGlucose(first.copy(lifeCount = 20_001, mgDL = 122))
        Thread.sleep(250L)
        assertTrue(manager.activeNotifications.none { it.id == 1 })

        WearLiveUpdatesNotifier.setEnabled(context, true)
        await { manager.activeNotifications.any { it.id == 1 && it.notification.title() == "↗ 122 Δ +2" } }
        // A non-numeric live packet must hide the cached valid reading immediately.
        LibreCR.manager.acceptRemoteGlucoseUnavailable(WearDataSync.GlucoseUnavailable(
            lifeCount = 20_002, trend = "UNKNOWN", receivedAtMs = now, reason = "test", detail = "",
        ))
        await { manager.activeNotifications.any { it.id == 1 && it.notification.title() == "NA" } }
        val unavailable = manager.activeNotifications.single { it.id == 1 }.notification
        assertEquals(now, unavailable.`when`)
        assertTrue(unavailable.extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
        assertTrue(unavailable.timeoutAfter in 1L..330_000L)
        if (Build.VERSION.SDK_INT >= 36) {
            assertEquals("NA · 0′", unavailable.extras.getString("android.shortCriticalText"))
            assertTrue(unavailable.hasPromotableCharacteristics())
        }
        val naStatus = requireNotNull(OngoingActivity.recoverOngoingActivity(context)?.status)
        val naNow = SystemClock.elapsedRealtime()
        assertTrue(naStatus.getText(context, naNow).toString().startsWith("NA · "))
        assertFalse(naStatus.getText(context, naNow).toString() ==
            naStatus.getText(context, naNow + 65_000L).toString())
        LibreCR.manager.acceptRemoteGlucose(first.copy(lifeCount = 20_003, receivedAtMs = now - 330_001L))
        Thread.sleep(250L)
        assertTrue(manager.activeNotifications.none { it.id == 1 })

        // Leave a fresh sample for manual inspection of the round-screen notification and icon.
        LibreCR.manager.acceptRemoteGlucose(first.copy(lifeCount = 20_004, receivedAtMs = System.currentTimeMillis()))
        await { manager.activeNotifications.any { it.id == 1 } }
        assertEquals(1, manager.activeNotifications.count { it.id == 1 })
    }

    private fun Notification.title(): String = extras.getCharSequence(Notification.EXTRA_TITLE).toString()

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Notification state did not arrive within 10 seconds" }
            Thread.sleep(50L)
        }
    }
}
