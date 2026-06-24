package re.abbot.librecr.protocol.dataplane

/**
 * Post-pairing data-plane outer framing. Direct port of Swift `DataFrame`.
 *
 *   [ encrypted_payload | seq_byte | type_byte ]
 *
 * The final two bytes are the 16-bit packet sequence (LE) used in CCM nonce
 * construction. Parses the outer framing only; does NOT decrypt.
 */
data class DataFrame(val encrypted: ByteArray, val seq: Int, val type: Int) {

    val sequenceNumber: Int get() = (seq and 0xff) or ((type and 0xff) shl 8)

    val raw: ByteArray
        get() = encrypted + byteArrayOf((seq and 0xff).toByte(), (type and 0xff).toByte())

    companion object {
        fun parse(raw: ByteArray): DataFrame {
            if (raw.size < 3) throw DataFrameException.TooShort(raw.size)
            val trailerStart = raw.size - 2
            return DataFrame(
                encrypted = raw.copyOfRange(0, trailerStart),
                seq = raw[trailerStart].toInt() and 0xff,
                type = raw[trailerStart + 1].toInt() and 0xff,
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DataFrame) return false
        return encrypted.contentEquals(other.encrypted) && seq == other.seq && type == other.type
    }

    override fun hashCode(): Int =
        (encrypted.contentHashCode() * 31 + seq) * 31 + type
}

sealed class DataFrameException(message: String) : Exception(message) {
    class TooShort(val size: Int) : DataFrameException("frame too short: $size")
}
