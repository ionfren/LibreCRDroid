package re.abbot.librecr.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import re.abbot.librecr.protocol.crypto.FirstPairSourceSlice

/** Byte-for-byte parity for the DF80 transform (leaf of FirstPairSourceSlice). */
class FirstPairDf80Test {
    @Test
    fun df80Transform() {
        val blocks = ByteArray(4 * 66) { ((it * 5 + 3) and 7).toByte() }
        val state = ByteArray(8 * 18) { ((it * 7 + 2) and 7).toByte() }

        val transformed = FirstPairSourceSlice.df80Transform(state, blocks)
        assertEquals(144, transformed.size)

        val expected =
            "060505060603010607050500030303010703040102050702030300020103040406" +
            "060304030403060005010706050001060203000404030301030000040706010105" +
            "000102030607" +
            "040707050405010406070505010204020502060200000307060605040407020503" +
            "070404070704010106060002050303040503010604070007070402050504070703" +
            "040704070607"
        assertEquals(expected, transformed.toHex(), "df80Transform")

        // also verify the staged path equals the one-shot transform
        val workspace = FirstPairSourceSlice.df80InitialWorkspace(blocks)
        val schedule = FirstPairSourceSlice.df80ExpandedSchedule(workspace)
        val compressed = FirstPairSourceSlice.df80CompressState(state, schedule)
        assertEquals(transformed.toHex(), compressed.toHex(), "staged == one-shot")
    }
}
