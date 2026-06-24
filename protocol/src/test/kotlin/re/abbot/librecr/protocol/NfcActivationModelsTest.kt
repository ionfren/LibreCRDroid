package re.abbot.librecr.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import re.abbot.librecr.protocol.pairing.Libre3NfcActivationErrorResponse
import re.abbot.librecr.protocol.pairing.Libre3NfcActivationResponse
import re.abbot.librecr.protocol.pairing.Libre3NfcPatchInfo
import re.abbot.librecr.protocol.pairing.NfcActivationCommandCode

class NfcActivationModelsTest {
    @Test
    fun activationResponseExtractsBleAddressAndPin() {
        val raw = hexToBytes("a5000102030405063225ec7211223344aabb")
        val response = Libre3NfcActivationResponse(raw)

        assertEquals("06:05:04:03:02:01", response.bleAddressDisplay)
        assertEquals("3225ec72", response.blePin.toHex())
        assertEquals(0x44332211L, response.activationTimeSeconds)
    }

    @Test
    fun activationErrorResponseParsesJugglucoNonFatalStatus() {
        val response = Libre3NfcActivationErrorResponse(hexToBytes("00a501b1"))

        assertEquals(0xb1, response.errorCode)
        assertEquals(true, response.isJugglucoNonFatal)
    }

    @Test
    fun patchInfoParsesLifecycleAndChoosesA0ForStorage() {
        val raw = hexToBytes(
            "00a500000000000300c04e040302017b0c01" +
                "4142433132333435360000"
        )
        val patch = Libre3NfcPatchInfo(raw)

        assertEquals("ABC123456", patch.serialNumber)
        assertEquals(0x01, patch.stateByte)
        assertEquals("1.2.3.4", patch.firmwareVersion)
        assertEquals(60, patch.warmupMinutes)
        assertEquals(20160, patch.wearDurationMinutes)
        assertEquals(NfcActivationCommandCode.ACTIVATE, patch.recommendedCommandCode)
    }

    @Test
    fun patchInfoChoosesA8ForActiveSensor() {
        val raw = hexToBytes(
            "00a500000000000300c04e040302017b0c04" +
                "4142433132333435360000"
        )
        val patch = Libre3NfcPatchInfo(raw)

        assertEquals(NfcActivationCommandCode.SWITCH_RECEIVER, patch.recommendedCommandCode)
    }
}
