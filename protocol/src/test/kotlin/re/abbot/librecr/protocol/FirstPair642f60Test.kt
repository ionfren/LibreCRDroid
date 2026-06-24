package re.abbot.librecr.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import re.abbot.librecr.protocol.crypto.FirstPairSourceSlice
import java.security.MessageDigest

/** Byte-for-byte parity for 642f60 affine stages + the 64bd0c arg0 reducer. */
class FirstPair642f60Test {
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).toHex()
    private fun packU32(a: UIntArray): ByteArray {
        val out = ByteArray(a.size * 4)
        for (i in a.indices) { var v = a[i]; for (k in 0 until 4) { out[i * 4 + k] = (v and 0xffu).toByte(); v = v shr 8 } }
        return out
    }
    private fun packU64(a: ULongArray): ByteArray {
        val out = ByteArray(a.size * 8)
        for (i in a.indices) { var v = a[i]; for (k in 0 until 8) { out[i * 8 + k] = (v and 0xffuL).toByte(); v = v shr 8 } }
        return out
    }

    @Test
    fun stageSp2a8() {
        val x1 = ByteArray(88) { ((it * 13 + 9) and 0xff).toByte() }
        val sp2a8 = FirstPairSourceSlice.builder642f60StageSP2A8WordsFromX1(x1)
        assertEquals("8f828a7da4493c59a03f2e57e5040ba00114d22495ebc08a9bb71d06bdc115d5", sha(packU32(sp2a8)))
        assertEquals(listOf(0x4a545152u, 0xeb8ceacdu, 0x6ee8542cu, 0x8f614dc2u), sp2a8.take(4))
        assertEquals(listOf(0x9da005cbu, 0x375c139au, 0xc085fb32u, 0x8542933bu), sp2a8.takeLast(4))
    }

    @Test
    fun stageSp1f8() {
        val x0 = ByteArray(88) { ((it * 15 + 2) and 0xff).toByte() }
        val sp1f8 = FirstPairSourceSlice.builder642f60StageSP1F8WordsFromX0(x0)
        assertEquals("3fd4edb09dc91b6ddc9ad925775420a371151828eac3ee32b1e0e92932651e57", sha(packU32(sp1f8)))
        assertEquals(listOf(0x69e6983eu, 0x52adf2b0u, 0x9c3e0b1cu, 0xce05e1cdu), sp1f8.take(4))
        assertEquals(listOf(0xf8aada36u, 0x50d2d9efu, 0x8d23cbd4u, 0x222c26acu), sp1f8.takeLast(4))
    }

    @Test
    fun bd0cArg0Words() {
        val arg0 = ByteArray(88) { ((it * 19 + 3) and 0xff).toByte() }
        val words = FirstPairSourceSlice.builder64bd0cArg0U64Words(arg0)
        assertEquals("959177503b2023288b94d3167c99cd47f8e7fc6f3449aa7f20f3a461d18251f9", sha(packU64(words)))
    }

    private fun packU32Bytes(a: UIntArray) = packU32(a)

    @Test
    fun workspaceChain() {
        val x1 = ByteArray(88) { ((it * 13 + 9) and 0xff).toByte() }
        val x0 = ByteArray(88) { ((it * 15 + 2) and 0xff).toByte() }
        val x2 = ByteArray(88) { ((it * 21 + 7) and 0xff).toByte() }
        val arg0 = ByteArray(88) { ((it * 19 + 3) and 0xff).toByte() }
        val scalar = 0x0fedcba987654321uL

        val sp2a8 = FirstPairSourceSlice.builder642f60StageSP2A8WordsFromX1(x1)
        val firstWorkspace = FirstPairSourceSlice.builder642f60First64bd0cWorkspaceFromX1(x1, sp2a8)
        assertEquals(44 * 8, firstWorkspace.size)
        assertEquals("71999b7359489904962a33e87d4f7d7b045b1d59b6056d2031c3039df85b9003", sha(firstWorkspace))
        assertEquals("086cb880065cb85808ad97f53a270ada48ff6b096bd1aa3ea0268718ad4e3ddf", firstWorkspace.copyOfRange(0, 32).toHex())
        assertEquals("0c413ff42dbed2247aaebca271a82607684e5068580db306084c720cc23e1703", firstWorkspace.copyOfRange(firstWorkspace.size - 32, firstWorkspace.size).toHex())

        val arg0Words = FirstPairSourceSlice.builder64bd0cArg0U64Words(arg0)
        val updated = FirstPairSourceSlice.builder64bd0cWorkspaceAfterUpdate(arg0Words, scalar, firstWorkspace)
        assertEquals("bb1551ac44a49ebd22583cb1eb07bc918a8b522172e049092b8596fc7ab2bf29", sha(updated))
        val output = FirstPairSourceSlice.builder64bd0cFinalU32Words(updated)
        assertEquals("5d3ef1f04a3b810f276531edc05b4901e6ec17dca864503d7ee38c1dae9c5a6e", sha(packU32(output)))
        assertEquals(output.toList(), FirstPairSourceSlice.builder64bd0cOutputWords(arg0, scalar, firstWorkspace).toList())

        val sp1f8 = FirstPairSourceSlice.builder642f60StageSP1F8WordsFromX0(x0)
        val sp300 = FirstPairSourceSlice.builder642f60StageSP300WordsFrom64bd0cOutput(packU32(output))
        val secondWorkspace = FirstPairSourceSlice.builder642f60Second64bd0cWorkspace(sp1f8, sp300)
        assertEquals("ca10191ddcf021d37fe567b3840c74edd67cb2d8eb6f93e062ede78437ac75cd", sha(secondWorkspace))
        assertEquals("b471e2fe79a9a301ac050344d103e81f5a31686d8112adeef233681d13dc7244", secondWorkspace.copyOfRange(0, 32).toHex())

        val secondOutput = FirstPairSourceSlice.builder64bd0cOutputWords(arg0, scalar, secondWorkspace)
        val sp250 = FirstPairSourceSlice.builder642f60StageSP250WordsFrom64bd0cOutput(packU32(secondOutput))
        assertEquals("589183582eca59a369e4539a9af3447aa94a9254685aa95ec7b6425ae70cb98b", sha(packU32(sp250)))

        val thirdWorkspace = FirstPairSourceSlice.builder642f60Third64bd0cWorkspaceFromX2(x2)
        assertEquals("eb364923c9af8081354fdb867b420b25a7671cd6ad29be6a59d6f42575c64b78", sha(thirdWorkspace))
    }

    @Test
    fun outputsWithBundledContext() {
        val x1 = ByteArray(88) { ((it * 13 + 9) and 0xff).toByte() }
        val x0 = ByteArray(88) { ((it * 15 + 2) and 0xff).toByte() }
        val x2 = ByteArray(88) { ((it * 21 + 7) and 0xff).toByte() }
        val r = FirstPairSourceSlice.builder642f60OutputsFromBundledContext(x0, x1, x2)
        assertEquals(88, r.out0.size)
        assertEquals("e4e4bc44d23db2b617f3d9a3f84a9dc1a6767d4d242a4c52b8427c587148a813", sha(r.out0))
        assertEquals("f6e027253992cc3f10bc117332271b9c97a5e6570be183b0808309bcc759bfd2", sha(r.out1))
        assertEquals("9c0ec2ac6f581933c3e457e0c4267507a575e1280caf5cbe09b962f265307e92", sha(r.out2))
        assertEquals("7b97c74090a4e4e2c720abf39d86a1343ba5e1961f206e227b3e06a531bc51ff", sha(r.out0 + r.out1 + r.out2))
    }
}
