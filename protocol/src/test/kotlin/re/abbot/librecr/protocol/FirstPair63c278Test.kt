package re.abbot.librecr.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import re.abbot.librecr.protocol.crypto.FirstPairSourceSlice
import java.security.MessageDigest

/** Byte-for-byte parity for the 63c278 schedule linear part (no branch loop). */
class FirstPair63c278Test {
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
    fun initialMixAndTailReducers() {
        val arg0 = ByteArray(88) { ((it * 7 + 3) and 0xff).toByte() }
        val arg1 = ByteArray(88) { ((it * 5 + 11) and 0xff).toByte() }
        val arg2 = ByteArray(88) { ((it * 3 + 17) and 0xff).toByte() }
        val scalar = 0x0123456789abcdefuL

        val (x1, x0) = FirstPairSourceSlice.builder63c278InitialVectors(arg0, arg1)
        assertEquals("7510279962382f9fbfd7acbc437c419e0efe3aa928c3b86a09fa3235880799d1", sha(packU64(x1)))
        assertEquals("cd3066baa97e86c4cd0882325622da57d6283f598225b6c7b19f3e066e18a9d3", sha(packU64(x0)))

        val (x2, x0b) = FirstPairSourceSlice.builder63c278SecondInitialVectors(arg0, arg2)
        assertEquals("571998e49bf13bc6a2e9d7df592371186beacebb27c8fd7d498924f41591f53e", sha(packU64(x2)))
        assertEquals("dbbd9840317de13798bd1cf50ef6fec8ad0e26638b894a62b1cfd14aee785fda", sha(packU64(x0b)))

        val mixed1 = FirstPairSourceSlice.builder63c278ScalarMixVector(x1, x0, scalar)
        val mixed2 = FirstPairSourceSlice.builder63c278ScalarMix2Vector(x2, x0b, scalar)
        assertEquals("4692583a47aa6989ac7d4fab5d20c97ee27970af79f4590824a8268dbf1b27dd", sha(packU64(mixed1)))
        assertEquals("5b7a4c6be8ac3f3c33331e866588d75f17b62dcdf9b1bfab22846801cd262dca", sha(packU64(mixed2)))

        val tail1 = FirstPairSourceSlice.builder63c278Tail1U32Words(mixed1)
        val tail2 = FirstPairSourceSlice.builder63c278Tail2U32Words(mixed2)
        assertEquals("27e1f0bcd8f8555166c80cb3ee788ce5c03a1ee08dde0f2aa8e9c3282a72b472", sha(packU32(tail1)))
        assertEquals("9b840d5956cdaae86b1835984c6bdcfa4b44577c1cfeae66455c2c04739d1e4f", sha(packU32(tail2)))
        assertEquals(listOf(0xc21a61c6u, 0x74c4feafu, 0x58177aecu, 0x7a88bfb1u), tail1.take(4))
        assertEquals(listOf(0xc822edf3u, 0x3210de15u, 0x669f83ceu, 0x9d56a88eu), tail2.take(4))

        val accum = FirstPairSourceSlice.builder63c278AccumulatorStreams(arg2, tail2)
        assertEquals("b5ba8730b4f348f2bead511a543354ac3cba1388e04d737af3487124cbc79598", sha(packU64(accum.sp440)))
        assertEquals("2f26f4d41701f9596582f852831caeede87aa078ff624ab70e59dad3eb170b5d", sha(packU64(accum.sp4f0)))
        assertEquals("84932501eaef1bcf7c0b58a51b1bf8653c46ae9527a759b0469ff2653e43db03", sha(packU64(accum.sp5a0)))
        assertEquals("22518465559d66c1e916c293fe4bc2e9380a5018f23d213d7bedfaef9f3f746b", sha(packU64(accum.sp390)))

        val bridgeConv = FirstPairSourceSlice.builder63c278BridgeConvolutionVector(accum)
        val bridgeX0 = FirstPairSourceSlice.builder63c278BridgeX0Vector(arg0)
        val bridgeMix = FirstPairSourceSlice.builder63c278BridgeMixVector(bridgeConv, bridgeX0, scalar)
        val sp128 = FirstPairSourceSlice.builder63c278BridgeSP128Words(bridgeMix)
        assertEquals("9d307ee87af9694b08e35935470c931390def241ff8cf03071b0c97e9288a7e6", sha(packU64(bridgeConv)))
        assertEquals("ba1a0930d3edc74b7b1cbc65257d0d5e0b84935ba8baf2a8d1bffb131618f370", sha(packU64(bridgeX0)))
        assertEquals("51c7581842c0ac902cde4145e6edec72344c6b174e1637b7804a54cf8c639e1c", sha(packU64(bridgeMix)))
        assertEquals("eacc144f8735791a4d91c9c60617e7203b18f9338142c63fd549bc9e19957670", sha(packU32(sp128)))

        val prebranch = FirstPairSourceSlice.builder63c278PrebranchInitialStreams(arg0, tail1, sp128)
        val pre4f0 = FirstPairSourceSlice.builder63c278PrebranchSP4F0Words(arg0)
        val pre230 = FirstPairSourceSlice.builder63c278PrebranchSP230Words(pre4f0)
        val pre5a0 = FirstPairSourceSlice.builder63c278PrebranchSP5A0Words(pre230)
        assertEquals("bb76f8765891dfcb76e25a5b078bf9a703142137ab005f646526f4654e693626", sha(packU32(prebranch.sp390)))
        assertEquals("7eb39fcc20f253b51776dd9ea7a697b92813d2db917ca91ed5c8378b1e7fd37c", sha(packU32(prebranch.sp440)))
        assertEquals("a1594bb70ed406c9e25a8cd067e68d56e23292b717ec02cd551456e5ed89f6a4", sha(packU32(prebranch.sp6b0)))
        assertEquals("8ac4e5d77a1070b2926a06ef3c8fbb01a0c4618c8237d5073f4198044ce1c02d", sha(packU32(prebranch.sp658)))
        assertEquals("d9523b2165986722e70834869ba3bc017dfe1c0ae5bc42ca299bb7dabd2450c6", sha(packU32(pre4f0)))
        assertEquals("651bc9459d7ca269bd73de0e77a883f06126649d59e0ed448912291dd626832f", sha(packU32(pre230)))
        assertEquals("fe3dad8c38db4b9a4ebf05fa507d885080f4119e6751a0c30a92ef115b676746", sha(packU32(pre5a0)))
    }

    @Test
    fun scheduleWords() {
        val arg0 = FirstPairSourceSlice.pre63c278Arg0Source
        data class Vec(val arg1: String, val arg2: String, val expected: List<UInt>)
        val vectors = listOf(
            Vec(
                "870f4045410102fa6ae48ed935d4528112946ec5085053dcda8e537f1f02ea84" +
                    "ddb295ec23ff6a8c58b97fed9a2736abd68482c3517b7ad27fbe711c7fb9f" +
                    "32a00c1356f38b3025e143d56dd9017a173d68482c3517b7ad2",
                "d9f6a9980cacf13569f2843968ae9cdc112262a50b5bdb6a435931a1d02896c3" +
                    "ab70c95476b7ea17fb1fadf32aeabc0881ab695ce36c62eca5621fafcf684" +
                    "616c5198a7948a03ad3e82ae3783b32b01681ab695ce36c62ec",
                listOf(0x8c15c5dau, 0x34dd429du, 0x955af9feu, 0x6897e537u, 0x1bad4a31u,
                    0xb3206998u, 0x3bda123du, 0x3fdb46c5u, 0xd42db9fdu, 0x29dc0f3au,
                    0x3b95a64cu, 0xcce6d138u, 0x70227a65u, 0x87ca2121u, 0xefb07a8fu,
                    0xc4749659u, 0x1cd92603u, 0xe0ab3767u, 0x3b95a64cu, 0xcce6d138u),
            ),
            Vec(
                "2576fe36ecd8e7be514212a7129bcb32c361f0d230d0612528124ca25fd446a2" +
                    "5979de6adb6a70c2b534e301985718a0d68482c3517b7ad27fbe711c7fb9f" +
                    "32a00c1356f38b3025e143d56dd9017a173d68482c3517b7ad2",
                "37b2af34160b02ddf8e45aef8f22c626ed2984e27b754dc75c89f9a58cb0b0a8" +
                    "eea62a650526362ea30760fa9319f39781ab695ce36c62eca5621fafcf684" +
                    "616c5198a7948a03ad3e82ae3783b32b01681ab695ce36c62ec",
                listOf(0x04961c3du, 0x1f110752u, 0x271f9e47u, 0x551739bcu, 0x828a0f59u,
                    0xd01fa5beu, 0x6703b5b7u, 0x22e03d75u, 0x9cbed758u, 0x7f4e06d1u,
                    0x3b95a64cu, 0xcce6d138u, 0x70227a65u, 0x87ca2121u, 0xefb07a8fu,
                    0xc4749659u, 0x1cd92603u, 0xe0ab3767u, 0x3b95a64cu, 0xcce6d138u),
            ),
        )
        for (v in vectors) {
            val schedule = FirstPairSourceSlice.builder63c278ScheduleWords(
                arg0, hexToBytes(v.arg1), hexToBytes(v.arg2), FirstPairSourceSlice.pre63c278Scalar,
            )
            assertEquals(v.expected, schedule.toList())
        }
    }
}
