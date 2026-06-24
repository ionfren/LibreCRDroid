package re.abbot.librecr.protocol.dataplane

import re.abbot.librecr.protocol.u16le

/** Decoded patch-status plaintext (12 bytes). Port of Swift `PatchStatus`.
 *  Signed fields are stored as Int (sign-extended from Int16/Int8). */
class PatchStatus(plaintext: ByteArray) {
    val lifeCount: Int
    val errorData: Int
    val eventDataRaw: Int
    val eventData: Int
    val index: Int
    val totalEvents: Int
    val patchState: Int
    val currentLifeCount: Int
    val stackDisconnectReason: Int
    val appDisconnectReason: Int

    val isActive: Boolean get() = patchState == 4
    val hasErrorData: Boolean get() = errorData != 0
    val hasDisconnectReason: Boolean get() = stackDisconnectReason != 0 || appDisconnectReason != 0

    init {
        if (plaintext.size != PLAINTEXT_SIZE) throw PatchStatusException.WrongPlaintextSize(plaintext.size)
        val pt = plaintext
        lifeCount = s16(pt, 0)
        errorData = s16(pt, 2)
        eventDataRaw = s16(pt, 4)
        eventData = 4000 + eventDataRaw
        index = pt[6].toInt() // already signed
        totalEvents = index + 1
        patchState = pt[7].toInt()
        currentLifeCount = s16(pt, 8)
        stackDisconnectReason = pt[10].toInt()
        appDisconnectReason = pt[11].toInt()
    }

    fun lifecycle(
        warmupDurationMinutes: Int = SensorLifecycle.DEFAULT_WARMUP_DURATION_MINUTES,
        wearDurationMinutes: Int? = null,
    ): SensorLifecycle = SensorLifecycle(currentLifeCount, warmupDurationMinutes, wearDurationMinutes)

    companion object {
        const val PLAINTEXT_SIZE = 12
        private fun s16(b: ByteArray, off: Int): Int = b.u16le(off).toShort().toInt()
    }
}

sealed class PatchStatusException(message: String) : Exception(message) {
    class WrongPlaintextSize(val size: Int) : PatchStatusException("wrong plaintext size $size")
}
