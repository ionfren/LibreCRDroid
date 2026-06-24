package re.abbot.librecr.protocol.crypto

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement

/**
 * P-256 (secp256r1) ECDH + ECDSA via the JDK provider. Mirrors the byte
 * contracts of the Swift CryptoKit usage:
 *   - public keys are uncompressed X9.63 points (0x04 ‖ X32 ‖ Y32),
 *   - ECDH shared secret is the raw 32-byte X coordinate,
 *   - ECDSA signatures are raw r‖s (64B); verification hashes with SHA-256.
 */
object Ecc {

    private val params: ECParameterSpec by lazy {
        val ap = AlgorithmParameters.getInstance("EC")
        ap.init(ECGenParameterSpec("secp256r1"))
        ap.getParameterSpec(ECParameterSpec::class.java)
    }

    private val fieldSize = 32

    class EphemeralKeyPair(val privateKey: ECPrivateKey, val publicKey65: ByteArray)

    fun generateEphemeral(): EphemeralKeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()
        return EphemeralKeyPair(kp.private as ECPrivateKey, encodeUncompressed(kp.public as ECPublicKey))
    }

    fun privateKeyFromScalarBE(scalar: ByteArray): ECPrivateKey {
        val s = BigInteger(1, scalar)
        val kf = KeyFactory.getInstance("EC")
        return kf.generatePrivate(ECPrivateKeySpec(s, params)) as ECPrivateKey
    }

    fun publicKeyFromUncompressed(point65: ByteArray): ECPublicKey {
        require(point65.size == 65 && point65[0].toInt() == 0x04) { "expected 65-byte uncompressed point" }
        val x = BigInteger(1, point65.copyOfRange(1, 33))
        val y = BigInteger(1, point65.copyOfRange(33, 65))
        val kf = KeyFactory.getInstance("EC")
        return kf.generatePublic(ECPublicKeySpec(ECPoint(x, y), params)) as ECPublicKey
    }

    fun encodeUncompressed(key: ECPublicKey): ByteArray {
        val out = ByteArray(65)
        out[0] = 0x04
        toFixed(key.w.affineX, fieldSize).copyInto(out, 1)
        toFixed(key.w.affineY, fieldSize).copyInto(out, 33)
        return out
    }

    /** ECDH(privateKey, peerPub65) → raw 32-byte X coordinate. */
    fun sharedSecret(privateKey: ECPrivateKey, peerPub65: ByteArray): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(privateKey)
        ka.doPhase(publicKeyFromUncompressed(peerPub65), true)
        val secret = ka.generateSecret()
        return if (secret.size == fieldSize) secret else toFixed(BigInteger(1, secret), fieldSize)
    }

    /** Verify ECDSA-P256(SHA-256) over [payload] with raw r‖s [sigRaw] and 65B key. */
    fun verifyEcdsa(signingPublicKey65: ByteArray, payload: ByteArray, sigRaw: ByteArray): Boolean {
        require(sigRaw.size == 64) { "expected raw r||s signature (64 bytes)" }
        val pub = publicKeyFromUncompressed(signingPublicKey65)
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initVerify(pub)
        sig.update(payload)
        return sig.verify(rawToDer(sigRaw))
    }

    private fun toFixed(v: BigInteger, len: Int): ByteArray {
        var b = v.toByteArray()
        if (b.size == len) return b
        if (b.size == len + 1 && b[0].toInt() == 0) return b.copyOfRange(1, b.size) // strip sign byte
        if (b.size > len) return b.copyOfRange(b.size - len, b.size)
        val out = ByteArray(len)
        b.copyInto(out, len - b.size)
        return out
    }

    /** Convert raw r||s (64B) to a DER-encoded ECDSA signature. */
    private fun rawToDer(raw: ByteArray): ByteArray {
        val r = derInteger(raw.copyOfRange(0, 32))
        val s = derInteger(raw.copyOfRange(32, 64))
        val body = r + s
        return byteArrayOf(0x30) + derLength(body.size) + body
    }

    private fun derInteger(value: ByteArray): ByteArray {
        var v = value
        var i = 0
        while (i < v.size - 1 && v[i].toInt() == 0) i++ // strip leading zeros
        v = v.copyOfRange(i, v.size)
        if (v[0].toInt() and 0x80 != 0) v = byteArrayOf(0) + v // ensure positive
        return byteArrayOf(0x02) + derLength(v.size) + v
    }

    private fun derLength(len: Int): ByteArray =
        if (len < 0x80) byteArrayOf(len.toByte())
        else byteArrayOf(0x81.toByte(), len.toByte())
}
