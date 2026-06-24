package re.abbot.librecr.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import re.abbot.librecr.protocol.crypto.Phase5KeySchedule

class Phase5ScheduleGoldenTest {
    @Test
    fun deriveRawKey() {
        val arr = Golden.arr("phase5_keysched")
        for (i in 0 until arr.length()) {
            val v = arr.getJSONObject(i)
            val key = Phase5KeySchedule.deriveRawKey(hexToBytes(v.getString("input66")))
            assertEquals(v.getString("rawKey"), key.toHex(), "phase5_keysched[$i]")
        }
    }
}
