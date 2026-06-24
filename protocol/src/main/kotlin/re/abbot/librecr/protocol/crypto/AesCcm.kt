package re.abbot.librecr.protocol.crypto

import re.abbot.librecr.protocol.constantTimeEquals
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** A 16-byte AES block-encrypt primitive (standard or white-box). */
typealias AesBlock = (ByteArray) -> ByteArray

class AesCcmException(message: String) : Exception(message) {
    companion object {
        fun invalidParameters() = AesCcmException("invalid CCM parameters")
        fun macMismatch() = AesCcmException("CCM MAC mismatch")
    }
}

/**
 * AES-128-CCM (NIST SP 800-38C) with the AES block primitive supplied as a
 * lambda. Direct port of Swift `AESCCM`. Tag length is configurable; Libre 3
 * Phase 5/6 + data plane use M = 4.
 */
object AesCcm {

    data class Result(val ciphertext: ByteArray, val tag: ByteArray)

    fun encrypt(
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray = ByteArray(0),
        tagLength: Int = 8,
        aes: AesBlock,
    ): Result {
        checkParameters(nonce, tagLength, plaintext.size)
        val mac = cbcMac(nonce, aad, plaintext, tagLength, aes)
        val (ctOut, s0) = ctrApply(plaintext, nonce, aes)
        val tag = xor(mac.copyOf(tagLength), s0.copyOf(tagLength))
        return Result(ctOut, tag)
    }

    fun decrypt(
        nonce: ByteArray,
        ciphertext: ByteArray,
        tag: ByteArray,
        aad: ByteArray = ByteArray(0),
        aes: AesBlock,
    ): ByteArray {
        checkParameters(nonce, tag.size, ciphertext.size)
        val (ptCandidate, s0) = ctrApply(ciphertext, nonce, aes)
        val mac = cbcMac(nonce, aad, ptCandidate, tag.size, aes)
        val expected = xor(mac.copyOf(tag.size), s0.copyOf(tag.size))
        if (!constantTimeEquals(expected, tag)) throw AesCcmException.macMismatch()
        return ptCandidate
    }

    /** CBC-MAC over B_0 || formatted_AAD || zero-padded plaintext. */
    private fun cbcMac(
        nonce: ByteArray,
        aad: ByteArray,
        plaintext: ByteArray,
        tagLength: Int,
        aes: AesBlock,
    ): ByteArray {
        var b = formatHeader(nonce, aad, plaintext.size, tagLength)
        b += plaintext
        if (b.size % 16 != 0) b += ByteArray(16 - (b.size % 16))
        var y = ByteArray(16)
        for (i in 0 until b.size / 16) {
            val block = b.copyOfRange(i * 16, (i + 1) * 16)
            y = aes(xor(y, block))
        }
        return y
    }

    private fun checkParameters(nonce: ByteArray, tagLength: Int, plaintextLen: Int) {
        val l = 15 - nonce.size
        if (l < 2 || l > 8) throw AesCcmException.invalidParameters()
        if (tagLength !in intArrayOf(4, 6, 8, 10, 12, 14, 16)) throw AesCcmException.invalidParameters()
        if (plaintextLen < 0) throw AesCcmException.invalidParameters()
        if (l < 8) {
            val max = (1L shl (l * 8)) - 1
            if (plaintextLen.toLong() > max) throw AesCcmException.invalidParameters()
        }
    }

    private fun formatHeader(nonce: ByteArray, aad: ByteArray, plaintextLen: Int, tagLength: Int): ByteArray {
        val l = 15 - nonce.size
        val b0 = ByteArray(16)
        val aFlag = if (aad.isEmpty()) 0 else 0x40
        val m = (tagLength - 2) / 2
        val lFlag = l - 1
        b0[0] = (aFlag or (m shl 3) or lFlag).toByte()
        System.arraycopy(nonce, 0, b0, 1, nonce.size)
        var q = plaintextLen.toLong()
        for (i in 0 until l) {
            b0[15 - i] = (q and 0xff).toByte()
            q = q ushr 8
        }

        if (aad.isEmpty()) return b0

        val enc = ArrayList<Byte>()
        val a = aad.size.toLong()
        when {
            a < 0xFF00L -> {
                enc.add(((a ushr 8) and 0xff).toByte())
                enc.add((a and 0xff).toByte())
            }
            a <= 0xFFFFFFFFL -> {
                enc.add(0xFF.toByte()); enc.add(0xFE.toByte())
                val v = a
                for (s in intArrayOf(24, 16, 8, 0)) enc.add(((v ushr s) and 0xff).toByte())
            }
            else -> {
                enc.add(0xFF.toByte()); enc.add(0xFF.toByte())
                for (s in intArrayOf(56, 48, 40, 32, 24, 16, 8, 0)) enc.add(((a ushr s) and 0xff).toByte())
            }
        }
        for (byte in aad) enc.add(byte)
        if (enc.size % 16 != 0) repeat(16 - (enc.size % 16)) { enc.add(0) }
        return b0 + enc.toByteArray()
    }

    /** CTR mode: returns (output, S_0) where S_0 = AES(A_0). */
    private fun ctrApply(input: ByteArray, nonce: ByteArray, aes: AesBlock): Pair<ByteArray, ByteArray> {
        val l = 15 - nonce.size
        val a = ByteArray(16)
        a[0] = (l - 1).toByte()
        System.arraycopy(nonce, 0, a, 1, nonce.size)
        val s0Block = a.copyOf()
        for (i in (16 - l) until 16) s0Block[i] = 0
        val s0 = aes(s0Block)

        val out = ByteArray(input.size)
        var ctr = 1L
        var idx = 0
        while (idx < input.size) {
            val ai = a.copyOf()
            var c = ctr
            for (j in 0 until l) {
                ai[15 - j] = (c and 0xff).toByte()
                c = c ushr 8
            }
            val s = aes(ai)
            val take = minOf(16, input.size - idx)
            for (k in 0 until take) out[idx + k] = (input[idx + k].toInt() xor s[k].toInt()).toByte()
            idx += take
            ctr += 1
        }
        return out to s0
    }

    private fun xor(a: ByteArray, b: ByteArray): ByteArray {
        val n = minOf(a.size, b.size)
        val out = ByteArray(n)
        for (i in 0 until n) out[i] = (a[i].toInt() xor b[i].toInt()).toByte()
        return out
    }

    /** AES-128 ECB block-encrypt under [key] (standard-AES paths, e.g. data plane). */
    fun jceAesBlock(key: ByteArray): AesBlock {
        require(key.size == 16) { "AES-128 requires a 16-byte key" }
        val spec = SecretKeySpec(key, "AES")
        return { input ->
            require(input.size == 16)
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, spec)
            cipher.doFinal(input)
        }
    }
}
