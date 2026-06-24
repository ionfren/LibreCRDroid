package re.abbot.librecr.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import re.abbot.librecr.protocol.crypto.FirstPairSourceSlice
import java.security.MessageDigest

/** Byte-for-byte parity for the 633fa8 null scalar-window path (on-device entropy → prelude). */
class FirstPair633fa8NullTest {
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).toHex()
    private fun packU32(a: UIntArray): ByteArray {
        val out = ByteArray(a.size * 4)
        for (i in a.indices) { var v = a[i]; for (k in 0 until 4) { out[i * 4 + k] = (v and 0xffu).toByte(); v = v shr 8 } }
        return out
    }

    @Test
    fun nullEntrySourcesAndInitial() {
        val sources = FirstPairSourceSlice.builder633fa8NullEntrySourcesFromInvariantEntry()
        assertEquals(0x11a, sources.prologueSource.size)
        assertEquals("b2b08a579ebd69e28c8bbb33b19317c3d4c22cce9ec2a6d60eb81e49c7729115", sha(sources.prologueSource))
        assertEquals("4b29cac325304080f0e7b82a92ffe3ef9c3e252aac748ab6e776673fe7e73db6", sha(packU32(sources.check1SourceWords)))
        assertEquals("8905c6b8cd1d1fec875c168212d3555909e4554ee63432a54744a404db243e4d", sha(packU32(sources.check2SourceWords)))

        val entropy = ByteArray(0x11a) { ((it * 11 + 3) and 0xff).toByte() }
        val initial = FirstPairSourceSlice.builder633fa8NullInitialFromEntropy(entropy, sources.prologueSource)
        assertEquals("23db0a42e5599a320a6384094203a1ecf34a1f7517c94c7fd47267708c760834", sha(initial.maskedEntropy))
        assertEquals("9f50c4c539508ffd0d5a87b37d60997bb1622db044b55662c4d1d6d8bad6f532", sha(initial.cf0))
        assertEquals("0d0d59d1394d720b3d30d2a5f0ae4af4e811d2c9c690767b95d603883164cba5", sha(initial.e10))
        assertEquals("296b545eb6c3114d4b731abf59bbcb43e1e34b321e787683228ceb02c11d9cc2", sha(initial.seedInputs))
        assertEquals("30f124b2c0d6cd19c0bbf4e4f8cf1974e5766ed3171e9556e69979108e74626f", sha(initial.seedBlocks))

        val loop = FirstPairSourceSlice.builder633fa8NullFirstLoopFromBlocks(initial.seedBlocks)
        assertEquals("060504020202040404020204040404010504", loop.finalTLane.toHex())
        assertEquals("652ce3a7810e6b09bf6ce92f7029f7a79599a95db235538aea7e84bec65e21f0", sha(packU32(loop.scheduleWords)))
        assertEquals(listOf(0x77de69c8u, 0xc857bd48u, 0x65000b63u, 0xa6ddb53bu), loop.scheduleWords.take(4))
        assertEquals(listOf(0x7c13a2ceu, 0xe082b5bau, 0xbfaf4d29u, 0xc67887e7u), loop.scheduleWords.takeLast(4))

        val acc = FirstPairSourceSlice.builder633fa8NullScheduleAcceptance(loop.scheduleWords, sources.check1SourceWords, sources.check2SourceWords)
        assertTrue(acc.firstOK); assertTrue(acc.secondOK)
        val rejWords = loop.scheduleWords.copyOf(); rejWords[19] = rejWords[19] xor 1u
        val rej = FirstPairSourceSlice.builder633fa8NullScheduleAcceptance(rejWords, sources.check1SourceWords, sources.check2SourceWords)
        assertFalse(rej.firstOK); assertFalse(rej.secondOK)

        val postAccept = FirstPairSourceSlice.builder633fa8NullPostAcceptBlocks(loop.scheduleWords)
        assertEquals(20 * 16, postAccept.blocks4080.size)
        assertEquals(20 * 16, postAccept.blocks3f40.size)
        assertEquals("a8732537d6be3b54f8d00663ae3d0461ed7974b6861b1095b0d16730c08f9c86", sha(postAccept.blocks4080))
        assertEquals("8975ea6381dc1f9149d202522d21abf7105e2faf2888a306b5122d3c8f6f0b7c", sha(postAccept.blocks3f40))
        assertEquals("01070705070306040206010301020603", postAccept.blocks4080.copyOfRange(0, 16).toHex())
        assertEquals("02030203010200000706020600030203", postAccept.blocks3f40.copyOfRange(0, 16).toHex())

        val prelude = FirstPairSourceSlice.builder633fa8NullPreludeSourceFromPostAccept(postAccept.blocks4080, postAccept.blocks3f40)
        assertEquals(0x10a, prelude.size)
        assertEquals("ed4e5c29dff15da45590bf9bc4ea8b7124af32f51f3add5e786cf77fd36c747d", sha(prelude))
        assertEquals("05000204070405070006020301060606", prelude.copyOfRange(0, 16).toHex())
    }
}
