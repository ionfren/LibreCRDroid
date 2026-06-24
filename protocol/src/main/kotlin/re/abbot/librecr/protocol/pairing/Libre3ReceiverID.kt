package re.abbot.librecr.protocol.pairing

/**
 * Libre 3 receiver identity. Direct port of Swift `Libre3ReceiverID`.
 * `value` is a uint32 stored in a Kotlin `Int` bit pattern.
 */
data class Libre3ReceiverID(val value: Int) {
    val unsignedValue: Long
        get() = value.toLong() and 0xffffffffL

    val littleEndian: ByteArray
        get() = byteArrayOf(
            (value and 0xff).toByte(),
            ((value ushr 8) and 0xff).toByte(),
            ((value ushr 16) and 0xff).toByte(),
            ((value ushr 24) and 0xff).toByte(),
        )

    val littleEndianHex: String
        get() = littleEndian.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        /** FNV-1a-style hash over UTF-16 code units (matches Swift exactly). */
        fun accountlessValue(uniqueID: String): Int {
            var value = 0
            for (c in uniqueID) {
                value = (value * 0x811c9dc5.toInt()) xor c.code
            }
            return value
        }

        fun accountless(uniqueID: String): Libre3ReceiverID =
            Libre3ReceiverID(accountlessValue(uniqueID))

        /**
         * Accepts the values a user can realistically copy from Juggluco/LibreView:
         * - decimal Juggluco account id number
         * - 4-byte little-endian hex, optionally prefixed with 0x
         * - LibreView account UUID/string, hashed the way Juggluco does internally
         */
        fun fromNfcIdentityInput(raw: String): Libre3ReceiverID? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null

            if (trimmed.all { it.isDigit() }) {
                return trimmed.toLongOrNull()
                    ?.takeIf { it in 1L..0xffffffffL }
                    ?.let { Libre3ReceiverID(it.toInt()) }
            }

            if (trimmed.startsWith("0x", ignoreCase = true) || trimmed.hasHexLetter()) {
                fromLittleEndianHex(trimmed)?.takeIf { it.value != 0 }?.let { return it }
            }

            return Libre3ReceiverID(jugglucoAccountIdNumber(trimmed))
                .takeIf { it.value != 0 }
        }

        fun jugglucoAccountIdNumber(accountId: String): Int {
            val bytes = ByteArray(36)
            val source = accountId.trim().toByteArray(Charsets.US_ASCII)
            source.copyInto(bytes, endIndex = minOf(source.size, bytes.size))
            var value = 0
            for (b in bytes) {
                value = (value * 0x811c9dc5.toInt()) xor (b.toInt() and 0xff)
            }
            return value
        }

        fun fromLittleEndianHex(raw: String): Libre3ReceiverID? {
            val cleaned = raw.replace("0x", "", ignoreCase = true).filter { it.isHexDigit() }
            if (cleaned.length != 8) return null
            val bytes = IntArray(4)
            for (i in 0 until 4) {
                bytes[i] = cleaned.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null
            }
            val v = bytes[0] or (bytes[1] shl 8) or (bytes[2] shl 16) or (bytes[3] shl 24)
            return Libre3ReceiverID(v)
        }

        private fun Char.isHexDigit(): Boolean =
            this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

        private fun String.hasHexLetter(): Boolean =
            any { it in 'a'..'f' || it in 'A'..'F' }
    }
}
