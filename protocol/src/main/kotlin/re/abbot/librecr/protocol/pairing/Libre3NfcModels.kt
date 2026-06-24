package re.abbot.librecr.protocol.pairing

import re.abbot.librecr.protocol.toHex

class Libre3NfcPatchInfo(raw: ByteArray) {
    val inputRaw: ByteArray = raw.copyOf()
    val raw: ByteArray = normalizePatchInfoResponse(raw)
    val stateByte: Int
    val productType: Int
    val generation: Int
    val wearDurationMinutes: Int
    val warmupMinutes: Int
    val firmwareVersion: String
    val serialNumber: String

    init {
        if (this.raw.size < 29 || this.raw.u8(0) != 0x00 || this.raw.u8(1) != 0xa5) {
            throw Libre3NfcException.InvalidPatchInfo(raw)
        }
        generation = this.raw.u16le(7)
        wearDurationMinutes = this.raw.u16le(9)
        firmwareVersion = "${this.raw.u8(14)}.${this.raw.u8(13)}.${this.raw.u8(12)}.${this.raw.u8(11)}"
        productType = this.raw.u8(15)
        warmupMinutes = this.raw.u8(16) * 5
        stateByte = this.raw.u8(17)
        val serialBytes = this.raw.copyOfRange(18, 27)
        serialNumber = if (serialBytes.all { (it.toInt() and 0xff) < 0x80 }) {
            serialBytes.toString(Charsets.US_ASCII)
        } else {
            serialBytes.toHex()
        }
    }

    val isStorageState: Boolean get() = stateByte == 0x01

    val recommendedCommandCode: NfcActivationCommandCode
        get() = if (isStorageState) {
            NfcActivationCommandCode.ACTIVATE
        } else {
            NfcActivationCommandCode.SWITCH_RECEIVER
        }
}

class Libre3NfcActivationResponse(raw: ByteArray) {
    val raw: ByteArray = normalizeActivationResponse(raw)
    val bleAddressLittleEndian: ByteArray
    val blePin: ByteArray
    val activationTimeRaw: ByteArray
    val trailingCrc: ByteArray

    init {
        if (
            this.raw.size != 19 ||
            this.raw.u8(0) != 0x00 ||
            this.raw.u8(1) != 0xa5 ||
            this.raw.u8(2) != 0x00
        ) {
            throw Libre3NfcException.InvalidActivationResponse(raw)
        }
        bleAddressLittleEndian = this.raw.copyOfRange(3, 9)
        blePin = this.raw.copyOfRange(9, 13)
        activationTimeRaw = this.raw.copyOfRange(13, 17)
        trailingCrc = this.raw.copyOfRange(17, 19)
    }

    val bleAddressDisplay: String
        get() = bleAddressLittleEndian.reversedArray()
            .joinToString(":") { "%02X".format(it.toInt() and 0xff) }

    val activationTimeSeconds: Long
        get() =
            (activationTimeRaw.u8(0).toLong()) or
                (activationTimeRaw.u8(1).toLong() shl 8) or
                (activationTimeRaw.u8(2).toLong() shl 16) or
                (activationTimeRaw.u8(3).toLong() shl 24)
}

class Libre3NfcActivationErrorResponse(raw: ByteArray) {
    val raw: ByteArray = normalizeActivationResponse(raw)
    val errorCode: Int

    init {
        if (
            this.raw.size != 4 ||
            this.raw.u8(0) != 0x00 ||
            this.raw.u8(1) != 0xa5 ||
            this.raw.u8(2) != 0x01
        ) {
            throw Libre3NfcException.InvalidActivationResponse(raw)
        }
        errorCode = this.raw.u8(3)
    }

    val isJugglucoNonFatal: Boolean get() = errorCode == 0xb1
}

data class Libre3NfcScanResult(
    val patchInfo: Libre3NfcPatchInfo,
    val commandCode: NfcActivationCommandCode? = null,
    val commandParameters: ByteArray? = null,
    val activationResponse: Libre3NfcActivationResponse? = null,
    val activationError: Libre3NfcActivationErrorResponse? = null,
)

sealed class Libre3NfcScanMode {
    object ReadPatchInfo : Libre3NfcScanMode()

    data class ActivateFreshSensor(
        val receiverId: Int,
        val timeSeconds: Int? = null,
    ) : Libre3NfcScanMode()

    data class SwitchReceiver(
        val receiverId: Int,
        val timeSeconds: Int? = null,
    ) : Libre3NfcScanMode()

    data class ActivateOrSwitchReceiver(
        val receiverId: Int,
        val timeSeconds: Int? = null,
    ) : Libre3NfcScanMode()

    data class ForceActivationCommand(
        val commandCode: NfcActivationCommandCode,
        val receiverId: Int,
        val timeSeconds: Int? = null,
    ) : Libre3NfcScanMode()
}

sealed class Libre3NfcException(message: String) : Exception(message) {
    object ReaderUnavailable : Libre3NfcException("NFC reader unavailable")
    object ReaderDisabled : Libre3NfcException("NFC is disabled")
    object SessionAlreadyActive : Libre3NfcException("NFC scan already active")
    object NoTag : Libre3NfcException("No NFC tag detected")
    object NonIso15693Tag : Libre3NfcException("Tag is not ISO 15693 / NfcV")
    class InvalidPatchInfo(val data: ByteArray) :
        Libre3NfcException("Invalid Libre 3 patch info (${data.size} bytes): ${data.toHex()}")
    class InvalidActivationResponse(val data: ByteArray) :
        Libre3NfcException("Invalid Libre 3 activation response (${data.size} bytes): ${data.toHex()}")
    class InvalidActivationResponseForPatch(
        val commandCode: NfcActivationCommandCode,
        val patchInfo: Libre3NfcPatchInfo,
        val data: ByteArray,
    ) : Libre3NfcException(
        "Invalid Libre 3 activation response for command 0x${"%02x".format(commandCode.raw)} " +
            "on state 0x${"%02x".format(patchInfo.stateByte)} " +
            "(${data.size} bytes): ${data.toHex()}"
    )
    class UnexpectedSensorState(val patchInfo: Libre3NfcPatchInfo) :
        Libre3NfcException(
            "Unexpected sensor state 0x${"%02x".format(patchInfo.stateByte)} " +
                "serial=${patchInfo.serialNumber}"
        )
}

private fun normalizePatchInfoResponse(raw: ByteArray): ByteArray {
    var frame = raw.copyOf()
    if (frame.firstOrNull()?.toInt()?.and(0xff) == 0xa5) {
        frame = byteArrayOf(0x00) + frame
    }
    if (frame.size >= 2 && frame.u8(0) == 0x00 && frame.u8(1) == 0xa5) {
        var bodyStart = 2
        while (bodyStart < frame.size && frame.u8(bodyStart) == 0xa5) {
            bodyStart += 1
        }
        if (bodyStart > 2) {
            return byteArrayOf(0x00, 0xa5.toByte()) + frame.copyOfRange(bodyStart, frame.size)
        }
    }
    return frame
}

private fun normalizeActivationResponse(raw: ByteArray): ByteArray {
    if (raw.size >= 2 && raw.u8(0) == 0x00 && raw.u8(1) == 0xa5) {
        return raw.copyOf()
    }
    if (raw.firstOrNull()?.toInt()?.and(0xff) == 0xa5) {
        return byteArrayOf(0x00) + raw
    }
    return raw.copyOf()
}

private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xff

private fun ByteArray.u16le(offset: Int): Int =
    u8(offset) or (u8(offset + 1) shl 8)
