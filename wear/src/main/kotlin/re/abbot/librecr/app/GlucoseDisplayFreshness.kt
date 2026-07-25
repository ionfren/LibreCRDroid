package re.abbot.librecr.app

/**
 * Keep the last valid glucose visible while a short BLE interruption is being recovered.
 *
 * Juggluco uses an 11 x 30-second display window. Sharing the same boundary here keeps the
 * activity, foreground notification, and complications consistent.
 */
const val WEAR_GLUCOSE_DISPLAY_HOLD_MS = 11L * 30_000L

fun isWearGlucoseFresh(
    receivedAtMs: Long,
    nowMs: Long = System.currentTimeMillis(),
): Boolean = receivedAtMs > 0L && nowMs - receivedAtMs < WEAR_GLUCOSE_DISPLAY_HOLD_MS
