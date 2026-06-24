package re.abbot.librecr.protocol.ble

/**
 * Wire framing for the Libre 3 GATT layer. Direct port of Swift `BleFraming`.
 *
 *   Writes   (phone → sensor): 2-byte LE byte-offset prefix + payload chunk
 *   Notifies (sensor → phone): 1-byte sequence prefix + payload chunk
 *
 * Effective payload per fragment is 18 bytes for writes and 19 bytes for
 * notifies (set by ATT MTU). Pure functions, BLE-free, so they unit-test.
 */
object BleFraming {

    const val WRITE_CHUNK_PAYLOAD = 18
    const val NOTIFY_CHUNK_PAYLOAD = 19

    /**
     * Split a logical message into ATT-write fragments. Each fragment is
     * `[offset_lo, offset_hi, chunk_bytes...]`.
     */
    fun fragmentForWrite(message: ByteArray, chunkSize: Int = WRITE_CHUNK_PAYLOAD): List<ByteArray> {
        require(chunkSize > 0)
        val out = ArrayList<ByteArray>()
        var offset = 0
        while (offset < message.size) {
            val end = minOf(offset + chunkSize, message.size)
            val frag = ByteArray(2 + (end - offset))
            frag[0] = (offset and 0xff).toByte()
            frag[1] = ((offset ushr 8) and 0xff).toByte()
            message.copyInto(frag, destinationOffset = 2, startIndex = offset, endIndex = end)
            out.add(frag)
            offset = end
        }
        return out
    }

    /** Inverse of [fragmentForWrite]; validates continuity. */
    fun reassembleWrite(fragments: List<ByteArray>): ByteArray {
        val parsed = fragments.map { f ->
            if (f.size < 2) throw BleFramingException.FragmentTooShort
            val off = (f[0].toInt() and 0xff) or ((f[1].toInt() and 0xff) shl 8)
            off to f.copyOfRange(2, f.size)
        }.sortedBy { it.first }
        var out = ByteArray(0)
        for ((off, body) in parsed) {
            if (off != out.size) throw BleFramingException.DiscontinuousOffsets
            out += body
        }
        return out
    }

    /**
     * Streaming reassembler for sensor → phone notifies. Each fragment carries
     * a 1-byte sequence number followed by the payload chunk. Verifies sequence
     * monotonicity and concatenates payloads.
     */
    class NotifyReassembler {
        private var buffer = ByteArray(0)
        private var nextExpectedSeq: Int? = null // unset until first fragment

        val availableBytes: Int get() = buffer.size

        /** Append a single notify fragment; returns new reassembled length. */
        fun feed(fragment: ByteArray): Int {
            if (fragment.isEmpty()) throw BleFramingException.FragmentTooShort
            val seq = fragment[0].toInt() and 0xff
            val expected = nextExpectedSeq
            if (expected != null && seq != expected) {
                throw BleFramingException.SequenceGap(expected, seq)
            }
            nextExpectedSeq = (seq + 1) and 0xff
            buffer += fragment.copyOfRange(1, fragment.size)
            return buffer.size
        }

        /** Consume the first [n] reassembled bytes; throws if fewer available. */
        fun take(n: Int): ByteArray {
            if (buffer.size < n) throw BleFramingException.NotEnoughBytes(buffer.size, n)
            val head = buffer.copyOfRange(0, n)
            buffer = buffer.copyOfRange(n, buffer.size)
            return head
        }

        fun reset() {
            buffer = ByteArray(0)
            nextExpectedSeq = null
        }
    }
}

sealed class BleFramingException(message: String) : Exception(message) {
    object FragmentTooShort : BleFramingException("fragment too short")
    object DiscontinuousOffsets : BleFramingException("discontinuous offsets")
    class SequenceGap(val expected: Int, val got: Int) :
        BleFramingException("sequence gap: expected $expected got $got")
    class NotEnoughBytes(val have: Int, val want: Int) :
        BleFramingException("not enough bytes: have $have want $want")
}
