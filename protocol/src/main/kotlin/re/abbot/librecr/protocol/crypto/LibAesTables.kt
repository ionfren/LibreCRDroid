package re.abbot.librecr.protocol.crypto

import re.abbot.librecr.protocol.u32le

/**
 * Loads the bundled `libaes_*.bin` tables that drive [LibAes]. These binaries
 * are byte-identical copies of the Swift package's RuntimeTables resources, so
 * no porting of the data itself is required — only the interpreting code.
 *
 * Loaded once from the classpath (`/runtime_tables/...`), which works in both
 * JVM unit tests and on Android.
 */
internal class LibAesTables private constructor() {
    val round1Tables: ByteArray = load("libaes_round1_tables_278dc2")
    val round2Tables: IntArray = loadWords("libaes_round2_9_tables_279dc4")
    val phase5Round1Tables: ByteArray = load("libaes_5defec_round1_tables_26f621")
    val keyexpTables: ByteArray = load("libaes_keyexp_tables_275bbb")
    val keyexpConsts: ByteArray = load("libaes_keyexp_consts_276bbc")
    val finalKeyTables: ByteArray = load("libaes_final_key_tables_276cfc")
    val finalTableIndex: ByteArray = load("libaes_final_table_index_277cfc")
    val finalTableMap: ByteArray = load("libaes_final_table_map_277d3c")
    val finalTableWords: ByteArray = load("libaes_final_table_words_270624")

    // Swift derives phase5RoundTables from the finalTableWords bytes (u32 LE).
    val phase5RoundTables: IntArray = run {
        val words = IntArray(finalTableWords.size / 4)
        for (i in words.indices) words[i] = finalTableWords.u32le(i * 4)
        words
    }

    companion object {
        val shared: LibAesTables by lazy { LibAesTables() }

        private fun load(name: String): ByteArray {
            val path = "/runtime_tables/$name.bin"
            val stream = LibAesTables::class.java.getResourceAsStream(path)
                ?: throw LibAesException.MissingResource(name)
            return stream.use { it.readBytes() }
        }

        private fun loadWords(name: String): IntArray {
            val bytes = load(name)
            val words = IntArray(bytes.size / 4)
            for (i in words.indices) words[i] = bytes.u32le(i * 4)
            return words
        }
    }
}

sealed class LibAesException(message: String) : Exception(message) {
    class MissingResource(name: String) : LibAesException("missing runtime table: $name")
    class InvalidKeyLength(len: Int) : LibAesException("invalid key length: $len")
    class InvalidInputLength(len: Int) : LibAesException("invalid input length: $len")
    class InvalidContextLength(len: Int) : LibAesException("invalid context length: $len")
}
