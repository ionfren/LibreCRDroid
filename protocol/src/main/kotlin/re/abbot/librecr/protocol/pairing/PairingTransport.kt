package re.abbot.librecr.protocol.pairing

/** GATT characteristic identifier from the pairing flow's perspective. */
enum class BleCharRef {
    /** Cert + ephemeral exchange (handle 0x002d). */
    CERT_HANDSHAKE,

    /** Challenge / response (handle 0x002a). */
    CHALLENGE,
}

/**
 * Transport abstraction for the pairing state machine. Decouples [PairingFlow]
 * from Android BLE so it can be driven by a scripted transport in JVM tests and
 * by a real `BluetoothGatt` on device, via the same code path. Port of Swift
 * `PairingTransport`.
 */
interface PairingTransport {
    /** Write a logical message (the transport fragments it). Resolves on ACK. */
    suspend fun write(message: ByteArray, characteristic: BleCharRef)

    /** Reassemble notify fragments and return the first [exactly] bytes. */
    suspend fun awaitNotify(characteristic: BleCharRef, exactly: Int): ByteArray
}

/** Adds the single-byte command/response channel (handle 0x0027). */
interface CommandPairingTransport : PairingTransport {
    suspend fun writeCommand(command: Int)
    suspend fun awaitCommandResponse(timeoutMs: Long): ByteArray
}
