package re.abbot.librecr.app.data

import org.json.JSONObject

/** Glucose alarm configuration synced from the phone and stored locally on the watch. */
data class AlarmSettings(
    val enabled: Boolean = false,
    val lowEnabled: Boolean = true,
    val lowMgDl: Int = 70,
    val urgentLowEnabled: Boolean = true,
    val urgentLowMgDl: Int = 54,
    val highEnabled: Boolean = true,
    val highMgDl: Int = 240,
    val wearHapticsEnabled: Boolean = false,
    val wearActiveHoursEnabled: Boolean = false,
    val wearActiveStartMinutes: Int = 8 * 60,
    val wearActiveEndMinutes: Int = 22 * 60,
    val snoozeMinutes: Int = 30,
    val activeHoursEnabled: Boolean = false,
    val activeStartMinutes: Int = 8 * 60,
    val activeEndMinutes: Int = 22 * 60,
    val persistentHighEnabled: Boolean = false,
    val persistentHighMgDl: Int = 250,
    val persistentHighMinutes: Int = 120,
    val persistentLowEnabled: Boolean = false,
    val persistentLowMgDl: Int = 70,
    val persistentLowMinutes: Int = 30,
) {
    fun toJson(): String = JSONObject()
        .put("version", 1)
        .put("enabled", enabled)
        .put("lowEnabled", lowEnabled)
        .put("lowMgDl", lowMgDl)
        .put("urgentLowEnabled", urgentLowEnabled)
        .put("urgentLowMgDl", urgentLowMgDl)
        .put("highEnabled", highEnabled)
        .put("highMgDl", highMgDl)
        .put("wearHapticsEnabled", wearHapticsEnabled)
        .put("wearActiveHoursEnabled", wearActiveHoursEnabled)
        .put("wearActiveStartMinutes", wearActiveStartMinutes)
        .put("wearActiveEndMinutes", wearActiveEndMinutes)
        .put("snoozeMinutes", snoozeMinutes)
        .put("activeHoursEnabled", activeHoursEnabled)
        .put("activeStartMinutes", activeStartMinutes)
        .put("activeEndMinutes", activeEndMinutes)
        .put("persistentHighEnabled", persistentHighEnabled)
        .put("persistentHighMgDl", persistentHighMgDl)
        .put("persistentHighMinutes", persistentHighMinutes)
        .put("persistentLowEnabled", persistentLowEnabled)
        .put("persistentLowMgDl", persistentLowMgDl)
        .put("persistentLowMinutes", persistentLowMinutes)
        .toString()

    companion object {
        fun fromJson(raw: String): AlarmSettings {
            val defaults = AlarmSettings()
            val json = JSONObject(raw)
            return AlarmSettings(
                enabled = json.optBoolean("enabled", defaults.enabled),
                lowEnabled = json.optBoolean("lowEnabled", defaults.lowEnabled),
                lowMgDl = json.optInt("lowMgDl", defaults.lowMgDl),
                urgentLowEnabled = json.optBoolean("urgentLowEnabled", defaults.urgentLowEnabled),
                urgentLowMgDl = json.optInt("urgentLowMgDl", defaults.urgentLowMgDl),
                highEnabled = json.optBoolean("highEnabled", defaults.highEnabled),
                highMgDl = json.optInt("highMgDl", defaults.highMgDl),
                wearHapticsEnabled = json.optBoolean("wearHapticsEnabled", defaults.wearHapticsEnabled),
                wearActiveHoursEnabled = json.optBoolean("wearActiveHoursEnabled", defaults.wearActiveHoursEnabled),
                wearActiveStartMinutes = json.optInt("wearActiveStartMinutes", defaults.wearActiveStartMinutes),
                wearActiveEndMinutes = json.optInt("wearActiveEndMinutes", defaults.wearActiveEndMinutes),
                snoozeMinutes = json.optInt("snoozeMinutes", defaults.snoozeMinutes),
                activeHoursEnabled = json.optBoolean("activeHoursEnabled", defaults.activeHoursEnabled),
                activeStartMinutes = json.optInt("activeStartMinutes", defaults.activeStartMinutes),
                activeEndMinutes = json.optInt("activeEndMinutes", defaults.activeEndMinutes),
                persistentHighEnabled = json.optBoolean("persistentHighEnabled", defaults.persistentHighEnabled),
                persistentHighMgDl = json.optInt("persistentHighMgDl", defaults.persistentHighMgDl),
                persistentHighMinutes = json.optInt("persistentHighMinutes", defaults.persistentHighMinutes),
                persistentLowEnabled = json.optBoolean("persistentLowEnabled", defaults.persistentLowEnabled),
                persistentLowMgDl = json.optInt("persistentLowMgDl", defaults.persistentLowMgDl),
                persistentLowMinutes = json.optInt("persistentLowMinutes", defaults.persistentLowMinutes),
            )
        }
    }
}
