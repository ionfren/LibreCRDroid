package re.abbot.librecr.app.log

import android.content.Context

/**
 * Synchronous, process-start-safe persistence for the watch logger. The phone owns the user-facing
 * toggle and sends its value through the Wear Data Layer; the watch keeps the last received choice
 * so logging stays off (or on, when explicitly requested) after either device restarts.
 */
object DiagnosticLogSettings {
    private const val PREFERENCES_NAME = "librecr_diagnostic_log"
    private const val KEY_ENABLED = "enabled"

    fun initialize(context: Context) {
        BleLog.setEnabled(read(context))
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
        BleLog.setEnabled(enabled)
    }

    private fun read(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
}
