package re.abbot.librecr.app.data

import org.json.JSONObject
import re.abbot.librecr.protocol.hexToBytes
import re.abbot.librecr.protocol.toHex

/**
 * Permanent sensor provisioning learned from NFC. This is the only payload that
 * may cross Phone <-> Watch: BLE identity, PIN/auth tail, receiver/sensor IDs,
 * and static sensor metadata. Transient BLE security material is intentionally
 * not serialized or imported.
 */
data class ImportedSession(
    val bleAddress: String,
    val bleDeviceName: String?,
    val blePin: ByteArray,
    /** Legacy/local-only field kept so older UI/call-sites compile; ignored for BLE and JSON. */
    val phase5RawKey: ByteArray?,
    /** Legacy/local-only field kept for compatibility; provisioning uses the bundled cert. */
    val phoneCert: ByteArray?,
    val receiverId: Int?,
    val serial: String?,
    val warmupMinutes: Int?,
    val wearMinutes: Int?,
    val sensorProductType: Int? = null,
    val sensorGeneration: Int? = null,
    val sensorFirmwareVersion: String? = null,
) {
    init {
        require(blePin.size == 4) { "blePin must be 4 bytes" }
        require(phase5RawKey == null || phase5RawKey.size == 16) { "phase5RawKey must be 16 bytes" }
        require(phoneCert == null || phoneCert.size == 162) { "phoneCert must be 162 bytes" }
    }

    fun toJson(): String = JSONObject().apply {
        put("bleAddress", bleAddress)
        bleDeviceName?.let { put("bleDeviceName", it) }
        put("blePin", blePin.toHex())
        receiverId?.let { put("receiverId", it.toLong() and 0xffffffffL) }
        serial?.let { put("serial", it) }
        warmupMinutes?.let { put("warmupMinutes", it) }
        wearMinutes?.let { put("wearMinutes", it) }
        sensorProductType?.let { put("sensorProductType", it) }
        sensorGeneration?.let { put("sensorGeneration", it) }
        sensorFirmwareVersion?.let { put("sensorFirmwareVersion", it) }
    }.toString()

    fun toProvisioningJson(): String = withoutTransientCrypto().toJson()

    fun withoutTransientCrypto(): ImportedSession =
        if (phase5RawKey == null && phoneCert == null) this else copy(phase5RawKey = null, phoneCert = null)

    companion object {
        fun fromJson(json: String): ImportedSession {
            val o = JSONObject(json)
            val pin = o.optString("blePin").ifBlank { o.optString("blePIN") }
            return ImportedSession(
                bleAddress = o.getString("bleAddress"),
                bleDeviceName = optionalString(o, "bleDeviceName") ?: optionalString(o, "deviceName"),
                blePin = parseHex(pin),
                phase5RawKey = null,
                phoneCert = null,
                receiverId = if (o.has("receiverId")) o.getLong("receiverId").toInt() else null,
                serial = if (o.has("serial")) o.getString("serial") else null,
                warmupMinutes = if (o.has("warmupMinutes")) o.getInt("warmupMinutes") else null,
                wearMinutes = if (o.has("wearMinutes")) o.getInt("wearMinutes") else null,
                sensorProductType = optionalInt(o, "sensorProductType") ?: optionalInt(o, "productType"),
                sensorGeneration = optionalInt(o, "sensorGeneration") ?: optionalInt(o, "generation"),
                sensorFirmwareVersion = optionalString(o, "sensorFirmwareVersion") ?: optionalString(o, "firmwareVersion"),
            )
        }

        private fun optionalInt(o: JSONObject, key: String): Int? =
            if (o.has(key) && !o.isNull(key)) o.getInt(key) else null

        private fun optionalString(o: JSONObject, key: String): String? =
            if (o.has(key) && !o.isNull(key)) o.getString(key) else null

        private fun parseHex(raw: String): ByteArray {
            val compact = raw
                .replace("0x", "", ignoreCase = true)
                .filter { it.isHexDigit() }
            return hexToBytes(compact)
        }

        private fun Char.isHexDigit(): Boolean =
            this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
    }
}
