package re.abbot.librecr.app.live

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import re.abbot.librecr.app.data.GlucoseUnit

internal enum class WearLiveUpdateMode {
    HIDDEN, SERVICE, LIVE;

    companion object {
        fun from(enabled: Boolean, foreground: Boolean): WearLiveUpdateMode = when {
            enabled -> LIVE
            foreground -> SERVICE
            else -> HIDDEN
        }
    }
}

/** Only notification-visible fields; persistence metadata must not trigger another Binder post. */
internal data class WearLiveUpdateNotificationKey(
    val pinned: Boolean,
    val foreground: Boolean,
    val status: WearLiveUpdateContent.Status,
    val value: String,
    val arrow: String,
    val delta: String,
    val unit: GlucoseUnit,
    val observedAtMs: Long,
    val ageMinutes: Long?,
)

internal fun WearLiveUpdateContent.notificationKey(pinned: Boolean, foreground: Boolean, nowMs: Long) =
    WearLiveUpdateNotificationKey(
        pinned, foreground, status, value, arrow, delta, unit,
        if (hasFreshObservation) observedAtMs else 0L,
        if (pinned && hasFreshObservation) ageMinutes(nowMs) else null,
    )

/** Hiding the relay display cancels both its data subscriptions and its pending delay. */
internal suspend fun collectWearLiveUpdates(
    modes: Flow<WearLiveUpdateMode>,
    content: () -> Flow<WearLiveUpdateContent>,
    refresh: () -> Unit,
    nowMs: () -> Long = System::currentTimeMillis,
    wait: suspend (Long) -> Unit = { delay(it) },
) {
    modes.distinctUntilChanged().collectLatest { mode ->
        if (mode == WearLiveUpdateMode.HIDDEN) {
            refresh()
        } else {
            content().distinctUntilChanged().collectLatest { snapshot ->
                while (true) {
                    refresh()
                    val remaining = snapshot.nextRefreshDelayMs(nowMs(), showAge = mode == WearLiveUpdateMode.LIVE)
                        ?: break
                    wait(remaining.coerceAtLeast(1L))
                }
            }
        }
    }
}
