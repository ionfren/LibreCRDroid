package re.abbot.librecr.protocol.pairing

import re.abbot.librecr.protocol.crypto.AesBlock
import re.abbot.librecr.protocol.crypto.AesCcm
import re.abbot.librecr.protocol.crypto.LibAes

/** Decrypted Phase 6 session material: R2 ‖ R1 ‖ kEnc ‖ ivEnc8. */
class Phase6SessionMaterial(
    val phoneR2: ByteArray,
    val sensorR1: ByteArray,
    val kEnc: ByteArray,
    val ivEnc: ByteArray,
) {
    init {
        if (phoneR2.size != 16) throw Phase6ResponseException.WrongR2Size(phoneR2.size)
        if (sensorR1.size != 16) throw Phase6ResponseException.WrongR1Size(sensorR1.size)
        if (kEnc.size != 16) throw Phase6ResponseException.WrongKEncSize(kEnc.size)
        if (ivEnc.size != 8) throw Phase6ResponseException.WrongIVEncSize(ivEnc.size)
    }
}

/**
 * Phase 6 sensor→phone response (handle 0x002a), 67 bytes. Port of Swift
 * `Phase6Response`.
 *   [0..56)  ciphertext for plaintext56 = R2 ‖ R1 ‖ kEnc ‖ ivEnc8
 *   [56..60) CCM tag (M=4)
 *   [60..67) sensor nonce7
 */
class Phase6Response(val ciphertext: ByteArray, val tag: ByteArray, val nonce: ByteArray) {
    init {
        if (ciphertext.size != CIPHERTEXT_SIZE) throw Phase6ResponseException.WrongCiphertextSize(ciphertext.size)
        if (tag.size != TAG_SIZE) throw Phase6ResponseException.WrongTagSize(tag.size)
        if (nonce.size != 7) throw ChallengeException.WrongNonceSize(nonce.size)
    }

    fun decrypt(aes: AesBlock, aad: ByteArray = ByteArray(0)): Phase6SessionMaterial {
        val plaintext = AesCcm.decrypt(nonce, ciphertext, tag, aad, aes)
        if (plaintext.size != PLAINTEXT_SIZE) throw Phase6ResponseException.WrongPlaintextSize(plaintext.size)
        return Phase6SessionMaterial(
            phoneR2 = plaintext.copyOfRange(0, 16),
            sensorR1 = plaintext.copyOfRange(16, 32),
            kEnc = plaintext.copyOfRange(32, 48),
            ivEnc = plaintext.copyOfRange(48, 56),
        )
    }

    fun decrypt(rawKey: ByteArray, aad: ByteArray = ByteArray(0)): Phase6SessionMaterial =
        decrypt(LibAes.phase5BlockEncryptor(rawKey), aad)

    companion object {
        const val PLAINTEXT_SIZE = 56
        const val CIPHERTEXT_SIZE = 56
        const val TAG_SIZE = 4
        const val LOGICAL_SIZE = 60
        const val WIRE_SIZE = 67

        fun decode(raw: ByteArray): Phase6Response {
            if (raw.size != WIRE_SIZE) throw ChallengeException.WrongWireSize(raw.size)
            return Phase6Response(
                ciphertext = raw.copyOfRange(0, CIPHERTEXT_SIZE),
                tag = raw.copyOfRange(CIPHERTEXT_SIZE, LOGICAL_SIZE),
                nonce = raw.copyOfRange(LOGICAL_SIZE, WIRE_SIZE),
            )
        }
    }
}

sealed class Phase6ResponseException(message: String) : Exception(message) {
    class WrongCiphertextSize(val size: Int) : Phase6ResponseException("wrong ciphertext size $size")
    class WrongTagSize(val size: Int) : Phase6ResponseException("wrong tag size $size")
    class WrongPlaintextSize(val size: Int) : Phase6ResponseException("wrong plaintext size $size")
    class WrongR2Size(val size: Int) : Phase6ResponseException("wrong R2 size $size")
    class WrongR1Size(val size: Int) : Phase6ResponseException("wrong R1 size $size")
    class WrongKEncSize(val size: Int) : Phase6ResponseException("wrong kEnc size $size")
    class WrongIVEncSize(val size: Int) : Phase6ResponseException("wrong ivEnc size $size")
}
