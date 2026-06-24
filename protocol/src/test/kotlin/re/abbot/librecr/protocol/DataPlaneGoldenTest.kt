package re.abbot.librecr.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import re.abbot.librecr.protocol.dataplane.ClinicalReadingRecord
import re.abbot.librecr.protocol.dataplane.DataFrame
import re.abbot.librecr.protocol.dataplane.DataPlaneChannel
import re.abbot.librecr.protocol.dataplane.DataPlaneCrypto
import re.abbot.librecr.protocol.dataplane.DataPlaneDecoder
import re.abbot.librecr.protocol.dataplane.DataPlaneNotificationAssembler
import re.abbot.librecr.protocol.dataplane.DataPlanePacketKind
import re.abbot.librecr.protocol.dataplane.HistoricalReadingPage
import re.abbot.librecr.protocol.dataplane.PatchControlCommand
import re.abbot.librecr.protocol.dataplane.PatchStatus
import re.abbot.librecr.protocol.dataplane.RealtimeGlucoseReading

class DataPlaneGoldenTest {

    private fun kind(name: String) = when (name) {
        "kind0" -> DataPlanePacketKind.KIND0
        "kind2" -> DataPlanePacketKind.KIND2
        "kind3" -> DataPlanePacketKind.KIND3
        "kind4" -> DataPlanePacketKind.KIND4
        "patchData" -> DataPlanePacketKind.PATCH_DATA
        else -> error("unknown kind $name")
    }

    private fun channel(name: String) = when (name) {
        "glucoseData" -> DataPlaneChannel.GLUCOSE_DATA
        "patchStatus" -> DataPlaneChannel.PATCH_STATUS
        "historicData" -> DataPlaneChannel.HISTORIC_DATA
        else -> error("unknown channel $name")
    }

    @Test
    fun dataplaneNonce() {
        for (i in 0 until Golden.arr("dataplane_nonce").length()) {
            val v = Golden.arr("dataplane_nonce").getJSONObject(i)
            val c = DataPlaneCrypto(v.bytes("kEnc"), v.bytes("ivEnc"))
            val n = c.nonce(v.getInt("seq"), kind(v.getString("kind")))
            assertEquals(v.getString("nonce"), n.toHex(), "dataplane_nonce[$i]")
        }
    }

    @Test
    fun dataplaneDecrypt() {
        for (i in 0 until Golden.arr("dataplane_decrypt").length()) {
            val v = Golden.arr("dataplane_decrypt").getJSONObject(i)
            val crypto = DataPlaneCrypto(v.bytes("kEnc"), v.bytes("ivEnc"))
            val frame = DataFrame.parse(v.bytes("frameRaw"))
            val packet = DataPlaneDecoder(crypto).decrypt(frame, channel(v.getString("channel")))
            assertEquals(v.getInt("seq"), frame.sequenceNumber, "dataplane_decrypt[$i] seq")
            assertEquals(v.getInt("kindOrdinal"), packet.kind.ordinal, "dataplane_decrypt[$i] kind")
            assertEquals(v.getString("plaintext"), packet.plaintext.toHex(), "dataplane_decrypt[$i] plaintext")
        }
    }

    @Test
    fun glucoseSplit() {
        val o = Golden.obj("dataplane_glucose_split")
        val a = DataPlaneNotificationAssembler()
        assertNull(a.feed(o.bytes("prefix"), DataPlaneChannel.GLUCOSE_DATA))
        val combined = a.feed(o.bytes("suffix"), DataPlaneChannel.GLUCOSE_DATA)
        assertEquals(o.getString("combined"), combined!!.toHex(), "glucose split")
    }

    @Test
    fun glucoseParse() {
        for (i in 0 until Golden.arr("glucose_parse").length()) {
            val v = Golden.arr("glucose_parse").getJSONObject(i)
            val r = RealtimeGlucoseReading(v.bytes("plaintext"))
            assertEquals(v.getInt("lifeCount"), r.lifeCount, "glucose[$i] lifeCount")
            assertEquals(v.getInt("readingMgDL"), r.readingMgDL, "glucose[$i] readingMgDL")
            if (v.isNull("currentGlucoseMgDL")) assertNull(r.currentGlucoseMgDL)
            else assertEquals(v.getInt("currentGlucoseMgDL"), r.currentGlucoseMgDL, "glucose[$i] mgdl")
            assertEquals(v.getInt("rateOfChangeRaw"), r.rateOfChangeRaw.toInt(), "glucose[$i] roc")
            assertEquals(v.getInt("trendRaw"), r.trendRaw, "glucose[$i] trend")
            assertEquals(v.getInt("actionableStatus"), r.actionableStatus, "glucose[$i] action")
            assertEquals(v.getInt("historicalLifeCount"), r.historicalLifeCount, "glucose[$i] histLC")
            assertEquals(v.getInt("historicalReading"), r.historicalReading, "glucose[$i] histRead")
            assertEquals(v.getInt("temperature"), r.temperature, "glucose[$i] temp")
            assertEquals(v.getInt("uncappedCurrentMgDL"), r.uncappedCurrentMgDL, "glucose[$i] uncapped")
            assertEquals(v.getString("fastData"), r.fastData.toHex(), "glucose[$i] fastData")
        }
    }

    @Test
    fun patchStatusParse() {
        for (i in 0 until Golden.arr("patchstatus_parse").length()) {
            val v = Golden.arr("patchstatus_parse").getJSONObject(i)
            val s = PatchStatus(v.bytes("plaintext"))
            assertEquals(v.getInt("lifeCount"), s.lifeCount, "patch[$i] lifeCount")
            assertEquals(v.getInt("errorData"), s.errorData, "patch[$i] errorData")
            assertEquals(v.getInt("eventData"), s.eventData, "patch[$i] eventData")
            assertEquals(v.getInt("index"), s.index, "patch[$i] index")
            assertEquals(v.getInt("patchState"), s.patchState, "patch[$i] patchState")
            assertEquals(v.getInt("currentLifeCount"), s.currentLifeCount, "patch[$i] currentLifeCount")
            assertEquals(v.getInt("stackDisconnectReason"), s.stackDisconnectReason, "patch[$i] stack")
            assertEquals(v.getInt("appDisconnectReason"), s.appDisconnectReason, "patch[$i] app")
        }
    }

    @Test
    fun historicalParse() {
        for (i in 0 until Golden.arr("historical_parse").length()) {
            val v = Golden.arr("historical_parse").getJSONObject(i)
            val p = HistoricalReadingPage(v.bytes("plaintext"))
            assertEquals(v.getInt("startLifeCount"), p.startLifeCount, "hist[$i] start")
            val values = v.getJSONArray("values")
            assertEquals(values.length(), p.values.size, "hist[$i] count")
            for (j in p.values.indices) assertEquals(values.getInt(j), p.values[j], "hist[$i][$j]")
        }
    }

    @Test
    fun clinicalParse() {
        for (i in 0 until Golden.arr("clinical_parse").length()) {
            val v = Golden.arr("clinical_parse").getJSONObject(i)
            val c = ClinicalReadingRecord(v.bytes("plaintext"))
            assertEquals(v.getInt("lifeCount"), c.lifeCount, "clin[$i] lifeCount")
            assertEquals(v.getInt("currentGlucoseRaw"), c.currentGlucoseRaw, "clin[$i] cur")
            assertEquals(v.getInt("historicGlucoseRaw"), c.historicGlucoseRaw, "clin[$i] hist")
        }
    }

    @Test
    fun patchControl() {
        val expected = mutableMapOf<String, String>()
        for (i in 0 until Golden.arr("patchcontrol").length()) {
            val v = Golden.arr("patchcontrol").getJSONObject(i)
            expected[v.getString("label").substringBefore(" ")] = v.getString("plaintext")
        }
        // Match exact plaintexts by reconstructing the same commands.
        assertEquals(Golden.arr("patchcontrol").getJSONObject(0).getString("plaintext"),
            PatchControlCommand.historicalBackfillGreaterEqual(5).plaintext.toHex())
        assertEquals(Golden.arr("patchcontrol").getJSONObject(1).getString("plaintext"),
            PatchControlCommand.clinicalBackfillGreaterEqual(1).plaintext.toHex())
        assertEquals(Golden.arr("patchcontrol").getJSONObject(3).getString("plaintext"),
            PatchControlCommand.eventLog(1).plaintext.toHex())
        assertEquals(Golden.arr("patchcontrol").getJSONObject(4).getString("plaintext"),
            PatchControlCommand.factoryData().plaintext.toHex())
        assertEquals(Golden.arr("patchcontrol").getJSONObject(5).getString("plaintext"),
            PatchControlCommand.shutdownPatch().plaintext.toHex())
    }
}
