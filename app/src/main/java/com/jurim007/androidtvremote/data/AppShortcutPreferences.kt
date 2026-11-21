package com.jurim007.androidtvremote.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jurim007.androidtvremote.domain.MAX_TV_SHORTCUTS
import com.jurim007.androidtvremote.domain.TvAppShortcut
import com.jurim007.androidtvremote.domain.TvDevice
import com.jurim007.androidtvremote.domain.defaultTvShortcuts
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.preferencesDataStore by preferencesDataStore(name = "remote_preferences")

class AppShortcutPreferences(context: Context) {
    private val dataStore = context.preferencesDataStore

    val shortcuts: Flow<List<TvAppShortcut>> = dataStore.data.map { preferences ->
        (preferences[TV_APP_SHORTCUTS]?.let(::decodeShortcuts) ?: defaultTvShortcuts).take(MAX_TV_SHORTCUTS)
    }

    val touchpadEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[TOUCHPAD_ENABLED] ?: false
    }

    val savedTvs: Flow<List<TvDevice>> = dataStore.data.map { preferences ->
        preferences[SAVED_TVS]?.let(::decodeTvDevices).orEmpty()
    }

    val selectedTvId: Flow<String?> = dataStore.data.map { preferences ->
        preferences[SELECTED_TV_ID]
    }

    suspend fun saveShortcuts(shortcuts: List<TvAppShortcut>) {
        dataStore.edit { preferences ->
            preferences[TV_APP_SHORTCUTS] = encodeShortcuts(shortcuts.take(MAX_TV_SHORTCUTS))
        }
    }

    suspend fun setTouchpadEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[TOUCHPAD_ENABLED] = enabled
        }
    }

    suspend fun saveTv(device: TvDevice) {
        requireNotNull(device.serverCertificateFingerprint) {
            "A TV must complete pairing before it can be saved."
        }
        dataStore.edit { preferences ->
            val devices = preferences[SAVED_TVS]?.let(::decodeTvDevices).orEmpty()
            preferences[SAVED_TVS] =
                encodeTvDevices(devices.filterNot { it.id == device.id } + device)
            preferences[SELECTED_TV_ID] = device.id
        }
    }

    suspend fun selectTv(deviceId: String): Boolean {
        val updated = dataStore.edit { preferences ->
            val devices = preferences[SAVED_TVS]?.let(::decodeTvDevices).orEmpty()
            if (devices.any { it.id == deviceId }) {
                preferences[SELECTED_TV_ID] = deviceId
            } else if (preferences[SELECTED_TV_ID] == deviceId) {
                preferences.remove(SELECTED_TV_ID)
            }
        }
        return updated[SELECTED_TV_ID] == deviceId
    }

    suspend fun removeTv(deviceId: String) {
        dataStore.edit { preferences ->
            val devices = preferences[SAVED_TVS]?.let(::decodeTvDevices).orEmpty()
            preferences[SAVED_TVS] =
                encodeTvDevices(devices.filterNot { it.id == deviceId })
            if (preferences[SELECTED_TV_ID] == deviceId) {
                preferences.remove(SELECTED_TV_ID)
            }
        }
    }

    private companion object {
        val TV_APP_SHORTCUTS = stringPreferencesKey("tv_app_shortcuts")
        val TOUCHPAD_ENABLED = booleanPreferencesKey("touchpad_enabled")
        val SAVED_TVS = stringPreferencesKey("saved_tvs")
        val SELECTED_TV_ID = stringPreferencesKey("selected_tv_id")

        fun encodeShortcuts(shortcuts: List<TvAppShortcut>): String =
            JSONArray().apply {
                shortcuts.forEach { shortcut ->
                    put(
                        JSONObject()
                            .put("name", shortcut.name)
                            .put("packageName", shortcut.packageName)
                            .put("iconUri", shortcut.iconUri),
                    )
                }
            }.toString()

        fun decodeShortcuts(encoded: String): List<TvAppShortcut> {
            val array = JSONArray(encoded)
            return List(array.length()) { index ->
                val item = array.getJSONObject(index)
                TvAppShortcut(
                    name = item.getString("name"),
                    packageName = item.getString("packageName"),
                    iconUri = item.optString("iconUri").takeIf(String::isNotBlank),
                )
            }
        }

        fun encodeTvDevices(devices: List<TvDevice>): String =
            JSONArray().apply {
                devices.forEach { device ->
                    put(
                        JSONObject()
                            .put("name", device.name)
                            .put("host", device.host)
                            .put("port", device.port)
                            .put("certificateFingerprint", device.serverCertificateFingerprint),
                    )
                }
            }.toString()

        fun decodeTvDevices(encoded: String): List<TvDevice> {
            val array = JSONArray(encoded)
            return List(array.length()) { index ->
                val item = array.getJSONObject(index)
                TvDevice(
                    name = item.getString("name"),
                    host = item.getString("host"),
                    port = item.optInt("port", TvDevice.REMOTE_PORT),
                    serverCertificateFingerprint = item
                        .optString("certificateFingerprint")
                        .takeIf(String::isNotBlank),
                )
            }
        }
    }
}
