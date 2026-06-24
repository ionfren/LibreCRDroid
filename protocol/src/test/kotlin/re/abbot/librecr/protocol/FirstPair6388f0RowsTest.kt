package re.abbot.librecr.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import re.abbot.librecr.protocol.crypto.FirstPairSourceSlice
import java.security.MessageDigest

/** Byte-for-byte parity for the full 6388f0 caller-row loop through row 59 (the reseed boundary). */
class FirstPair6388f0RowsTest {
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).toHex()

    @Test
    fun rowsThroughRow59() {
        val row0Out0Seed = ByteArray(88) { ((it * 13 + 5) and 0xff).toByte() }
        val row0Out1Seed = ByteArray(88) { ((it * 17 + 11) and 0xff).toByte() }
        val row59Out0Seed = ByteArray(88) { ((it * 19 + 7) and 0xff).toByte() }
        val row59Out1Seed = ByteArray(88) { ((it * 23 + 3) and 0xff).toByte() }
        val starts = FirstPairSourceSlice.builder6388f0FirstPair642f60StreamStarts(row0Out0Seed, row0Out1Seed, row59Out0Seed, row59Out1Seed)
        val row0LowPreimages = FirstPairSourceSlice.Builder6473d0OutputPreimages(
            ByteArray(88) { ((it * 3 + 1) and 0xff).toByte() },
            ByteArray(88) { ((it * 5 + 2) and 0xff).toByte() },
            ByteArray(88) { ((it * 7 + 3) and 0xff).toByte() },
            row0Out1Seed,
            row0Out0Seed,
        )
        val rows = FirstPairSourceSlice.builder6388f0SeededCaller64Rows(
            starts, row0LowPreimages, FirstPairSourceSlice.builder6388f0CallerContextFromBundle(), 60,
        )
        assertEquals(60, rows.size)

        data class E(val i: Int, val cx0: String, val p4: String, val p1: String, val p0: String, val a4: String,
                     val c1: String, val c2: String, val c3: String, val nx0: String, val nx1: String, val nx2: String)
        val expected = listOf(
            E(0, "ff807599b1ce0b18fbafc1f5ef3b1af310536e6a355f84b6fe6e5e7e70cb5d07",
                "32c5c3611fb9ddd8920ced6bd80e2f7823679cbeee2de9f315a1f98e47ade845",
                "c49ad60aa507e639c71430a12067b0eb5d75737460bd9997b020b5760197ceb8",
                "76cebb860262dd83aa186fc63ea614b3af5633e56600dda4d4da79ba840366bd",
                "aa133ccd20f64550f79e24d95edb4bd3840ea41170fb073ccfa7ed2f0a5ec2a6",
                "0c4e075f7394388253e849f3d6640211b122a3b80d4ec2f0fd6215ed2e2fa2d9",
                "50f98bbf26612a10b73c619cc69c6762dc158314f8f92eb9d5d6361129d1fae6",
                "82c27ffaaccebe24cfc57998d8c2db525d9fe19c49bca454cf60e9b09be83457",
                "735de0e678900db387d508151c8fecb730ea63cfd0c032c074d1259e6e312982",
                "c7fee1e743d7292d4cbaf16608f95bfdbe17aaac23696d2a79dff247ef44f833",
                "6b3c31cad24792ed5bef2eed597201dcf6ec3f3a0914bdf5a6db1b3fae3b994a"),
            E(1, "735de0e678900db387d508151c8fecb730ea63cfd0c032c074d1259e6e312982",
                "aa133ccd20f64550f79e24d95edb4bd3840ea41170fb073ccfa7ed2f0a5ec2a6",
                "c49ad60aa507e639c71430a12067b0eb5d75737460bd9997b020b5760197ceb8",
                "76cebb860262dd83aa186fc63ea614b3af5633e56600dda4d4da79ba840366bd",
                "387aa890d590abea241e416b8445b86cfcc0a167dfc7b108d746e462070d677e",
                "b8336b40eeb0d42767f8ff51ef6a32045fa0c08edb97c957f251290b9620bfcf",
                "c558bc603fed9ec13aa0b2479ca91d9eb5e57f14aabf113cfc8771dbc6525324",
                "2d6815f226308263a2648eea425e228698171b3a5947f7e10a615cb472d694ff",
                "66e37479739d0ec8db1f675bbf6e89320057bb0dae89f52fdf0f0f72aa4d3f84",
                "bf69b44f03b7832f07b7ca5e3be463f2884c3a52756834101008fdc9e76d5e7c",
                "95334b88f87b86184812a0451dbbf2138087eaa12cc3748af820ab8645959c5e"),
            E(58, "0f8cf013564d57471ee687ff1259e4a475284ac082bda3c145d1b39ba10735ff",
                "f8397a23467f7b742df4ac4ad0ef5039a53da85e2c59ecfb9201bf33284bedb3",
                "c49ad60aa507e639c71430a12067b0eb5d75737460bd9997b020b5760197ceb8",
                "76cebb860262dd83aa186fc63ea614b3af5633e56600dda4d4da79ba840366bd",
                "c0a36f54bde4a40820313a1f342aafece509cf5688f1cc6db298966d2c3d0fca",
                "1ea5c372b85c3d5a6d7653ed5e4eb6d3675633ed78224c5b61995994cfc8860e",
                "3ecc46b9fe2761cebc879dd2449b713f0bfadfd62091540e71412c246ef094c5",
                "89751b3b26a68d55cfd1b35764b4f26cf8f708bc04303cabc54228e8b20aaab6",
                "4ea5999b0919caf0302f7a36d44c30f88f5f0ea65ad455d7c8ad87c4d5e3af1a",
                "17c85652013c611c936ffda604594177fdc765a9b180be98c5a0edcff665903b",
                "88a7fe81fc5d1f1b6d5b24290c54f843de57fb3339e423afdb8a0e2da0dfe934"),
            E(59, "a2105eb9e12ffa599c55ff1714223addc7fb0fcfa9fb7b6ed4bec1acbcf1c31c",
                "c0a36f54bde4a40820313a1f342aafece509cf5688f1cc6db298966d2c3d0fca",
                "378ca711504b9c45e43c6abb6bbcfc018c3f1eb73af7be15195da5c2498d985d",
                "297391c138bee4a4837718a9e29bc9f007a0fd05321b8a84c0d2281d0111f905",
                "d6f95c8bd7aa8b4bdadcd02c4564a79ffa05c47fbc9c9f742a568e898e62dacc",
                "fde9684c6e046abd640e0d23a7df62c944606b55dae31bf0abb273861c7cb8b5",
                "93f147f5441ccbe38cdb51420c5c9a581a3cc59437ab74f6c829ed38276c898e",
                "0243976e2069612ff32ef284e79dfc3b11383403d465954c6b7d5e30aa5988ac",
                "2df364928960728a222d1a524f62a1affac4a974f17d06728aba4d1f0ec9772e",
                "5bbcc6cfaa034ffcc8f11766af29399c0717143569892f2cd0283ca21fb64338",
                "8f8f1879368b68c69b226763814a7c4cff7ed661c93283b3bc1f3c13b66f0ee4"),
        )
        for (e in expected) {
            val row = rows[e.i]
            assertEquals(e.i, row.index)
            assertEquals(e.cx0, sha(row.current642f60.x0), "cx0 ${e.i}")
            assertEquals(e.p4, sha(row.preimages.out4), "p4 ${e.i}")
            assertEquals(e.p1, sha(row.preimages.out1), "p1 ${e.i}")
            assertEquals(e.p0, sha(row.preimages.out0), "p0 ${e.i}")
            assertEquals(e.a4, sha(row.after6473d0.out4), "a4 ${e.i}")
            assertEquals(e.c1, sha(row.first64cd40.output), "c1 ${e.i}")
            assertEquals(e.c2, sha(row.second64cd40.output), "c2 ${e.i}")
            assertEquals(e.c3, sha(row.third64cd40.output), "c3 ${e.i}")
            assertEquals(e.nx0, sha(row.next642f60.x0), "nx0 ${e.i}")
            assertEquals(e.nx1, sha(row.next642f60.x1), "nx1 ${e.i}")
            assertEquals(e.nx2, sha(row.next642f60.x2), "nx2 ${e.i}")
        }
    }
}
