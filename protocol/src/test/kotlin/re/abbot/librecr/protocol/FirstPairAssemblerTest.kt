package re.abbot.librecr.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import re.abbot.librecr.protocol.crypto.FirstPairSourceSlice
import re.abbot.librecr.protocol.crypto.Phase5KeySchedule
import java.security.MessageDigest

/** Byte-for-byte parity for the final assemblers: scalar windows + sensor points → source/key. */
class FirstPairAssemblerTest {
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).toHex()
    private fun gen(n: Int, m: Int, a: Int) = ByteArray(n) { ((it * m + a) and 0xff).toByte() }

    private val entrySource = ByteArray(0x214) { ((it * 5 + 1) and 7).toByte() }
    private val nullEntropy = gen(0x11a, 11, 3)
    private val r0f = gen(70, 19, 9); private val r0s = gen(70, 23, 4)
    private val r59f = gen(70, 41, 6); private val r59s = gen(70, 43, 7)
    private val generator = re.abbot.librecr.protocol.hexToBytes(
        "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296" +
            "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5")

    @Test
    fun seedsFrom5bcf98Outputs() {
        val seeds = FirstPairSourceSlice.builder6388f0FirstPairStreamSeedsFrom5bcf98Outputs(
            gen(88, 3, 1), gen(88, 5, 2), gen(88, 7, 3), r0f, r0s, r59f, r59s,
            gen(70, 29, 1), gen(70, 31, 2), nullEntropy, 2)
        assertEquals(2, seeds.nullAttempts)
        val source = FirstPairSourceSlice.deriveFrom6388f0FirstPairStreamSeeds(seeds)
        assertEquals(
            "0404040101050405010306060207010704000002020100020106040104030400" +
                "07050303000306070304070503010202020402050106030505050207070104020100",
            source.toHex())
    }

    @Test
    fun seedsFromEntrySourceAndEntropy() {
        val entrySeeds = FirstPairSourceSlice.builder6388f0FirstPairStreamSeedsFromEntrySourceAnd5bcf98Outputs(
            entrySource, r0f, r0s, r59f, r59s, gen(70, 29, 1), gen(70, 31, 2), nullEntropy, 2)
        assertEquals("e70d3f912b290b5bd31c6dd27e8816448c16863247354286fc66957bdf2a8e27", sha(entrySeeds.row0Out4))

        val entropySeeds = FirstPairSourceSlice.builder6388f0FirstPairStreamSeedsFromEntropyAnd5bcf98Outputs(
            entrySource, r0f, r0s, r59f, r59s, nullEntropy)
        assertEquals(1, entropySeeds.nullAttempts)
        assertEquals("c4f2357511bf2071de2a5478a5d3d8a17c2b4da7b46c6cb46f4834ecb3a2f2ba", sha(entropySeeds.nullScalarWindow))
    }

    @Test
    fun fullEntropyAndSensorPointsToSourceAndKey() {
        val seeds = FirstPairSourceSlice.builder6388f0FirstPairStreamSeedsFromEntropyAndSensorPoints(entrySource, nullEntropy, generator, generator)
        assertEquals(1, seeds.nullAttempts)
        assertEquals("581f613027f3b91683819a69b62ac6b691103abc55a62825f0f6a889322c1269", sha(seeds.staticScalarWindow))
        assertEquals("fbc744031431d9fda2ceed80266ee2dfefb9a55e585ea5bbc2666a144379f042", sha(seeds.row0Out0))

        val source = FirstPairSourceSlice.deriveFrom6388f0FirstPairEntropyAndSensorPoints(entrySource, nullEntropy, generator, generator)
        assertEquals("ef4495c4b868489d0b4a30546bbf3d3b3ef51498e314a792214092d50ea09f2f", sha(source))
        assertEquals(
            "04040706000606020005070707050402050701070106000602000707060002050402" +
                "0407050605040400060004020400000106060102060205030303040600040606",
            source.toHex())

        val key = FirstPairSourceSlice.phase5RawKeyFrom6388f0FirstPairEntropyAndSensorPoints(entrySource, nullEntropy, generator, generator)
        assertEquals(Phase5KeySchedule.deriveRawKey(source).toHex(), key.toHex())

        // entropy-source (retry) path must equal the direct path
        val retry = FirstPairSourceSlice.deriveFrom6388f0FirstPairEntropySourceAndSensorPoints(
            entrySource, generator, generator, maxAttempts = 4) { nullEntropy }
        assertEquals(source.toHex(), retry.toHex())
    }
}
