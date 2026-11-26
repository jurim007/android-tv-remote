package com.jurim007.androidtvremote.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class TvAppCatalogTest {
    @Test
    fun titlesMatchAcrossSpellings() {
        val disney = TvAppCatalog.forTitle("Disney+")
        assertSame(disney, TvAppCatalog.forTitle("Disney Plus"))
        assertSame(TvAppCatalog.forTitle("Prime Video"), TvAppCatalog.forTitle("Amazon Prime Video"))
        assertNull(TvAppCatalog.forTitle("YouTube Kids"))
    }

    @Test
    fun androidShortcutsLaunchTheInstalledWebOsApp() {
        val installed = listOf(TvInstalledApp("amazon", "Prime Video"), TvInstalledApp("netflix", "Netflix"))
        assertEquals("amazon", TvAppCatalog.resolveWebOsId("com.amazon.amazonvideo.livingroom", installed))
        assertEquals("netflix", TvAppCatalog.resolveWebOsId("netflix", installed))
        assertEquals("youtube.leanback.v4", TvAppCatalog.resolveWebOsId("com.google.android.youtube.tv", null))
    }

    @Test
    fun installedAppsMatchedByTitleUseTheTvsOwnId() {
        val installed = listOf(TvInstalledApp("com.example.peacock-webos", "Peacock"))
        assertEquals("com.example.peacock-webos", TvAppCatalog.resolveWebOsId("com.peacocktv.peacockandroid", installed))
    }

    @Test
    fun webOsDefaultsComeFromInstalledAppsInPriorityOrder() {
        val installed = listOf(
            TvInstalledApp("com.webos.app.browser", "Web Browser"),
            TvInstalledApp("netflix", "Netflix"),
            TvInstalledApp("spotify-beehive", "Spotify - Music and Podcasts"),
            TvInstalledApp("youtube.leanback.v4", "YouTube"),
            TvInstalledApp("com.apple.appletv", "Apple TV"),
            TvInstalledApp("hulu", "Hulu"),
        )
        assertEquals(
            listOf(
                TvAppShortcut("YouTube", "youtube.leanback.v4"),
                TvAppShortcut("Netflix", "netflix"),
                TvAppShortcut("Apple TV", "com.apple.appletv"),
                TvAppShortcut("Hulu", "hulu"),
            ),
            TvAppCatalog.defaultShortcuts(TvPlatform.WEBOS, installed),
        )
    }

    @Test
    fun webOsDefaultsFallBackWhenNothingIsDetected() {
        assertEquals(defaultWebOsShortcuts, TvAppCatalog.defaultShortcuts(TvPlatform.WEBOS, null))
        assertEquals(
            defaultWebOsShortcuts,
            TvAppCatalog.defaultShortcuts(TvPlatform.WEBOS, listOf(TvInstalledApp("com.webos.app.hdmi1", "HDMI 1"))),
        )
        assertEquals(defaultTvShortcuts, TvAppCatalog.defaultShortcuts(TvPlatform.ANDROID_TV, null))
    }

    @Test
    fun shortcutsTranslateOnlyWhenTheAppHasAWebOsId() {
        assertEquals(
            TvAppShortcut("Netflix", "netflix", "custom.png"),
            TvAppCatalog.translate(TvAppShortcut("Netflix", "com.netflix.ninja", "custom.png"), TvPlatform.WEBOS),
        )
        assertNull(TvAppCatalog.translate(TvAppShortcut("Kodi", "org.xbmc.kodi"), TvPlatform.WEBOS))
        assertNull(TvAppCatalog.translate(TvAppShortcut("Mine", "com.example.mine"), TvPlatform.WEBOS))
    }

    /** The launch points reported by a Sunny webOS Hub TV. */
    private val sunnyApps = listOf(
        TvInstalledApp("youtube.leanback.v4", "YouTube"),
        TvInstalledApp("ui30", "Rakuten TV"),
        TvInstalledApp("com.webos.app.discovery", "Apps"),
        TvInstalledApp("com.webos.app.homeoffice", "Home Office"),
        TvInstalledApp("amazon", "Prime Video"),
        TvInstalledApp("com.webos.app.totalmusic", "Music"),
        TvInstalledApp("com.webos.app.homeconnect", "Home Hub"),
        TvInstalledApp("com.webos.app.browser", "Web Browser"),
        TvInstalledApp("com.webos.app.mediadiscovery", "Media Player"),
    )

    @Test
    fun everyAppOnTheSunnyTvHasALogo() {
        val missing = sunnyApps.filter { TvAppCatalog.match(it.id, it.title) == null }
        assertEquals(emptyList<TvInstalledApp>(), missing)
    }

    @Test
    fun sunnyDefaultsUseItsStreamingApps() {
        assertEquals(
            listOf(
                TvAppShortcut("YouTube", "youtube.leanback.v4"),
                TvAppShortcut("Prime Video", "amazon"),
                TvAppShortcut("Rakuten TV", "ui30"),
            ),
            TvAppCatalog.defaultShortcuts(TvPlatform.WEBOS, sunnyApps),
        )
    }

    @Test
    fun webOsSystemAppsAreNotSuggestedForAndroidTv() {
        val androidApps = TvAppCatalog.apps.filter { it.ids(TvPlatform.ANDROID_TV).isNotEmpty() }.map { it.name }
        assertEquals(false, "Home Hub" in androidApps || "HDMI 1" in androidApps)
    }

    @Test
    fun catalogIdsAreUnique() {
        val ids = TvAppCatalog.apps.flatMap { it.androidIds + it.webOsIds }
        assertEquals(ids.size, ids.toSet().size)
    }
}
