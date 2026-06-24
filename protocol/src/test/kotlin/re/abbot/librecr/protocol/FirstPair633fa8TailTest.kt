package re.abbot.librecr.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import re.abbot.librecr.protocol.crypto.FirstPairSourceSlice
import java.security.MessageDigest

/** Byte-for-byte parity for the 633fa8 scalar-window tail backbone. */
class FirstPair633fa8TailTest {
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
    fun tailQwords() {
        val words3ab0 = uintArrayOf(
            0x561f0a13u, 0x2703b81fu, 0xc60ebb71u, 0x13ae9923u, 0x6151794du,
            0xcbd488b3u, 0x105a57bau, 0xbe270b51u, 0x35178421u, 0x9c1e6b02u,
            0x8131d744u, 0x995e53ccu, 0xe98d93e2u, 0xbcf84415u, 0xbfccce8eu,
            0x6c32338cu, 0xd608b5a1u, 0xe7c2db10u, 0x8131d744u, 0x995e53ccu)
        val words3120 = uintArrayOf(
            0xb33842d7u, 0x7b6ba784u, 0xa2f90f36u, 0xde5e2ad7u, 0x3c3537a9u,
            0x81d564f6u, 0x339ab4a2u, 0x999de03bu, 0x56c13b42u, 0xff14a487u,
            0x5a31640cu, 0xc3f85236u, 0x3c1dc79eu, 0x58a8d4a6u, 0x541cb00eu,
            0x63323fcdu, 0x1aa54a16u, 0x01f1b661u, 0x5a31640cu, 0xc3f85236u)
        val words2dfc = uintArrayOf(
            0x9bed19fdu, 0xc70a4d0fu, 0x8257d22bu, 0xe2fafcb3u, 0x02c77d20u,
            0xb5ed0efau, 0x878c1b06u, 0x4bd92d7du, 0x21c6944fu, 0xd3ec5d2fu,
            0x876fda86u, 0x37f3e22au, 0x3cfcd7ceu, 0xabdc16ebu, 0x84ad2f7du,
            0x4bd92d7du, 0xf647adceu, 0xaa7b701eu, 0x876fda86u, 0x37f3e22au)
        val qwords = FirstPairSourceSlice.builder633fa8TailQwordsFromSources(words3ab0, words3120, words2dfc, 0xb6ccf02833a9825euL)
        assertEquals(
            listOf(
                0x278653e978fb8d86uL, 0x01531105e76d5345uL, 0x6ca239d879644a5cuL, 0xa06b5f9758fb4bd5uL,
                0xd4aba6030256919auL, 0x701b8d245771a9c8uL, 0x25f9e61e7612a2cbuL, 0x42af4c71aeed4949uL,
                0xf69e5c8932e52f6cuL, 0x7785655189e16a0fuL, 0x7785655189e16a0fuL, 0x7785655189e16a0fuL,
                0x7785655189e16a0fuL, 0x7785655189e16a0fuL, 0x7785655189e16a0fuL, 0x7785655189e16a0fuL,
                0x7785655189e16a0fuL, 0x7785655189e16a0fuL, 0x7785655189e16a0fuL, 0x7785655189e16a0fuL),
            qwords.toList())
        assertEquals("8718a3b565f0e38d8631d894877d72c491cfaa21abccc8958829a7b0ca97b15d", sha(packU64(qwords)))
        val e10 = FirstPairSourceSlice.builder633fa8E10WordsFromTailQwords(qwords)
        assertEquals(
            "4532bea83bfdabcf74fdaeeb0319a83c051a31e40a620e3bd0db1cd993ed8522" +
                "0000000000000000000000000000000000000000000000000000000000000000000000000000",
            FirstPairSourceSlice.builder633fa8ScalarWindowFromE10Words(e10).toHex())
    }

    @Test
    fun e10Words() {
        val tailQwords = ulongArrayOf(
            0x278653e978fb8d86uL, 0x01531105e76d5345uL, 0x6ca239d879644a5cuL, 0xa06b5f9758fb4bd5uL,
            0xd4aba6030256919auL, 0x701b8d245771a9c8uL, 0x25f9e61e7612a2cbuL, 0x42af4c71aeed4949uL,
            0xf69e5c8932e52f6cuL, 0x7785655189e16a0fuL, 0x7785655189e16a0fuL, 0x7785655189e16a0fuL,
            0x7785655189e16a0fuL, 0x7785655189e16a0fuL, 0x7785655189e16a0fuL, 0x7785655189e16a0fuL,
            0x7785655189e16a0fuL, 0x7785655189e16a0fuL, 0x7785655189e16a0fuL, 0x7785655189e16a0fuL)
        val words = FirstPairSourceSlice.builder633fa8E10WordsFromTailQwords(tailQwords)
        assertEquals(
            listOf(
                0x5a1e4b39u, 0x5e9483afu, 0xcf48138fu, 0x9e28b8cdu, 0x55b48903u,
                0xdefd3261u, 0x2c462f90u, 0x5d22446du, 0x5170b893u, 0xdcd2fa37u,
                0xfaacce40u, 0x997a6babu, 0x7781207bu, 0x182c4538u, 0x5475ee9au,
                0xf1fd3b9cu, 0x8281f8c2u, 0x0ba21025u, 0xfaacce40u, 0x997a6babu),
            words.toList())
        assertEquals("4f7646b6cb17189560193adc7b951d47443edf292ea8213d2481cd8c89ba79a9", sha(packU32(words)))
    }

    @Test
    fun scalarWindow() {
        val e10Words = uintArrayOf(
            0xf15eecb3u, 0x6c31d20du, 0x7a812282u, 0x88c66764u, 0xc7daeb98u,
            0xcb55b447u, 0x7dc4c98au, 0xe8533b12u, 0x3976a2b8u, 0x39a2c9bdu,
            0xa7ca28eau, 0x6e74c495u, 0x06708db4u, 0x5a2caf42u, 0xedb8643du,
            0xd19d3544u, 0x8281f8c2u, 0x0ba21025u, 0xfaacce40u, 0x997a6babu)
        val scalar = FirstPairSourceSlice.builder633fa8ScalarWindowFromE10Words(e10Words)
        assertEquals(70, scalar.size)
        assertEquals(
            "f38d95844ac5834265c854266814d19822125ef87edcfcab64db2fd1a3b4b0e7a" +
                "d6a1fa15f51ce7eea7853023be2e9ecb5a99876f7a8a0e00000000000000000000000000000",
            scalar.toHex())
        assertEquals("af6aea9e701fb090af64b2446d8ccdef01327837f264bbe65b20db784345fa16", sha(scalar))
    }
}
