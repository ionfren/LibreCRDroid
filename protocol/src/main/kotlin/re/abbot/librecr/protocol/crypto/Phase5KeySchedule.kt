package re.abbot.librecr.protocol.crypto

/**
 * Clean-room port of lib+0x5de1e4 — the Phase 5 wire-cipher key schedule
 * (Swift `Phase5KeySchedule`). Turns the 66-byte stage-1 source (produced by the
 * first-pair source builder) into the 16-byte raw key consumed by
 * [LibAes.phase5BlockEncryptor].
 *
 * Driven by an 19-bit S-box (`sbox_19bit_lib_986819`, 0x80000 bytes) and the
 * `phase5_keysched_region_274000` region (0x2000 bytes). Ported op-for-op; all
 * VM math uses Int with explicit `and 0xff` masking.
 */
object Phase5KeySchedule {
    private const val STAGE1_COUNT = 66

    fun deriveRawKey(input66: ByteArray): ByteArray {
        if (input66.size != STAGE1_COUNT) throw Phase5KeyScheduleException.InvalidInputLength(input66.size)
        val t = Tables.shared
        val sbox = t.sbox
        val region = t.region
        val prog = region.copyOfRange(PROG_DATA_BASE - REGION_BASE, PROG_DATA_BASE - REGION_BASE + 0x1000)

        val inBuf = ByteArray(0x90)
        val stage1Prog = prog.copyOfRange(STAGE1_TABLE_OFFSET, STAGE1_TABLE_OFFSET + STAGE1_COUNT)
        val stage1 = vmA(sbox, input66, input66, stage1Prog, STAGE1_COUNT)
        stage1.copyInto(inBuf, 0)

        val stack = ByteArray(0x180)
        for (call in PHASE1_CALLS) {
            val totalRead = when (call.op) {
                OP_VMD -> 6
                OP_VMB -> call.countA + call.countB
                else -> call.countB // OP_VMA
            }
            val src1 = source(call.src1, inBuf, stack, totalRead)
            val src2 = source(call.src2, inBuf, stack, totalRead)
            val progSeg = prog.copyOfRange(call.progOffset, call.progOffset + totalRead)
            val out = when (call.op) {
                OP_VMA -> vmA(sbox, src1, src2, progSeg, call.countB)
                OP_VMB -> vmB(sbox, src1, src2, progSeg, call.countA, call.countB, 0)
                else -> vmD(sbox, src1, src2, progSeg)
            }
            out.copyInto(stack, call.dst)
        }

        val subOrcBytes = ByteArray(16)
        for (iter in 0 until 16) {
            val chunkInOff = PHASE2_CHUNK_OFFSETS_SP[iter]
            for (round in 0 until 4) {
                val src1Off = if (round == 0) chunkInOff else PHASE2_SCRATCH_SRCS[round]
                val src1 = stack.copyOfRange(src1Off, src1Off + 6)
                val src2 = regionSlice(region, PHASE2_SRC2_LIB_OFFSETS[iter][round], 6)
                val progSeg = prog.copyOfRange(PHASE2_PROG_OFFSETS[iter][round], PHASE2_PROG_OFFSETS[iter][round] + 6)
                val out = vmD(sbox, src1, src2, progSeg)
                out.copyInto(stack, PHASE2_SCRATCH_DSTS[round])
            }
            val compressed = subOrc(stack.copyOfRange(SUB_ORC_ARG_SP, SUB_ORC_ARG_SP + 6), sbox, prog, region)
            subOrcBytes[iter] = compressed
            stack[SUB_ORC_ACCUM_SP] = compressed
        }

        val aesKey = ByteArray(16)
        for (iter in 0 until 16) {
            val tableOffset = if (iter == 0) 0 else iter * 0x100 + 0x40
            val regionOff = POST_LOOP_BASE - REGION_BASE + tableOffset + (subOrcBytes[iter].toInt() and 0xff)
            aesKey[KEY_POS_BY_ITER[iter]] = read(region, regionOff)
        }
        return aesKey
    }

    // ---- VM primitives ----

    private fun vmA(sbox: ByteArray, src1: ByteArray, src2: ByteArray, prog: ByteArray, length: Int): ByteArray {
        var state = 0
        val out = ByteArray(length)
        for (i in 0 until length) {
            state = vmOpNoWrite(state, src1[i], src2[i], prog[i], sbox)
            out[i] = (state and 7).toByte()
        }
        return out
    }

    private fun vmB(
        sbox: ByteArray, src1: ByteArray, src2: ByteArray, prog: ByteArray,
        lengthA: Int, lengthB: Int, lengthC: Int,
    ): ByteArray {
        var state = 0
        for (i in 0 until lengthA) state = vmOpNoWrite(state, src1[i], src2[i], prog[i], sbox)
        val out = ByteArray(lengthB + lengthC)
        for (i in 0 until lengthB) {
            val idx = lengthA + i
            state = vmOpNoWrite(state, src1[idx], src2[idx], prog[idx], sbox)
            out[i] = (state and 7).toByte()
        }
        for (i in 0 until lengthC) {
            val idx = lengthA + lengthB + i
            val sboxIdx = ((state and 0xf8) or ((prog[idx].toInt() and 0xff) shl 11)) and 0x7ffff
            state = sbox[sboxIdx].toInt() and 0xff
            out[lengthB + i] = (state and 7).toByte()
        }
        return out
    }

    private fun vmD(sbox: ByteArray, src1: ByteArray, src2: ByteArray, prog: ByteArray): ByteArray =
        vmA(sbox, src1, src2, prog, 6)

    private fun vmOpNoWrite(state: Int, src1: Byte, src2: Byte, prog: Byte, sbox: ByteArray): Int {
        val s1 = src1.toInt() and 0xff
        val s2 = src2.toInt() and 0xff
        val p = prog.toInt() and 0xff
        val idx = (((state and 0xf8) xor s1) or ((s2 shl 8) xor (p shl 11))) and 0x7ffff
        return sbox[idx].toInt() and 0xff
    }

    private fun subOrc(arg6B: ByteArray, sbox: ByteArray, prog: ByteArray, region: ByteArray): Byte {
        val scratch = transform6B(arg6B, sbox)
        val vma1Prog = prog.copyOfRange(SUB_ORC_VMA1_OFFSET, SUB_ORC_VMA1_OFFSET + 4)
        val vma2Prog = prog.copyOfRange(SUB_ORC_VMA2_OFFSET, SUB_ORC_VMA2_OFFSET + 4)

        val keep = vmA(sbox, scratch.copyOfRange(0, 4), scratch.copyOfRange(0, 4), vma1Prog, 4)

        val scratchMut = scratch.copyOf()
        val vmbProg = prog.copyOfRange(SUB_ORC_VMB_OFFSET, SUB_ORC_VMB_OFFSET + SUB_ORC_VMB_LEN_A + SUB_ORC_VMB_LEN_B + SUB_ORC_VMB_LEN_C)
        val vmbOut = vmB(sbox, scratchMut, scratchMut, vmbProg, SUB_ORC_VMB_LEN_A, SUB_ORC_VMB_LEN_B, SUB_ORC_VMB_LEN_C)
        vmbOut.copyInto(scratchMut, 0)

        val step5 = vmA(sbox, keep, keep, vma2Prog, 4)
        val idx5 = (step5[2].toInt() and 0xff) xor ((step5[3].toInt() and 0xff) shl 3)
        val lookup5 = read(region, PHASE5_SBOX_OFF + idx5).toInt() and 0xff

        val postVMB = scratchMut.copyOfRange(0, 4)
        val keep2 = vmA(sbox, postVMB, postVMB, vma1Prog, 4)
        val step7 = vmA(sbox, keep2, keep2, vma2Prog, 4)
        val idx7 = (step7[2].toInt() and 0xff) xor ((step7[3].toInt() and 0xff) shl 3)
        val lookup7 = read(region, PHASE5_SBOX_OFF + idx7).toInt() and 0xff

        return ((lookup7 and 0xf0) or (lookup5 and 0x0f)).toByte()
    }

    private fun transform6B(arg6B: ByteArray, sbox: ByteArray): ByteArray {
        fun a(i: Int) = arg6B[i].toInt() and 0xff
        val out = ByteArray(6)
        var x = scramblerHalfword(sbox, a(0) + 0x2000)
        x = scramblerHalfword(sbox, ((x and 0xff8) xor a(1)) or 0x2000)
        x = scramblerHalfword(sbox, ((x and 0xff8) xor a(2)) or 0x2000)
        x = scramblerHalfword(sbox, ((x and 0xff8) xor a(3)) or 0x4000)
        out[0] = (x and 7).toByte()

        x = scramblerHalfword(sbox, (((x and 0xff8) xor a(4)) or 0x21000) + 0xd000)
        out[1] = (x and 7).toByte()
        x = scramblerHalfword(sbox, ((x and 0xff8) xor a(5)) or 0x21000)
        out[2] = (x and 7).toByte()

        x = scramblerHalfword(sbox, (x xor a(2)) xor 0x6000)
        out[3] = (x and 7).toByte()
        x = scramblerHalfword(sbox, (x xor a(3)) xor 0x2000)
        out[4] = (x and 7).toByte()
        x = scramblerHalfword(sbox, (x xor a(4)) xor 0x4000)
        out[5] = (x and 7).toByte()
        return out
    }

    private fun scramblerHalfword(sbox: ByteArray, index: Int): Int {
        val byteOffset = SCRAMBLER_OFFSET_IN_SBOX + index * 2
        val lo = read(sbox, byteOffset).toInt() and 0xff
        val hi = read(sbox, byteOffset + 1).toInt() and 0xff
        return lo or (hi shl 8)
    }

    // ---- helpers ----

    private fun source(src: Int, inBuf: ByteArray, stack: ByteArray, count: Int): ByteArray =
        if (src == SRC_INPUT) inBuf.copyOfRange(0, count) else stack.copyOfRange(src, src + count)

    private fun regionSlice(region: ByteArray, libOffset: Int, count: Int): ByteArray {
        val offset = libOffset - REGION_BASE
        if (offset < 0 || offset + count > region.size) {
            throw Phase5KeyScheduleException.TableReadOutOfBounds("phase5_keysched_region", offset)
        }
        return region.copyOfRange(offset, offset + count)
    }

    private fun read(bytes: ByteArray, offset: Int): Byte {
        if (offset < 0 || offset >= bytes.size) {
            throw Phase5KeyScheduleException.TableReadOutOfBounds("table", offset)
        }
        return bytes[offset]
    }

    // ---- constants ----

    private const val REGION_BASE = 0x274000
    private const val PROG_DATA_BASE = 0x274624
    private const val STAGE1_TABLE_OFFSET = 0x124
    private const val PHASE5_SBOX_OFF = 0x9d3
    private const val POST_LOOP_BASE = 0x274a13
    private const val SCRAMBLER_OFFSET_IN_SBOX = 0x20001

    private const val SUB_ORC_VMA1_OFFSET = 465
    private const val SUB_ORC_VMB_OFFSET = 194
    private const val SUB_ORC_VMB_LEN_A = 2
    private const val SUB_ORC_VMB_LEN_B = 4
    private const val SUB_ORC_VMB_LEN_C = 2
    private const val SUB_ORC_VMA2_OFFSET = 833

    private val PHASE2_SCRATCH_DSTS = intArrayOf(0xb4, 0x92, 0xe8, 0xd6)
    private val PHASE2_SCRATCH_SRCS = intArrayOf(0, 0xb4, 0x92, 0xe8)
    private const val SUB_ORC_ARG_SP = 0xd6
    private const val SUB_ORC_ACCUM_SP = 0x104

    private val PHASE2_CHUNK_OFFSETS_SP = intArrayOf(
        0x8c, 0x86, 0x80, 0x7a, 0x74, 0x6e, 0x68, 0x62,
        0x5c, 0x56, 0x50, 0x4a, 0x44, 0x3e, 0x38, 0x32,
    )

    private val PHASE2_PROG_OFFSETS = arrayOf(
        intArrayOf(611, 364, 805, 382),
        intArrayOf(581, 208, 569, 160),
        intArrayOf(124, 136, 765, 737),
        intArrayOf(106, 112, 370, 18),
        intArrayOf(731, 523, 425, 843),
        intArrayOf(553, 895, 675, 715),
        intArrayOf(487, 24, 398, 879),
        intArrayOf(12, 743, 821, 30),
        intArrayOf(669, 657, 873, 118),
        intArrayOf(547, 517, 587, 188),
        intArrayOf(166, 0, 182, 286),
        intArrayOf(837, 36, 651, 867),
        intArrayOf(404, 202, 663, 376),
        intArrayOf(605, 130, 575, 681),
        intArrayOf(6, 599, 749, 499),
        intArrayOf(645, 493, 358, 541),
    )

    private val PHASE2_SRC2_LIB_OFFSETS = arrayOf(
        intArrayOf(0x2749bb, 0x2749c1, 0x2749c7, 0x2749cd),
        intArrayOf(0x275a53, 0x275a59, 0x275a5f, 0x275a65),
        intArrayOf(0x275a6b, 0x275a71, 0x275a77, 0x275a7d),
        intArrayOf(0x275a83, 0x275a89, 0x275a8f, 0x275a95),
        intArrayOf(0x275a9b, 0x275aa1, 0x275aa7, 0x275aad),
        intArrayOf(0x275ab3, 0x275ab9, 0x275abf, 0x275ac5),
        intArrayOf(0x275acb, 0x275ad1, 0x275ad7, 0x275add),
        intArrayOf(0x275ae3, 0x275ae9, 0x275aef, 0x275af5),
        intArrayOf(0x275afb, 0x275b01, 0x275b07, 0x275b0d),
        intArrayOf(0x275b13, 0x275b19, 0x275b1f, 0x275b25),
        intArrayOf(0x275b2b, 0x275b31, 0x275b37, 0x275b3d),
        intArrayOf(0x275b43, 0x275b49, 0x275b4f, 0x275b55),
        intArrayOf(0x275b5b, 0x275b61, 0x275b67, 0x275b6d),
        intArrayOf(0x275b73, 0x275b79, 0x275b7f, 0x275b85),
        intArrayOf(0x275b8b, 0x275b91, 0x275b97, 0x275b9d),
        intArrayOf(0x275ba3, 0x275ba9, 0x275baf, 0x275bb5),
    )

    private val KEY_POS_BY_ITER = intArrayOf(
        3, 2, 1, 0,
        7, 6, 5, 4,
        11, 10, 9, 8,
        15, 14, 13, 12,
    )

    private const val OP_VMA = 0
    private const val OP_VMB = 1
    private const val OP_VMD = 2
    private const val SRC_INPUT = -1

    private class Phase1Call(
        val op: Int,
        val progOffset: Int,
        val countA: Int,
        val countB: Int,
        val src1: Int,
        val src2: Int,
        val dst: Int,
    )

    private val PHASE1_CALLS = arrayOf(
        Phase1Call(OP_VMA, 0x1af, 0, 34, SRC_INPUT, SRC_INPUT, 0xb4),
        Phase1Call(OP_VMB, 0x0d6, 32, 34, SRC_INPUT, SRC_INPUT, 0x92),
        Phase1Call(OP_VMA, 0x269, 0, 18, 0xb4, 0xb4, 0xe8),
        Phase1Call(OP_VMB, 0x303, 16, 18, 0xb4, 0xb4, 0xd6),
        Phase1Call(OP_VMA, 0x02a, 0, 10, 0xe8, 0xe8, 0x104),
        Phase1Call(OP_VMB, 0x1d5, 8, 10, 0xe8, 0xe8, 0xfa),
        Phase1Call(OP_VMD, 0x211, 0, 6, 0x104, 0x104, 0x32),
        Phase1Call(OP_VMB, 0x32b, 4, 6, 0x104, 0x104, 0x38),
        Phase1Call(OP_VMD, 0x1f9, 0, 6, 0xfa, 0xfa, 0x3e),
        Phase1Call(OP_VMB, 0x2d1, 4, 6, 0xfa, 0xfa, 0x44),
        Phase1Call(OP_VMA, 0x0ac, 0, 10, 0xd6, 0xd6, 0x104),
        Phase1Call(OP_VMB, 0x2b9, 8, 10, 0xd6, 0xd6, 0xfa),
        Phase1Call(OP_VMD, 0x1a3, 0, 6, 0x104, 0x104, 0x4a),
        Phase1Call(OP_VMB, 0x034, 4, 6, 0x104, 0x104, 0x50),
        Phase1Call(OP_VMD, 0x217, 0, 6, 0xfa, 0xfa, 0x56),
        Phase1Call(OP_VMB, 0x27b, 4, 6, 0xfa, 0xfa, 0x5c),
        Phase1Call(OP_VMA, 0x08e, 0, 18, 0x92, 0x92, 0xe8),
        Phase1Call(OP_VMB, 0x03e, 16, 18, 0x92, 0x92, 0xd6),
        Phase1Call(OP_VMA, 0x22f, 0, 10, 0xe8, 0xe8, 0x104),
        Phase1Call(OP_VMB, 0x351, 8, 10, 0xe8, 0xe8, 0xfa),
        Phase1Call(OP_VMD, 0x1ff, 0, 6, 0x104, 0x104, 0x62),
        Phase1Call(OP_VMB, 0x375, 4, 6, 0x104, 0x104, 0x68),
        Phase1Call(OP_VMD, 0x33b, 0, 6, 0xfa, 0xfa, 0x6e),
        Phase1Call(OP_VMB, 0x2af, 4, 6, 0xfa, 0xfa, 0x74),
        Phase1Call(OP_VMA, 0x184, 0, 10, 0xd6, 0xd6, 0x104),
        Phase1Call(OP_VMB, 0x385, 8, 10, 0xd6, 0xd6, 0xfa),
        Phase1Call(OP_VMD, 0x118, 0, 6, 0x104, 0x104, 0x7a),
        Phase1Call(OP_VMB, 0x2f3, 4, 6, 0x104, 0x104, 0x80),
        Phase1Call(OP_VMD, 0x251, 0, 6, 0xfa, 0xfa, 0x86),
        Phase1Call(OP_VMB, 0x060, 4, 6, 0xfa, 0xfa, 0x8c),
    )

    private class Tables private constructor() {
        val sbox: ByteArray = load("sbox_19bit_lib_986819")
        val region: ByteArray = load("phase5_keysched_region_274000")

        init {
            if (sbox.size != 0x80000) throw Phase5KeyScheduleException.InvalidTableSize("sbox19", sbox.size)
            if (region.size != 0x2000) throw Phase5KeyScheduleException.InvalidTableSize("phase5_keysched_region", region.size)
        }

        companion object {
            val shared: Tables by lazy { Tables() }
            private fun load(name: String): ByteArray {
                val stream = Tables::class.java.getResourceAsStream("/runtime_tables/$name.bin")
                    ?: throw Phase5KeyScheduleException.MissingResource(name)
                return stream.use { it.readBytes() }
            }
        }
    }
}

sealed class Phase5KeyScheduleException(message: String) : Exception(message) {
    class InvalidInputLength(len: Int) : Phase5KeyScheduleException("invalid input length $len")
    class InvalidTableSize(name: String, size: Int) : Phase5KeyScheduleException("invalid table size $name=$size")
    class TableReadOutOfBounds(name: String, off: Int) : Phase5KeyScheduleException("read OOB $name @ $off")
    class MissingResource(name: String) : Phase5KeyScheduleException("missing resource $name")
}
