package re.abbot.librecr.app.ble

import kotlinx.coroutines.withTimeout
import re.abbot.librecr.app.log.BleLog
import re.abbot.librecr.protocol.ble.BleFraming
import re.abbot.librecr.protocol.pairing.BleCharRef
import re.abbot.librecr.protocol.pairing.CommandPairingTransport
import java.util.UUID

/**
 * Drives [re.abbot.librecr.protocol.pairing.PairingFlow] over a real
 * [SensorConnection]. Maps BleCharRef → GATT UUID, fragments phone→sensor
 * writes (2B LE offset + 18B chunks), and reassembles sensor→phone notifies
 * (1B seq + chunk). Android analogue of Swift `SensorSessionTransport`.
 */
class AndroidGattTransport(
    private val conn: SensorConnection,
    private val perFragmentTimeoutMs: Long = 10_000L,
) : CommandPairingTransport {

    private fun uuidFor(ref: BleCharRef): UUID = when (ref) {
        BleCharRef.CERT_HANDSHAKE -> LibreSensorGatt.CERT_HANDSHAKE
        BleCharRef.CHALLENGE -> LibreSensorGatt.CHALLENGE
    }

    override suspend fun write(message: ByteArray, characteristic: BleCharRef) {
        val uuid = uuidFor(characteristic)
        BleLog.log("BLE write $characteristic len=${message.size} data=${BleLog.hex(message)}")
        for (frag in BleFraming.fragmentForWrite(message)) {
            conn.writeCharacteristic(uuid, frag, withResponse = true, timeoutMs = perFragmentTimeoutMs)
        }
    }

    override suspend fun awaitNotify(characteristic: BleCharRef, exactly: Int): ByteArray {
        val uuid = uuidFor(characteristic)
        BleLog.log("BLE await notify $characteristic exactly=$exactly")
        val channel = conn.notifyChannel(uuid)
        val reassembler = BleFraming.NotifyReassembler()
        while (true) {
            val frag = withTimeout(perFragmentTimeoutMs) { channel.receive() }
            reassembler.feed(frag)
            BleLog.log("BLE notify progress $characteristic available=${reassembler.availableBytes} wanted=$exactly")
            if (reassembler.availableBytes >= exactly) {
                val out = reassembler.take(exactly)
                BleLog.log("BLE notify complete $characteristic len=${out.size} data=${BleLog.hex(out)}")
                return out
            }
        }
    }

    override suspend fun writeCommand(command: Int) {
        BleLog.log("BLE write command=0x%02x".format(command))
        conn.writeCharacteristic(
            LibreSensorGatt.SEC_COMMAND_RESPONSE,
            byteArrayOf(command.toByte()),
            withResponse = true,
            timeoutMs = perFragmentTimeoutMs,
        )
    }

    override suspend fun awaitCommandResponse(timeoutMs: Long): ByteArray {
        val channel = conn.notifyChannel(LibreSensorGatt.SEC_COMMAND_RESPONSE)
        val resp = withTimeout(timeoutMs) { channel.receive() }
        BleLog.log("BLE command response len=${resp.size} data=${BleLog.hex(resp)}")
        return resp
    }
}
