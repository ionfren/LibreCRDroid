package re.abbot.librecr.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import re.abbot.librecr.protocol.crypto.FirstPairSourceSlice
import java.security.MessageDigest

/** Byte-for-byte parity for the 64c524 caller-row primitive. */
class FirstPair64c524Test {
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).toHex()
    private fun packU64(a: ULongArray): ByteArray {
        val out = ByteArray(a.size * 8)
        for (i in a.indices) { var v = a[i]; for (k in 0 until 8) { out[i * 8 + k] = (v and 0xffuL).toByte(); v = v shr 8 } }
        return out
    }
    private fun packU32(a: UIntArray): ByteArray {
        val out = ByteArray(a.size * 4)
        for (i in a.indices) { var v = a[i]; for (k in 0 until 4) { out[i * 4 + k] = (v and 0xffu).toByte(); v = v shr 8 } }
        return out
    }

    @Test
    fun outputWords() {
        val arg0 = ByteArray(88) { ((it * 7 + 3) and 0xff).toByte() }
        val scalar = 0x0123456789abcdefuL
        val x2Workspace = ByteArray(352) { ((it * 5 + 11) and 0xff).toByte() }

        val arg0Words = FirstPairSourceSlice.builder64c524Arg0U64Words(arg0)
        assertEquals("b4c289307b76fd400a7ebb93cfd81f6df15931c9553af9a9c8bc1a9f047ca741", sha(packU64(arg0Words)))
        assertEquals(
            listOf(0x3ff86d27c281a51buL, 0x82c9235432f698dauL, 0xf846c40785c0883cuL, 0x9f193ff19acdd538uL),
            arg0Words.take(4),
        )

        val updated = FirstPairSourceSlice.builder64c524WorkspaceAfterUpdate(arg0Words, scalar, x2Workspace)
        assertEquals(44 * 8, updated.size)
        assertEquals("0dce7bc80d9fd277b44333a61aa8e14a608c6205b63cee6b9ea498a4407533ee", sha(updated))
        assertEquals("af4609c269497b9cc32a915a34c3bbd1c87d116319769c5fab21064d51fe471b", updated.copyOfRange(0, 32).toHex())
        assertEquals("88227680af94c55adff3deccb1f86d6c81b410c13274fcc1c3c8cdd2d7dce1e6", updated.copyOfRange(updated.size - 32, updated.size).toHex())

        val output = FirstPairSourceSlice.builder64c524FinalU32Words(updated)
        assertEquals("59eebec8dcac326baa8e1be4844f38155835ea1734a2985fd12249e77de2d6b3", sha(packU32(output)))
        assertEquals(listOf(0x2c9a5e95u, 0x39ffcae7u, 0xdb27a8feu, 0x35947d74u), output.take(4))
        assertEquals(listOf(0x35c32db9u, 0x322abd54u, 0x38781571u, 0xeb184e5fu), output.takeLast(4))
        assertEquals(output.toList(), FirstPairSourceSlice.builder64c524OutputWords(arg0, scalar, x2Workspace).toList())
    }
}
