package re.abbot.librecr.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import re.abbot.librecr.protocol.crypto.FirstPairSourceSlice
import java.security.MessageDigest

/** Byte-for-byte parity for the 6421c0 caller, 5bcf98 P-256 outputs, and HighSeed seeds. */
class FirstPair6421c0Test {
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).toHex()
    private fun packU32(a: UIntArray): ByteArray {
        val out = ByteArray(a.size * 4)
        for (i in a.indices) { var v = a[i]; for (k in 0 until 4) { out[i * 4 + k] = (v and 0xffu).toByte(); v = v shr 8 } }
        return out
    }

    @Test
    fun highSeedHelpers() {
        val x0 = ByteArray(80) { ((it * 11 + 7) and 0xff).toByte() }
        val x1 = ByteArray(88) { ((it * 13 + 3) and 0xff).toByte() }
        val x2 = ByteArray(88) { ((it * 17 + 5) and 0xff).toByte() }
        val output = FirstPairSourceSlice.builder6421c0OutputWords(x0, x1, x2, 0x0123456789abcdefuL)
        assertEquals(
            listOf(
                0xdbc1c7c6u, 0x2033fae4u, 0xdbba46f4u, 0x51d8e106u, 0x06acf332u,
                0x8bad4314u, 0xb5c9adb4u, 0x54da2609u, 0x4ea01830u, 0x00da7af7u,
                0x207da04au, 0xbaa6764du, 0x0e8a02aau, 0x41fc4b04u, 0x299ed743u,
                0xa8d7eaf6u, 0x088c1fe0u, 0x83d47285u, 0x9d6a5499u, 0x640e0bb3u,
                0x799af52du, 0xa7308434u),
            output.toList())
        assertEquals("613beae0326a26de5b07c1bca00a356d6d497e222d8cfe34e4cb84ae26be14a8", sha(packU32(output)))

        val source70 = ByteArray(70) { ((it * 19 + 9) and 0xff).toByte() }
        val highX0 = FirstPairSourceSlice.builder6388f0HighSeedX0SourceFrom5bcf98Output(source70)
        assertEquals(
            "3a2dfcb318b7344cf51e5b96b0815468383cdb6a10f727cbc3e953daf6513562" +
                "261b8e3aeda4df7071901e85677af85bdc709a418e53b9f15fe323f7937078" +
                "c9120920c1c2928a95ed01e2731e03cdea",
            highX0.toHex())

        val secondSource70 = ByteArray(70) { ((it * 23 + 4) and 0xff).toByte() }
        val highSeeds = FirstPairSourceSlice.builder6388f0HighSeedStreamStartSeedsFrom5bcf98Outputs(source70, secondSource70)
        assertEquals(
            "8d85f2399a367b70a58ac991bc36c7604a45a128e91d968a99cb7b3dd020a2b5" +
                "f7b82949a78159b9b962810cbd3e57fd708617d83910a7dbd632aedc0bb5e126" +
                "cb69b28f6590cc6baf92149d7683fe94708617d83910a7db",
            highSeeds.out1.toHex())
        assertEquals("bd9129fe22f4ab7d395e31c3e369cfc6cc62b3109df0b4ec7d82cffa5117d0e2", sha(highSeeds.out0 + highSeeds.out1))

        val scalarWindow = re.abbot.librecr.protocol.hexToBytes(
            "3b588dd68f20da5f883993332cabcda6576645712cdd039d0a8195f4b1c0b52e" +
                "0000000000000000000000000000000000000000000000000000000000000000000000000000")
        val generatorPoint = re.abbot.librecr.protocol.hexToBytes(
            "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296" +
                "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5")
        val p256 = FirstPairSourceSlice.builder5bcf98P256Outputs(scalarWindow, generatorPoint)
        assertEquals(
            "a1e69a746868223565f55b036dcb352ac7ad64457d8304d2a015b5ee90942023" +
                "0000000000000000000000000000000000000000000000000000000000000000000000000000",
            p256.xOutput70.toHex())
        assertEquals(
            "3ac85ab9f4754fade9fb79588ec4d48ef3af4d916151ad0477d595de947261ea" +
                "0000000000000000000000000000000000000000000000000000000000000000000000000000",
            p256.yOutput70.toHex())

        val p256HighSeeds = FirstPairSourceSlice.builder6388f0HighSeedStreamStartSeedsFromScalarP256(scalarWindow, generatorPoint)
        assertEquals("fbc744031431d9fda2ceed80266ee2dfefb9a55e585ea5bbc2666a144379f042", sha(p256HighSeeds.out0))
        assertEquals("f9b223b45fe8ec5687cdcbd18218714f4b261938a4befb37e0ced7b42d012289", sha(p256HighSeeds.out1))
    }
}
