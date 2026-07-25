package re.abbot.librecr.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import re.abbot.librecr.protocol.dataplane.BackfillStream
import re.abbot.librecr.protocol.dataplane.glucoseChartBackfillCommands

class GlucoseBackfillPlanTest {
    @ParameterizedTest
    @CsvSource(
        "-1, 0",
        "0, 0",
        "1, 1",
        "15545, 15545",
        "65535, 65535",
    )
    fun `minute chart backfill requests only the clinical stream`(
        requestedLifeCount: Int,
        expectedLifeCount: Int,
    ) {
        val commands = glucoseChartBackfillCommands(requestedLifeCount)

        assertEquals(1, commands.size)
        val plaintext = commands.single().plaintext
        assertEquals(BackfillStream.CLINICAL.wireValue, plaintext[1].toInt())
        assertEquals(expectedLifeCount and 0xff, plaintext[3].toInt() and 0xff)
        assertEquals((expectedLifeCount ushr 8) and 0xff, plaintext[4].toInt() and 0xff)
    }
}
