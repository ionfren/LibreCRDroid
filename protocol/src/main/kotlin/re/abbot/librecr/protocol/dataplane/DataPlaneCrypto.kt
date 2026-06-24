package re.abbot.librecr.protocol.dataplane

import re.abbot.librecr.protocol.crypto.AesCcm

/** Data-plane packet descriptors. Direct port of Swift `DataPlanePacketKind`. */
enum class DataPlanePacketKind(val descriptor: ByteArray) {
    KIND0(byteArrayOf(0x00, 0x00, 0x00)),
    HANDSHAKE(byteArrayOf(0x00, 0x00, 0x0f)),
    KIND2(byteArrayOf(0x00, 0x00, 0xf0.toByte())),
    KIND3(byteArrayOf(0x00, 0x0f, 0x00)),
    KIND4(byteArrayOf(0x00, 0xf0.toByte(), 0x00)),
    KIND5(byteArrayOf(0x0f, 0x00, 0x00)),
    KIND6(byteArrayOf(0xf0.toByte(), 0x00, 0x00)),
    PATCH_DATA(byteArrayOf(0x44, 0x00, 0x00));

    companion object {
        /** Outbound PatchDataControl command writes use descriptor index 0. */
        val patchControlWrite = KIND0
    }
}

/**
 * Post-authorization data-plane AES-CCM helper. Direct port of Swift
 * `DataPlaneCrypto`. key = Phase 6 kEnc (16B); nonce13 = seqLE2 ‖ descriptor3 ‖
 * ivEnc8; tag = 4B. The block primitive is standard AES-128 (JCA).
 */
class DataPlaneCrypto(val kEnc: ByteArray, val ivEnc: ByteArray) {
    init {
        if (kEnc.size != 16) throw DataPlaneCryptoException.WrongKEncSize(kEnc.size)
        if (ivEnc.size != 8) throw DataPlaneCryptoException.WrongIVEncSize(ivEnc.size)
    }

    private val aes = AesCcm.jceAesBlock(kEnc)

    fun nonce(sequence: Int, kind: DataPlanePacketKind): ByteArray {
        val out = ByteArray(13)
        out[0] = (sequence and 0xff).toByte()
        out[1] = ((sequence ushr 8) and 0xff).toByte()
        kind.descriptor.copyInto(out, 2)
        ivEnc.copyInto(out, 5)
        return out
    }

    fun decrypt(frame: DataFrame, kind: DataPlanePacketKind): ByteArray {
        if (frame.encrypted.size < TAG_SIZE) throw DataPlaneCryptoException.PayloadTooShort(frame.encrypted.size)
        val tagStart = frame.encrypted.size - TAG_SIZE
        val ciphertext = frame.encrypted.copyOfRange(0, tagStart)
        val tag = frame.encrypted.copyOfRange(tagStart, frame.encrypted.size)
        return AesCcm.decrypt(nonce(frame.sequenceNumber, kind), ciphertext, tag, aes = aes)
    }

    data class DecryptResult(val kind: DataPlanePacketKind, val plaintext: ByteArray)

    fun decryptTryingAllKinds(frame: DataFrame): DecryptResult {
        for (kind in DataPlanePacketKind.entries) {
            val pt = try {
                decrypt(frame, kind)
            } catch (_: Exception) {
                null
            }
            if (pt != null) return DecryptResult(kind, pt)
        }
        throw DataPlaneCryptoException.NoDescriptorMatched
    }

    fun encrypt(plaintext: ByteArray, sequence: Int, kind: DataPlanePacketKind): DataFrame {
        val r = AesCcm.encrypt(nonce(sequence, kind), plaintext, tagLength = TAG_SIZE, aes = aes)
        return DataFrame(
            encrypted = r.ciphertext + r.tag,
            seq = sequence and 0xff,
            type = (sequence ushr 8) and 0xff,
        )
    }

    companion object {
        const val TAG_SIZE = 4
    }
}

sealed class DataPlaneCryptoException(message: String) : Exception(message) {
    class WrongKEncSize(val size: Int) : DataPlaneCryptoException("kEnc size $size")
    class WrongIVEncSize(val size: Int) : DataPlaneCryptoException("ivEnc size $size")
    class PayloadTooShort(val size: Int) : DataPlaneCryptoException("payload too short $size")
    object NoDescriptorMatched : DataPlaneCryptoException("no descriptor matched")
}
