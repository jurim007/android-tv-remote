package com.jurim007.androidtvremote.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jurim007.androidtvremote.domain.TvAppShortcut
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.preferencesDataStore by preferencesDataStore(name = "remote_preferences")

class AppShortcutPreferences(context: Context) {
    private val dataStore = context.preferencesDataStore

    val shortcuts: Flow<List<TvAppShortcut>> = dataStore.data.map { preferences ->
        preferences[TV_APP_SHORTCUTS]?.let(::decodeShortcuts).orEmpty().take(MAX_SHORTCUTS)
    }

    val touchpadEnabled: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[TOUCHPAD_ENABLED] ?: false
    }

    suspend fun saveShortcuts(shortcuts: List<TvAppShortcut>) {
        dataStore.edit { preferences ->
            preferences[TV_APP_SHORTCUTS] = encodeShortcuts(shortcuts.take(MAX_SHORTCUTS))
        }
    }

    suspend fun setTouchpadEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[TOUCHPAD_ENABLED] = enabled
        }
    }

    private companion object {
        val TV_APP_SHORTCUTS = stringPreferencesKey("tv_app_shortcuts")
        val TOUCHPAD_ENABLED = booleanPreferencesKey("touchpad_enabled")
        const val MAX_SHORTCUTS = 4

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
    }
}
