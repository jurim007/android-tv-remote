package com.jurim007.androidtvremote.data

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jurim007.androidtvremote.domain.RemoteOption
import com.jurim007.androidtvremote.domain.TvAppShortcut
import com.jurim007.androidtvremote.domain.TvDevice
import com.jurim007.androidtvremote.domain.defaultTvShortcuts
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class ShortcutTestRunner : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val report = StringBuilder()
        val result = runCatching {
            runBlocking {
                checkImages()
                report.appendLine("PASS raster formats, PNG alpha, proportions, persistence, missing images and SVG")
                checkTvShortcuts()
                report.appendLine("PASS per-TV switching, editing, ordering, restart and one-time migration")
                checkUnassignedShortcuts()
                report.appendLine("PASS migration without a saved TV")
                checkNavigationPreferences()
                report.appendLine("PASS saved layout on first read, existing navigation preference and independent visibility")
            }
        }
        result.exceptionOrNull()?.let { report.appendLine(it.stackTraceToString()) }
        finish(if (result.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply {
            putString("stream", "\n$report")
            putInt("failures", if (result.isSuccess) 0 else 1)
        })
    }

    @Suppress("DEPRECATION")
    private suspend fun checkImages() {
        val source = File.createTempFile("shortcut-fixture-", ".image", targetContext.cacheDir)
        val stored = mutableListOf<File>()
        try {
            for ((format, dimensions) in listOf(
                Bitmap.CompressFormat.PNG to (1200 to 400),
                Bitmap.CompressFormat.JPEG to (240 to 480),
                Bitmap.CompressFormat.WEBP to (160 to 160),
            )) {
                val (width, height) = dimensions
                val fixture = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                fixture.eraseColor(Color.TRANSPARENT)
                fixture.setPixel(width / 2, height / 2, Color.RED)
                source.outputStream().use { check(fixture.compress(format, 90, it)) }
                fixture.recycle()
                val reference = ShortcutImageDownloader.copyFromUri(targetContext, Uri.fromFile(source))
                stored += File(targetContext.filesDir, reference)
                check(source.delete())
                val restored = checkNotNull(ShortcutImageDownloader.openStoredImage(targetContext, reference)).use {
                    ShortcutImageDownloader.renderRaster(it)
                }
                try {
                    check(restored.width * height == restored.height * width)
                    check(maxOf(restored.width, restored.height) <= 383)
                    if (format == Bitmap.CompressFormat.PNG) check(Color.alpha(restored.getPixel(0, 0)) == 0)
                } finally {
                    restored.recycle()
                }
                check(stored.last().delete())
                check(runCatching { ShortcutImageDownloader.openStoredImage(targetContext, reference) }
                    .exceptionOrNull() is FileNotFoundException)
            }

            source.writeText("""<svg xmlns="http://www.w3.org/2000/svg" width="20" height="10"><rect width="20" height="10"/></svg>""")
            val svg = ShortcutImageDownloader.copyFromUri(targetContext, Uri.fromFile(source))
            stored += File(targetContext.filesDir, svg)
            val legacyReference = "file:///old/install/shortcut-icons/${stored.last().name}"
            val legacy = checkNotNull(ShortcutImageDownloader.openStoredImage(targetContext, legacyReference))
            legacy.use { ShortcutImageDownloader.renderSvg(it).also { bitmap ->
                try {
                    check(bitmap.width == bitmap.height * 2)
                } finally {
                    bitmap.recycle()
                }
            } }
            source.writeText("not an image")
            check(runCatching { ShortcutImageDownloader.copyFromUri(targetContext, Uri.fromFile(source)) }.isFailure)
        } finally {
            source.delete()
            stored.forEach { it.delete() }
        }
    }

    private suspend fun checkTvShortcuts() {
        val first = TvDevice("Living Room", "living-room", serverCertificateFingerprint = "pin-a")
        val second = TvDevice("Bedroom", "bedroom", serverCertificateFingerprint = "pin-b", usesUniqueClientIdentity = true)
        val legacy = listOf(
            TvAppShortcut("Local", "com.example.local", "file:///old/shortcut-icons/legacy.svg"),
            TvAppShortcut("Custom", "com.example.custom", "shortcut-icons/custom.png"),
        )
        val file = File.createTempFile("shortcut-tv-", ".preferences_pb", targetContext.cacheDir)
        file.delete()

        withPreferences(file, migrate = false) { store ->
            store.edit {
                it[stringPreferencesKey("saved_tvs")] = AppShortcutPreferences.encodeTvDevices(listOf(first, second))
                it[stringPreferencesKey("selected_tv_id")] = second.id
                it[stringPreferencesKey("tv_app_shortcuts")] = AppShortcutPreferences.encodeShortcuts(legacy)
            }
        }
        withPreferences(file) { store ->
            val preferences = AppShortcutPreferences(store)
            check(preferences.shortcuts.first() == SelectedTvShortcuts(second.id, legacy))
            check(preferences.savedTvs.first() == listOf(first, second))
            check(preferences.selectTv(first.id))
            check(preferences.shortcuts.first() == SelectedTvShortcuts(first.id, defaultTvShortcuts))
            preferences.saveShortcuts(first.id, listOf(legacy.first().copy(name = "Edited")))
            check(preferences.selectTv(second.id))
            check(preferences.shortcuts.first().items == legacy)
            preferences.saveShortcuts(second.id, legacy.reversed())
        }
        withPreferences(file) { store ->
            val preferences = AppShortcutPreferences(store)
            check(preferences.shortcuts.first() == SelectedTvShortcuts(second.id, legacy.reversed()))
            check(preferences.selectTv(first.id))
            check(preferences.shortcuts.first().items.single().name == "Edited")
            preferences.restoreRemoteDefaults(first.id)
            check(preferences.selectTv(second.id))
            check(preferences.shortcuts.first().items == legacy.reversed())
            check(store.data.first()[stringPreferencesKey("tv_app_shortcuts")] == AppShortcutPreferences.encodeShortcuts(legacy))
        }
        file.delete()
    }

    private suspend fun checkUnassignedShortcuts() {
        val file = File.createTempFile("shortcut-draft-", ".preferences_pb", targetContext.cacheDir)
        file.delete()
        withPreferences(file, migrate = false) { store ->
            store.edit {
                it[stringPreferencesKey("tv_app_shortcuts")] = "[]"
            }
        }
        withPreferences(file) { store ->
            val preferences = AppShortcutPreferences(store)
            val tv = TvDevice("New TV", "first-tv", serverCertificateFingerprint = "pin")
            check(preferences.shortcuts.first() == SelectedTvShortcuts(null, emptyList()))
            preferences.saveTv(tv)
            check(preferences.shortcuts.first() == SelectedTvShortcuts(tv.id, emptyList()))
            preferences.saveTv(TvDevice("Another TV", "second-tv", serverCertificateFingerprint = "pin"))
            check(preferences.shortcuts.first().items == defaultTvShortcuts)
        }
        file.delete()
    }

    private suspend fun checkNavigationPreferences() {
        val file = File.createTempFile("remote-navigation-", ".preferences_pb", targetContext.cacheDir)
        file.delete()
        val tv = TvDevice("Living Room", "living-room", serverCertificateFingerprint = "pin")
        val shortcuts = listOf(TvAppShortcut("Custom", "com.example.custom"))
        try {
            withPreferences(file, migrate = false) { store ->
                store.edit { it[booleanPreferencesKey("touchpad_enabled")] = true }
            }
            withPreferences(file) { store ->
                val preferences = AppShortcutPreferences(store)
                val layout = preferences.remoteLayout.first()
                check(layout.touchpadEnabled)
                check(RemoteOption.NAVIGATION_SELECTOR in layout.options)
                preferences.saveTv(tv)
                preferences.saveShortcuts(tv.id, shortcuts)
                preferences.setRemoteOption(RemoteOption.NAVIGATION_SELECTOR, false)
                preferences.setRemoteOption(RemoteOption.VOLUME, false)
            }
            withPreferences(file) { store ->
                val preferences = AppShortcutPreferences(store)
                val layout = preferences.remoteLayout.first()
                check(layout.touchpadEnabled)
                check(RemoteOption.NAVIGATION_SELECTOR !in layout.options)
                check(RemoteOption.VOLUME !in layout.options)
                check(layout.shortcuts == SelectedTvShortcuts(tv.id, shortcuts))
                check(layout.savedTvs == listOf(tv))
                preferences.setRemoteOption(RemoteOption.NAVIGATION_SELECTOR, true)
                check(preferences.remoteLayout.first().touchpadEnabled)
                preferences.setTouchpadEnabled(false)
            }
            withPreferences(file) { store ->
                val preferences = AppShortcutPreferences(store)
                val layout = preferences.remoteLayout.first()
                check(!layout.touchpadEnabled)
                check(RemoteOption.NAVIGATION_SELECTOR in layout.options)
            }
        } finally {
            file.delete()
        }
    }

    private suspend fun withPreferences(
        file: File,
        migrate: Boolean = true,
        test: suspend (androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>) -> Unit,
    ) {
        val job = SupervisorJob()
        try {
            val migrations = if (migrate) listOf(AppShortcutPreferences.shortcutMigration) else emptyList()
            test(PreferenceDataStoreFactory.create(migrations = migrations, scope = CoroutineScope(Dispatchers.IO + job)) { file })
        } finally {
            job.cancelAndJoin()
        }
    }
}
