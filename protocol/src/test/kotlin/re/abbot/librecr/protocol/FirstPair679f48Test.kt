package re.abbot.librecr.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import re.abbot.librecr.protocol.crypto.FirstPairSourceSlice
import java.security.MessageDigest

/** Byte-for-byte parity for the vm-only 679f48 descriptor-input sub-layers. */
class FirstPair679f48Test {
    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).toHex()

    @Test
    fun previousDescriptorBlocksToDD7CInputs() {
        val previous = ByteArray(2 * 66) { ((it * 5 + 2) and 7).toByte() }
        val updates = FirstPairSourceSlice.previousDescriptorBlocksToDD7CInputs(previous)
        assertEquals(132, updates.size)
        assertEquals("44e2abbbeb7fa7615a64007196fcdbbfb396ed9fe8e48e6b048404e8f96a2730", sha256(updates))
    }

    @Test
    fun deriveFrom679f48Inputs() {
        val previous = ByteArray(2 * 66) { ((it * 5 + 2) and 7).toByte() }
        val context = FirstPairSourceSlice.finalized679f48ContextFromInputs(previous)
        assertEquals("8a57624059dee8d2679edd1b2e6de78f8d1856a871eb268d59f309943d10aa11", sha256(context))
        val source = FirstPairSourceSlice.deriveFrom679f48Inputs(previous, offset = 0, length = 16)
        assertEquals(
            "040400020506020406010204030101020502070705070404000302040501050505" +
                "010004030304070206030607070000000005000102030205000107030202050000",
            source.toHex(),
        )
    }

    @Test
    fun deriveFrom660448RawDescriptor() {
        val raw = ByteArray(2 * 66) { ((it * 3 + 1) and 7).toByte() }
        val source = FirstPairSourceSlice.deriveFrom660448RawDescriptor(raw, offset = 0, length = 16)
        assertEquals(
            "040401010705040302070407030002030400040004030507070305050106050402" +
                "070401040604040707070702010000030500040203000304030103030004060301",
            source.toHex(),
        )
    }

    @Test
    fun constructor67076c() {
        val raw = ByteArray(2 * 66) { ((it * 3 + 1) and 7).toByte() }
        assertEquals(
            "706be35f728909a58b2924e4ddb1a8aad7a725fa7f614445cd92feefb611de1c",
            sha256(FirstPairSourceSlice.constructor670978Ptr28Blocks(raw)),
        )
        assertEquals(
            "914e5cf5c7677d9c570a74351fffbd879f75aca534c08426f3042eb2c6212d2b",
            sha256(FirstPairSourceSlice.constructor670a54Ptr10Blocks(raw)),
        )
    }
}
