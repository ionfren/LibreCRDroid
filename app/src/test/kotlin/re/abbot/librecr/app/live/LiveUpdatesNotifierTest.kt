package re.abbot.librecr.app.live

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import re.abbot.librecr.app.GLUCOSE_STALE_AFTER_MS
import re.abbot.librecr.app.data.GlucoseUnit
import re.abbot.librecr.app.data.LiveUpdateSettings
import re.abbot.librecr.app.data.SensorStateStore

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35, 36])
class LiveUpdatesNotifierTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private lateinit var state: LiveUpdatesNotifier.State

    @Before
    fun setUp() {
        manager.cancelAll()
        val now = System.currentTimeMillis()
        state = LiveUpdatesNotifier.State(
            settings = LiveUpdateSettings(enabled = true),
            unit = GlucoseUnit.MG_DL,
            reading = SensorStateStore.LastGlucose(100, 120, "RISING", now - 60_000L, 2.0),
            lifecycle = SensorStateStore.SensorLifecycleSnapshot(100, now),
            session = null,
        )
    }

    @Test
    fun `NA replaces the number in the same ongoing notification and retains the packet timestamp`() {
        LiveUpdatesNotifier.update(context, state)
        val first = manager.activeNotifications.single()
        assertTrue(first.notification.title().contains("120"))

        val unavailableAt = System.currentTimeMillis()
        LiveUpdatesNotifier.update(context, state.copy(unavailableAtMs = unavailableAt))
        val unavailable = manager.activeNotifications.single()
        assertEquals(first.id, unavailable.id)
        assertEquals("NA", unavailable.notification.title())
        assertEquals(unavailableAt, unavailable.notification.`when`)
        assertTrue(unavailable.notification.extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
        assertTrue(unavailable.notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertFalse(unavailable.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("+2"))
        if (Build.VERSION.SDK_INT >= 36) {
            assertEquals("NA", unavailable.notification.extras.getString("android.shortCriticalText"))
            // Robolectric's API 36 image uses an older preview of the promotion criteria.
            assertTrue(NotificationCompat.isRequestPromotedOngoing(unavailable.notification))
            assertEquals("android.app.Notification\$ProgressStyle",
                unavailable.notification.extras.getString(Notification.EXTRA_TEMPLATE))
        }

        val nextUnavailableAt = unavailableAt + 60_000L
        LiveUpdatesNotifier.update(context, state.copy(unavailableAtMs = nextUnavailableAt))
        assertEquals(nextUnavailableAt, manager.activeNotifications.single().notification.`when`)

        LiveUpdatesNotifier.update(context, state.copy(reading = state.reading!!.copy(mgDL = 126,
            lifeCount = 103, receivedAtMs = nextUnavailableAt)))
        val recovered = manager.activeNotifications.single().notification
        assertTrue(recovered.title().contains("126"))
        assertEquals(nextUnavailableAt, recovered.`when`)
    }

    @Test
    fun `NA is independent of glucose units and unavailable packets do not need a previous number`() {
        LiveUpdatesNotifier.update(context, state.copy(reading = null, unit = GlucoseUnit.MMOL_L,
            unavailableAtMs = System.currentTimeMillis()))
        assertEquals("NA", manager.activeNotifications.single().notification.title())
    }

    @Test
    fun `expired or disabled NA removes the card without resurrecting a cached number`() {
        LiveUpdatesNotifier.update(context, state.copy(unavailableAtMs = System.currentTimeMillis()))
        LiveUpdatesNotifier.update(context, state.copy(
            unavailableAtMs = System.currentTimeMillis() - GLUCOSE_STALE_AFTER_MS))
        assertTrue(manager.activeNotifications.isEmpty())

        LiveUpdatesNotifier.update(context, state.copy(unavailableAtMs = System.currentTimeMillis()))
        LiveUpdatesNotifier.update(context, state.copy(settings = state.settings.copy(enabled = false),
            unavailableAtMs = System.currentTimeMillis()))
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test
    fun `metric style uses text NA as its critical metric without retaining the old number`() {
        // Build the compat model on the available JVM SDK; API 37 renders this same model.
        val style = ReflectionHelpers.callInstanceMethod<NotificationCompat.MetricStyle>(
            LiveUpdatesNotifier, "metricStyle",
            ReflectionHelpers.ClassParameter.from(Context::class.java, context),
            ReflectionHelpers.ClassParameter.from(LiveUpdatesNotifier.State::class.java,
                state.copy(reading = null, unavailableAtMs = System.currentTimeMillis())),
        )
        assertEquals(1, style.metrics.size)
        assertEquals(NotificationCompat.Metric.FixedText("NA"), style.criticalMetric!!.value)
    }

    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE).toString()
}
