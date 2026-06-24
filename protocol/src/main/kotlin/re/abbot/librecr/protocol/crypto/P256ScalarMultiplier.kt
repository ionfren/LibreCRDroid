package re.abbot.librecr.protocol.crypto

import java.math.BigInteger

/**
 * Clean-room P-256 scalar multiplication used by the first-pair Phase 5 source
 * builder (Swift `P256ScalarMultiplier` + `Field`). Point validation stays
 * value-for-value with the clean-room field implementation; scalar multiplication
 * uses the runtime BigInteger engine and reduces the scalar modulo the P-256 group
 * order before the Jacobian loop.
 *
 * Verified against the JDK EC provider (k·G) in tests.
 */
internal object P256ScalarMultiplier {

    class AffinePoint {
        val x: Field
        val y: Field

        constructor(xBE: ByteArray, yBE: ByteArray) {
            val x = Field.fromBigEndian32(xBE)
            val y = Field.fromBigEndian32(yBE)
            val rhs = x.squared() * x - x.times3() + Field.B
            if (y.squared() != rhs) throw FirstPairSourceException.InvalidP256Point
            this.x = x
            this.y = y
        }

        constructor(x: Field, y: Field) {
            this.x = x
            this.y = y
        }
    }

    fun multiply(scalarLE: ByteArray, point: AffinePoint): AffinePoint {
        val reducedScalarLE = reduceScalar(scalarLE)
        val topBit = highestSetBit(reducedScalarLE) ?: throw FirstPairSourceException.InvalidP256Point
        val addend = BigAffinePoint(point.x.toBigInteger(), point.y.toBigInteger())
        var result = BigJacobianPoint.INFINITY
        for (bit in topBit downTo 0) {
            result = double(result)
            if (scalarBit(reducedScalarLE, bit)) result = addMixed(result, addend)
        }
        return affine(result)
    }

    private fun reduceScalar(scalarLE: ByteArray): ByteArray {
        val reduced = BigInteger(1, scalarLE.reversedArray()).mod(P256_ORDER)
        if (reduced.signum() == 0) throw FirstPairSourceException.InvalidP256Point
        return toFixed32BE(reduced).reversedArray()
    }

    private fun highestSetBit(scalarLE: ByteArray): Int? {
        for (byteIndex in scalarLE.indices.reversed()) {
            val b = scalarLE[byteIndex].toInt() and 0xff
            if (b == 0) continue
            for (bit in 7 downTo 0) if (b and (1 shl bit) != 0) return byteIndex * 8 + bit
        }
        return null
    }

    private fun scalarBit(scalarLE: ByteArray, bit: Int): Boolean =
        ((scalarLE[bit / 8].toInt() and 0xff) shr (bit and 7)) and 1 != 0

    private data class BigAffinePoint(val x: BigInteger, val y: BigInteger)

    private data class BigJacobianPoint(
        val x: BigInteger,
        val y: BigInteger,
        val z: BigInteger,
        val infinity: Boolean = false,
    ) {
        companion object {
            val INFINITY = BigJacobianPoint(BigInteger.ZERO, BigInteger.ONE, BigInteger.ZERO, true)
        }
    }

    private fun double(p: BigJacobianPoint): BigJacobianPoint {
        if (p.infinity || p.y.signum() == 0) return BigJacobianPoint.INFINITY
        val yy = square(p.y)
        val yyyy = square(yy)
        val zz = square(p.z)
        val zzzz = square(zz)
        val s = times4(mul(p.x, yy))
        val m = times3(sub(square(p.x), zzzz))
        val x3 = sub(square(m), s, s)
        val y3 = sub(mul(m, sub(s, x3)), times8(yyyy))
        val z3 = times2(mul(p.y, p.z))
        return BigJacobianPoint(x3, y3, z3)
    }

    private fun addMixed(p: BigJacobianPoint, addend: BigAffinePoint): BigJacobianPoint {
        if (p.infinity) return BigJacobianPoint(addend.x, addend.y, BigInteger.ONE)
        val z1z1 = square(p.z)
        val u2 = mul(addend.x, z1z1)
        val s2 = mul(addend.y, mul(p.z, z1z1))
        val h = sub(u2, p.x)
        if (h.signum() == 0) return if (s2 == p.y) double(p) else BigJacobianPoint.INFINITY
        val hh = square(h)
        val i = times4(hh)
        val j = mul(h, i)
        val r = times2(sub(s2, p.y))
        val v = mul(p.x, i)
        val x3 = sub(square(r), j, v, v)
        val y3 = sub(mul(r, sub(v, x3)), times2(mul(p.y, j)))
        val z3 = sub(square(add(p.z, h)), z1z1, hh)
        return BigJacobianPoint(x3, y3, z3)
    }

    private fun affine(p: BigJacobianPoint): AffinePoint {
        if (p.infinity) throw FirstPairSourceException.InvalidP256Point
        val zInv = p.z.modInverse(P256_PRIME)
        val zInv2 = square(zInv)
        val zInv3 = mul(zInv2, zInv)
        val x = mul(p.x, zInv2)
        val y = mul(p.y, zInv3)
        return AffinePoint(Field.fromBigEndian32(toFixed32BE(x)), Field.fromBigEndian32(toFixed32BE(y)))
    }

    private fun Field.toBigInteger(): BigInteger =
        BigInteger(1, littleEndianPadded70.copyOfRange(0, 32).reversedArray())

    private fun add(a: BigInteger, b: BigInteger): BigInteger = a.add(b).mod(P256_PRIME)
    private fun sub(a: BigInteger, b: BigInteger): BigInteger = a.subtract(b).mod(P256_PRIME)
    private fun sub(a: BigInteger, vararg rest: BigInteger): BigInteger {
        var out = a
        for (v in rest) out = out.subtract(v)
        return out.mod(P256_PRIME)
    }
    private fun mul(a: BigInteger, b: BigInteger): BigInteger = a.multiply(b).mod(P256_PRIME)
    private fun square(a: BigInteger): BigInteger = a.multiply(a).mod(P256_PRIME)
    private fun times2(a: BigInteger): BigInteger = a.shiftLeft(1).mod(P256_PRIME)
    private fun times3(a: BigInteger): BigInteger = a.multiply(BigInteger.valueOf(3)).mod(P256_PRIME)
    private fun times4(a: BigInteger): BigInteger = a.shiftLeft(2).mod(P256_PRIME)
    private fun times8(a: BigInteger): BigInteger = a.shiftLeft(3).mod(P256_PRIME)

    private fun toFixed32BE(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        val out = ByteArray(32)
        if (raw.size <= 32) {
            raw.copyInto(out, 32 - raw.size)
        } else {
            raw.copyInto(out, 0, raw.size - 32, raw.size)
        }
        return out
    }

    private val P256_PRIME = BigInteger(
        "ffffffff00000001000000000000000000000000ffffffffffffffffffffffff",
        16,
    )

    private val P256_ORDER = BigInteger(
        "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551",
        16,
    )
}

/** secp256r1 prime-field element (4×64-bit limbs, little-endian limb order). */
internal class Field(val l0: ULong, val l1: ULong, val l2: ULong, val l3: ULong) : Comparable<Field> {

    val limbs: ULongArray get() = ulongArrayOf(l0, l1, l2, l3)

    override fun equals(other: Any?): Boolean =
        other is Field && l0 == other.l0 && l1 == other.l1 && l2 == other.l2 && l3 == other.l3

    override fun hashCode(): Int = (((l0.hashCode() * 31 + l1.hashCode()) * 31 + l2.hashCode()) * 31 + l3.hashCode())

    override fun compareTo(other: Field): Int = compare(this, other)

    val littleEndianPadded70: ByteArray
        get() {
            val out = ByteArray(70)
            for ((idx, limb) in limbs.withIndex()) {
                var v = limb
                for (b in 0 until 8) {
                    out[idx * 8 + b] = (v and 0xffUL).toByte()
                    v = v shr 8
                }
            }
            return out
        }

    operator fun plus(rhs: Field): Field {
        val (sum, carry) = addRaw(this, rhs)
        if (carry) {
            val (corrected, _) = addRaw(sum, CARRY_CORRECTION)
            return if (corrected >= MODULUS) subRaw(corrected, MODULUS) else corrected
        }
        return if (sum >= MODULUS) subRaw(sum, MODULUS) else sum
    }

    operator fun minus(rhs: Field): Field {
        if (this >= rhs) return subRaw(this, rhs)
        val diff = subRaw(rhs, this)
        return subRaw(MODULUS, diff)
    }

    operator fun times(rhs: Field): Field {
        val product = ULongArray(8)
        val a = limbs
        val b = rhs.limbs
        for (i in 0 until 4) for (j in 0 until 4) addProduct(product, i + j, a[i], b[j])
        return reduce(product)
    }

    fun squared(): Field = this * this
    fun doubled(): Field = this + this
    fun times3(): Field = this.doubled() + this
    fun times4(): Field = this.doubled().doubled()
    fun times8(): Field = this.times4().doubled()

    fun inverted(): Field {
        val exponent = Field(0xffff_ffff_ffff_fffdUL, 0x0000_0000_ffff_ffffUL, 0UL, 0xffff_ffff_0000_0001UL)
        var result = ONE
        for (bit in 255 downTo 0) {
            result = result.squared()
            if (exponent.bit(bit)) result = result * this
        }
        return result
    }

    private fun bit(bit: Int): Boolean = ((limbs[bit / 64] shr (bit and 63)) and 1UL) != 0UL

    companion object {
        val ZERO = Field(0UL, 0UL, 0UL, 0UL)
        val ONE = Field(1UL, 0UL, 0UL, 0UL)
        val MODULUS = Field(0xffff_ffff_ffff_ffffUL, 0x0000_0000_ffff_ffffUL, 0UL, 0xffff_ffff_0000_0001UL)
        val CARRY_CORRECTION = Field(1UL, 0xffff_ffff_0000_0000UL, 0xffff_ffff_ffff_ffffUL, 0x0000_0000_ffff_fffeUL)
        val B = Field(0x3bce_3c3e_27d2_604bUL, 0x651d_06b0_cc53_b0f6UL, 0xb3eb_bd55_7698_86bcUL, 0x5ac6_35d8_aa3a_93e7UL)

        fun fromBigEndian32(bytes: ByteArray): Field {
            if (bytes.size != 32) throw FirstPairSourceException.InvalidP256PointLength(bytes.size)
            return Field(readU64BE(bytes, 24), readU64BE(bytes, 16), readU64BE(bytes, 8), readU64BE(bytes, 0))
        }

        private fun compare(lhs: Field, rhs: Field): Int {
            val a = lhs.limbs
            val b = rhs.limbs
            for (index in 3 downTo 0) {
                if (a[index] < b[index]) return -1
                if (a[index] > b[index]) return 1
            }
            return 0
        }

        private fun addRaw(lhs: Field, rhs: Field): Pair<Field, Boolean> {
            val a = lhs.limbs
            val b = rhs.limbs
            val out = ULongArray(4)
            var carry = false
            for (index in 0 until 4) {
                val s1 = a[index] + b[index]
                val o1 = s1 < a[index]
                val s2 = s1 + (if (carry) 1UL else 0UL)
                val o2 = s2 < s1
                out[index] = s2
                carry = o1 || o2
            }
            return Field(out[0], out[1], out[2], out[3]) to carry
        }

        private fun subRaw(lhs: Field, rhs: Field): Field {
            val a = lhs.limbs
            val b = rhs.limbs
            val out = ULongArray(4)
            var borrow = false
            for (index in 0 until 4) {
                val d1 = a[index] - b[index]
                val o1 = a[index] < b[index]
                val d2 = d1 - (if (borrow) 1UL else 0UL)
                val o2 = d1 < (if (borrow) 1UL else 0UL)
                out[index] = d2
                borrow = o1 || o2
            }
            return Field(out[0], out[1], out[2], out[3])
        }

        private fun addProduct(product: ULongArray, index: Int, lhs: ULong, rhs: ULong) {
            val (high, low) = mulFullWidth(lhs, rhs)
            var carry = addWord(product, index, low)
            carry = addWord(product, index + 1, high + carry)
            if (high == ULong.MAX_VALUE && carry == 0UL) carry = 1UL
            var carryIndex = index + 2
            while (carry != 0UL) {
                carry = addWord(product, carryIndex, carry)
                carryIndex += 1
            }
        }

        private fun addWord(limbs: ULongArray, index: Int, word: ULong): ULong {
            val sum = limbs[index] + word
            val overflow = sum < limbs[index]
            limbs[index] = sum
            return if (overflow) 1UL else 0UL
        }

        /** 64×64 → 128-bit unsigned multiply, returns (high, low). */
        private fun mulFullWidth(a: ULong, b: ULong): Pair<ULong, ULong> {
            val mask = 0xffff_ffffUL
            val aLo = a and mask
            val aHi = a shr 32
            val bLo = b and mask
            val bHi = b shr 32
            val ll = aLo * bLo
            val lh = aLo * bHi
            val hl = aHi * bLo
            val hh = aHi * bHi
            val cross = (ll shr 32) + (lh and mask) + (hl and mask)
            val low = (ll and mask) or (cross shl 32)
            val high = hh + (lh shr 32) + (hl shr 32) + (cross shr 32)
            return high to low
        }

        private fun reduce(limbs: ULongArray): Field {
            var remainder = ZERO
            for (bit in (limbs.size * 64 - 1) downTo 0) {
                remainder = shiftAppendBitModP(remainder, bitSet(limbs, bit))
            }
            return remainder
        }

        private fun shiftAppendBitModP(value: Field, bit: Boolean): Field {
            val limbs = value.limbs
            val out = ULongArray(4)
            var carry: ULong = if (bit) 1UL else 0UL
            for (index in 0 until 4) {
                val nextCarry = limbs[index] shr 63
                out[index] = (limbs[index] shl 1) or carry
                carry = nextCarry
            }
            var shifted = Field(out[0], out[1], out[2], out[3])
            if (carry != 0UL) {
                val (corrected, _) = addRaw(shifted, CARRY_CORRECTION)
                shifted = corrected
            } else if (shifted >= MODULUS) {
                shifted = subRaw(shifted, MODULUS)
            }
            return if (shifted >= MODULUS) subRaw(shifted, MODULUS) else shifted
        }

        private fun bitSet(limbs: ULongArray, bit: Int): Boolean =
            ((limbs[bit / 64] shr (bit and 63)) and 1UL) != 0UL

        private fun readU64BE(bytes: ByteArray, offset: Int): ULong {
            var value = 0UL
            for (index in 0 until 8) value = (value shl 8) or (bytes[offset + index].toULong() and 0xffUL)
            return value
        }
    }
}

sealed class FirstPairSourceException(message: String) : Exception(message) {
    object InvalidP256Point : FirstPairSourceException("invalid P-256 point")
    class InvalidP256PointLength(len: Int) : FirstPairSourceException("invalid P-256 point length $len")
    class InvalidP256ScalarLength(len: Int) : FirstPairSourceException("invalid P-256 scalar length $len")
}
