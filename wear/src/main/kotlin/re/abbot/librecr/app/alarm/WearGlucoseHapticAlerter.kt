package re.abbot.librecr.app.alarm

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import re.abbot.librecr.app.data.AlarmSettings
import re.abbot.librecr.app.log.BleLog
import java.util.Calendar

/**
 * Watch-local threshold haptics. It runs from the already-decoded glucose stream, so it adds no
 * polling, alarms, or extra BLE/network work. The phone is needed only to sync [AlarmSettings].
 */
object WearGlucoseHapticAlerter {
    private const val HYSTERESIS_MG_DL = 5
    private val doubleTapTimings = longArrayOf(0L, 80L, 140L, 80L)
    private val doubleTapAmplitudes = intArrayOf(0, 180, 0, 180)

    @Volatile private var episode: Episode? = null
    @Volatile private var lastAlertLifeCount: Int? = null

    fun onReading(
        context: Context,
        mgDl: Int,
        lifeCount: Int,
        settings: AlarmSettings,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (!settings.wearHapticsEnabled) {
            episode = null
            return
        }
        val breach = breachFor(mgDl, settings, minuteOfDay(nowMs))
        val activeEpisode = episode
        if (activeEpisode != null) {
            if (breach == null) {
                episode = null
                return
            }
            val cleared = when (activeEpisode.side) {
                Side.LOW -> mgDl >= activeEpisode.thresholdMgDl + HYSTERESIS_MG_DL
                Side.HIGH -> mgDl <= activeEpisode.thresholdMgDl - HYSTERESIS_MG_DL
            }
            val switchedSide = breach.side != activeEpisode.side
            if (!cleared && !switchedSide) return
            episode = null
        }
        val nextBreach = breach ?: return
        episode = Episode(nextBreach.side, nextBreach.thresholdMgDl)
        if (lastAlertLifeCount == lifeCount) return
        lastAlertLifeCount = lifeCount
        BleLog.log(
            "wear haptic alert side=${nextBreach.side} mgdl=$mgDl " +
                "threshold=${nextBreach.thresholdMgDl} lc=$lifeCount",
        )
        vibrateDoubleTap(context)
    }

    private fun breachFor(mgDl: Int, settings: AlarmSettings, minuteOfDay: Int): Breach? {
        val withinWearHours = !settings.wearActiveHoursEnabled ||
            inWindow(minuteOfDay, settings.wearActiveStartMinutes, settings.wearActiveEndMinutes)
        if (!withinWearHours) return null

        if (settings.urgentLowEnabled && mgDl < settings.urgentLowMgDl) {
            val threshold = if (settings.lowEnabled) settings.lowMgDl else settings.urgentLowMgDl
            return Breach(Side.LOW, threshold)
        }
        if (settings.lowEnabled && mgDl < settings.lowMgDl) {
            return Breach(Side.LOW, settings.lowMgDl)
        }
        if (settings.highEnabled && mgDl > settings.highMgDl) {
            return Breach(Side.HIGH, settings.highMgDl)
        }
        return null
    }

    private fun vibrateDoubleTap(context: Context) {
        runCatching {
            val vibrator = context.defaultVibrator() ?: return
            if (!vibrator.hasVibrator()) return
            val effect = if (vibrator.hasAmplitudeControl()) {
                VibrationEffect.createWaveform(doubleTapTimings, doubleTapAmplitudes, -1)
            } else {
                VibrationEffect.createWaveform(doubleTapTimings, -1)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(
                    effect,
                    VibrationAttributes.Builder()
                        .setUsage(VibrationAttributes.USAGE_ALARM)
                        .build(),
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(
                    effect,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
            }
        }.onFailure {
            BleLog.log("wear haptic alert failed: ${it.message ?: it::class.java.simpleName}")
        }
    }

    private fun Context.defaultVibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    /** True if [minute] is inside [start, end); handles windows that wrap past midnight. */
    private fun inWindow(minute: Int, start: Int, end: Int): Boolean {
        if (start == end) return true
        return if (start < end) minute in start until end else (minute >= start || minute < end)
    }

    private fun minuteOfDay(ms: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = ms }
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }

    private enum class Side { LOW, HIGH }
    private data class Breach(val side: Side, val thresholdMgDl: Int)
    private data class Episode(val side: Side, val thresholdMgDl: Int)
}
