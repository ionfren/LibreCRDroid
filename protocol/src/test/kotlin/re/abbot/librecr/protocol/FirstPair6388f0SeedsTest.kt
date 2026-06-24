package re.abbot.librecr.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import re.abbot.librecr.protocol.crypto.FirstPairSourceSlice
import java.security.MessageDigest

/** Byte-for-byte parity for the 6388f0 first-pair stream-seed entry (out-seeds → source/key). */
class FirstPair6388f0SeedsTest {
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).toHex()
    private fun gen(n: Int, m: Int, a: Int) = ByteArray(n) { ((it * m + a) and 0xff).toByte() }

    @Test
    fun deriveFromSeeds() {
        val seeds = FirstPairSourceSlice.Builder6388f0FirstPairStreamSeeds(
            nullScalarWindow = gen(70, 29, 1),
            staticScalarWindow = gen(70, 31, 2),
            nullEntropy11A = gen(0x11a, 37, 3),
            nullAttempts = 2,
            row0Out4 = gen(88, 3, 1),
            row0Out3 = gen(88, 5, 2),
            row0Out2 = gen(88, 7, 3),
            row0Out1 = gen(88, 17, 11),
            row0Out0 = gen(88, 13, 5),
            row59Out1 = gen(88, 23, 3),
            row59Out0 = gen(88, 19, 7),
        )

        val starts = FirstPairSourceSlice.builder6388f0FirstPair642f60StreamStarts(seeds)
        assertEquals("ff807599b1ce0b18fbafc1f5ef3b1af310536e6a355f84b6fe6e5e7e70cb5d07", sha(starts.row0.x0))
        assertEquals("a2105eb9e12ffa599c55ff1714223addc7fb0fcfa9fb7b6ed4bec1acbcf1c31c", sha(starts.row59.x0))

        val source = FirstPairSourceSlice.deriveFrom6388f0FirstPairStreamSeeds(seeds)
        assertEquals(
            "040400010402030107000002030007050505030706070204050000060303020307" +
                "020600070302040604000305030603020102020003010306050605060207060303",
            source.toHex(),
        )
        assertEquals("515ca99cb8c0deaf1208df352078064d", FirstPairSourceSlice.phase5RawKeyFrom6388f0FirstPairStreamSeeds(seeds).toHex())
    }
}
