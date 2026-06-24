package re.abbot.librecr.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import re.abbot.librecr.protocol.crypto.Ecc
import re.abbot.librecr.protocol.crypto.LibAes
import re.abbot.librecr.protocol.pairing.Phase5Challenge
import re.abbot.librecr.protocol.pairing.Phase6Response

class PairingGoldenTest {

    @Test
    fun phase5Challenge() {
        for (i in 0 until Golden.arr("phase5_challenge").length()) {
            val v = Golden.arr("phase5_challenge").getJSONObject(i)
            val aes = LibAes.phase5BlockEncryptor(v.bytes("rawKey"))
            val pt = v.bytes("r1") + v.bytes("r2") + v.bytes("tail4")
            val ch = Phase5Challenge.encrypt(pt, aes, v.bytes("nonce"))
            assertEquals(v.getString("logical"), ch.logicalBytes.toHex(), "phase5[$i]")
        }
    }

    @Test
    fun phase6() {
        for (i in 0 until Golden.arr("phase6").length()) {
            val v = Golden.arr("phase6").getJSONObject(i)
            val resp = Phase6Response.decode(v.bytes("wire"))
            assertEquals(v.getString("tag"), resp.tag.toHex(), "phase6[$i] tag")
            assertEquals(v.getString("nonce"), resp.nonce.toHex(), "phase6[$i] nonce")
            val m = resp.decrypt(v.bytes("rawKey"))
            assertEquals(v.getString("r2"), m.phoneR2.toHex(), "phase6[$i] r2")
            assertEquals(v.getString("r1"), m.sensorR1.toHex(), "phase6[$i] r1")
            assertEquals(v.getString("kEnc"), m.kEnc.toHex(), "phase6[$i] kEnc")
            assertEquals(v.getString("ivEnc"), m.ivEnc.toHex(), "phase6[$i] ivEnc")
        }
    }

    @Test
    fun ecdh() {
        val v = Golden.obj("ecdh")
        val priv = Ecc.privateKeyFromScalarBE(v.bytes("privA"))
        val shared = Ecc.sharedSecret(priv, v.bytes("pubB65"))
        assertEquals(v.getString("shared"), shared.toHex(), "ecdh shared")
    }

    @Test
    fun ecdsa() {
        for (i in 0 until Golden.arr("ecdsa").length()) {
            val v = Golden.arr("ecdsa").getJSONObject(i)
            val ok = Ecc.verifyEcdsa(v.bytes("pub65"), v.bytes("payload"), v.bytes("sigRaw"))
            assertEquals(v.getBoolean("valid"), ok, "ecdsa[$i]")
        }
    }
}
