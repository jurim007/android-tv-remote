package com.jurim007.androidtvremote.data

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jurim007.androidtvremote.domain.MAX_TV_SHORTCUTS
import com.jurim007.androidtvremote.domain.RemoteOption
import com.jurim007.androidtvremote.domain.TvAppCatalog
import com.jurim007.androidtvremote.domain.TvAppShortcut
import com.jurim007.androidtvremote.domain.TvDevice
import com.jurim007.androidtvremote.domain.TvPlatform
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.preferencesDataStore by preferencesDataStore(
    name = "remote_preferences",
    produceMigrations = { listOf(AppShortcutPreferences.shortcutMigration) },
)

data class SelectedTvShortcuts(val tvId: String?, val items: List<TvAppShortcut>)

internal data class RemoteLayoutPreferences(
    val shortcuts: SelectedTvShortcuts,
    val touchpadEnabled: Boolean,
    val options: Set<RemoteOption>,
    val savedTvs: List<TvDevice>,
)

class AppShortcutPreferences internal constructor(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.preferencesDataStore)

    internal val remoteLayout: Flow<RemoteLayoutPreferences> = dataStore.data
        .catch { error ->
            if (error !is IOException) throw error
            emit(emptyPreferences())
        }
        .map { preferences ->
            val tvId = preferences[SELECTED_TV_ID]
            val savedTvs = preferences[SAVED_TVS]?.let(::decodeTvDevices).orEmpty()
            val platform = savedTvs.firstOrNull { it.id == tvId }?.platform ?: TvPlatform.ANDROID_TV
            val shortcuts = preferences[shortcutKey(tvId)]?.let(::decodeShortcuts)
                ?: TvAppCatalog.defaultShortcuts(platform, installed = null)
            RemoteLayoutPreferences(
                shortcuts = SelectedTvShortcuts(tvId, shortcuts.take(MAX_TV_SHORTCUTS)),
                touchpadEnabled = preferences[TOUCHPAD_ENABLED] ?: false,
                options = readRemoteOptions(preferences),
                savedTvs = savedTvs,
            )
        }

    val shortcuts: Flow<SelectedTvShortcuts> = remoteLayout.map { it.shortcuts }
    val remoteOptions: Flow<Set<RemoteOption>> = dataStore.data.map(::readRemoteOptions)

    private fun readRemoteOptions(preferences: Preferences): Set<RemoteOption> =
        RemoteOption.entries.filter { option ->
            preferences[optionKeys.getValue(option)] ?: option.enabledByDefault
        }.toSet()

    suspend fun setRemoteOption(option: RemoteOption, enabled: Boolean) {
        dataStore.edit { preferences -> preferences[optionKeys.getValue(option)] = enabled }
    }

    val savedTvs: Flow<List<TvDevice>> = dataStore.data.map { preferences ->
        preferences[SAVED_TVS]?.let(::decodeTvDevices).orEmpty()
    }

    val selectedTvId: Flow<String?> = dataStore.data.map { preferences ->
        preferences[SELECTED_TV_ID]
    }

    val tclInputsTvIds: Flow<Set<String>> = dataStore.data.map { preferences ->
        preferences[TCL_INPUTS_TV_IDS].orEmpty()
    }

    suspend fun setTclInputsEnabled(deviceId: String, enabled: Boolean) {
        dataStore.edit { preferences ->
            val tvIds = preferences[TCL_INPUTS_TV_IDS].orEmpty()
            preferences[TCL_INPUTS_TV_IDS] = if (enabled) tvIds + deviceId else tvIds - deviceId
        }
    }

    /** Whether shortcuts were saved for the TV, as opposed to showing its defaults. */
    suspend fun hasSavedShortcuts(tvId: String): Boolean =
        dataStore.data.first()[shortcutKey(tvId)] != null

    suspend fun saveShortcuts(tvId: String?, shortcuts: List<TvAppShortcut>) {
        dataStore.edit { preferences ->
            if (tvId == null || preferences[SAVED_TVS]?.let(::decodeTvDevices).orEmpty().any { it.id == tvId }) {
                preferences[shortcutKey(tvId)] = encodeShortcuts(shortcuts.take(MAX_TV_SHORTCUTS))
            }
        }
    }

    suspend fun setTouchpadEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[TOUCHPAD_ENABLED] = enabled
        }
    }

    suspend fun restoreRemoteDefaults(tvId: String?) {
        dataStore.edit { preferences ->
            preferences.remove(TOUCHPAD_ENABLED)
            preferences.remove(shortcutKey(tvId))
            preferences.remove(TCL_INPUTS_TV_IDS)
            optionKeys.values.forEach { preferences.remove(it) }
        }
    }

    suspend fun saveTv(device: TvDevice) {
        requireNotNull(device.serverCertificateFingerprint) {
            "A TV must complete pairing before it can be saved."
        }
        require(device.platform != TvPlatform.WEBOS || device.clientKey != null) {
            "A webOS TV must accept the connection before it can be saved."
        }
        dataStore.edit { preferences ->
            val devices = preferences[SAVED_TVS]?.let(::decodeTvDevices).orEmpty()
            preferences[SAVED_TVS] =
                encodeTvDevices(devices.filterNot { it.id == device.id } + device)
            if (devices.isEmpty()) {
                val unassigned = shortcutKey(null)
                val destination = shortcutKey(device.id)
                if (preferences[destination] == null) {
                    preferences[unassigned]?.let { encoded ->
                        if (device.platform == TvPlatform.ANDROID_TV) {
                            preferences[destination] = encoded
                        } else {
                            // Shortcuts added before any TV was paired use Android package IDs.
                            decodeShortcuts(encoded)
                                .mapNotNull { TvAppCatalog.translate(it, device.platform) }
                                .takeIf { it.isNotEmpty() }
                                ?.let { preferences[destination] = encodeShortcuts(it) }
                        }
                    }
                }
                preferences.remove(unassigned)
            }
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
            preferences[TCL_INPUTS_TV_IDS] = preferences[TCL_INPUTS_TV_IDS].orEmpty() - deviceId
            if (preferences[SELECTED_TV_ID] == deviceId) {
                preferences.remove(SELECTED_TV_ID)
            }
        }
    }

    internal companion object {
        val optionKeys = RemoteOption.entries.associateWith { booleanPreferencesKey(it.preferenceKey) }
        val TV_APP_SHORTCUTS = stringPreferencesKey("tv_app_shortcuts")
        val TOUCHPAD_ENABLED = booleanPreferencesKey("touchpad_enabled")
        val SAVED_TVS = stringPreferencesKey("saved_tvs")
        val SELECTED_TV_ID = stringPreferencesKey("selected_tv_id")
        val TCL_INPUTS_TV_IDS = stringSetPreferencesKey("tcl_inputs_tv_ids")

        private val SHORTCUTS_MIGRATED = booleanPreferencesKey("tv_app_shortcuts_migrated")

        private fun shortcutKey(tvId: String?) = stringPreferencesKey(
            if (tvId == null) "unassigned_tv_app_shortcuts" else "tv_app_shortcuts_for_$tvId",
        )

        val shortcutMigration = object : DataMigration<Preferences> {
            override suspend fun shouldMigrate(currentData: Preferences): Boolean =
                currentData[SHORTCUTS_MIGRATED] != true

            override suspend fun migrate(currentData: Preferences): Preferences =
                currentData.toMutablePreferences().apply {
                    currentData[TV_APP_SHORTCUTS]?.let { legacy ->
                        val tvs = currentData[SAVED_TVS]?.let(::decodeTvDevices).orEmpty()
                        val selected = tvs.firstOrNull { it.id == currentData[SELECTED_TV_ID] }
                        val destination = shortcutKey((selected ?: tvs.firstOrNull())?.id)
                        if (this[destination] == null) this[destination] = legacy
                    }
                    this[SHORTCUTS_MIGRATED] = true
                }

            override suspend fun cleanUp() = Unit
        }

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
                            .put("certificateFingerprint", device.serverCertificateFingerprint)
                            .put("usesUniqueClientIdentity", device.usesUniqueClientIdentity)
                            .put("platform", device.platform.name)
                            .put("clientKey", device.clientKey)
                            .put("macAddresses", JSONArray(device.macAddresses)),
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
                    usesUniqueClientIdentity = item.optBoolean("usesUniqueClientIdentity", false),
                    platform = TvPlatform.entries.firstOrNull { it.name == item.optString("platform") }
                        ?: TvPlatform.ANDROID_TV,
                    clientKey = item.optString("clientKey").takeIf(String::isNotBlank),
                    macAddresses = item.optJSONArray("macAddresses")?.let { macs ->
                        List(macs.length()) { macs.optString(it) }.filter(String::isNotBlank)
                    }.orEmpty(),
                )
            }
        }
    }
}
