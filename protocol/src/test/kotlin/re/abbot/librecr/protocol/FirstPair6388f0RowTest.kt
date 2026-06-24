package re.abbot.librecr.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import re.abbot.librecr.protocol.crypto.FirstPairSourceSlice
import java.security.MessageDigest

/** Byte-for-byte parity for the 6388f0 seeded caller row (642f60→6473d0→3×64cd40→next inputs). */
class FirstPair6388f0RowTest {
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).toHex()

    private val current = FirstPairSourceSlice.Builder6388f0Next642f60Inputs(
        ByteArray(88) { ((it * 15 + 2) and 0xff).toByte() },
        ByteArray(88) { ((it * 13 + 9) and 0xff).toByte() },
        ByteArray(88) { ((it * 21 + 7) and 0xff).toByte() },
    )
    private val preimages = FirstPairSourceSlice.Builder6473d0OutputPreimages(
        ByteArray(88) { ((it * 3 + 1) and 0xff).toByte() },
        ByteArray(88) { ((it * 5 + 2) and 0xff).toByte() },
        ByteArray(88) { ((it * 7 + 3) and 0xff).toByte() },
        ByteArray(88) { ((it * 17 + 11) and 0xff).toByte() },
        ByteArray(88) { ((it * 13 + 5) and 0xff).toByte() },
    )
    private val context = FirstPairSourceSlice.builder6388f0CallerContextFromBundle()

    private data class Row(val idx: Int, val c1: String, val c2: String, val c3: String, val nx0: String, val nx1: String, val nx2: String)

    @Test
    fun seededRow() {
        val rows = listOf(
            Row(0,
                "5d2fd9d65710079e70120c4b41d30ef96b43de537f0812f9c55bcde5d13da6ff",
                "a1738cb1be833dfc46837e95defc6b05ce0d477432c31ce23185931226fd54c2",
                "4d643ba15818807886f8ce5a4b5b3dfdd1eaeb0c1fa1aa217372ef3c41daee36",
                "16c108e1f0d3e9f6056c72f55125d51ecebe8499991f335f23e7cbbbb925af77",
                "815963ee2b9a9870282a384b356ef0593e1dafb8b8db3721803374854b2a5a7e",
                "c92bd26c7e87a582242cd62421ca05a6fd2820dead0b9fa14002882b331c0e79"),
            Row(17,
                "02afcb4bc9530532a885d949bd994c13c953de5ea643dbf476a98a35a7133bfa",
                "2a13bd55dca210d9687243e8871f8e71a130da7ca0d0bd19d3fdc446dd3ff7eb",
                "760c499e675bb4a49c79f3c4ce2ffda348cf28a8238e950121f82ca6b79e6e3e",
                "08d621986111b3b183965665743855d3e3babc160708137f5f38c9ffe236675c",
                "645a5a07228ceb9f3da9396abcdc1b052b50ffe7196e746dfbe9d4b3fc4cae61",
                "1a9300451e9d8f4398354952d1dddfabbbd5123f77649e1b59746df4921096a4"),
            Row(58,
                "843342ef8bf55e0707d422f4b7df49fe1955f7c2fd9093169d3ce09045ee8c76",
                "81f4b91efc1d50c84ff8d861a4f1cd2fe1aa4e70f7a289bc16570faac0f55e92",
                "3b13937db283e3408050fbb9b07c6b1445ea6d0799c5bbecbbe0cf4edff68730",
                "4cd9881f383c036b27aa7956269a5e7e40863b7849f112c7abae3f459a7143d2",
                "e8e0c80abbcc900e14eaf167711b0aac4b705109f47b0311ea941e3fcc41d32c",
                "011ae4ecb8fdee4ed0158a4e198f43df9aed402ddbc981695e22ff8dfc113059"),
        )
        for (e in rows) {
            val row = FirstPairSourceSlice.builder6388f0SeededCaller64Row(e.idx, current, preimages, context)
            assertEquals(e.idx, row.index)
            assertEquals("e4e4bc44d23db2b617f3d9a3f84a9dc1a6767d4d242a4c52b8427c587148a813", sha(row.after642f60.out0))
            assertEquals("f6e027253992cc3f10bc117332271b9c97a5e6570be183b0808309bcc759bfd2", sha(row.after642f60.out1))
            assertEquals("9c0ec2ac6f581933c3e457e0c4267507a575e1280caf5cbe09b962f265307e92", sha(row.after642f60.out2))
            assertEquals("0d5c73fceaada7a6b59cc13b20292c84f7ed53adb1761938dcc102b24df3b333", sha(row.after6473d0.out2))
            assertEquals("7d7898d5e1c1655ad924fba7ba2ecfd10669e8215583328da2e3b5e579c6d1a2", sha(row.after6473d0.out3))
            assertEquals("4f8334dadc445d8e94bb4478cc915f6b7ed5097d904b916608a7f96b291c8c1d", sha(row.after6473d0.out4))
            assertEquals("2a768e7ee55607748ec665cd8f02d2afa8e7585fac8796918438dc443bde1df4", sha(row.minimalStack20))
            assertEquals(e.c1, sha(row.first64cd40.output), "first ${e.idx}")
            assertEquals(e.c2, sha(row.second64cd40.output), "second ${e.idx}")
            assertEquals(e.c3, sha(row.third64cd40.output), "third ${e.idx}")
            assertEquals(e.nx0, sha(row.next642f60.x0), "nx0 ${e.idx}")
            assertEquals(e.nx1, sha(row.next642f60.x1), "nx1 ${e.idx}")
            assertEquals(e.nx2, sha(row.next642f60.x2), "nx2 ${e.idx}")
        }
    }
}
