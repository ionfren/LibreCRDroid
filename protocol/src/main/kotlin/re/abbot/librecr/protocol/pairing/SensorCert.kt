package re.abbot.librecr.protocol.pairing

import re.abbot.librecr.protocol.crypto.Ecc

private fun bytesOf(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

/** Abbott patch-signing public keys (X9.63 P-256). Public verifier material. */
object Libre3PatchSigningKey {
    val level0 = bytesOf(
        0x04,
        0xb6, 0x9d, 0x17, 0x34, 0xf5, 0xe4, 0x25, 0xbc,
        0xc0, 0x57, 0x6a, 0xd1, 0xf7, 0x27, 0xc1, 0x31,
        0x1c, 0x90, 0xb6, 0xea, 0x98, 0x6f, 0x00, 0x6e,
        0x7e, 0x9f, 0x90, 0x96, 0xf6, 0xa8, 0x28, 0x4f,
        0x12, 0xbf, 0x7d, 0xdf, 0xe1, 0x54, 0xa3, 0xf1,
        0xd4, 0x5a, 0x0f, 0x27, 0x34, 0xec, 0xab, 0xca,
        0x6b, 0x9e, 0xb5, 0x6e, 0xe4, 0xec, 0xca, 0x87,
        0x85, 0x3a, 0xd8, 0x53, 0xb6, 0xa6, 0x41, 0x80,
    )
    val level1 = bytesOf(
        0x04,
        0xa2, 0xd8, 0x47, 0x89, 0x90, 0x94, 0x5f, 0x70,
        0xa9, 0x57, 0x0a, 0xde, 0x07, 0xb1, 0x55, 0xbc,
        0x90, 0x4d, 0x2d, 0x38, 0x06, 0x47, 0x58, 0x7b,
        0x12, 0x39, 0x17, 0x01, 0x30, 0x9b, 0xd1, 0x0b,
        0x59, 0x90, 0xc4, 0xc4, 0x7c, 0x47, 0xf1, 0xf0,
        0x80, 0x46, 0xcb, 0x6f, 0x2d, 0xe0, 0x74, 0x8d,
        0x1f, 0xa7, 0xf7, 0x37, 0x90, 0xec, 0x9d, 0x8d,
        0xd6, 0x37, 0x21, 0x27, 0x78, 0x52, 0x88, 0x38,
    )
    val known: List<ByteArray> = listOf(level0, level1)
}

/**
 * Sensor certificate (140 bytes), received in Phase 2. Port of Swift `SensorCert`.
 *   [0..11)   header
 *   [11..76)  sensor STATIC pubkey (65B uncompressed, 0x04 prefix)
 *   [76..140) ECDSA signature (64B raw r‖s) over raw[0..76)
 */
class SensorCert(val raw: ByteArray) {
    val staticPub: ByteArray
    val signature: ByteArray

    init {
        if (raw.size != TOTAL_SIZE) throw SensorCertException.WrongSize(raw.size)
        val pub = raw.copyOfRange(11, 76)
        if (pub[0].toInt() != 0x04) throw SensorCertException.NotUncompressedPoint
        staticPub = pub
        signature = raw.copyOfRange(76, 140)
    }

    val header: ByteArray get() = raw.copyOfRange(0, 11)
    val signedPayload: ByteArray get() = raw.copyOfRange(0, 76)

    fun verifyEcdsa(signingPublicKey: ByteArray): Boolean {
        if (signingPublicKey.size != 65 || signingPublicKey[0].toInt() != 0x04) {
            throw SensorCertException.InvalidSigningPublicKey
        }
        return Ecc.verifyEcdsa(signingPublicKey, signedPayload, signature)
    }

    fun verifiedSigningKeyIndex(signingKeys: List<ByteArray> = Libre3PatchSigningKey.known): Int? {
        for ((index, key) in signingKeys.withIndex()) {
            if (verifyEcdsa(key)) return index
        }
        return null
    }

    companion object {
        const val TOTAL_SIZE = 140
    }
}

sealed class SensorCertException(message: String) : Exception(message) {
    class WrongSize(val size: Int) : SensorCertException("wrong size $size")
    object NotUncompressedPoint : SensorCertException("not an uncompressed point")
    object InvalidSigningPublicKey : SensorCertException("invalid signing public key")
}
