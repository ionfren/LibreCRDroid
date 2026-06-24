package re.abbot.librecr.protocol

/**
 * Byte / little-endian helpers shared across the protocol layer.
 *
 * The Swift original uses `Data` + `UInt8`/`UInt16`/`UInt32`. We use Kotlin
 * `ByteArray` + `Int` (32-bit, so XOR/shift/rotate behave like uint32) with
 * explicit `and 0xff` masking, matching the Swift math bit-for-bit.
 */

/** Lowercase hex, no separators (matches Swift `data.map { %02x }.joined()`). */
fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xff
        sb.append(HEX_DIGITS[v ushr 4])
        sb.append(HEX_DIGITS[v and 0x0f])
    }
    return sb.toString()
}

private const val HEX_DIGITS = "0123456789abcdef"

/** Parse a hex string (even length, no separators) into bytes. */
fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "hex length must be even: ${hex.length}" }
    val out = ByteArray(hex.length / 2)
    var i = 0
    while (i < hex.length) {
        out[i / 2] = ((hexNibble(hex[i]) shl 4) or hexNibble(hex[i + 1])).toByte()
        i += 2
    }
    return out
}

private fun hexNibble(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> throw IllegalArgumentException("invalid hex digit '$c'")
}

internal fun ByteArray.u8(off: Int): Int = this[off].toInt() and 0xff

/** Little-endian unsigned 16-bit read as Int. */
internal fun ByteArray.u16le(off: Int): Int =
    (this[off].toInt() and 0xff) or ((this[off + 1].toInt() and 0xff) shl 8)

/** Little-endian unsigned 32-bit read returned as Int (uint32 bit pattern). */
internal fun ByteArray.u32le(off: Int): Int =
    (this[off].toInt() and 0xff) or
        ((this[off + 1].toInt() and 0xff) shl 8) or
        ((this[off + 2].toInt() and 0xff) shl 16) or
        ((this[off + 3].toInt() and 0xff) shl 24)

internal fun ByteArray.putU32le(off: Int, v: Int) {
    this[off] = (v and 0xff).toByte()
    this[off + 1] = ((v ushr 8) and 0xff).toByte()
    this[off + 2] = ((v ushr 16) and 0xff).toByte()
    this[off + 3] = ((v ushr 24) and 0xff).toByte()
}

/** Append two LE bytes for a 16-bit value. */
internal fun MutableList<Byte>.appendU16le(v: Int) {
    add((v and 0xff).toByte())
    add(((v ushr 8) and 0xff).toByte())
}

/** Append four LE bytes for a 32-bit value. */
internal fun MutableList<Byte>.appendU32le(v: Int) {
    add((v and 0xff).toByte())
    add(((v ushr 8) and 0xff).toByte())
    add(((v ushr 16) and 0xff).toByte())
    add(((v ushr 24) and 0xff).toByte())
}

/** Constant-time byte-array equality (matches Swift `constantTimeEqual`). */
internal fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}
