package re.abbot.librecr.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import re.abbot.librecr.app.libreview.MeasurementPayload

class MeasurementPayloadTest {
    // 2022-06-20T20:24:33.000Z
    private val ms = 1_655_756_673_000L
    private val device = MeasurementPayload.Device(
        hardwareDescriptor = "Pixel 10 Pro XL",
        hardwareName = "Google",
        modelName = "com.freestylelibre.app.gb",
        osVersion = "35",
        uniqueIdentifier = "b76f19a9-d4a0-4d67-8999-56b89b0968ee",
    )

    @Test
    fun utcTimestampIsDeterministic() {
        assertEquals("2022-06-20T20:24:33.000Z", MeasurementPayload.utcTimestamp(ms))
    }

    @Test
    fun trendMapping() {
        assertEquals("FallingQuickly", MeasurementPayload.trendName("FALLING_QUICKLY"))
        assertEquals("Stable", MeasurementPayload.trendName("STABLE"))
        assertEquals("RisingQuickly", MeasurementPayload.trendName("RISING_QUICKLY"))
        assertEquals("NotComputable", MeasurementPayload.trendName(null))
    }

    @Test
    fun scheduledEntryHasExactFields() {
        val o = JSONObject(MeasurementPayload.scheduledEntry(159, ms, 12_345L))
        assertEquals(159, o.getInt("valueInMgPerDl"))
        assertEquals(12_345L, o.getLong("recordNumber"))
        assertEquals("2022-06-20T20:24:33.000Z", o.getJSONObject("extendedProperties").getString("factoryTimestamp"))
        assertFalse(o.getJSONObject("extendedProperties").getBoolean("isFirstAfterTimeChange"))
    }

    @Test
    fun currentEntryCarriesTrendAndOutOfRange() {
        val ext = JSONObject(MeasurementPayload.currentEntry(35, "FALLING", ms, 7L)).getJSONObject("extendedProperties")
        assertEquals("Falling", ext.getString("trendArrow"))
        assertEquals("true", ext.getString("lowOutOfRange"))  // 35 < 40
        assertEquals("false", ext.getString("highOutOfRange"))
    }

    @Test
    fun buildProducesValidEnvelope() {
        val body = MeasurementPayload.build(
            device = device,
            unitLabel = "mg/dL",
            isStreaming = true,
            language = "en-GB",
            targetLow = 70,
            targetHigh = 180,
            hour24 = true,
            nowMs = ms,
            userToken = "JWT.TOKEN.HERE",
            currentEntries = listOf(MeasurementPayload.currentEntry(120, "STABLE", ms, 100L)),
            scheduledEntries = listOf(MeasurementPayload.scheduledEntry(120, ms, 100L)),
        )
        val root = JSONObject(body) // also asserts the concatenation is well-formed JSON
        assertEquals("JWT.TOKEN.HERE", root.getString("UserToken"))
        assertEquals("Libreview", root.getString("Domain"))
        assertEquals("FSLibreLink.Android", root.getString("GatewayType"))
        val deviceData = root.getJSONObject("DeviceData")
        assertEquals("Android", deviceData.getJSONObject("header").getJSONObject("device").getString("osType"))
        val log = deviceData.getJSONObject("measurementLog")
        assertEquals(120, log.getJSONArray("scheduledContinuousGlucoseEntries").getJSONObject(0).getInt("valueInMgPerDl"))
        assertEquals(120, log.getJSONArray("currentGlucoseEntries").getJSONObject(0).getInt("valueInMgPerDl"))
        assertEquals("Google", log.getJSONObject("device").getString("hardwareName")) // measurementLog repeats device
        assertEquals(70, deviceData.getJSONObject("deviceSettings").getJSONObject("miscellaneous").getInt("valueGlucoseTargetRangeLowInMgPerDl"))
    }
}
