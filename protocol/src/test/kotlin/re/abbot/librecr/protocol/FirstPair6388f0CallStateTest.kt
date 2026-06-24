package re.abbot.librecr.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import re.abbot.librecr.protocol.crypto.FirstPairSourceSlice
import java.security.MessageDigest

/** Byte-for-byte parity for the 6388f0 First/Second/Third 64cd40 CallState composers. */
class FirstPair6388f0CallStateTest {
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).toHex()

    private val in2 = ByteArray(88) { ((it * 21 + 7) and 0xff).toByte() }
    private val in0 = ByteArray(88) { ((it * 15 + 2) and 0xff).toByte() }
    private val in1 = ByteArray(88) { ((it * 13 + 9) and 0xff).toByte() }
    private val out0Seed = ByteArray(88) { ((it * 13 + 5) and 0xff).toByte() }
    private val out1Seed = ByteArray(88) { ((it * 17 + 11) and 0xff).toByte() }

    private val context = FirstPairSourceSlice.builder6388f0CallerContextFromBundle()
    private val result = FirstPairSourceSlice.builder6473d0Outputs(in0, in1, in2, context, out0Seed, out1Seed)
    private val preimages = FirstPairSourceSlice.Builder6473d0OutputPreimages(result.out4, result.out3, result.out2, result.out1, result.out0)
    private val stack20 = FirstPairSourceSlice.builder6473d0MinimalStack20FromPreimages(preimages)
    private val postVectors = FirstPairSourceSlice.builder6473d0PostVectors(result)

    /** row = entryIndex, x2sha, x2prefix, x2suffix, winSha, winPrefix, winSuffix, outSha, outPrefix, outSuffix */
    private data class Row(
        val idx: Int, val x2: String, val x2p: String, val x2s: String,
        val win: String, val winP: String, val winS: String,
        val out: String, val outP: String, val outS: String,
    )

    private fun check(call: FirstPairSourceSlice.Builder6388f0Caller64Call, row: Row, arg0Sha: String, x3Sha: String) {
        assertEquals(0x68404ef676a9b7d3uL, call.scalar)
        assertEquals(88, call.arg0.size)
        assertEquals(arg0Sha, sha(call.arg0))
        assertEquals(352, call.x2Workspace.size)
        assertEquals(row.x2, sha(call.x2Workspace), "x2 entry ${row.idx}")
        assertEquals(row.x2p, call.x2Workspace.copyOfRange(0, 8).toHex(), "x2prefix ${row.idx}")
        assertEquals(row.x2s, call.x2Workspace.copyOfRange(344, 352).toHex(), "x2suffix ${row.idx}")
        assertEquals(88, call.x3Preimage.size)
        assertEquals(x3Sha, sha(call.x3Preimage), "x3 entry ${row.idx}")
        assertEquals(0xb50, call.stackWindow.size)
        assertEquals(row.win, sha(call.stackWindow), "window ${row.idx}")
        assertEquals(row.winP, call.stackWindow.copyOfRange(0, 8).toHex(), "winprefix ${row.idx}")
        assertEquals(row.winS, call.stackWindow.copyOfRange(0xb50 - 8, 0xb50).toHex(), "winsuffix ${row.idx}")
        assertEquals(88, call.output.size)
        assertEquals(row.out, sha(call.output), "output ${row.idx}")
        assertEquals(row.outP, call.output.copyOfRange(0, 8).toHex(), "outprefix ${row.idx}")
        assertEquals(row.outS, call.output.copyOfRange(80, 88).toHex(), "outsuffix ${row.idx}")
    }

    @Test
    fun firstCallState() {
        val rows = listOf(
            Row(0, "9b74a2232218a70449b125c5b25a1be077125ce8b113783a46454ed02d816021", "9b8cd70e7c008f70", "adfaa15b83fb7c0a",
                "e9a7694cd9ab4b0bae1d8434ff29d370977c0828549628487f9a1395e4469f8e", "22604f4f42c846ff", "0000000000000000",
                "a87ab02c52a3f0e4d24c373618e06f5ed46879e1cfd78bad86063abb421dbbc4", "a29c55c2cd095992", "21f8df859b8bfaae"),
            Row(17, "80e40a3e574da19ee4e2698456b25369913b97fa6b5a8c5692dc57e96cef45cb", "15c9a7f3b301eb2e", "adfaa15b83fb7c0a",
                "4d9957cb000e2390f1f3351a15f22f85d4dde632936f68fc4e91ecafbdf3476e", "22604f4f42c846ff", "0000000000000000",
                "8970960806c647297af6a2c2e803fc62cb1a188b546b1234c35b507b3854788c", "f17f9f4f39b1673b", "21f8df859b8bfaae"),
            Row(58, "433d81b9f713d296a31dbd232f7456b5545ca0f89640d5c98f257737207dad79", "c18fefc075d749c5", "adfaa15b83fb7c0a",
                "ec7eb53f85b5c2ae678ede450ae0d2ab16d1c8b6cdd35de4c6d8db90aefc3a70", "22604f4f42c846ff", "0000000000000000",
                "fe323876b4ec44df84474ba3d699aa824c9417b8363ab776f23c0c0def8e6359", "dfd4e82cac137e2b", "21f8df859b8bfaae"),
        )
        val arg0Sha = "496aa2bee379c421196b33f0e1ea8ff833a919340d09d4dd9c360e8322c9d362"
        val x3Sha = "10eef285deef7a4b7c82b22aa53589b7833df29de3814649c772bbd5c832f365"
        for (row in rows) {
            val call = FirstPairSourceSlice.builder6388f0Call64Call(
                FirstPairSourceSlice.builder6388f0First64cd40CallState(context, stack20, postVectors, row.idx),
            )
            assertEquals("d6ce5d63de75b391", call.arg0.copyOfRange(0, 8).toHex())
            assertEquals("19ae4d0dc970204b", call.arg0.copyOfRange(80, 88).toHex())
            check(call, row, arg0Sha, x3Sha)
        }
    }

    @Test
    fun secondCallState() {
        val rows = listOf(
            Row(0, "fdf23daa0954614bc792d8a4dfa7bdb95f110bb155ee30e63a3079c2c636d4db", "88d330fdf438d13f", "adfaa15b83fb7c0a",
                "3a3bd479b489dae29e94ac6fe23fb61edf0444b8489d330b0f86a30530cdc2de", "22604f4f42c846ff", "0000000000000000",
                "a88449fc45dda85642e0fb0945bdb49cc80f61fb70f2a2973239c4dc66234551", "543d01d17c9b3d9d", "21f8df859b8bfaae"),
            Row(17, "674d446fa8d4488a5733b4abdea474ae081c4bd0066feff1870a3207d06e935e", "a95d0d81704cb873", "adfaa15b83fb7c0a",
                "889cd7b12cfc4a4dd772e404acdedc3bf64ea0b7f75e38c179381bfa60e47793", "22604f4f42c846ff", "0000000000000000",
                "25556b8bcbaede26866b77bd708a51231b62ba928254d3ebc71e8034c580337f", "47d5daecac25c281", "21f8df859b8bfaae"),
            Row(58, "3bccb00b36a989df5282f89f0d9cad1874be43fc7ade4b7f14c64d16f80696e2", "77572d1aa71cdc21", "adfaa15b83fb7c0a",
                "fce4abbb0537b096ee71bcd98efb5f056fead875cf1fd53762d5608671e69580", "22604f4f42c846ff", "0000000000000000",
                "ccdf21b6f656f68f98024e8c8e049460de41630149212b23b38aeec2e6a30235", "02802ed57a5241ee", "21f8df859b8bfaae"),
        )
        val arg0Sha = "496aa2bee379c421196b33f0e1ea8ff833a919340d09d4dd9c360e8322c9d362"
        for (row in rows) {
            val first = FirstPairSourceSlice.builder6388f0Call64Call(
                FirstPairSourceSlice.builder6388f0First64cd40CallState(context, stack20, postVectors, row.idx),
            )
            val call = FirstPairSourceSlice.builder6388f0Call64Call(
                FirstPairSourceSlice.builder6388f0Second64cd40CallState(context, stack20, postVectors, first.output, row.idx),
            )
            // x3Preimage equals first call's output
            assertEquals(first.output.toList(), call.x3Preimage.toList(), "x3==first.output ${row.idx}")
            check(call, row, arg0Sha, sha(first.output))
        }
    }

    @Test
    fun thirdCallState() {
        val rows = listOf(
            Row(0, "27fe07cba0b4e7f62d5da4f07f91f5d1887d8f2038431d7f5d14e6d6c38683eb", "cc1a18d569c77ddd", "adfaa15b83fb7c0a",
                "a9194cbdf9a9f7e76911b05d2641ecddcb6a76e1721f0f1c37cac228a7b7995e", "22604f4f42c846ff", "0000000000000000",
                "a1c8d6d83a91ce22e8025eb97c641fa5396cdb629e7105d3b646767e1c2d6a29", "13e228e615652c1f", "21f8df859b8bfaae"),
            Row(17, "611f5018d6ecd757ec6298405c6da354f4c757fa882244c1dc8e05cf0adadf75", "11af0e262ddece94", "adfaa15b83fb7c0a",
                "c3d48c9a66812e39a6cffe630fdabaea3a4f449953f3a14fdb30b9222bc34524", "22604f4f42c846ff", "0000000000000000",
                "4e61a8e846d745236315e1d28fc341466130e735de858695281b7bd40b10f2e2", "49821358928f5609", "21f8df859b8bfaae"),
            Row(58, "c2ca68df25da9dab6dc07a22501cb037543119fee471ffcf4a139e28240a498b", "d7661ccde60dfbe2", "adfaa15b83fb7c0a",
                "1a99dbd410c58bc5b0fea71ec0a21804bf5151de2b77ee22ca393cd8355531c2", "22604f4f42c846ff", "0000000000000000",
                "6348e4b69ed966be0a14615776c7ad56f938543f74f0fea19539ebdd7b3a1449", "0bfcade05c9c24dd", "21f8df859b8bfaae"),
        )
        val arg0Sha = "496aa2bee379c421196b33f0e1ea8ff833a919340d09d4dd9c360e8322c9d362"
        for (row in rows) {
            val first = FirstPairSourceSlice.builder6388f0Call64Call(
                FirstPairSourceSlice.builder6388f0First64cd40CallState(context, stack20, postVectors, row.idx),
            )
            val second = FirstPairSourceSlice.builder6388f0Call64Call(
                FirstPairSourceSlice.builder6388f0Second64cd40CallState(context, stack20, postVectors, first.output, row.idx),
            )
            val call = FirstPairSourceSlice.builder6388f0Call64Call(
                FirstPairSourceSlice.builder6388f0Third64cd40CallState(context, stack20, postVectors, second.output, row.idx),
            )
            assertEquals(second.output.toList(), call.x3Preimage.toList(), "x3==second.output ${row.idx}")
            check(call, row, arg0Sha, sha(second.output))
        }
    }
}
