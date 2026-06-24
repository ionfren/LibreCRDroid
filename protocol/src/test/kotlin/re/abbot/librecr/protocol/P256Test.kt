package re.abbot.librecr.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import re.abbot.librecr.protocol.crypto.P256ScalarMultiplier
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * Validates the clean-room P-256 scalar multiplier against the JDK EC provider:
 * for a random keypair (scalar s, public point P), our multiply(s, G) must equal
 * P. No hardcoded vectors — fully self-checking.
 */
class P256Test {
    private val gx = hexToBytes("6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296")
    private val gy = hexToBytes("4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5")

    @Test
    fun scalarTimesGeneratorMatchesJdk() {
        repeat(8) {
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp256r1"))
            val kp = kpg.generateKeyPair()
            val s = (kp.private as ECPrivateKey).s
            val pub = (kp.public as ECPublicKey).w

            val sLE = toFixed32BE(s).reversedArray()
            val product = P256ScalarMultiplier.multiply(sLE, P256ScalarMultiplier.AffinePoint(gx, gy))

            assertEquals(pub.affineX, fieldToBigInteger(product.x), "X mismatch")
            assertEquals(pub.affineY, fieldToBigInteger(product.y), "Y mismatch")
        }
    }

    private fun fieldToBigInteger(f: re.abbot.librecr.protocol.crypto.Field): BigInteger {
        val le32 = f.littleEndianPadded70.copyOfRange(0, 32)
        return BigInteger(1, le32.reversedArray())
    }

    private fun toFixed32BE(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        val out = ByteArray(32)
        if (raw.size <= 32) raw.copyInto(out, 32 - raw.size)
        else raw.copyInto(out, 0, raw.size - 32, raw.size) // strip leading sign byte
        return out
    }
}
