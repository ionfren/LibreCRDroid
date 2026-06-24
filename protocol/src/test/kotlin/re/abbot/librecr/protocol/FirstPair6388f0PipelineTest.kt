package re.abbot.librecr.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import re.abbot.librecr.protocol.crypto.FirstPairSourceSlice
import java.security.MessageDigest

/** Byte-for-byte parity for the 6388f0 lane→pack→stage→prefinal→internal→raw→source pipeline. */
class FirstPair6388f0PipelineTest {
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).toHex()
    private fun gen(n: Int, m: Int, a: Int, mask: Int = 7) = ByteArray(n) { ((it * m + a) and mask).toByte() }

    @Test
    fun rawStream64d774() {
        val first = gen(66, 5, 2)
        val second = gen(66, 7, 4)
        val src = FirstPairSourceSlice.deriveFrom64d774RawStreams(first, second, offset = 0, length = 16)
        assertEquals(
            "040406010201060506000205040004000004010001010207060607060607050502" +
                "000107040306040106020704040002000600030200070603040004010004010207",
            src.toHex(),
        )
    }

    @Test
    fun tailLayers() {
        assertEquals("56ddfdbf0fcc1b60f339a70d9cba7e8262d02c9e7bed64970126f795fb635576",
            sha(FirstPairSourceSlice.builder6388f0FinalRawBlocks(gen(132, 5, 1))))
        assertEquals("b6af68238f927cb2f27d318fa8fb6e0c77494d16555c4dd64a06d1376544995a",
            sha(FirstPairSourceSlice.builder6388f0PrefinalLen32InternalBlocks(gen(132, 3, 2))))
        assertEquals("b7fed8b0449eb2368269402165ea6bf581f68580bdb3a1719b0ecf14ac2f9172",
            sha(FirstPairSourceSlice.builder6388f0Len32PrefinalSourcesFromWorkspace(gen(266, 7, 4))))
        assertEquals("cc33aa4c896081feb2a44b378afd6b9830ca655c174f6d507c9716e8a68a7830",
            sha(FirstPairSourceSlice.builder6388f0Len32PrefinalSourcesFromStageInputs(gen(282, 5, 2), gen(282, 3, 6))))
    }

    @Test
    fun deriveLayers() {
        val internal = gen(132, 5, 1); val prefinal = gen(132, 3, 2)
        assertEquals(
            "040400040702000206000504060306040706020100040303030205020506030603" +
                "020207050704040602060006000600020002020305050000020401070006030103",
            FirstPairSourceSlice.deriveFrom6388f0InternalStreams(internal, prefinal, offset = 0, length = 16).toHex())
        assertEquals(
            "040400000703050704050203030503020706030400060402020001060302050104" +
                "070104060000030706020104020007060300040207050502050502000405050700",
            FirstPairSourceSlice.deriveFrom6388f0PrefinalLen32Streams(prefinal, internal, offset = 0, length = 16).toHex())
        assertEquals(
            "040407040606030500050503030005050606010206050607040407000306050202" +
                "070100040200040407070303020600010302040007020501000306000406020107",
            FirstPairSourceSlice.deriveFrom6388f0WorkspaceLen32Streams(gen(266, 7, 4), gen(266, 7, 5), offset = 0, length = 16).toHex())
        assertEquals(
            "040400030102000103000703030201010707070106000506020306040203060202" +
                "070203000305040200010504020501070103070706000700040501060003060005",
            FirstPairSourceSlice.deriveFrom6388f0StageLen32Streams(gen(282, 5, 2), gen(282, 3, 6), gen(282, 3, 1), gen(282, 5, 4), offset = 0, length = 16).toHex())
    }

    @Test
    fun packAndLaneLayers() {
        val primary0 = gen(320, 3, 1); val secondary0 = gen(320, 5, 2)
        val primary1 = gen(320, 7, 4); val secondary1 = gen(320, 3, 6)
        val pack0 = FirstPairSourceSlice.builder6388f0PackOutputsFromLaneBlocks(primary0, secondary0)
        assertEquals("cbb73fff67fc8d84576195e087bdadabd862476bea09fd3603ec19f75f703f28", sha(pack0.stageBPackHead16))
        assertEquals("1ed65e8008b64c579a8dc5ef91bbb8026a7bd10180c16fd9022395cc6f21e583", sha(pack0.stageBPackBody16))
        assertEquals("0419abe716a28762aae3d3abdfcbd20069e74db5f4cc62060cbf32f11ed3c2ea", sha(pack0.stageAPackHead16))
        assertEquals("ec559fc571319e7d69e31ba7761b9035072bcddc75324c3de9f78b7eca830560", sha(pack0.stageAPackBody16))

        val stage0 = FirstPairSourceSlice.builder6388f0Len32StageInputsFromPackOutputs(pack0.stageBPackHead16, pack0.stageBPackBody16, pack0.stageAPackHead16, pack0.stageAPackBody16)
        assertEquals("fbece039249b3700c3908af39c16cfdffda2a8e94765ce1243280ede14a0db22", sha(stage0.stageASource))
        assertEquals("55622eae1f29c6264a33d62e2b41fb3875832683281b456831037d8a4e01d4f6", sha(stage0.stageBSource))

        val pack1 = FirstPairSourceSlice.builder6388f0PackOutputsFromLaneBlocks(primary1, secondary1)
        val sourceFromPack = FirstPairSourceSlice.deriveFrom6388f0PackLen32Streams(
            pack0.stageBPackHead16, pack0.stageBPackBody16, pack0.stageAPackHead16, pack0.stageAPackBody16,
            pack1.stageBPackHead16, pack1.stageBPackBody16, pack1.stageAPackHead16, pack1.stageAPackBody16, offset = 0, length = 16)
        assertEquals(
            "040400060501060401060002050500050600010504000007060300050100000407" +
                "060406030405050001050500010002050100020501010304040405040706050200",
            sourceFromPack.toHex())
        val sourceFromLanes = FirstPairSourceSlice.deriveFrom6388f0LaneLen32Streams(primary0, secondary0, primary1, secondary1, offset = 0, length = 16)
        assertEquals(sourceFromPack.toHex(), sourceFromLanes.toHex())
    }

    @Test
    fun scheduleLayer() {
        val schedule0 = uintArrayOf(
            0x11223344u, 0x12243648u, 0x1326394cu, 0x14283c50u, 0x152a3f54u,
            0x162c4258u, 0x172e455cu, 0x18304860u, 0x19324b64u, 0x1a344e68u,
            0x1b36516cu, 0x1c385470u, 0x1d3a5774u, 0x1e3c5a78u, 0x1f3e5d7cu,
            0x20406080u, 0x21426384u, 0x22446688u, 0x2346698cu, 0x24486c90u)
        val schedule1 = uintArrayOf(
            0x89abcdefu, 0x8aaccef0u, 0x8badcff1u, 0x8caed0f2u, 0x8dafd1f3u,
            0x8eb0d2f4u, 0x8fb1d3f5u, 0x90b2d4f6u, 0x91b3d5f7u, 0x92b4d6f8u,
            0x93b5d7f9u, 0x94b6d8fau, 0x95b7d9fbu, 0x96b8dafcu, 0x97b9dbfdu,
            0x98badcfeu, 0x99bbddffu, 0x9abcdf00u, 0x9bbde001u, 0x9cbee102u)

        val lanes0 = FirstPairSourceSlice.builder6388f0LaneBlocksFromScheduleWords(schedule0)
        assertEquals("1b70254a30185288de09f9a35ec6b1293474b349a720f89794631dd5cd43c2f8", sha(lanes0.primaryLaneBlocks))
        assertEquals("d718360163754dcd6fc33adfe4d184ce65e7f9f6e9bd32b4fcb677b425845bc1", sha(lanes0.secondaryLaneBlocks))
        val lanes1 = FirstPairSourceSlice.builder6388f0LaneBlocksFromScheduleWords(schedule1)
        assertEquals("2ebd449c1b18906b11e48c56f3bdef9cb98072e5be686a4b89cb02b2dab45d04", sha(lanes1.primaryLaneBlocks))
        assertEquals("cbc6175ef451f45406d268a601058f257271e2ddcd12d6a773c0b51f48c8f2f0", sha(lanes1.secondaryLaneBlocks))

        val source = FirstPairSourceSlice.deriveFrom6388f0ScheduleLen32Streams(schedule0, schedule1, offset = 0, length = 16)
        assertEquals(
            "040401070203010704050106010001020204070005060100030704020306000701" +
                "070104060605050201000402010102050201000404040305070103060002030602",
            source.toHex())
        assertEquals("e407917d692fd119fbf18baf60644ded", FirstPairSourceSlice.phase5RawKeyFrom6388f0ScheduleLen32Streams(schedule0, schedule1).toHex())
    }

    @Test
    fun fullRowsToSourceAndKey() {
        val row0Out0Seed = gen(88, 13, 5, 0xff)
        val row0Out1Seed = gen(88, 17, 11, 0xff)
        val row59Out0Seed = gen(88, 19, 7, 0xff)
        val row59Out1Seed = gen(88, 23, 3, 0xff)
        val starts = FirstPairSourceSlice.builder6388f0FirstPair642f60StreamStarts(row0Out0Seed, row0Out1Seed, row59Out0Seed, row59Out1Seed)
        val row0LowPreimages = FirstPairSourceSlice.Builder6473d0OutputPreimages(
            gen(88, 3, 1, 0xff), gen(88, 5, 2, 0xff), gen(88, 7, 3, 0xff), row0Out1Seed, row0Out0Seed)
        val rows = FirstPairSourceSlice.builder6388f0SeededCaller64Rows(starts, row0LowPreimages, FirstPairSourceSlice.builder6388f0CallerContextFromBundle())
        assertEquals(118, rows.size)

        val schedules = FirstPairSourceSlice.builder6388f0Seeded63c278SchedulesFromRows(rows)
        assertEquals(58, schedules.first.rowIndex)
        assertEquals(117, schedules.second.rowIndex)
        assertEquals(FirstPairSourceSlice.pre63c278Scalar, schedules.first.scalar)
        assertEquals("b31620465a69c66dee3504561f60addaf2c74055d82ad778a1f13e52da4750d0", sha(packU32(schedules.first.scheduleWords)))
        assertEquals("f5187d8a9042e39cbc2758abd244f0a7bc91bc63a624c743ee8b10bb7af64cf1", sha(packU32(schedules.second.scheduleWords)))

        val source = FirstPairSourceSlice.deriveFrom6388f0SeededCaller64Rows(rows)
        assertEquals(
            "040400010402030107000002030007050505030706070204050000060303020307" +
                "020600070302040604000305030603020102020003010306050605060207060303",
            source.toHex())
        assertEquals("515ca99cb8c0deaf1208df352078064d", FirstPairSourceSlice.phase5RawKeyFrom6388f0SeededCaller64Rows(rows).toHex())
    }

    private fun packU32(a: UIntArray): ByteArray {
        val out = ByteArray(a.size * 4)
        for (i in a.indices) { var v = a[i]; for (k in 0 until 4) { out[i * 4 + k] = (v and 0xffu).toByte(); v = v shr 8 } }
        return out
    }
}
