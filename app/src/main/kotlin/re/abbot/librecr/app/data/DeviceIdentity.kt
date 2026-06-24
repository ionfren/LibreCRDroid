package re.abbot.librecr.app.data

import android.content.Context
import java.util.Locale
import java.util.UUID

private const val IDENTITY_PREFS = "librecr_identity"
private const val IDENTITY_KEY = "LibreCRAccountlessUniqueID"

/**
 * Stable per-install UUID used both as the LibreView login `DeviceId` and the measurement
 * `uniqueIdentifier`. Shares the same prefs key the NFC/account flow already uses.
 */
fun deviceUniqueId(context: Context): String {
    val prefs = context.getSharedPreferences(IDENTITY_PREFS, Context.MODE_PRIVATE)
    prefs.getString(IDENTITY_KEY, null)?.takeIf { it.isNotBlank() }?.let { return it }
    return UUID.randomUUID().toString().lowercase(Locale.US).also {
        prefs.edit().putString(IDENTITY_KEY, it).apply()
    }
}
