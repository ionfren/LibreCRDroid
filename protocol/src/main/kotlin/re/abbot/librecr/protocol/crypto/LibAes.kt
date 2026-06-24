package re.abbot.librecr.protocol.crypto

import re.abbot.librecr.protocol.putU32le
import re.abbot.librecr.protocol.u32le

/**
 * Clean-room port of Abbott's Phase 5 block primitive (Swift `LibAES`):
 *   key setup     lib+0x5dec48..0x5defb0
 *   block encrypt lib+0x5e41b4..0x5e45e4
 *   wire block    lib+0x5defec..0x5df414
 *
 * Table-driven AES-like primitive (NOT standard AES). Tables under
 * runtime_tables/libaes_*.bin. Ported operation-for-operation; all 32-bit math
 * uses Kotlin `Int` (uint32 bit pattern), `ushr` for logical right shift.
 */
object LibAes {
    const val CONTEXT_SIZE = 0x10b0

    fun blockEncryptor(rawKey: ByteArray): AesBlock {
        val ctx = keySetup(rawKey)
        return { block -> blockEncrypt(block, ctx) }
    }

    fun phase5BlockEncryptor(rawKey: ByteArray): AesBlock {
        val ctx = keySetup(rawKey)
        return { block -> phase5BlockEncrypt(block, ctx) }
    }

    fun keySetup(rawKey: ByteArray): ByteArray {
        if (rawKey.size != 16) throw LibAesException.InvalidKeyLength(rawKey.size)
        val t = LibAesTables.shared
        val ctx = ByteArray(CONTEXT_SIZE)
        rawKey.copyInto(ctx, 0, 0, 16)

        var outOff = 0x10
        var constA = 0x08   // 0x276bc4 - 0x276bbc
        var constB = 0xa8   // 0x276c64 - 0x276bbc
        var loopCtr = -4
        var w13 = ctx.u32le(0x0c)

        while (true) {
            val w14 = t.keyexpConsts.u32le(constA - 8)
            loopCtr += 4
            val keepGoing = loopCtr < 0x24

            w13 = w14 xor ror32(w13, 24)
            var sub = subword(t.keyexpTables, w13, 0)
            val prev0 = ctx.u32le(outOff - 0x10)
            val prev1 = ctx.u32le(outOff - 0x0c)
            var w15 = prev0 xor t.keyexpConsts.u32le(constB - 8)
            w13 = w15 xor sub
            ctx.putU32le(outOff, w13)

            w13 = t.keyexpConsts.u32le(constA - 4) xor w13
            sub = subword(t.keyexpTables, w13, 1)
            var w14Mix = prev1 xor t.keyexpConsts.u32le(constB - 4)
            w13 = w14Mix xor sub
            ctx.putU32le(outOff + 0x04, w13)

            w13 = t.keyexpConsts.u32le(constA) xor w13
            sub = subword(t.keyexpTables, w13, 2)
            val prev2 = ctx.u32le(outOff - 0x08)
            val prev3 = ctx.u32le(outOff - 0x04)
            w15 = prev2 xor t.keyexpConsts.u32le(constB)
            w13 = w15 xor sub
            ctx.putU32le(outOff + 0x08, w13)

            w13 = t.keyexpConsts.u32le(constA + 0x04) xor w13
            constA += 0x10
            sub = subword(t.keyexpTables, w13, 3)
            w14Mix = prev3 xor t.keyexpConsts.u32le(constB + 0x04)
            constB += 0x10
            w13 = w14Mix xor sub
            ctx.putU32le(outOff + 0x0c, w13)

            outOff += 0x10
            if (!keepGoing) break
        }

        for (group in 0 until 4) {
            val off = 0xa0 + group * 4
            ctx.putU32le(off, subword(t.finalKeyTables, ctx.u32le(off), group))
        }

        for (tableIdx in 0 until 16) {
            val wordTableIdx = t.finalTableIndex.u32le(tableIdx * 4)
            val wordTableBase = wordTableIdx * 0x400
            val mapBase = tableIdx * 0x100
            val keyWord = ctx.u32le(0xa0 + (tableIdx shr 2) * 4)
            val shift = 24 - 8 * (tableIdx and 3)
            val dst = 0xb0 + tableIdx * 0x100

            for (i in 0 until 256) {
                val mixed = keyWord xor t.finalTableWords.u32le(wordTableBase + i * 4)
                ctx[dst + i] = t.finalTableMap[mapBase + ((mixed ushr shift) and 0xff)]
            }
        }

        return ctx
    }

    fun blockEncrypt(plaintext: ByteArray, ctx: ByteArray): ByteArray {
        if (plaintext.size != 16) throw LibAesException.InvalidInputLength(plaintext.size)
        if (ctx.size < CONTEXT_SIZE) throw LibAesException.InvalidContextLength(ctx.size)

        val t = LibAesTables.shared
        val pt = plaintext
        val r2 = t.round2Tables

        val state = ByteArray(16)
        for (c in 0 until 4) {
            for (r in 0 until 4) {
                val ti = c * 4 + (3 - r)
                state[c * 4 + r] =
                    (t.round1Tables[ti * 0x100 + (pt[ti].toInt() and 0xff)].toInt() xor ctx[c * 4 + r].toInt()).toByte()
            }
        }

        var w16 = state.u32le(0)
        var w17 = state.u32le(4)
        var w14 = state.u32le(8)
        var w13 = state.u32le(12)
        var x8 = 0
        var w11 = 0
        var w15 = 0

        while (true) {
            // ---- first half ----
            w11 = w17 and 0xff
            w15 = ubfx(w13, 16, 8)
            var w0 = ubfx(w14, 8, 8)
            var x11i = w11
            var w3 = w16 ushr 24
            var w6 = ctx.u32le(0x10 + x8)
            var w7 = ctx.u32le(0x14 + x8)
            val x15i = w15
            var x0i = w0
            w11 = tw(r2, 7, x11i)
            var w5 = w14 and 0xff
            var w4 = ubfx(w13, 8, 8)
            w3 = tw(r2, 0, w3)
            var x5i = w5
            w15 = tw(r2, 13, x15i)
            w0 = tw(r2, 10, x0i)
            w11 = w11 xor w6
            w6 = ubfx(w16, 16, 8)
            var w19 = w17 ushr 24
            w11 = w11 xor w15
            w15 = w0 xor w3
            x0i = w4
            w3 = tw(r2, 11, x5i)
            var x4i = w6
            x5i = w19
            w6 = w13 and 0xff
            w0 = tw(r2, 14, x0i)
            w19 = w14 ushr 24
            w4 = tw(r2, 1, x4i)
            w5 = tw(r2, 4, x5i)
            var x6i = w6
            w3 = w3 xor w7
            val x7i = w19
            w11 = w11 xor w15
            w15 = w3 xor w0
            w0 = w4 xor w5
            w6 = tw(r2, 15, x6i)
            w19 = ctx.u32le(0x18 + x8)
            w4 = ctx.u32le(0x1c + x8)
            w3 = tw(r2, 8, x7i)
            val w7i = ubfx(w17, 16, 8)
            val w14i = ubfx(w14, 16, 8)
            val w17i = ubfx(w17, 8, 8)
            val w13i = w13 ushr 24
            w5 = w19 xor w6
            val w6i = ubfx(w16, 8, 8)
            val w16i = w16 and 0xff
            val w16t = tw(r2, 3, w16i)
            val w7t = tw(r2, 5, w7i)
            val w14t = tw(r2, 9, w14i)
            val w6t = tw(r2, 2, w6i)
            val w17t = tw(r2, 6, w17i)
            val w13t = tw(r2, 12, w13i)
            w16 = w4 xor w16t
            w3 = w5 xor w3
            w5 = w6t xor w7t
            w14 = w17t xor w14t
            w16 = w16 xor w13t
            w13 = w15 xor w0
            w15 = w3 xor w5
            w14 = w14 xor w16

            if (x8 == 0x80) break

            // ---- second half ----
            w17 = w13 and 0xff
            w16 = w11 ushr 24
            w0 = ubfx(w15, 8, 8)
            w4 = ubfx(w11, 16, 8)
            val x17i = w17
            w5 = w15 and 0xff
            w3 = ubfx(w14, 16, 8)
            w6 = ubfx(w14, 8, 8)
            w16 = tw(r2, 0, w16)
            x0i = w0
            x4i = w4
            w17 = tw(r2, 7, x17i)
            w7 = w13 ushr 24
            x5i = w5
            val x3iFirst = w3
            w0 = tw(r2, 10, x0i)
            w4 = tw(r2, 1, x4i)
            w16 = w17 xor w16
            w17 = tw(r2, 11, x5i)
            x5i = w6
            x6i = w7
            w16 = w16 xor w0
            w0 = tw(r2, 13, x3iFirst)
            val oldX8 = x8
            x8 += 0x20
            w17 = w17 xor w4
            w3 = tw(r2, 14, x5i)
            w4 = tw(r2, 4, x6i)
            w5 = w14 and 0xff
            val w14tmp = w14 ushr 24
            w6 = ubfx(w11, 8, 8)
            w3 = w3 xor w4
            x5i = w5
            val w11tmp = w11 and 0xff
            val w7rk = ctx.u32le(0x20 + oldX8)
            val w4rk = ctx.u32le(0x24 + oldX8)
            w17 = w17 xor w3
            val x14i = w14tmp
            x11i = w11tmp
            val x3i = w6
            w0 = w0 xor w7rk
            w17 = w17 xor w4rk
            val w4idx = ubfx(w15, 16, 8)
            w16 = w16 xor w0
            w0 = tw(r2, 15, x5i)
            val w5idx = ubfx(w13, 8, 8)
            val w15tmp = w15 ushr 24
            val w13tmp = ubfx(w13, 16, 8)
            x4i = w4idx
            x5i = w5idx
            val w14v = tw(r2, 12, x14i)
            val w11v = tw(r2, 3, x11i)
            val x15i2 = w15tmp
            val x13i = w13tmp
            val w4v = tw(r2, 9, x4i)
            val w6rk = ctx.u32le(0x28 + oldX8)
            val w12rk = ctx.u32le(0x2c + oldX8)
            val w5v = tw(r2, 6, x5i)
            val w3v = tw(r2, 2, x3i)
            val w15v = tw(r2, 8, x15i2)
            val w13v = tw(r2, 5, x13i)
            w11 = w14v xor w11v
            w0 = w0 xor w6rk
            w4 = w5v xor w4v
            w14 = w0 xor w3v
            w13 = w15v xor w13v
            w11 = w4 xor w11
            w14 = w14 xor w13
            w13 = w11 xor w12rk
        }

        val out = ByteArray(16)
        out[0] = dyn(ctx, 0x0b0, w11 ushr 24)
        out[1] = dyn(ctx, 0x1b0, w14 ushr 16)
        out[2] = dyn(ctx, 0x2b0, w15 ushr 8)
        out[3] = dyn(ctx, 0x3b0, w13)
        out[4] = dyn(ctx, 0x4b0, w13 ushr 24)
        out[5] = dyn(ctx, 0x5b0, w11 ushr 16)
        out[6] = dyn(ctx, 0x6b0, w14 ushr 8)
        out[7] = dyn(ctx, 0x7b0, w15)
        out[8] = dyn(ctx, 0x8b0, w15 ushr 24)
        out[9] = dyn(ctx, 0x9b0, w13 ushr 16)
        out[10] = dyn(ctx, 0xab0, w11 ushr 8)
        out[11] = dyn(ctx, 0xbb0, w14)
        out[12] = dyn(ctx, 0xcb0, w14 ushr 24)
        out[13] = dyn(ctx, 0xdb0, w15 ushr 16)
        out[14] = dyn(ctx, 0xeb0, w13 ushr 8)
        out[15] = dyn(ctx, 0xfb0, w11)
        return out
    }

    fun phase5BlockEncrypt(plaintext: ByteArray, ctx: ByteArray): ByteArray {
        if (plaintext.size != 16) throw LibAesException.InvalidInputLength(plaintext.size)
        if (ctx.size < CONTEXT_SIZE) throw LibAesException.InvalidContextLength(ctx.size)

        val t = LibAesTables.shared
        val pt = plaintext
        val r1 = t.phase5Round1Tables

        var w16 = ctx.u32le(0x00) xor
            ((r1[0x000 + (pt[0].toInt() and 0xff)].toInt() and 0xff) shl 24) xor
            ((r1[0x100 + (pt[1].toInt() and 0xff)].toInt() and 0xff) shl 16) xor
            ((r1[0x200 + (pt[2].toInt() and 0xff)].toInt() and 0xff) shl 8) xor
            (r1[0x300 + (pt[3].toInt() and 0xff)].toInt() and 0xff)
        var w14 = ctx.u32le(0x04) xor
            ((r1[0x400 + (pt[4].toInt() and 0xff)].toInt() and 0xff) shl 24) xor
            ((r1[0x500 + (pt[5].toInt() and 0xff)].toInt() and 0xff) shl 16) xor
            ((r1[0x600 + (pt[6].toInt() and 0xff)].toInt() and 0xff) shl 8) xor
            (r1[0x700 + (pt[7].toInt() and 0xff)].toInt() and 0xff)
        var w13 = ctx.u32le(0x08) xor
            ((r1[0x800 + (pt[8].toInt() and 0xff)].toInt() and 0xff) shl 24) xor
            ((r1[0x900 + (pt[9].toInt() and 0xff)].toInt() and 0xff) shl 16) xor
            ((r1[0xa00 + (pt[10].toInt() and 0xff)].toInt() and 0xff) shl 8) xor
            (r1[0xb00 + (pt[11].toInt() and 0xff)].toInt() and 0xff)
        var w15 = ctx.u32le(0x0c) xor
            ((r1[0xc00 + (pt[12].toInt() and 0xff)].toInt() and 0xff) shl 24) xor
            ((r1[0xd00 + (pt[13].toInt() and 0xff)].toInt() and 0xff) shl 16) xor
            ((r1[0xe00 + (pt[14].toInt() and 0xff)].toInt() and 0xff) shl 8) xor
            (r1[0xf00 + (pt[15].toInt() and 0xff)].toInt() and 0xff)

        var x8 = 0
        var w11 = 0
        while (true) {
            val fh = phase5FirstHalf(w16, w14, w13, w15, ctx, x8)
            w11 = fh[0]; w14 = fh[1]; w13 = fh[2]; w15 = fh[3]
            if (x8 == 0x80) break
            val sh = phase5SecondHalf(w11, w14, w13, w15, ctx, x8)
            w16 = sh[0]; w14 = sh[1]; w13 = sh[2]; w15 = sh[3]
            x8 += 0x20
        }

        val out = ByteArray(16)
        out[0] = dyn(ctx, 0x0b0, w11 ushr 24)
        out[1] = dyn(ctx, 0x1b0, w14 ushr 16)
        out[2] = dyn(ctx, 0x2b0, w13 ushr 8)
        out[3] = dyn(ctx, 0x3b0, w15)
        out[4] = dyn(ctx, 0x4b0, w14 ushr 24)
        out[5] = dyn(ctx, 0x5b0, w13 ushr 16)
        out[6] = dyn(ctx, 0x6b0, w15 ushr 8)
        out[7] = dyn(ctx, 0x7b0, w11)
        out[8] = dyn(ctx, 0x8b0, w13 ushr 24)
        out[9] = dyn(ctx, 0x9b0, w15 ushr 16)
        out[10] = dyn(ctx, 0xab0, w11 ushr 8)
        out[11] = dyn(ctx, 0xbb0, w14)
        out[12] = dyn(ctx, 0xcb0, w15 ushr 24)
        out[13] = dyn(ctx, 0xdb0, w11 ushr 16)
        out[14] = dyn(ctx, 0xeb0, w14 ushr 8)
        out[15] = dyn(ctx, 0xfb0, w13)
        return out
    }
}

// ---- file-private helpers ----

private fun ubfx(x: Int, lsb: Int, width: Int): Int = (x ushr lsb) and ((1 shl width) - 1)

private fun ror32(x: Int, shift: Int): Int = (x ushr shift) or (x shl (32 - shift))

private fun subword(table: ByteArray, value: Int, group: Int): Int {
    val base = group * 0x400
    val b0 = value and 0xff
    val b1 = (value ushr 8) and 0xff
    val b2 = (value ushr 16) and 0xff
    val b3 = (value ushr 24) and 0xff
    return (table[base + 0x300 + b0].toInt() and 0xff) or
        ((table[base + 0x100 + b2].toInt() and 0xff) shl 16) or
        ((table[base + 0x200 + b1].toInt() and 0xff) shl 8) or
        ((table[base + b3].toInt() and 0xff) shl 24)
}

private fun tw(table: IntArray, tableIdx: Int, index: Int): Int = table[tableIdx * 256 + (index and 0xff)]

private fun dyn(ctx: ByteArray, off: Int, idx: Int): Byte = ctx[off + (idx and 0xff)]

private fun phase5FirstHalf(w16: Int, w14: Int, w13: Int, w15: Int, ctx: ByteArray, x8: Int): IntArray {
    val t = LibAesTables.shared.phase5RoundTables
    val rk0 = ctx.u32le(0x10 + x8)
    val rk1 = ctx.u32le(0x14 + x8)
    val rk2 = ctx.u32le(0x18 + x8)
    val rk3 = ctx.u32le(0x1c + x8)

    val out11 = tw(t, 0, w16 ushr 24) xor rk0 xor tw(t, 15, w15) xor
        tw(t, 5, ubfx(w14, 16, 8)) xor tw(t, 10, ubfx(w13, 8, 8))

    val out14 = (rk1 xor tw(t, 3, w16) xor tw(t, 9, ubfx(w13, 16, 8))) xor
        (tw(t, 4, w14 ushr 24) xor tw(t, 14, ubfx(w15, 8, 8)))

    val out13 = tw(t, 13, ubfx(w15, 16, 8)) xor tw(t, 7, w14) xor
        tw(t, 2, ubfx(w16, 8, 8)) xor tw(t, 8, w13 ushr 24) xor rk2

    val out15 = (tw(t, 6, ubfx(w14, 8, 8)) xor rk3) xor
        (tw(t, 12, w15 ushr 24) xor tw(t, 11, w13)) xor tw(t, 1, ubfx(w16, 16, 8))

    return intArrayOf(out11, out14, out13, out15)
}

private fun phase5SecondHalf(w11: Int, w14: Int, w13: Int, w15: Int, ctx: ByteArray, x8: Int): IntArray {
    val t = LibAesTables.shared.phase5RoundTables
    val rk0 = ctx.u32le(0x20 + x8)
    val rk1 = ctx.u32le(0x24 + x8)
    val rk2 = ctx.u32le(0x28 + x8)
    val rk3 = ctx.u32le(0x2c + x8)

    val out16 = tw(t, 15, w15) xor tw(t, 0, w11 ushr 24) xor
        tw(t, 10, ubfx(w13, 8, 8)) xor tw(t, 5, ubfx(w14, 16, 8)) xor rk0

    val out14 = (tw(t, 4, w14 ushr 24) xor tw(t, 3, w11) xor tw(t, 14, ubfx(w15, 8, 8))) xor
        rk1 xor tw(t, 9, ubfx(w13, 16, 8))

    val out13 = (tw(t, 13, ubfx(w15, 16, 8)) xor rk2 xor tw(t, 2, ubfx(w11, 8, 8))) xor
        tw(t, 8, w13 ushr 24) xor tw(t, 7, w14)

    val out15 = (tw(t, 11, w13) xor tw(t, 1, ubfx(w11, 16, 8)) xor rk3) xor
        (tw(t, 12, w15 ushr 24) xor tw(t, 6, ubfx(w14, 8, 8)))

    return intArrayOf(out16, out14, out13, out15)
}
