package com.jurim007.androidtvremote.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.preferencesDataStore by preferencesDataStore(name = "remote_preferences")

class AppShortcutPreferences(context: Context) {
    private val dataStore = context.preferencesDataStore

    val packageNames: Flow<List<String>> = dataStore.data.map { preferences ->
        preferences[SHORTCUT_PACKAGES]
            ?.split(PACKAGE_SEPARATOR)
            ?.filter(String::isNotBlank)
            ?: DEFAULT_SHORTCUT_PACKAGES
    }

    suspend fun savePackageNames(packageNames: List<String>) {
        dataStore.edit { preferences ->
            preferences[SHORTCUT_PACKAGES] = packageNames.joinToString(PACKAGE_SEPARATOR)
        }
    }

    private companion object {
        val SHORTCUT_PACKAGES = stringPreferencesKey("shortcut_packages")
        const val PACKAGE_SEPARATOR = "|"
        val DEFAULT_SHORTCUT_PACKAGES = listOf(
            "com.netflix.mediaclient",
            "com.google.android.youtube.tv",
            "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus",
        )
    }
}
