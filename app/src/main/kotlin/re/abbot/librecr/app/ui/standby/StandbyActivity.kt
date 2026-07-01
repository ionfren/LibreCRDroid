package re.abbot.librecr.app.ui.standby

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import re.abbot.librecr.app.LibreCR
import re.abbot.librecr.app.data.AppSettings
import re.abbot.librecr.app.ui.theme.LibreCRTheme

/**
 * Full-screen standby (nightstand) shown over the lock screen while the phone is on a wireless
 * charger. Launched by [StandbyController]; self-finishes when no longer eligible (unplugged or
 * outside the window). Standby is landscape-only, matching a horizontal charging dock.
 */
class StandbyActivity : ComponentActivity() {

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            lifecycleScope.launch {
                if (!StandbyController.isEligible(this@StandbyActivity)) finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setBackgroundDrawableResource(android.R.color.black)
        window.decorView.setBackgroundColor(AndroidColor.BLACK)
        window.statusBarColor = AndroidColor.BLACK
        window.navigationBarColor = AndroidColor.BLACK
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        StandbyController.markVisible(true)
        StandbyController.cancelNotification(this) // the activity is up; drop the launcher notification
        setContent {
            val settings by LibreCR.settings.settingsFlow.collectAsStateWithLifecycle(initialValue = AppSettings())
            LibreCRTheme(dark = true, dynamicColor = false) {
                StandbyScreen(settings = settings)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this,
            powerReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_DATE_CHANGED)
                addAction(StandbyController.ACTION_ELIGIBILITY_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        lifecycleScope.launch {
            if (!StandbyController.isEligible(this@StandbyActivity)) finish()
        }
    }

    override fun onPause() {
        runCatching { unregisterReceiver(powerReceiver) }
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation != Configuration.ORIENTATION_LANDSCAPE) {
            StandbyController.evaluate(applicationContext)
            finish()
            return
        }
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        StandbyController.markVisible(false)
        super.onDestroy()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
