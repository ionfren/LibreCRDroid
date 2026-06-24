package re.abbot.librecr.app.libreview

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Builds the body for `POST /api/measurements`, ported from Juggluco's native
 * `cpp/net/libreview/libreview.cpp` (the network side is plain JSON, so no native code is needed).
 *
 * The envelope is `DeviceData` → `deviceSettings` + `header.device` + `measurementLog`. Each glucose
 * reading goes into `currentGlucoseEntries` (the live value followers see) and
 * `scheduledContinuousGlucoseEntries` (the graph). `factoryTimestamp` is UTC, `timestamp` is local
 * with offset — both for the same instant. Everything is pure/JVM so it is unit-testable.
 */
object MeasurementPayload {

    data class Device(
        val hardwareDescriptor: String, // Build.MODEL
        val hardwareName: String,        // Build.MANUFACTURER
        val modelName: String,           // e.g. com.freestylelibre.app.gb
        val osVersion: String,           // Build.VERSION.SDK_INT
        val uniqueIdentifier: String,    // stable device UUID
    )

    // The exact capability list Abbott's LibreLink app advertises (from libreview.cpp).
    private const val CAPABILITIES =
        "\"scheduledContinuousGlucose\",\"unscheduledContinuousGlucose\",\"currentGlucose\"," +
            "\"bloodGlucose\",\"insulin\",\"food\"," +
            "\"generic-com.abbottdiabetescare.informatics.exercise\"," +
            "\"generic-com.abbottdiabetescare.informatics.customnote\"," +
            "\"generic-com.abbottdiabetescare.informatics.ondemandalarm.low\"," +
            "\"generic-com.abbottdiabetescare.informatics.ondemandalarm.high\"," +
            "\"generic-com.abbottdiabetescare.informatics.ondemandalarm.projectedlow\"," +
            "\"generic-com.abbottdiabetescare.informatics.ondemandalarm.projectedhigh\"," +
            "\"generic-com.abbottdiabetescare.informatics.sensorstart\"," +
            "\"generic-com.abbottdiabetescare.informatics.error\"," +
            "\"generic-com.abbottdiabetescare.informatics.isfGlucoseAlarm\"," +
            "\"generic-com.abbottdiabetescare.informatics.alarmSetting\"," +
            "\"generic-com.abbottdiabetescare.informatics.sensorend\"," +
            "\"generic-com.abbottdiabetescare.informatics.bleDisconnect\"," +
            "\"generic-com.abbottdiabetescare.informatics.bleReconnect\""

    fun trendName(trend: String?): String = when (trend) {
        "FALLING_QUICKLY" -> "FallingQuickly"
        "FALLING" -> "Falling"
        "STABLE" -> "Stable"
        "RISING" -> "Rising"
        "RISING_QUICKLY" -> "RisingQuickly"
        else -> "NotComputable"
    }

    fun utcTimestamp(atMs: Long): String = format("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", TimeZone.getTimeZone("UTC"), atMs)

    fun localTimestamp(atMs: Long): String = format("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", TimeZone.getDefault(), atMs)

    private fun format(pattern: String, tz: TimeZone, atMs: Long): String =
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = tz }.format(atMs)

    /** A `scheduledContinuousGlucoseEntries` item (graph point). */
    fun scheduledEntry(mgDl: Int, atMs: Long, recordNumber: Long): String =
        "{\"valueInMgPerDl\":$mgDl,\"extendedProperties\":{\"factoryTimestamp\":\"${utcTimestamp(atMs)}\"," +
            "\"isFirstAfterTimeChange\":false},\"recordNumber\":$recordNumber,\"timestamp\":\"${localTimestamp(atMs)}\"}"

    /** A `currentGlucoseEntries` item (the live value, with trend). */
    fun currentEntry(mgDl: Int, trend: String?, atMs: Long, recordNumber: Long): String =
        "{\"valueInMgPerDl\":$mgDl,\"extendedProperties\":{\"factoryTimestamp\":\"${utcTimestamp(atMs)}\"," +
            "\"isViewed\":\"false\",\"lowOutOfRange\":\"${mgDl < 40}\",\"highOutOfRange\":\"${mgDl > 500}\"," +
            "\"trendArrow\":\"${trendName(trend)}\",\"isActionable\":\"true\",\"isFirstAfterTimeChange\":\"false\"}," +
            "\"recordNumber\":$recordNumber,\"timestamp\":\"${localTimestamp(atMs)}\"}"

    fun build(
        device: Device,
        unitLabel: String,           // "mg/dL"
        isStreaming: Boolean,
        language: String,            // e.g. "en-GB"
        targetLow: Int,
        targetHigh: Int,
        hour24: Boolean,
        nowMs: Long,
        userToken: String,
        currentEntries: List<String>,
        scheduledEntries: List<String>,
    ): String {
        fun q(s: String) = JSONObject.quote(s)
        val deviceObj = "\"device\":{\"hardwareDescriptor\":${q(device.hardwareDescriptor)}," +
            "\"hardwareName\":${q(device.hardwareName)},\"modelName\":${q(device.modelName)}," +
            "\"osType\":\"Android\",\"osVersion\":${q(device.osVersion)}," +
            "\"uniqueIdentifier\":${q(device.uniqueIdentifier)}}"
        return buildString {
            append("{\"DeviceData\":{\"connectedDevices\":{\"insulinDevices\":[]},")
            append("\"deviceSettings\":{\"factoryConfig\":{\"UOM\":${q(unitLabel)}},")
            append("\"firmwareVersion\":\"2.12.0\",\"miscellaneous\":{\"isStreaming\":$isStreaming,")
            append("\"selectedLanguage\":${q(language)},")
            append("\"valueGlucoseTargetRangeLowInMgPerDl\":$targetLow,")
            append("\"valueGlucoseTargetRangeHighInMgPerDl\":$targetHigh,")
            append("\"selectedTimeFormat\":\"${if (hour24) 24 else 12}hr\",\"selectedCarbType\":\"grams of carbs\"},")
            append("\"timestamp\":${q(localTimestamp(nowMs))}},\"forceUpload\":false,")
            append("\"header\":{$deviceObj},")
            append("\"measurementLog\":{\"bloodGlucoseEntries\":[],\"capabilities\":[$CAPABILITIES],")
            append("\"currentGlucoseEntries\":[").append(currentEntries.joinToString(",")).append("],")
            append(deviceObj).append(",")
            append("\"foodEntries\":[],\"genericEntries\":[],\"insulinEntries\":[],\"ketoneEntries\":[],")
            append("\"scheduledContinuousGlucoseEntries\":[").append(scheduledEntries.joinToString(",")).append("],")
            append("\"unscheduledContinuousGlucoseEntries\":[]}}")
            append(",\"UserToken\":${q(userToken)},\"Domain\":\"Libreview\",\"GatewayType\":\"FSLibreLink.Android\"}")
        }
    }
}
