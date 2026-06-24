package re.abbot.librecr.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import re.abbot.librecr.protocol.crypto.FirstPairSourceSlice
import java.security.MessageDigest

/** Byte-for-byte parity for 6473d0 staged via the per-stage 64c524-slice vectors. */
class FirstPair6473d0Test {
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).toHex()
    private fun packU64(a: ULongArray): ByteArray {
        val out = ByteArray(a.size * 8)
        for (i in a.indices) { var v = a[i]; for (k in 0 until 8) { out[i * 8 + k] = (v and 0xffuL).toByte(); v = v shr 8 } }
        return out
    }
    private fun packU32(a: UIntArray): ByteArray {
        val out = ByteArray(a.size * 4)
        for (i in a.indices) { var v = a[i]; for (k in 0 until 4) { out[i * 4 + k] = (v and 0xffu).toByte(); v = v shr 8 } }
        return out
    }

    @Test
    fun firstSlice() {
        val in2 = ByteArray(88) { ((it * 21 + 7) and 0xff).toByte() }
        val arg0 = ByteArray(88) { ((it * 7 + 3) and 0xff).toByte() }
        val scalar = 0x0123456789abcdefuL

        val s = FirstPairSourceSlice.builder6473d0FirstStreamsFromIn2(in2)
        assertEquals("b1f4dcc49a972799f58fb3a398591ad74882914c02ccbe7e3457bb85d15f9e2a", sha(packU64(s.a)))
        assertEquals("546485573a7fa23d64c96fb217f9196d379f766f199e80e02609e286e0c0d2ee", sha(packU64(s.b)))
        assertEquals("07ca498940d4fdd6ecaf9d82d38c47e974e51b0c1afad1db014de69f9bd0b560", sha(packU64(s.c)))
        assertEquals("6b2c5100098474366f5642502dabe909b52b3ea6217c32b4d5ab4d0e206a805c", sha(packU64(s.d)))
        assertEquals(listOf(0x266614493d8fa940uL, 0x6007c6dd9fbb3cceuL, 0x4a0463a7929bbbdbuL, 0xe71ae9adfa4dffb7uL), s.a.take(4))
        assertEquals(listOf(0x21609531c6f7bbc2uL, 0x3a01cb0167bc1884uL, 0x6d10d5fd47b5d72fuL, 0xb3988c7c118905f3uL), s.b.take(4))

        val workspace = FirstPairSourceSlice.builder6473d0First64c524Workspace(in2)
        assertEquals(44 * 8, workspace.size)
        assertEquals("5acfd8607b33aa61a6f38cc24ecc4555d321cd500d9e6b9ff684d36884a827ed", sha(workspace))
        assertEquals("8c8f9e0050dc253a33746dd5535a791918564522a208dfbd7939d24e6a8f7413", workspace.copyOfRange(0, 32).toHex())

        val output = FirstPairSourceSlice.builder64c524OutputWords(arg0, scalar, workspace)
        assertEquals("107a44c71f1c2072c3659db4f8df499dcd81173019370d11fa3e5bf73ca829cd", sha(packU32(output)))
        assertEquals(listOf(0x4b27dc37u, 0xbdbbe5dcu, 0xe19df049u, 0xa7b9b740u), output.take(4))

        val sp488 = FirstPairSourceSlice.builder6473d0SP488WordsFrom64c524Output(packU32(output))
        assertEquals("04041ca272f4abc0c853f762e581febd27c7eb6aac3d480aa0d94d5efe605160", sha(packU32(sp488)))
        assertEquals(listOf(0xa51e755au, 0x4ce7e2cbu, 0xb6a6acfau, 0xd6d1cdb6u), sp488.take(4))
    }

    // shared inputs / fixtures matching the Swift slice tests
    private val in2 = ByteArray(88) { ((it * 21 + 7) and 0xff).toByte() }
    private val in0 = ByteArray(88) { ((it * 15 + 2) and 0xff).toByte() }
    private val in1 = ByteArray(88) { ((it * 13 + 9) and 0xff).toByte() }
    private val out0Seed = ByteArray(88) { ((it * 13 + 5) and 0xff).toByte() }
    private val out1Seed = ByteArray(88) { ((it * 17 + 11) and 0xff).toByte() }
    private val arg0 = ByteArray(88) { ((it * 7 + 3) and 0xff).toByte() }
    private val scalar = 0x0123456789abcdefuL

    private fun sp488(): UIntArray {
        val firstWorkspace = FirstPairSourceSlice.builder6473d0First64c524Workspace(in2)
        val firstOutput = packU32(FirstPairSourceSlice.builder64c524OutputWords(arg0, scalar, firstWorkspace))
        return FirstPairSourceSlice.builder6473d0SP488WordsFrom64c524Output(firstOutput)
    }

    private fun out(workspace: ByteArray) =
        packU32(FirstPairSourceSlice.builder64c524OutputWords(arg0, scalar, workspace))

    @Test
    fun secondSlice() {
        val sp488 = sp488()
        val s = FirstPairSourceSlice.builder6473d0SecondStreams(out0Seed, sp488)
        assertEquals("df9ea4f10d2698fde031d956c2143316bb63595e4db592193408cbec3997a792", sha(packU64(s.a)))
        assertEquals("35e95635019b19c3fc100148b2a078890400453759c7e0e8a45fa91855518d64", sha(packU64(s.b)))
        assertEquals("b9099f56f72378936c3e52698f85809aede89bf5ca0cdab1248cc9864b954403", sha(packU64(s.c)))
        assertEquals("c0acb1f9c3464f26c4eb07d3ec1f88aed7a3203c5220256ea342fa643f86907a", sha(packU64(s.d)))
        assertEquals(listOf(0xf0e978b818b93c69uL, 0x75a018a3672cb577uL, 0xa4c98421009de949uL, 0x0684e334238a06ceuL), s.a.take(4))
        assertEquals(listOf(0x9fca7b25b214721cuL, 0x9f76e8d6d38da8e4uL, 0x8f10bab198854e7fuL, 0x1b1e7339e8891893uL), s.b.take(4))

        val workspace = FirstPairSourceSlice.builder6473d0Second64c524Workspace(out0Seed, sp488)
        assertEquals(44 * 8, workspace.size)
        assertEquals("cd1f9403619681b0c5d076306a3a56bff7ae16312a1aca44cc6e81aa7b685fed", sha(workspace))
        assertEquals("9fc6f0b5a5b45c108bae7302f9b19bd803d70a934a2fc7e122894534255008c3", workspace.copyOfRange(0, 32).toHex())
        assertEquals("daf46992df1ed21b8b56ac397d611828f1716ca712847b7551eb2ff475dc024d", workspace.copyOfRange(workspace.size - 32, workspace.size).toHex())

        val output = out(workspace)
        assertEquals("33532e554e19a5b35f3d621d486f9b7716d29c0605198e4a6ac1d320038bc28d", sha(output))
    }

    @Test
    fun thirdSlice() {
        val sp488 = sp488()
        val context = FirstPairSourceSlice.builder6388f0SharedContextFromBundle()
        val secondWorkspace = FirstPairSourceSlice.builder6473d0Second64c524Workspace(out0Seed, sp488)
        val secondOutput = out(secondWorkspace)

        val source = FirstPairSourceSlice.builder6473d0ThirdSourceWords(secondOutput, context, in0)
        assertEquals("6e34220606e6f5401122a935081884080412601262e305b059fddbe077803051", sha(packU32(source)))
        assertEquals(listOf(0xf5b579d3u, 0x9d2de314u, 0x2241a5f4u, 0x79d5b76fu), source.take(4))

        val sp430 = FirstPairSourceSlice.builder6473d0ThirdSP430Words(source)
        assertEquals("08a18f8160f3efca8c1f5834fa918d904b749724799d71b3d4420491df15318d", sha(packU32(sp430)))
        assertEquals(listOf(0x113e3183u, 0x7ec235c3u, 0x55dcb210u, 0xf48aa162u), sp430.take(4))

        val s = FirstPairSourceSlice.builder6473d0ThirdStreams(in2, sp488)
        assertEquals("cdcd8b9cba1a6f86509bbcc4a95e3d88787cfef390803bad4bb1f50cbd55eddf", sha(packU64(s.a)))
        assertEquals("20e806f4dec45407ae529955b5ae41af7c5228c240dbed1fb916a74a85417c6e", sha(packU64(s.b)))

        val workspace = FirstPairSourceSlice.builder6473d0Third64c524Workspace(in2, sp488)
        assertEquals("fb42796391ab808c901953f0f4c3be19bbd4d49553f4df5c111d566766a1ceb6", sha(workspace))
        val output = out(workspace)
        assertEquals("bddbfeeece5007421a61931dd17aa25f9e70ac7141aa394880fabbec7357b655", sha(output))
    }

    @Test
    fun fourthSlice() {
        val sp488 = sp488()
        val thirdWorkspace = FirstPairSourceSlice.builder6473d0Third64c524Workspace(in2, sp488)
        val thirdOutput = out(thirdWorkspace)

        val s = FirstPairSourceSlice.builder6473d0FourthStreams(out1Seed, thirdOutput)
        assertEquals("7d6cc7ceb43b20b5f628756c842c5ac7be1fde886d9309ed4518ac9ae4bb287c", sha(packU64(s.a)))
        assertEquals("f5bc9070ebae188fb4864386433ac3eeda3e68ed9a9e471aa5c21ebb45ca573b", sha(packU64(s.b)))

        val workspace = FirstPairSourceSlice.builder6473d0Fourth64c524Workspace(out1Seed, thirdOutput)
        assertEquals("2805724203e148d53582172f661f1c76fac1cceb16baa58a2d36f584fde6ebd0", sha(workspace))
        val output = out(workspace)
        assertEquals("1be5186490600873daaffdaf8dc02f9b053d163a70b498b6dabee5c10b7dbf88", sha(output))
    }

    @Test
    fun fifthSlice() {
        val sp488 = sp488()
        val context = FirstPairSourceSlice.builder6388f0SharedContextFromBundle()
        val secondWorkspace = FirstPairSourceSlice.builder6473d0Second64c524Workspace(out0Seed, sp488)
        val secondOutput = out(secondWorkspace)
        val thirdWorkspace = FirstPairSourceSlice.builder6473d0Third64c524Workspace(in2, sp488)
        val thirdOutput = out(thirdWorkspace)
        val thirdSource = FirstPairSourceSlice.builder6473d0ThirdSourceWords(secondOutput, context, in0)
        val sp430 = FirstPairSourceSlice.builder6473d0ThirdSP430Words(thirdSource)
        val fourthWorkspace = FirstPairSourceSlice.builder6473d0Fourth64c524Workspace(out1Seed, thirdOutput)
        val fourthOutput = out(fourthWorkspace)

        val source = FirstPairSourceSlice.builder6473d0FifthSourceWords(fourthOutput, context, in1)
        assertEquals("45c409753e24cb9cf75d8d77f758eaed100f898588b6ed1159f790a3754ee721", sha(packU32(source)))
        assertEquals(listOf(0x038ec1c7u, 0x7d124e33u, 0x9326dce9u, 0x7d8762dfu), source.take(4))

        val sp3d8 = FirstPairSourceSlice.builder6473d0FifthSP3D8Words(source)
        assertEquals("d655cd5c70f502d4fa869630aad63fa3640a51cae610093237b5d9a2e5c3fb52", sha(packU32(sp3d8)))
        assertEquals(listOf(0x2e3356bbu, 0x050404a6u, 0x85d8c536u, 0xef821eafu), sp3d8.take(4))

        val s = FirstPairSourceSlice.builder6473d0FifthStreams(sp430)
        assertEquals("df784feb1db29901a7c95bd7093566adc6e0bd687800292035222c6c8266f735", sha(packU64(s.a)))
        assertEquals("520a4f15468ed4cd614ee48c53dd7cc5c37f65f43d8d7eb1b43fdfe50674f3b3", sha(packU64(s.b)))

        val workspace = FirstPairSourceSlice.builder6473d0Fifth64c524Workspace(sp430)
        assertEquals("08b5f2127f4f9d38a87ed43cc9df67aa41ee69d2f8f59b28bb09cdb00ed7d475", sha(workspace))
        val output = out(workspace)
        assertEquals("7b8aeba91679cc87e0038e22256590e68819f8ae38935c5428e89b1783d3f4cc", sha(output))
    }

    @Test
    fun sixthSlice() {
        val sp488 = sp488()
        val context = FirstPairSourceSlice.builder6388f0SharedContextFromBundle()
        val secondWorkspace = FirstPairSourceSlice.builder6473d0Second64c524Workspace(out0Seed, sp488)
        val secondOutput = out(secondWorkspace)
        val thirdSource = FirstPairSourceSlice.builder6473d0ThirdSourceWords(secondOutput, context, in0)
        val sp430 = FirstPairSourceSlice.builder6473d0ThirdSP430Words(thirdSource)
        val fifthWorkspace = FirstPairSourceSlice.builder6473d0Fifth64c524Workspace(sp430)
        val fifthOutput = out(fifthWorkspace)

        val sp380 = FirstPairSourceSlice.builder6473d0SixthSP380Words(fifthOutput)
        assertEquals("9504a74f2e28965005e5404ec5efe753f7422bcb693e58f4a5a6a7c18c0ad9b0", sha(packU32(sp380)))
        assertEquals(listOf(0x90f87c6eu, 0x3895b89cu, 0x930c4ee5u, 0x982ce414u), sp380.take(4))

        val s = FirstPairSourceSlice.builder6473d0SixthStreams(sp430, sp380)
        assertEquals("2887bd4468fadc7e22e31f0fb9217bed4d97fe0244489fdf7f68d8bb9be25cbe", sha(packU64(s.a)))
        assertEquals("0935766dcd7efadb89b9ee300f6515d6919f7ca13efbef3d94b4c4338f44c0a9", sha(packU64(s.b)))

        val workspace = FirstPairSourceSlice.builder6473d0Sixth64c524Workspace(sp430, sp380)
        assertEquals("4c4b1b102a9fa301c2ba8541851a2c324d6064c6ca38d695888dd660bd77ef6a", sha(workspace))
        val output = out(workspace)
        assertEquals("2f5a21c7c184f09bdb00f9f11eb2d4292e2814306b2cf01cd97806dbb1f9c91b", sha(output))
    }

    /** Walks the full 6473d0 chain once; covers Seventh..Final byte-for-byte. */
    @Test
    fun seventhThroughFinal() {
        val sp488 = sp488()
        val ctx = FirstPairSourceSlice.builder6388f0SharedContextFromBundle()

        val secondOutput = out(FirstPairSourceSlice.builder6473d0Second64c524Workspace(out0Seed, sp488))
        val thirdOutput = out(FirstPairSourceSlice.builder6473d0Third64c524Workspace(in2, sp488))
        val thirdSource = FirstPairSourceSlice.builder6473d0ThirdSourceWords(secondOutput, ctx, in0)
        val sp430 = FirstPairSourceSlice.builder6473d0ThirdSP430Words(thirdSource)
        val fourthOutput = out(FirstPairSourceSlice.builder6473d0Fourth64c524Workspace(out1Seed, thirdOutput))
        val fifthSource = FirstPairSourceSlice.builder6473d0FifthSourceWords(fourthOutput, ctx, in1)
        val sp3d8 = FirstPairSourceSlice.builder6473d0FifthSP3D8Words(fifthSource)
        val fifthOutput = out(FirstPairSourceSlice.builder6473d0Fifth64c524Workspace(sp430))
        val sp380 = FirstPairSourceSlice.builder6473d0SixthSP380Words(fifthOutput)
        val sixthOutput = out(FirstPairSourceSlice.builder6473d0Sixth64c524Workspace(sp430, sp380))

        // Seventh
        val sp328 = FirstPairSourceSlice.builder6473d0SeventhSP328Words(sixthOutput)
        assertEquals("82f7fa5ba1bfc8e84f566c3807ad6f87b5d574c792446ed797577ebb74eaf27a", sha(packU32(sp328)))
        val seventhStreams = FirstPairSourceSlice.builder6473d0SeventhStreams(in0, sp380)
        assertEquals("591cba75b0159e431614c436e10997c886d68a6d759a8d31be910eca22cf85e8", sha(packU64(seventhStreams.a)))
        assertEquals("6b23d88bd5e0da2f00dfb68fdfba69ec6d94b5230348df4cb69d7adde0687c10", sha(packU64(seventhStreams.b)))
        val seventhWorkspace = FirstPairSourceSlice.builder6473d0Seventh64c524Workspace(in0, sp380)
        assertEquals("2700fbf891008ce8edc1f11ba1557fd080d684b965dd65136260fbf2b0964878", sha(seventhWorkspace))
        val seventhOutput = out(seventhWorkspace)
        assertEquals("0eccbe5cdbf85dff7a37c87d7e555481d1fd8bdae3b39088b6b6273af860a786", sha(seventhOutput))

        // Eighth
        val sp2d0 = FirstPairSourceSlice.builder6473d0EighthSP2D0Words(seventhOutput)
        assertEquals("56cbeb55b6aa494a61207e0fca41d0265e917f6002b1ba2b8ece63458b76b085", sha(packU32(sp2d0)))
        val eighthStreams = FirstPairSourceSlice.builder6473d0EighthStreams(sp3d8)
        assertEquals("183f2be6b622dc2eb373fa393d1683d6dad201749fa3e60f45f6d027a267bdd9", sha(packU64(eighthStreams.a)))
        assertEquals("8b884e80ad5ed6bd11860fe89f504e27b2a92ec8007bda2a1a51bf08fd9bb9f3", sha(packU64(eighthStreams.b)))
        val eighthWorkspace = FirstPairSourceSlice.builder6473d0Eighth64c524Workspace(sp3d8)
        assertEquals("be9f91423ea1b893b3e58a877eb02980b4b44bc8906e2cd19a8a6df6b3d941b3", sha(eighthWorkspace))
        val eighthOutput = out(eighthWorkspace)
        assertEquals("cc933de7d8905c4cac18b91c959fea8e7c302bd9fa3573677fe59f251908255a", sha(eighthOutput))

        // Ninth
        val source1 = FirstPairSourceSlice.builder6473d0NinthFirstSourceWords(eighthOutput, ctx, sp328, sp2d0)
        assertEquals("e18c8416b17464c4bae6ff625375d164dfec55af019420d2ca0116c3cc0ebed6", sha(packU32(source1)))
        val out2 = FirstPairSourceSlice.builder6473d0NinthOut2Words(source1)
        assertEquals("8368856aed45b1d49270602d6edba23e92809b2346290be6bc6a392f0aaf988d", sha(packU32(out2)))
        val source2 = FirstPairSourceSlice.builder6473d0NinthSecondSourceWords(sp2d0, ctx, out2)
        assertEquals("f8eb019cf9c0f4154874174ce8abf240b8e14a3ec59e411d84a550998a7a9d5c", sha(packU32(source2)))
        val sp278 = FirstPairSourceSlice.builder6473d0NinthSP278Words(source2)
        assertEquals("d572b9c1bb74f6bfc893a8cf8991fee8f10520a516289e4ba9c18db8eccd7750", sha(packU32(sp278)))

        val firstStreams = FirstPairSourceSlice.builder6473d0NinthFirstStreams(sp3d8, sp278)
        assertEquals("b71bdd49fef5edf17f87cbed6f144013ad6570fb619d7259eecee78563d4321e", sha(packU64(firstStreams.a)))
        assertEquals("95ceb0b250e78470133394a723b433a5bf8e3f7669cfb77be6dcaefcccac094d", sha(packU64(firstStreams.b)))
        assertEquals("43db401e21867455f70a7da5b3688986851ff1c50922e2b4a764382a75ec7bd7", sha(packU64(firstStreams.c)))
        assertEquals("e327fc0348a9eb211eec7c47eb4ad39feeabd01ad929c43990736a69c6961b40", sha(packU64(firstStreams.d)))
        val sp1c8 = FirstPairSourceSlice.builder6473d0NinthSP1C8Words(firstStreams.a, firstStreams.b)
        assertEquals("86cae00a8644b96d2f81fdcc84e5a861e6086d2e57635918c1aa0a285f01a839", sha(packU32(sp1c8)))

        val secondStreams = FirstPairSourceSlice.builder6473d0NinthSecondStreams(in1, sp328)
        assertEquals("e2610e9a2bb7e7431a9d50e0c52d546c4dbf46dbf3d806253e11af0b66db64e0", sha(packU64(secondStreams.a)))
        assertEquals("5cb107493620008b697a14577e98aca3b0b99ecbc0d5eb11824683df14589d8f", sha(packU64(secondStreams.b)))
        val sp118 = FirstPairSourceSlice.builder6473d0NinthSP118Words(secondStreams.a, secondStreams.b)
        assertEquals("ded780efbdff94c54fe57299d3fa3542f688bd86e0fc0b6890bd0a135e686c4c", sha(packU32(sp118)))

        val source3 = FirstPairSourceSlice.builder6473d0NinthThirdSourceWords(sp1c8, ctx, sp118)
        assertEquals("551881a522a53344a1f14689741406abc30267914aa02e8d36bd722182015f11", sha(packU32(source3)))
        val sp68 = FirstPairSourceSlice.builder6473d0NinthSP68Words(source3)
        assertEquals("b5b7d07e747d5a677fb6cf9182a96755196408c6487d1e4e77bb37364cd12a07", sha(packU32(sp68)))
        val ninthWorkspace = FirstPairSourceSlice.builder6473d0Ninth64c524Workspace(sp68)
        assertEquals(44 * 8, ninthWorkspace.size)
        assertEquals("01fab44ba58a157c53f8035e82a33ad31e7d5f3090040841bd4041841e9a2b22", sha(ninthWorkspace))
        val ninthOutput = out(ninthWorkspace)
        assertEquals("d81b8468a5a7d4b17bf44f39b479476a8568bfd82aadc937b563221ed16604c7", sha(ninthOutput))

        // Tenth + Final
        val out3 = FirstPairSourceSlice.builder6473d0TenthOut3Words(ninthOutput)
        assertEquals("8b4cdea006adbc427fc62ed7e593a7368be01c5433e4792558564f7d944edcd1", sha(packU32(out3)))
        val tenthWorkspace = FirstPairSourceSlice.builder6473d0Tenth64c524Workspace(in2, sp430)
        assertEquals("29506e31bf43027e2b607a86a6b4dd22f33d88454f491b93e8872a9171e6a9e2", sha(tenthWorkspace))
        val tenthOutput = out(tenthWorkspace)
        assertEquals("a36706388c5ac543203e0cd3eb2af42d02f3242aec9d4c183cb4fba7a3d7bb0c", sha(tenthOutput))
        val out4 = FirstPairSourceSlice.builder6473d0FinalOut4Words(tenthOutput)
        assertEquals("401fe726d4a75d7c7e6d399aa2e00fea0d56a3d6c5b3dc1b328178ea24d7a442", sha(packU32(out4)))
    }

    @Test
    fun outputsAssembler() {
        val r = FirstPairSourceSlice.builder6473d0OutputsFromBundledContext(in0, in1, in2, out0Seed, out1Seed)
        assertEquals(88, r.out2.size)
        assertEquals("9692c8145a24dcadc1fd23963c583512c8aebf55dc7c68ad677cb8f53f2117ea", sha(r.in0After))
        assertEquals("a4a1bb98f66e3d53a51c810379507e4a1f856bf51be0d007e10d5b3afc90252b", sha(r.in1After))
        assertEquals("8eb586c217d306dbde11f9301ab67d009e8dba5414bcebe90944e8542082edee", sha(r.in2After))
        assertEquals("76cebb860262dd83aa186fc63ea614b3af5633e56600dda4d4da79ba840366bd", sha(r.out0))
        assertEquals("c49ad60aa507e639c71430a12067b0eb5d75737460bd9997b020b5760197ceb8", sha(r.out1))
        assertEquals("c5e3ec0675df26d11bd8390e34135652ad6b530fb8e003151b44cf1dfce6e169", sha(r.out2))
        assertEquals("d1486d791a35e129933d31bab4e814a0cdcd3db8c3b4895950882cba18791c90", sha(r.out3))
        assertEquals("3d1a32df33f5ce078ed6cfa67972c041d5aff9606ff86c381b5d257fa4bb3517", sha(r.out4))
        val combined = r.in0After + r.in1After + r.in2After + r.out0 + r.out1 + r.out2 + r.out3 + r.out4
        assertEquals("62d20b19dfc648c822a404a8672031efe193c1da60496b82a337458c1c1d2a5c", sha(combined))
    }
}
