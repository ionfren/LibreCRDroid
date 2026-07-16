package re.abbot.librecr.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.wearAlarmDataStore by preferencesDataStore(name = "librecr_wear_alarms")

class WearAlarmStore(private val context: Context) {
    private val keySettingsJson = stringPreferencesKey("wear_alarms_json")

    val settingsFlow: Flow<AlarmSettings> = context.wearAlarmDataStore.data.map { prefs ->
        prefs[keySettingsJson]
            ?.let { runCatching { AlarmSettings.fromJson(it) }.getOrNull() }
            ?: AlarmSettings()
    }

    suspend fun current(): AlarmSettings = settingsFlow.first()

    suspend fun save(settings: AlarmSettings) {
        context.wearAlarmDataStore.edit {
            it[keySettingsJson] = settings.toJson()
        }
    }
}
