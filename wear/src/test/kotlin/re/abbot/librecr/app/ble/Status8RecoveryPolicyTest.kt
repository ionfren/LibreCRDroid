package re.abbot.librecr.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Status8RecoveryPolicyTest {
    @Test
    fun `fast reconnect watchdog and recovery scan are bounded`() {
        assertEquals(8_000L, STATUS8_GATT_CONNECT_WATCHDOG_MS)
        assertEquals(15_000L, STATUS8_SCAN_TIMEOUT_MS)
        assertEquals(15_000L, STATUS8_WAKE_LOCK_TIMEOUT_MS)
    }

    @Test
    fun `scan retry schedule starts at two seconds and caps at twenty`() {
        assertEquals(2_000L, status8RecoveryScanDelayMs(1))
        assertEquals(5_000L, status8RecoveryScanDelayMs(2))
        assertEquals(10_000L, status8RecoveryScanDelayMs(3))
        assertEquals(20_000L, status8RecoveryScanDelayMs(4))
        assertEquals(20_000L, status8RecoveryScanDelayMs(100))
    }

    @Test
    fun `recovery hands the wait to the controller after two failed scans`() {
        assertFalse(status8ShouldHandOffToController(0))
        assertFalse(status8ShouldHandOffToController(1))
        assertTrue(status8ShouldHandOffToController(STATUS8_MAX_RECOVERY_SCANS))
        assertTrue(status8ShouldHandOffToController(5))
    }
}
