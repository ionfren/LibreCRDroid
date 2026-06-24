package re.abbot.librecr.protocol.pairing

import re.abbot.librecr.protocol.crypto.AesBlock
import re.abbot.librecr.protocol.crypto.AesCcm

/**
 * Phase 5 phone→sensor message (handle 0x002a). Port of Swift `Phase5Challenge`.
 *
 *   plaintext36 = R1 ‖ R2 ‖ tail4
 *   logical40   = ct36 ‖ tag4 (M=4)
 *   wire54      = logical40 ‖ 14 zero pad
 *
 * CCM nonce is the 7-byte trailer from the sensor's 23B challenge notify.
 */
class Phase5Challenge(val ciphertext: ByteArray, val tag: ByteArray) {
    init {
        if (ciphertext.size != CIPHERTEXT_SIZE) throw ChallengeException.WrongCiphertextSize(ciphertext.size)
        if (tag.size != TAG_SIZE) throw ChallengeException.WrongTagSize(tag.size)
    }

    val logicalBytes: ByteArray get() = ciphertext + tag
    val wireBytes: ByteArray get() = logicalBytes + ByteArray(WIRE_SIZE - LOGICAL_SIZE)

    companion object {
        const val PLAINTEXT_SIZE = 36
        const val CIPHERTEXT_SIZE = 36
        const val TAG_SIZE = 4
        const val LOGICAL_SIZE = 40
        const val WIRE_SIZE = 54

        fun encrypt(plaintext: ByteArray, aes: AesBlock, nonce: ByteArray, aad: ByteArray = ByteArray(0)): Phase5Challenge {
            if (plaintext.size != PLAINTEXT_SIZE) throw ChallengeException.WrongPlaintextSize(plaintext.size)
            if (nonce.size != 7) throw ChallengeException.WrongNonceSize(nonce.size)
            val r = AesCcm.encrypt(nonce, plaintext, aad, TAG_SIZE, aes)
            return Phase5Challenge(r.ciphertext, r.tag)
        }

        fun decode(raw: ByteArray): Phase5Challenge {
            if (raw.size != WIRE_SIZE && raw.size != LOGICAL_SIZE) throw ChallengeException.WrongWireSize(raw.size)
            return Phase5Challenge(raw.copyOfRange(0, 36), raw.copyOfRange(36, 40))
        }
    }
}

sealed class ChallengeException(message: String) : Exception(message) {
    class WrongNonceSize(val size: Int) : ChallengeException("wrong nonce size $size")
    class WrongCiphertextSize(val size: Int) : ChallengeException("wrong ciphertext size $size")
    class WrongTagSize(val size: Int) : ChallengeException("wrong tag size $size")
    class WrongPlaintextSize(val size: Int) : ChallengeException("wrong plaintext size $size")
    class WrongKeySize(val size: Int) : ChallengeException("wrong key size $size")
    class WrongWireSize(val size: Int) : ChallengeException("wrong wire size $size")
}
