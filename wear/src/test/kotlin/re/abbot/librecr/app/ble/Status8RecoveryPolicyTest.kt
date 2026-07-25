package re.abbot.librecr.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Status8RecoveryPolicyTest {
    @Test
    fun `retained gatt is retried once then one scan then controller auto connect`() {
        var state = Status8RecoveryState()

        val retry = state.next(retainedGattAvailable = true)
        assertEquals(Status8RecoveryPhase.RETRY_RETAINED_GATT, retry.phase)
        state = retry.nextState

        val scan = state.next(retainedGattAvailable = false)
        assertEquals(Status8RecoveryPhase.ACTIVE_SCAN_ONCE, scan.phase)
        state = scan.nextState

        val handoff = state.next(retainedGattAvailable = false)
        assertEquals(Status8RecoveryPhase.CONTROLLER_AUTO_CONNECT, handoff.phase)
        assertEquals(
            Status8RecoveryPhase.CONTROLLER_AUTO_CONNECT,
            handoff.nextState.next(retainedGattAvailable = true).phase,
        )
    }

    @Test
    fun `missing retained gatt skips retry and permanently consumes its slot`() {
        val scan = Status8RecoveryState().next(retainedGattAvailable = false)

        assertEquals(Status8RecoveryPhase.ACTIVE_SCAN_ONCE, scan.phase)
        assertTrue(scan.nextState.fastGattConsumed)
        assertTrue(scan.nextState.activeScanConsumed)
        assertEquals(
            Status8RecoveryPhase.CONTROLLER_AUTO_CONNECT,
            scan.nextState.next(retainedGattAvailable = true).phase,
        )
    }

    @Test
    fun `recovery flags are monotonic`() {
        val initial = Status8RecoveryState()
        val afterRetry = initial.next(retainedGattAvailable = true).nextState
        val afterScan = afterRetry.next(retainedGattAvailable = false).nextState
        val afterHandoff = afterScan.next(retainedGattAvailable = false).nextState

        assertFalse(initial.fastGattConsumed)
        assertFalse(initial.activeScanConsumed)
        assertTrue(afterRetry.fastGattConsumed)
        assertFalse(afterRetry.activeScanConsumed)
        assertTrue(afterScan.fastGattConsumed)
        assertTrue(afterScan.activeScanConsumed)
        assertEquals(afterScan, afterHandoff)
    }

    @Test
    fun `active phases cover doze and a full advertising minute`() {
        assertEquals(8_000L, STATUS8_GATT_CONNECT_WATCHDOG_MS)
        assertTrue(STATUS8_SCAN_TIMEOUT_MS in 65_000L..70_000L)
        assertTrue(STATUS8_FAST_WAKE_LOCK_TIMEOUT_MS >= 40_000L)
        assertTrue(STATUS8_SCAN_WAKE_LOCK_TIMEOUT_MS >= STATUS8_SCAN_TIMEOUT_MS + 5_000L)
        assertTrue(CONNECTION_ESTABLISH_WAKE_LOCK_TIMEOUT_MS >= 45_000L + 3 * 5_000L + 5_000L)
    }

    @Test
    fun `controller auto connect has no application teardown timeout`() {
        assertNull(sensorConnectTimeoutMs(autoConnect = true, directTimeoutMs = 8_000L))
        assertEquals(8_000L, sensorConnectTimeoutMs(autoConnect = false, directTimeoutMs = 8_000L))
    }

    @Test
    fun `runtime mode honors controller handoff and one-shot direct escape`() {
        assertTrue(
            shouldUseControllerAutoConnect(
                controllerHandoff = true,
                reconnectAttempt = 1,
                directFallbackArmed = false,
            ),
        )
        assertTrue(
            shouldUseControllerAutoConnect(
                controllerHandoff = false,
                reconnectAttempt = 1,
                directFallbackArmed = false,
            ),
        )
        assertFalse(
            shouldUseControllerAutoConnect(
                controllerHandoff = false,
                reconnectAttempt = 2,
                directFallbackArmed = true,
            ),
        )
        assertFalse(
            shouldUseControllerAutoConnect(
                controllerHandoff = false,
                reconnectAttempt = 0,
                directFallbackArmed = false,
            ),
        )
    }
}
