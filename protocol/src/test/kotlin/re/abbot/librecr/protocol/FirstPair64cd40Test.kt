package re.abbot.librecr.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import re.abbot.librecr.protocol.crypto.FirstPairSourceSlice
import java.security.MessageDigest

/** Byte-for-byte parity for the 64cd40 caller-row primitive. */
class FirstPair64cd40Test {
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

        val arg0Words = FirstPairSourceSlice.builder64cd40Arg0U64Words(arg0)
        assertEquals("a44f4e91a177dc3a5cb312ef4f9719cdc71ddecceaddc3ec4e2edf789628e5d1", sha(packU64(arg0Words)))
        assertEquals(
            listOf(0x10ddde967190d344uL, 0xebd463e50c8fe285uL, 0x1a0e6a0158814d23uL, 0x0e751bdbe34a0c27uL),
            arg0Words.take(4),
        )

        val updated = FirstPairSourceSlice.builder64cd40WorkspaceAfterUpdate(arg0Words, scalar, x2Workspace)
        assertEquals(44 * 8, updated.size)
        assertEquals("4f72ca5ec1a21eaefb25bc5e1952d71e22482eff8776ab4d7d7b097dd898f698", sha(updated))
        assertEquals("d7b094c172d1d6f663803ebc41632b3a20173805dd60fba63f6cdf91fc96abe6", updated.copyOfRange(0, 32).toHex())
        assertEquals("428e3ebc4f8e7959b5f33857d937a682a920452868aad593c3c8cdd2d7dce1e6", updated.copyOfRange(updated.size - 32, updated.size).toHex())

        val output = FirstPairSourceSlice.builder64cd40FinalU32Words(updated)
        assertEquals("d9242621c538e422518e9e87083886228bb6f88827716cf209de9e42fb4a336d", sha(packU32(output)))
        assertEquals(listOf(0x00cec4dfu, 0x26e4cde3u, 0xcaeeb424u, 0xe561e5c9u), output.take(4))
        assertEquals(listOf(0xa935ba59u, 0x67d4d8c8u, 0x511b912cu), output.takeLast(3))
        assertEquals(output.toList(), FirstPairSourceSlice.builder64cd40OutputWords(arg0, scalar, x2Workspace).toList())
    }
}
