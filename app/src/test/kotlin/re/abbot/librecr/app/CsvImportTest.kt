package re.abbot.librecr.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import re.abbot.librecr.app.data.libreViewDateFormats
import re.abbot.librecr.app.data.parseLibreViewLine

class CsvImportTest {
    private val formats = libreViewDateFormats()

    @Test
    fun parsesHistoricRow() {
        val s = parseLibreViewLine("FreeStyle LibreLink,SER,10-21-2022 11:46 PM,0,74,,,,,", formats)!!
        assertEquals(74, s.mgDl)
        assertTrue(s.atMs > 0)
    }

    @Test
    fun parsesScanRowFromColumn5() {
        val s = parseLibreViewLine("FreeStyle LibreLink,SER,10-22-2022 12:01 AM,1,,121,,,", formats)!!
        assertEquals(121, s.mgDl)
    }

    @Test
    fun skipsOtherRecordTypesBlankAndMissingGlucose() {
        assertNull(parseLibreViewLine("FreeStyle LibreLink,SER,10-22-2022 12:01 AM,6,,,,,", formats))
        assertNull(parseLibreViewLine("", formats))
        assertNull(parseLibreViewLine("a,b,c,0,,,,", formats)) // record type 0 but no glucose
    }

    @Test
    fun rejectsOutOfRangeValues() {
        assertNull(parseLibreViewLine("X,Y,10-21-2022 11:46 PM,0,5,,,", formats))   // 5 < 10
        assertNull(parseLibreViewLine("X,Y,10-21-2022 11:46 PM,0,999,,,", formats)) // 999 > 600
    }
}
