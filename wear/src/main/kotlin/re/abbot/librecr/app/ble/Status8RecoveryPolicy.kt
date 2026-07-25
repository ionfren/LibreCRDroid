package re.abbot.librecr.app.ble

/**
 * One-shot recovery plan for a link-supervision timeout (GATT status 8).
 *
 * The retained BluetoothGatt gets one immediate retry, then the app performs one
 * advertising-cycle scan. If neither works, Android's controller owns a persistent
 * auto-connect instead of the app repeatedly scanning under Doze.
 */
internal enum class Status8RecoveryPhase {
    RETRY_RETAINED_GATT,
    ACTIVE_SCAN_ONCE,
    CONTROLLER_AUTO_CONNECT,
}

internal data class Status8RecoveryState(
    val fastGattConsumed: Boolean = false,
    val activeScanConsumed: Boolean = false,
) {
    fun next(retainedGattAvailable: Boolean): Status8RecoveryDecision = when {
        !fastGattConsumed && retainedGattAvailable ->
            Status8RecoveryDecision(
                phase = Status8RecoveryPhase.RETRY_RETAINED_GATT,
                nextState = copy(fastGattConsumed = true),
            )

        !activeScanConsumed ->
            Status8RecoveryDecision(
                phase = Status8RecoveryPhase.ACTIVE_SCAN_ONCE,
                // Once scanning starts, a stale GATT must never re-enter the plan.
                nextState = copy(fastGattConsumed = true, activeScanConsumed = true),
            )

        else ->
            Status8RecoveryDecision(
                phase = Status8RecoveryPhase.CONTROLLER_AUTO_CONNECT,
                nextState = this,
            )
    }
}

internal data class Status8RecoveryDecision(
    val phase: Status8RecoveryPhase,
    val nextState: Status8RecoveryState,
)

internal fun shouldUseControllerAutoConnect(
    controllerHandoff: Boolean,
    reconnectAttempt: Int,
    directFallbackArmed: Boolean,
): Boolean =
    controllerHandoff || (reconnectAttempt > 0 && !directFallbackArmed)
