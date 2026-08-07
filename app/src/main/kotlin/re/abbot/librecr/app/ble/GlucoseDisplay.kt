package re.abbot.librecr.app.ble

/**
 * Shared glucose display-state helpers, used by every surface that renders a reading (Home, standby,
 * AOD/floating overlays, the foreground-service notification). Kept next to [ConnectionState] and
 * [GlucoseUi] so all consumers agree on "when is a value unavailable" — previously each surface had
 * its own private copy that could silently drift apart.
 */
internal enum class GlucoseDisplayStatus { NORMAL, SENSOR_WARMUP, OUT_OF_RANGE, SENSOR_ERROR }

/**
 * The activation marker is removed as soon as a reading confirms that warmup completed. The
 * elapsed-time check also prevents a missing first reading from leaving the UI in warmup forever.
 */
internal fun isSensorWarmupActive(
    startedAtMs: Long?,
    warmupDurationMinutes: Int,
    nowMs: Long = System.currentTimeMillis(),
): Boolean {
    if (startedAtMs == null || startedAtMs <= 0L || warmupDurationMinutes <= 0) return false
    val elapsedMinutes = (nowMs - startedAtMs).coerceAtLeast(0L) / 60_000L
    return elapsedMinutes < warmupDurationMinutes.toLong()
}

/**
 * The connection is between readings (scanning / connecting / handshaking / reconnecting / error),
 * so there is no live value to show. This suppresses a stale value but is not a sensor error;
 * only patch-status attention can establish that.
 */
internal fun ConnectionState.isUnavailableForGlucoseDisplay(): Boolean =
    this == ConnectionState.SCANNING ||
        this == ConnectionState.CONNECTING ||
        this == ConnectionState.HANDSHAKING ||
        this == ConnectionState.RECONNECTING ||
        this == ConnectionState.ERROR
