package re.abbot.librecr.app.auto

import re.abbot.librecr.app.live.LiveUpdateFormatter
import re.abbot.librecr.app.widget.GlucoseWidgetInput
import re.abbot.librecr.app.widget.GlucoseWidgetPresentation
import re.abbot.librecr.app.widget.glucoseWidgetPresentation

/** POSTED confirms the phone accepted it, not that the car displayed it. */
internal enum class AutoNotificationDelivery { DISABLED, WAITING_FOR_CAR, READY, POSTED, DISMISSED, BLOCKED, FAILED }

/** No medical data is persisted here: only notification-session bookkeeping. */
internal data class AutoNotificationSession(
    val token: String? = null,
    val dismissed: Boolean = false,
    val posted: Boolean = false,
) {
    val canPost: Boolean get() = token != null && !dismissed
    val mayShowInitialHeadsUp: Boolean get() = canPost && !posted
}

internal fun autoNotificationSession(
    previous: AutoNotificationSession,
    enabled: Boolean,
    projected: Boolean,
    newToken: String,
): AutoNotificationSession = when {
    !enabled || !projected -> AutoNotificationSession()
    previous.token != null -> previous
    else -> AutoNotificationSession(token = newToken)
}

internal fun dismissAutoNotification(
    session: AutoNotificationSession,
    expectedToken: String,
): AutoNotificationSession =
    if (session.token == expectedToken) session.copy(dismissed = true) else session

internal data class AutoGlucoseContent(
    val presentation: GlucoseWidgetPresentation,
    val headline: String,
    val ageSeconds: Long?,
    /** Refreshed delivery time for the car transport; never a measurement time. */
    val eventAtMs: Long,
)

internal fun autoGlucoseContent(input: GlucoseWidgetInput, nowMs: Long): AutoGlucoseContent {
    val presentation = glucoseWidgetPresentation(input, nowMs)
    val arrow = LiveUpdateFormatter.trendSymbol(presentation.trend) ?: "—"
    return AutoGlucoseContent(
        presentation = presentation,
        headline = "${presentation.value} mg/dL $arrow · Δ ${presentation.deltaPerMinute}/min",
        ageSeconds = presentation.measuredAtMs?.let { (nowMs - it).coerceAtLeast(0L) / 1_000L },
        eventAtMs = nowMs,
    )
}
