package re.abbot.librecr.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import re.abbot.librecr.protocol.crypto.FirstPairSourceSlice
import java.security.MessageDigest

/** Byte-for-byte parity for the 633fa8 static scalar-window path (entrySource → boundary → scalar). */
class FirstPair633fa8StaticTest {
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).toHex()
    private fun packU32(a: UIntArray): ByteArray {
        val out = ByteArray(a.size * 4)
        for (i in a.indices) { var v = a[i]; for (k in 0 until 4) { out[i * 4 + k] = (v and 0xffu).toByte(); v = v shr 8 } }
        return out
    }

    @Test
    fun staticScalarWindow() {
        val entrySource = ByteArray(0x214) { ((it * 5 + 1) and 7).toByte() }
        val boundary = FirstPairSourceSlice.builder633fa8StaticTailBoundaryFromEntrySource(entrySource)
        assertEquals("e07c11f4368e33eb9812c3d31c186b76741a5b63eb7e77c1fd79a80dd680aaf2", sha(boundary.preludeSource))
        assertEquals("9bb588ed741963c1ed0b32efab701fbd87819dfe65f9e0192e8830e8a7a7574d", sha(packU32(boundary.words3ab0)))
        assertEquals("fe4e9fc8207e0cc3276f2cb073a8050bbaa842cd2b114165630eb8214fb30b01", sha(packU32(boundary.words3120)))
        assertEquals("2acd8bebf1f8746c4d0c264f28cd42010725f116a4587b702b29b75b8fbb2052", sha(packU32(boundary.words2dfc)))
        assertEquals(0xb6ccf02833a9825euL, boundary.seed3110)

        val scalar = FirstPairSourceSlice.builder633fa8StaticScalarWindowFromEntrySource(entrySource)
        assertEquals(
            "f38d95844ac5834265c854266814ed9e67ce508eea912fc81a9b2d28db0ddd5e" +
                "0000000000000000000000000000000000000000000000000000000000000000000000000000",
            scalar.toHex())
    }
}
