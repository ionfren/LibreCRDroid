package re.abbot.librecr.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import re.abbot.librecr.protocol.dataplane.DataPlaneChannel
import re.abbot.librecr.protocol.dataplane.DataPlaneNotificationAssembler
import re.abbot.librecr.protocol.dataplane.GlucoseTimeline

class GlucoseReassemblyTimelineTest {
    private var clock = 0L
    private fun assembler() = DataPlaneNotificationAssembler(orphanTimeoutMs = 2_000L, nowMs = { clock })

    @Test
    fun pairWithinTimeoutReassembles() {
        val a = assembler()
        clock = 1_000
        assertNull(a.feed(ByteArray(15), DataPlaneChannel.GLUCOSE_DATA))
        clock = 2_500 // 1.5s later — within the 2s window
        val r = a.feedDetailed(ByteArray(20), DataPlaneChannel.GLUCOSE_DATA)
        assertNull(r.flushedOrphanAgeMs)
        assertEquals(35, r.combined?.size)
    }

    @Test
    fun orphanPrefixFlushedAfterTimeout() {
        val a = assembler()
        clock = 0
        assertNull(a.feed(ByteArray(15), DataPlaneChannel.GLUCOSE_DATA)) // buffer prefix
        clock = 2_500 // suffix arrives too late
        val r = a.feedDetailed(ByteArray(20), DataPlaneChannel.GLUCOSE_DATA)
        assertEquals(2_500L, r.flushedOrphanAgeMs)
        // The stale prefix is dropped, so the late fragment is NOT mis-glued (would corrupt CCM).
        assertEquals(20, r.combined?.size)
    }

    @Test
    fun freshPrefixAfterFlushReassembles() {
        val a = assembler()
        clock = 0
        a.feed(ByteArray(15), DataPlaneChannel.GLUCOSE_DATA) // orphaned prefix
        clock = 3_000
        val first = a.feedDetailed(ByteArray(15), DataPlaneChannel.GLUCOSE_DATA) // flush + buffer new
        assertEquals(3_000L, first.flushedOrphanAgeMs)
        assertNull(first.combined)
        clock = 3_100
        val second = a.feedDetailed(ByteArray(20), DataPlaneChannel.GLUCOSE_DATA)
        assertNull(second.flushedOrphanAgeMs)
        assertEquals(35, second.combined?.size)
    }

    @Test
    fun freshPrefixBeforeTimeoutReplacesOrphanedPrefix() {
        val a = assembler()
        clock = 0
        a.feed(ByteArray(15) { 0x11 }, DataPlaneChannel.GLUCOSE_DATA) // suffix never arrives
        clock = 1_000
        val replacement = a.feedDetailed(ByteArray(15) { 0x22 }, DataPlaneChannel.GLUCOSE_DATA)
        assertNull(replacement.combined)
        assertEquals(1_000L, replacement.replacedPrefixAgeMs)

        clock = 1_050
        val completed = a.feedDetailed(ByteArray(20) { 0x33 }, DataPlaneChannel.GLUCOSE_DATA)
        assertEquals(35, completed.combined?.size)
        assertEquals(0x22, completed.combined?.first()?.toInt())
    }

    @Test
    fun orphanSuffixDoesNotPoisonNextSplitPacket() {
        val a = assembler()
        clock = 0
        val orphanSuffix = a.feedDetailed(ByteArray(20), DataPlaneChannel.GLUCOSE_DATA)
        assertEquals(20, orphanSuffix.combined?.size)
        assertEquals(20, orphanSuffix.orphanSuffixSize)

        clock = 100
        assertNull(a.feed(ByteArray(15), DataPlaneChannel.GLUCOSE_DATA))
        clock = 150
        val completed = a.feedDetailed(ByteArray(20), DataPlaneChannel.GLUCOSE_DATA)
        assertEquals(35, completed.combined?.size)
    }

    @Test
    fun nonGlucoseChannelPassesThroughUntouched() {
        val a = assembler()
        val frag = ByteArray(15)
        assertEquals(frag.size, a.feed(frag, DataPlaneChannel.PATCH_STATUS)?.size)
    }

    @Test
    fun healthyTimelineIsOk() {
        val t = GlucoseTimeline(
            lifeCount = 100,
            firstNotifyTs = 1_000, secondNotifyTs = 1_200, decodedTs = 1_210, savedTs = 1_240,
            sentToPhoneTs = 1_250, phoneReceivedTs = 1_450, uiRenderedTs = 1_500,
        )
        assertEquals(210L, t.firstNotifyToDecoded)
        assertEquals(200L, t.sentToPhoneReceived)
        assertEquals(50L, t.phoneReceivedToUi)
        assertEquals(listOf("OK"), t.classify(previousLifeCount = 99))
    }

    @Test
    fun skippedLifeCountIsLost() {
        val t = GlucoseTimeline(lifeCount = 105, firstNotifyTs = 1_000, decodedTs = 1_100)
        assertTrue(t.classify(previousLifeCount = 100).any { it.startsWith("LOST missing=4") })
    }

    @Test
    fun dataLayerStallDominatesDelayedVerdict() {
        val t = GlucoseTimeline(
            lifeCount = 100,
            firstNotifyTs = 1_000, secondNotifyTs = 1_200, decodedTs = 1_210, savedTs = 1_240,
            sentToPhoneTs = 1_250, phoneReceivedTs = 81_250, uiRenderedTs = 81_300,
        )
        val verdicts = t.classify(previousLifeCount = 99)
        assertEquals("DataLayer(sent→phoneRecv)", t.dominantSegment()?.first)
        assertTrue(verdicts.any { it.startsWith("DELAYED") && it.contains("DataLayer") })
    }

    @Test
    fun negativeCrossDeviceDeltaIsClockSkew() {
        val t = GlucoseTimeline(lifeCount = 100, sentToPhoneTs = 5_000, phoneReceivedTs = 3_000)
        assertTrue(t.classify(previousLifeCount = null).any { it.startsWith("CLOCK-SKEW") })
    }

    @Test
    fun partialTimelineLatenciesAreNull() {
        val t = GlucoseTimeline(lifeCount = 100, firstNotifyTs = 1_000, decodedTs = 1_050)
        assertEquals(50L, t.firstNotifyToDecoded)
        assertNull(t.decodedToSaved)
        assertNull(t.sentToPhoneReceived)
    }
}
