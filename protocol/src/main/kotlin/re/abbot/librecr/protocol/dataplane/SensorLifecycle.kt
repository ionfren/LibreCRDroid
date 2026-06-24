package re.abbot.librecr.protocol.dataplane

enum class SensorLifecyclePhase { WARMUP, ACTIVE, EXPIRED }

/** Sensor warmup/wear lifecycle. Port of Swift `SensorLifecycle`. */
class SensorLifecycle(
    currentLifeCountMinutes: Int,
    warmupDurationMinutes: Int = DEFAULT_WARMUP_DURATION_MINUTES,
    wearDurationMinutes: Int? = null,
) {
    val currentLifeCountMinutes: Int = currentLifeCountMinutes
    val warmupDurationMinutes: Int = maxOf(0, warmupDurationMinutes)
    val wearDurationMinutes: Int? = wearDurationMinutes?.let { maxOf(0, it) }

    val elapsedMinutes: Int get() = maxOf(0, currentLifeCountMinutes)
    val remainingWarmupMinutes: Int get() = maxOf(0, warmupDurationMinutes - elapsedMinutes)
    val remainingWearMinutes: Int? get() = wearDurationMinutes?.let { maxOf(0, it - elapsedMinutes) }
    val isWarmupComplete: Boolean get() = elapsedMinutes >= warmupDurationMinutes
    val isExpired: Boolean get() = wearDurationMinutes?.let { elapsedMinutes >= it } ?: false
    val isWarmingUp: Boolean get() = !isWarmupComplete && !isExpired
    val isActive: Boolean get() = phase == SensorLifecyclePhase.ACTIVE

    val phase: SensorLifecyclePhase
        get() = when {
            isExpired -> SensorLifecyclePhase.EXPIRED
            !isWarmupComplete -> SensorLifecyclePhase.WARMUP
            else -> SensorLifecyclePhase.ACTIVE
        }

    companion object {
        const val DEFAULT_WARMUP_DURATION_MINUTES = 60
    }
}
