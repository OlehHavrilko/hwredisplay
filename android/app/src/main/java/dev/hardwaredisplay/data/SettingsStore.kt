package dev.hardwaredisplay.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {

    val activeHostId: Flow<String?> =
        context.settingsDataStore.data.map { it[ACTIVE_HOST] }

    val keepScreenOn: Flow<Boolean> =
        context.settingsDataStore.data.map { it[KEEP_SCREEN_ON] ?: false }

    suspend fun activeHostIdOnce(): String? = activeHostId.first()

    suspend fun setActiveHost(hostId: String?) {
        context.settingsDataStore.edit { preferences ->
            if (hostId == null) {
                preferences.remove(ACTIVE_HOST)
            } else {
                preferences[ACTIVE_HOST] = hostId
            }
        }
    }

    fun observeActivePreset(hostId: String): Flow<String?> =
        context.settingsDataStore.data.map { it[presetKey(hostId)] }

    suspend fun activePresetId(hostId: String): String? =
        context.settingsDataStore.data.first()[presetKey(hostId)]

    suspend fun setActivePreset(hostId: String, presetId: String) {
        context.settingsDataStore.edit { it[presetKey(hostId)] = presetId }
    }

    suspend fun setKeepScreenOn(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEEP_SCREEN_ON] = enabled }
    }

    /** Каталог датчиков кэшируется, чтобы карточки сохраняли названия и единицы без связи. */
    suspend fun saveCatalog(hostId: String, json: String) {
        context.settingsDataStore.edit { it[stringPreferencesKey("catalog_$hostId")] = json }
    }

    suspend fun cachedCatalog(hostId: String): String? =
        context.settingsDataStore.data.first()[stringPreferencesKey("catalog_$hostId")]

    suspend fun clearCatalog(hostId: String) {
        context.settingsDataStore.edit { it.remove(stringPreferencesKey("catalog_$hostId")) }
    }

    private fun presetKey(hostId: String) = stringPreferencesKey("active_preset_$hostId")

    private companion object {
        val ACTIVE_HOST = stringPreferencesKey("active_host")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
    }
}
