package re.abbot.librecr.app.alarm

import re.abbot.librecr.app.data.AlarmSettings
import re.abbot.librecr.app.stats.GlucoseSample

enum class AlarmKind { URGENT_LOW, LOW, HIGH, PERSISTENT_HIGH, PERSISTENT_LOW }

data class AlarmDecision(val kind: AlarmKind, val mgDl: Int)

/**
 * Pure alarm decision logic — no Android types — so it is unit-testable. Urgent-low is a safety
 * alarm: it ignores both the active-hours window and snooze. Low/high respect active hours and
 * per-kind snooze.
 */
object AlarmEvaluator {
    /** Raw threshold breach ignoring enable/active/snooze — used to detect a return to range. */
    fun rawBreach(mgDl: Int, config: AlarmSettings): AlarmKind? = when {
        mgDl < config.urgentLowMgDl -> AlarmKind.URGENT_LOW
        mgDl < config.lowMgDl -> AlarmKind.LOW
        mgDl > config.highMgDl -> AlarmKind.HIGH
        else -> null
    }

    fun evaluate(
        mgDl: Int,
        config: AlarmSettings,
        minuteOfDay: Int,
        isSnoozed: (AlarmKind) -> Boolean,
    ): AlarmDecision? {
        if (!config.enabled) return null

        if (config.urgentLowEnabled && mgDl < config.urgentLowMgDl && !isSnoozed(AlarmKind.URGENT_LOW)) {
            return AlarmDecision(AlarmKind.URGENT_LOW, mgDl)
        }

        val withinActiveHours = !config.activeHoursEnabled ||
            inWindow(minuteOfDay, config.activeStartMinutes, config.activeEndMinutes)
        if (!withinActiveHours) return null

        if (config.lowEnabled && mgDl < config.lowMgDl && !isSnoozed(AlarmKind.LOW)) {
            return AlarmDecision(AlarmKind.LOW, mgDl)
        }
        if (config.highEnabled && mgDl > config.highMgDl && !isSnoozed(AlarmKind.HIGH)) {
            return AlarmDecision(AlarmKind.HIGH, mgDl)
        }
        return null
    }

    /** True if [minute] is inside [start, end); handles windows that wrap past midnight. */
    fun inWindow(minute: Int, start: Int, end: Int): Boolean {
        if (start == end) return true
        return if (start < end) minute in start until end else (minute >= start || minute < end)
    }

    /**
     * Persistent high/low: fires only if glucose stayed on the breaching side for the whole
     * configured window. Respects the master enable, active hours, and per-kind snooze.
     */
    fun persistentDecision(
        samples: List<GlucoseSample>,
        nowMs: Long,
        minuteOfDay: Int,
        config: AlarmSettings,
        isSnoozed: (AlarmKind) -> Boolean,
    ): AlarmDecision? {
        if (!config.enabled) return null
        val withinActiveHours = !config.activeHoursEnabled ||
            inWindow(minuteOfDay, config.activeStartMinutes, config.activeEndMinutes)
        if (!withinActiveHours) return null
        val last = samples.lastOrNull() ?: return null
        if (config.persistentHighEnabled && !isSnoozed(AlarmKind.PERSISTENT_HIGH) &&
            sustained(samples, nowMs, config.persistentHighMinutes) { it >= config.persistentHighMgDl }
        ) {
            return AlarmDecision(AlarmKind.PERSISTENT_HIGH, last.mgDl)
        }
        if (config.persistentLowEnabled && !isSnoozed(AlarmKind.PERSISTENT_LOW) &&
            sustained(samples, nowMs, config.persistentLowMinutes) { it <= config.persistentLowMgDl }
        ) {
            return AlarmDecision(AlarmKind.PERSISTENT_LOW, last.mgDl)
        }
        return null
    }

    /** The persistent condition ignoring enable/active/snooze — used to detect "all clear". */
    fun persistentRawKind(samples: List<GlucoseSample>, nowMs: Long, config: AlarmSettings): AlarmKind? {
        if (config.persistentHighEnabled &&
            sustained(samples, nowMs, config.persistentHighMinutes) { it >= config.persistentHighMgDl }
        ) {
            return AlarmKind.PERSISTENT_HIGH
        }
        if (config.persistentLowEnabled &&
            sustained(samples, nowMs, config.persistentLowMinutes) { it <= config.persistentLowMgDl }
        ) {
            return AlarmKind.PERSISTENT_LOW
        }
        return null
    }

    /**
     * True if every reading across the last [windowMinutes] satisfies [predicate] AND the window is
     * actually covered (data spans it, with a recent latest reading). A gap or any non-breaching
     * reading means it wasn't sustained — so we don't false-fire.
     */
    private inline fun sustained(
        samples: List<GlucoseSample>,
        nowMs: Long,
        windowMinutes: Int,
        predicate: (Int) -> Boolean,
    ): Boolean {
        if (windowMinutes <= 0) return false
        val windowMs = windowMinutes * 60_000L
        val start = nowMs - windowMs
        val inWindow = samples.filter { it.atMs in start..nowMs }
        if (inWindow.size < 3) return false
        val oldest = inWindow.minOf { it.atMs }
        val newest = inWindow.maxOf { it.atMs }
        val tolerance = maxOf(5 * 60_000L, windowMs / 10)
        val spansWindow = oldest <= start + tolerance
        val recent = nowMs - newest <= 6 * 60_000L
        return spansWindow && recent && inWindow.all { predicate(it.mgDl) }
    }
}
