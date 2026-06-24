package re.abbot.librecr.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import re.abbot.librecr.protocol.crypto.FirstPairSourceSlice

/** Byte-for-byte parity for the process2 phone-ephemeral public key (entropy → 65-byte 0x04‖x‖y). */
class Process2PublicKeyTest {
    @Test
    fun publicKeyFromEntropyMatchesAndroidEntryTraces() {
        val vectors = listOf(
            "8987c91f1595e8a060e4cba652368ae8797e9113cfd412bebd0ea1a03783ae59ee70d2c947578803b06b275c96632d148b81658bb87a3eabb5755273c40c397f7255f3c1d742df608383fbbfff5a9b9fbc11a1ab525382024c85687cf79c2a391ca7cc309ff82fe098c2d86e49f8b26364153f0bcb8945c887f5a2a7b54d568daa373a86c85c283fbb6285f35dca2d30263c34ce182c1fc63e6022a3c7e6eaebe3a473d3c754bb8f3982172431af66388948aaf5c709f6699b7608dcd161811dda99c61b302f46684433e61ef2afa4dd9f8b0f2472f6120197cdfc0b940ad5f93ac01fc7497fb355c753df9c65fc68721690c35a09550fb3c326e38bcbe37ebb309a680c383967627f58a108e1e94ecd16c5d2bc2f576dabdc7b" to
                "04b60e0f455a1f2ebc3a1246d9311a66722f80fbc0cbdc23d18ae5e50693eed2b1ea74d24eddcc8dd1957cf621a1f5514fcd7b40ec37f18f8c8060db6f8076b121",
            "726d47655b9434b44cd08664665dfb86934638911b6ebcc26420fe124ab654fde722e77f43756603943a8ee8196c6d5f83fc9cfe637e309f6f4b3c8fd5f109596f60b9e4899422925b8a0368b143580541bcaac3b4017b82f38d00c14d46fbe3197ccfa9af048f6b446973c664901b84d362e95086e235e58517883f7b89aef742768adc355131885657b686bdb6bd82feb11591b63f3e9466f0e21f20cc58757ac547f57a21ee59b4816779510bd7d911861a116c40332328cd4ec68579831e76ede1a5c6776c9d114a2788e8aed94b8f50a051da8cd8bdbdf7c77f53ce76ee259d5d568a7b71edd3564f80969a4550a920238d1739b34eceeb275c29f8dfb94796005ff15989a177536119388ed70c8fb6fa72109635da2741" to
                "049cb2d2658568e6685fea83f5051ff703baec07cbca3b10e58600d538b85795db5cd35248bd30f1918627a6d4f2f91ce31d21057279fa790b895b15192d040a99",
        )
        for ((entropyHex, expectedPubKey) in vectors) {
            val key = FirstPairSourceSlice.builderProcess2P5PublicKey65FromEntropy(re.abbot.librecr.protocol.hexToBytes(entropyHex))
            assertEquals(expectedPubKey, key.toHex())
        }
    }
}
