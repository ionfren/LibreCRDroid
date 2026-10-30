package re.abbot.librecr.app.auto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.car.app.notification.CarAppExtender
import androidx.core.app.NotificationCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import re.abbot.librecr.app.R
import re.abbot.librecr.app.data.SensorStateStore
import re.abbot.librecr.app.widget.GlucoseWidgetInput

/** Tests the real Android envelope and its serialized messaging actions, not only string policy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidAutoNotificationFactoryTest {
    private lateinit var context: Context
    private lateinit var manager: NotificationManager
    private val measuredAt = 10_000_000L
    private val eventAt = measuredAt + 120_000L
    private val session = AutoNotificationSession("drive")

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        manager = context.getSystemService(NotificationManager::class.java)
    }

    @Test
    fun `new channel is high importance with no sound or vibration`() {
        val channel = AndroidAutoNotificationFactory.ensureChannel(context)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
    }

    @Test
    fun `installed low channel migrates because importance cannot be raised in place`() {
        manager.createNotificationChannel(NotificationChannel(AndroidAutoNotificationFactory.LEGACY_CHANNEL_ID,
            "old", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) })
        val migrated = AndroidAutoNotificationFactory.ensureChannel(context)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, migrated.importance)
        assertEquals(NotificationManager.IMPORTANCE_LOW,
            manager.getNotificationChannel(AndroidAutoNotificationFactory.LEGACY_CHANNEL_ID).importance)
        assertNull(migrated.sound)
    }

    @Test
    fun `migration does not bypass a blocked legacy channel`() {
        manager.createNotificationChannel(NotificationChannel(AndroidAutoNotificationFactory.LEGACY_CHANNEL_ID,
            "blocked", NotificationManager.IMPORTANCE_NONE))
        assertFalse(AndroidAutoNotificationFactory.notificationsAllowed(context))
        assertEquals(NotificationManager.IMPORTANCE_NONE, AndroidAutoNotificationFactory.ensureChannel(context).importance)
        assertFalse(AndroidAutoNotificationFactory.notificationsAllowed(context))
    }

    @Test
    fun `migration preserves explicit user importance and an existing new channel`() {
        val userChannel = NotificationChannel(AndroidAutoNotificationFactory.LEGACY_CHANNEL_ID,
            "user choice", NotificationManager.IMPORTANCE_LOW)
        ReflectionHelpers.setField(userChannel, "mUserLockedFields", 4) // Android USER_LOCKED_IMPORTANCE.
        assertTrue(userChannel.hasUserSetImportance())
        manager.createNotificationChannel(userChannel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, AndroidAutoNotificationFactory.ensureChannel(context).importance)
        // Repeated updates must not reset that choice to HIGH.
        assertEquals(NotificationManager.IMPORTANCE_LOW, AndroidAutoNotificationFactory.ensureChannel(context).importance)
    }

    @Test
    fun `incoming envelope is dismissible and contains the required car actions`() {
        val notification = build()
        assertEquals(Notification.CATEGORY_MESSAGE, notification.category)
        assertEquals(0, notification.flags and Notification.FLAG_ONGOING_EVENT)
        assertEquals(0, notification.flags and Notification.FLAG_LOCAL_ONLY)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, CarAppExtender(notification).importance)
        val actions = NotificationCompat.getInvisibleActions(notification)
        val reply = actions.single { it.semanticAction == NotificationCompat.Action.SEMANTIC_ACTION_REPLY }
        val dismiss = actions.single { it.semanticAction == NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ }
        assertFalse(reply.showsUserInterface)
        assertFalse(reply.actionIntent!!.isImmutable)
        assertEquals(1, reply.remoteInputs!!.size)
        assertFalse(dismiss.showsUserInterface)
        assertTrue(dismiss.actionIntent!!.isImmutable)
        assertNotNull(notification.deleteIntent)
    }

    @Test
    fun `quiet update keeps one envelope and cannot request repeated heads up`() {
        val first = build()
        val update = build(session.copy(posted = true))
        assertEquals(first.channelId, update.channelId)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, CarAppExtender(update).importance)
        assertTrue(update.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(0, update.defaults and (Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE))
        assertNull(update.sound)
        assertNull(update.vibrate)
    }

    @Test
    fun `serialized delivery uses current drive time but visible age stays on the measurement`() {
        val notification = build()
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)!!
        val message = style.messages.single()
        assertEquals(eventAt, notification.`when`)
        assertEquals(eventAt, message.timestamp)
        assertFalse(notification.extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
        assertEquals("112 mg/dL ↗ · Δ +1.5/min", message.person!!.name.toString())
        assertTrue(message.text.toString().contains(context.getString(R.string.age_minutes, 2)))
        assertFalse(style.isGroupConversation)
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertFalse(notification.publicVersion.extras.toString().contains("112"))
    }

    private fun build(currentSession: AutoNotificationSession = session): Notification {
        AndroidAutoNotificationFactory.ensureChannel(context)
        val reading = SensorStateStore.LastGlucose(123, 112, "RISING", measuredAt, 1.5)
        return AndroidAutoNotificationFactory.build(context,
            autoGlucoseContent(GlucoseWidgetInput(reading), eventAt), currentSession).build()
    }
}
