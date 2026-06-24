package re.abbot.librecr.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import re.abbot.librecr.protocol.crypto.FirstPairSourceSlice
import re.abbot.librecr.protocol.crypto.Phase5KeySchedule
import re.abbot.librecr.protocol.pairing.SessionKey
import java.security.MessageDigest

/** SessionKey ties the white-box builders into the first-pair flow; cross-check vs the golden path. */
class SessionKeyTest {
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).toHex()

    private val entrySource = ByteArray(0x214) { ((it * 5 + 1) and 7).toByte() }
    private val nullEntropy = ByteArray(0x11a) { ((it * 11 + 3) and 0xff).toByte() }
    private val generatorXY = re.abbot.librecr.protocol.hexToBytes(
        "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296" +
            "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5")
    private val generator65 = byteArrayOf(0x04) + generatorXY

    @Test
    fun nativeEphemeralMatchesProcess2() {
        // The captured nullEntropy passes the 633fa8 acceptance checks → accepted on attempt 1.
        val eph = SessionKey.makeFirstPairNativeEphemeral { nullEntropy }
        assertEquals(1, eph.attempts)
        assertEquals(nullEntropy.toHex(), eph.nullEntropy11A.toHex())
        assertEquals(
            FirstPairSourceSlice.builderProcess2P5PublicKey65FromEntropy(nullEntropy).toHex(),
            eph.phoneEphemeralPub65.toHex())
        assertEquals(
            FirstPairSourceSlice.builder633fa8NullScalarWindowFromEntropy(nullEntropy).toHex(),
            eph.nullScalarWindow.toHex())
    }

    @Test
    fun phase5MaterialMatchesGoldenDerivation() {
        val material = SessionKey.deriveFirstPairPhase5Material(
            sensorEphemeralPub65 = generator65,
            sensorStaticPub65 = generator65,
            nullEntropy11A = nullEntropy,
            entrySource = entrySource)
        // Must equal the golden-verified entropy+sensor-points derivation (row0=ephemeral, row59=static).
        val goldenSource = FirstPairSourceSlice.deriveFrom6388f0FirstPairEntropyAndSensorPoints(
            entrySource, nullEntropy, generatorXY, generatorXY)
        assertEquals("ef4495c4b868489d0b4a30546bbf3d3b3ef51498e314a792214092d50ea09f2f", sha(material.source66))
        assertEquals(goldenSource.toHex(), material.source66.toHex())
        assertEquals(Phase5KeySchedule.deriveRawKey(goldenSource).toHex(), material.rawKey.toHex())
        assertEquals(16, material.rawKey.size)
    }

    @Test
    fun fastPathEqualsSearchPath() {
        val eph = SessionKey.makeFirstPairNativeEphemeral { nullEntropy }
        val fast = SessionKey.deriveFirstPairPhase5Material(
            generator65, generator65, eph.nullEntropy11A, eph.nullScalarWindow, entrySource)
        val direct = SessionKey.deriveFirstPairPhase5Material(
            generator65, generator65, nullEntropy, entrySource = entrySource)
        assertEquals(direct.rawKey.toHex(), fast.rawKey.toHex())
    }
}
