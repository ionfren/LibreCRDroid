package re.abbot.librecr.protocol.dataplane

import re.abbot.librecr.protocol.u16le

data class HistoricalReadingSample(val lifeCount: Int, val rawValue: Int) {
    val glucoseStatus: Libre3GlucoseValueStatus get() = Libre3GlucoseValueStatus.fromRaw(rawValue)
    val glucoseMgDL: Int? get() = glucoseStatus.displayMgDL
}

/** Decoded historical backfill page (14 bytes = start + 6 samples at 5-min stride). */
class HistoricalReadingPage(plaintext: ByteArray) {
    val startLifeCount: Int
    val values: List<Int>

    init {
        if (plaintext.size != PLAINTEXT_SIZE) throw HistoricalReadingPageException.WrongPlaintextSize(plaintext.size)
        val words = ArrayList<Int>(7)
        var off = 0
        while (off < plaintext.size) {
            words.add(plaintext.u16le(off)); off += 2
        }
        startLifeCount = words[0]
        values = words.drop(1)
    }

    val samples: List<HistoricalReadingSample>
        get() = values.mapIndexed { i, v ->
            HistoricalReadingSample(startLifeCount + i * SAMPLE_SPACING_LIFE_COUNTS, v)
        }

    val endLifeCount: Int get() = startLifeCount + (values.size - 1) * SAMPLE_SPACING_LIFE_COUNTS

    companion object {
        const val PLAINTEXT_SIZE = 14
        const val SAMPLE_SPACING_LIFE_COUNTS = 5
    }
}

sealed class HistoricalReadingPageException(message: String) : Exception(message) {
    class WrongPlaintextSize(val size: Int) : HistoricalReadingPageException("wrong plaintext size $size")
}
