package re.abbot.librecr.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

private val Context.wearAppearanceDataStore by preferencesDataStore(name = "librecr_wear_appearance")

class WearAppearanceStore(private val context: Context) {
    private val keySettingsJson = stringPreferencesKey("wear_appearance_json")
    @Volatile private var cachedSettings: WearAppearanceSettings? = null

    val settingsFlow: Flow<WearAppearanceSettings> = context.wearAppearanceDataStore.data.map { prefs ->
        prefs[keySettingsJson]
            ?.let { runCatching { WearAppearanceSettings.fromJson(it) }.getOrNull() }
            ?: WearAppearanceSettings()
    }.onEach { cachedSettings = it }

    /** Active complication requests use memory; DataStore is only the process-cold fallback. */
    suspend fun current(): WearAppearanceSettings = cachedSettings ?: settingsFlow.first()

    suspend fun save(settings: WearAppearanceSettings) {
        context.wearAppearanceDataStore.edit {
            it[keySettingsJson] = settings.toJson()
        }
        cachedSettings = settings
    }
}
