package re.abbot.librecr.app.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PatchControlRecoveryPolicyTest {
    @Test
    fun `status 253 rearms once and then gives up`() {
        assertEquals(
            PatchControlWriteRecovery.REARM_CCCDS_AND_RETRY,
            patchControlWriteRecovery(
                status = GATT_CCC_CONFIGURATION_ERROR_STATUS,
                cccdRetryUsed = false,
            ),
        )
        assertEquals(
            PatchControlWriteRecovery.GIVE_UP,
            patchControlWriteRecovery(
                status = GATT_CCC_CONFIGURATION_ERROR_STATUS,
                cccdRetryUsed = true,
            ),
        )
    }

    @Test
    fun `procedure already active is never blindly retried`() {
        assertEquals(
            PatchControlWriteRecovery.GIVE_UP,
            patchControlWriteRecovery(
                status = GATT_PROCEDURE_ALREADY_IN_PROGRESS_STATUS,
                cccdRetryUsed = false,
            ),
        )
    }

    @Test
    fun `patch command sequence advances but never reuses a nonce`() {
        assertEquals(2, nextPatchControlSequence(1))
        assertEquals(0xFFFF, nextPatchControlSequence(0xFFFE))
        assertNull(nextPatchControlSequence(0xFFFF))
    }

    @Test
    fun `completion sequence comes from the data frame trailer`() {
        assertEquals(
            0x1234,
            patchControlCompletionSequence(byteArrayOf(0x01, 0x02, 0x34, 0x12)),
        )
        assertNull(patchControlCompletionSequence(byteArrayOf(0x34, 0x12)))
    }

    @Test
    fun `data plane cccds use the complete captured order`() {
        assertEquals(
            listOf(
                LibreSensorGatt.PATCH_CONTROL,
                LibreSensorGatt.EVENT_LOG,
                LibreSensorGatt.HISTORIC_DATA,
                LibreSensorGatt.CLINICAL_DATA,
                LibreSensorGatt.FACTORY_DATA,
                LibreSensorGatt.PATCH_STATUS,
                LibreSensorGatt.GLUCOSE_DATA,
            ),
            LibreSensorGatt.dataPlaneNotifying,
        )
        assertEquals(
            LibreSensorGatt.dataPlaneNotifying.size,
            LibreSensorGatt.dataPlaneNotifying.distinct().size,
        )
    }

    @Test
    fun `active backfill coverage cannot expire while sensor procedure runs`() {
        assertEquals(
            true,
            shouldCoalesceBackfill(
                coveredFromLifeCount = 100,
                requestedFromLifeCount = 105,
                coverageActive = true,
                coverageAgeMs = 120_000L,
                cooldownMs = 60_000L,
            ),
        )
        assertEquals(
            true,
            shouldCoalesceBackfill(
                coveredFromLifeCount = 100,
                requestedFromLifeCount = 105,
                coverageActive = false,
                coverageAgeMs = 59_999L,
                cooldownMs = 60_000L,
            ),
        )
        assertEquals(
            false,
            shouldCoalesceBackfill(
                coveredFromLifeCount = 100,
                requestedFromLifeCount = 105,
                coverageActive = false,
                coverageAgeMs = 60_000L,
                cooldownMs = 60_000L,
            ),
        )
        assertEquals(
            false,
            shouldCoalesceBackfill(
                coveredFromLifeCount = 100,
                requestedFromLifeCount = 99,
                coverageActive = true,
                coverageAgeMs = 1L,
                cooldownMs = 60_000L,
            ),
        )
    }
}
