package re.abbot.librecr.protocol.pairing

import re.abbot.librecr.protocol.appendU16le
import re.abbot.librecr.protocol.appendU32le

enum class NfcActivationCommandCode(val raw: Int) {
    ACTIVATE(0xa0),
    SWITCH_RECEIVER(0xa8),
}

/**
 * NFC activation/switch payload construction + Abbott CRC16. Direct port of
 * Swift `NFCActivationCommand`. All values are uint32 bit patterns in `Int`.
 */
object NfcActivationCommand {
    val readPatchInfo = byteArrayOf(0x02, 0xa1.toByte(), 0x7a)
    const val MANUFACTURER_CODE = 0x7a

    fun metcrc(timeSeconds: Int, receiverID: Int): ByteArray {
        val body = ArrayList<Byte>(8)
        body.appendU32le(timeSeconds)
        body.appendU32le(receiverID)
        val bodyBytes = body.toByteArray()
        val crc = abbottCRC16(bodyBytes)
        val out = ArrayList<Byte>(10)
        out.addAll(bodyBytes.toList())
        out.appendU16le(crc)
        return out.toByteArray()
    }

    fun command(code: NfcActivationCommandCode, timeSeconds: Int, receiverID: Int): ByteArray {
        val head = byteArrayOf(0x02, code.raw.toByte(), 0x7a)
        return head + metcrc(timeSeconds, receiverID)
    }

    /** CRC-CCITT variant: init 0xffff, bit-reversed input bytes, poly 0x1021. */
    fun abbottCRC16(data: ByteArray): Int {
        var crc = 0xffff
        for (b in data) {
            crc = (crc xor ((reverse8(b.toInt() and 0xff)) shl 8)) and 0xffff
            for (i in 0 until 8) {
                crc = if ((crc and 0x8000) != 0) ((crc shl 1) xor 0x1021) else (crc shl 1)
                crc = crc and 0xffff
            }
        }
        return crc and 0xffff
    }

    private fun reverse8(value: Int): Int {
        var x = value
        var out = 0
        for (i in 0 until 8) {
            out = (out shl 1) or (x and 1)
            x = x ushr 1
        }
        return out and 0xff
    }
}
