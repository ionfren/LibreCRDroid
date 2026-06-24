package re.abbot.librecr.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import re.abbot.librecr.protocol.ble.BleFraming
import re.abbot.librecr.protocol.crypto.AesCcm
import re.abbot.librecr.protocol.crypto.LibAes
import re.abbot.librecr.protocol.pairing.Libre3ReceiverID
import re.abbot.librecr.protocol.pairing.NfcActivationCommand
import re.abbot.librecr.protocol.pairing.NfcActivationCommandCode

/** Byte-for-byte parity against the Swift implementation (P1 crypto/primitives). */
class CryptoGoldenTest {

    @Test
    fun crc16() {
        for (i in 0 until Golden.arr("crc16").length()) {
            val v = Golden.arr("crc16").getJSONObject(i)
            val crc = NfcActivationCommand.abbottCRC16(hexToBytes(v.getString("data")))
            assertEquals(v.getString("crc"), "%04x".format(crc), "crc16[$i]")
        }
    }

    @Test
    fun nfcMetcrc() {
        for (i in 0 until Golden.arr("nfc_metcrc").length()) {
            val v = Golden.arr("nfc_metcrc").getJSONObject(i)
            val out = NfcActivationCommand.metcrc(v.u32("time"), v.u32("receiver"))
            assertEquals(v.getString("out"), out.toHex(), "nfc_metcrc[$i]")
        }
    }

    @Test
    fun nfcCommand() {
        for (i in 0 until Golden.arr("nfc_command").length()) {
            val v = Golden.arr("nfc_command").getJSONObject(i)
            val code = if (v.getString("code") == "a0") NfcActivationCommandCode.ACTIVATE
            else NfcActivationCommandCode.SWITCH_RECEIVER
            val out = NfcActivationCommand.command(code, v.u32("time"), v.u32("receiver"))
            assertEquals(v.getString("out"), out.toHex(), "nfc_command[$i]")
        }
    }

    @Test
    fun receiverAccountless() {
        for (i in 0 until Golden.arr("receiver_accountless").length()) {
            val v = Golden.arr("receiver_accountless").getJSONObject(i)
            val value = Libre3ReceiverID.accountlessValue(v.getString("id"))
            assertEquals(v.u32("value"), value, "receiver_accountless[$i]")
        }
    }

    @Test
    fun nfcIdentityInput() {
        assertEquals(305419896, Libre3ReceiverID.fromNfcIdentityInput("305419896")?.value)
        assertEquals(0x12345678, Libre3ReceiverID.fromNfcIdentityInput("0x78563412")?.value)
        assertEquals(555962116, Libre3ReceiverID.fromNfcIdentityInput("044f2321")?.value)
        assertEquals(
            555962116,
            Libre3ReceiverID.fromNfcIdentityInput("e4aa685c-a2b2-4a09-9a20-1f013f121867")?.value,
        )
        assertNull(Libre3ReceiverID.fromNfcIdentityInput("0"))
    }

    @Test
    fun framingWrite() {
        for (i in 0 until Golden.arr("framing_write").length()) {
            val v = Golden.arr("framing_write").getJSONObject(i)
            val frags = BleFraming.fragmentForWrite(hexToBytes(v.getString("msg")), v.getInt("chunk"))
            val expected = v.getJSONArray("frags")
            assertEquals(expected.length(), frags.size, "framing_write[$i] count")
            for (j in frags.indices) assertEquals(expected.getString(j), frags[j].toHex(), "framing_write[$i][$j]")
            // round-trip reassembly
            assertEquals(v.getString("msg"), BleFraming.reassembleWrite(frags).toHex(), "framing_write[$i] reassemble")
        }
    }

    @Test
    fun framingNotify() {
        val o = Golden.obj("framing_notify")
        val r = BleFraming.NotifyReassembler()
        val frags = o.getJSONArray("fragments")
        for (i in 0 until frags.length()) r.feed(hexToBytes(frags.getString(i)))
        val takes = o.getJSONArray("take")
        for (i in 0 until takes.length()) {
            val t = takes.getJSONObject(i)
            assertEquals(t.getString("out"), r.take(t.getInt("n")).toHex(), "framing_notify take[$i]")
        }
    }

    @Test
    fun ccmStandard() {
        for (i in 0 until Golden.arr("ccm_std").length()) {
            val v = Golden.arr("ccm_std").getJSONObject(i)
            val aes = AesCcm.jceAesBlock(v.bytes("key"))
            val r = AesCcm.encrypt(v.bytes("nonce"), v.bytes("pt"), v.bytes("aad"), v.getInt("tagLen"), aes)
            assertEquals(v.getString("ctTag"), (r.ciphertext + r.tag).toHex(), "ccm_std[$i] encrypt")
            val pt = AesCcm.decrypt(v.bytes("nonce"), r.ciphertext, r.tag, v.bytes("aad"), aes)
            assertEquals(v.getString("pt"), pt.toHex(), "ccm_std[$i] decrypt")
        }
    }

    @Test
    fun libAesBlock() {
        for (i in 0 until Golden.arr("libaes_block").length()) {
            val v = Golden.arr("libaes_block").getJSONObject(i)
            val ctx = LibAes.keySetup(v.bytes("key"))
            val ct = LibAes.blockEncrypt(v.bytes("pt"), ctx)
            assertEquals(v.getString("ct"), ct.toHex(), "libaes_block[$i]")
        }
    }

    @Test
    fun libAesPhase5Block() {
        for (i in 0 until Golden.arr("libaes_phase5_block").length()) {
            val v = Golden.arr("libaes_phase5_block").getJSONObject(i)
            val ctx = LibAes.keySetup(v.bytes("key"))
            val ct = LibAes.phase5BlockEncrypt(v.bytes("pt"), ctx)
            assertEquals(v.getString("ct"), ct.toHex(), "libaes_phase5_block[$i]")
        }
    }

    @Test
    fun ccmOverLibAesM8() {
        val v = Golden.obj("ccm_libaes_m8")
        val aes = LibAes.blockEncryptor(v.bytes("key"))
        val r = AesCcm.encrypt(v.bytes("nonce"), v.bytes("pt"), tagLength = 8, aes = aes)
        assertEquals(v.getString("ctTag"), (r.ciphertext + r.tag).toHex(), "ccm_libaes_m8")
    }

    @Test
    fun ccmPhase5M4() {
        val v = Golden.obj("ccm_phase5_m4")
        val aes = LibAes.phase5BlockEncryptor(v.bytes("key"))
        val r = AesCcm.encrypt(v.bytes("nonce"), v.bytes("pt"), tagLength = 4, aes = aes)
        assertEquals(v.getString("ctTag"), (r.ciphertext + r.tag).toHex(), "ccm_phase5_m4")
    }
}
