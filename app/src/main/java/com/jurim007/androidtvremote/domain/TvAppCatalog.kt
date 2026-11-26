package com.jurim007.androidtvremote.domain

/**
 * A known TV app with its bundled logo and its IDs on each platform.
 *
 * webOS IDs are only listed when they are well established. Apps reported by a connected
 * webOS TV are also matched by title, so a missing webOS ID still gets the right logo.
 */
internal class TvCatalogApp(
    val name: String,
    val iconAsset: String,
    val androidIds: List<String>,
    val webOsIds: List<String> = emptyList(),
    val aliases: List<String> = emptyList(),
) {
    fun ids(platform: TvPlatform): List<String> = when (platform) {
        TvPlatform.ANDROID_TV -> androidIds
        TvPlatform.WEBOS -> webOsIds
    }
}

internal object TvAppCatalog {
    val apps = listOf(
        TvCatalogApp(
            "Apple TV", "appletv.svg",
            listOf("com.apple.atve.androidtv.appletv"),
            webOsIds = listOf("com.apple.appletv"),
            aliases = listOf("Apple TV+"),
        ),
        TvCatalogApp(
            "CloudStream", "cloudstream.svg",
            listOf(
                "com.lagradost.cloudstream3",
                "com.lagradost.cloudstream3.debug",
                "com.lagradost.cloudstream3.prerelease",
            ),
        ),
        TvCatalogApp("Crunchyroll", "crunchyroll.svg", listOf("com.crunchyroll.crunchyroid")),
        TvCatalogApp("DAZN", "dazn.svg", listOf("com.dazn")),
        TvCatalogApp(
            "Disney+", "disneyplus.svg",
            listOf("com.disney.disneyplus"),
            webOsIds = listOf("com.disney.disneyplus-prod"),
        ),
        TvCatalogApp("ESPN", "espn.svg", listOf("com.espn.score_center")),
        TvCatalogApp("Fubo", "fubo.svg", listOf("com.fubo.firetv"), aliases = listOf("fuboTV")),
        TvCatalogApp("HBO GO", "hbo.svg", listOf("eu.hbogo.androidtv.production", "com.hbo.hbonow")),
        TvCatalogApp("HBO Max", "hbomax.svg", listOf("com.wbd.stream", "com.hbo.hbomax"), aliases = listOf("Max")),
        TvCatalogApp(
            "Hulu", "hulu.svg",
            listOf("com.hulu.livingroomplus", "com.hulu.plus"),
            webOsIds = listOf("hulu"),
        ),
        TvCatalogApp("iHeartRadio", "iheartradio.svg", listOf("com.clearchannel.iheartradio.controller")),
        TvCatalogApp(
            "Jellyfin", "jellyfin.svg",
            listOf("org.jellyfin.androidtv"),
            webOsIds = listOf("org.jellyfin.webos"),
        ),
        TvCatalogApp("Kick", "kick.svg", listOf("com.kick.mobile")),
        TvCatalogApp("Kodi", "kodi.svg", listOf("org.xbmc.kodi")),
        TvCatalogApp("Netflix", "netflix.svg", listOf("com.netflix.ninja"), webOsIds = listOf("netflix")),
        TvCatalogApp("Paramount+", "paramountplus.svg", listOf("com.cbs.ott", "com.cbs.ca")),
        TvCatalogApp("Peacock", "peacock.svg", listOf("com.peacocktv.peacockandroid")),
        TvCatalogApp("Plex", "plex.svg", listOf("com.plexapp.android"), webOsIds = listOf("cdp-30")),
        TvCatalogApp("Pluto TV", "plutotv.svg", listOf("tv.pluto.android")),
        TvCatalogApp(
            "Prime Video", "primevideo.svg",
            listOf("com.amazon.amazonvideo.livingroom"),
            webOsIds = listOf("amazon"),
            aliases = listOf("Amazon Prime Video", "Amazon Prime"),
        ),
        TvCatalogApp("Rakuten Viki", "viki.svg", listOf("com.viki.android"), aliases = listOf("Viki")),
        TvCatalogApp("SoundCloud", "soundcloud.svg", listOf("com.soundcloud.android")),
        TvCatalogApp(
            "Spotify", "spotify.svg",
            listOf("com.spotify.tv.android", "com.spotify.music"),
            webOsIds = listOf("spotify-beehive"),
        ),
        TvCatalogApp("Stremio", "stremio.svg", listOf("com.stremio.one")),
        TvCatalogApp("Tubi", "tubi.svg", listOf("com.tubitv", "com.tubitv.ott")),
        TvCatalogApp("Twitch", "twitch.svg", listOf("tv.twitch.android.app", "tv.twitch.android.viewer")),
        TvCatalogApp("Vimeo", "vimeo.svg", listOf("com.vimeo.android.videoapp")),
        TvCatalogApp(
            "YouTube", "youtube.svg",
            listOf("com.google.android.youtube.tv"),
            webOsIds = listOf("youtube.leanback.v4"),
        ),
        TvCatalogApp(
            "YouTube Music", "youtubemusic.svg",
            listOf("com.google.android.youtube.tvmusic", "com.google.android.apps.youtube.music"),
        ),
    )

    private val byId: Map<String, TvCatalogApp> = buildMap {
        apps.forEach { app -> (app.androidIds + app.webOsIds).forEach { put(it, app) } }
    }

    private val byTitle: Map<String, TvCatalogApp> = buildMap {
        apps.forEach { app -> (listOf(app.name) + app.aliases).forEach { put(normalizeTitle(it), app) } }
    }

    fun forId(id: String): TvCatalogApp? = byId[id]

    fun forTitle(title: String): TvCatalogApp? = byTitle[normalizeTitle(title)]

    /** Finds the catalog entry for an app by its ID, falling back to an exact title match. */
    fun match(id: String, title: String?): TvCatalogApp? = forId(id) ?: title?.let(::forTitle)

    /**
     * Resolves a shortcut ID to the app to launch on a webOS TV. Android package IDs of
     * catalog apps are translated, and the installed apps are preferred when known.
     */
    fun resolveWebOsId(id: String, installed: List<TvInstalledApp>?): String {
        if (installed?.any { it.id == id } == true) return id
        val app = forId(id) ?: return id
        installed?.firstOrNull { it.id in app.webOsIds || forTitle(it.title) === app }?.let { return it.id }
        return app.webOsIds.firstOrNull() ?: id
    }

    /** Converts a shortcut to [platform], or null when its app has no known ID there. */
    fun translate(shortcut: TvAppShortcut, platform: TvPlatform): TvAppShortcut? {
        val app = forId(shortcut.packageName) ?: return null
        if (shortcut.packageName in app.ids(platform)) return shortcut
        val id = app.ids(platform).firstOrNull() ?: return null
        return shortcut.copy(packageName = id)
    }

    /** The default shortcuts for a TV, built from the apps it reports when available. */
    fun defaultShortcuts(platform: TvPlatform, installed: List<TvInstalledApp>?): List<TvAppShortcut> {
        if (platform == TvPlatform.ANDROID_TV) return defaultTvShortcuts
        if (installed.isNullOrEmpty()) return defaultWebOsShortcuts
        val detected = DEFAULT_PRIORITY.mapNotNull { name ->
            val app = apps.first { it.name == name }
            installed.firstOrNull { it.id in app.webOsIds || forTitle(it.title) === app }
                ?.let { TvAppShortcut(app.name, it.id) }
        }.distinctBy { it.packageName }.take(DEFAULT_SHORTCUT_COUNT)
        return detected.ifEmpty { defaultWebOsShortcuts }
    }

    fun normalizeTitle(title: String): String =
        title.lowercase().replace("+", "plus").filter(Char::isLetterOrDigit)

    private const val DEFAULT_SHORTCUT_COUNT = 4

    /** Apps tried in order when picking a webOS TV's default shortcuts. */
    private val DEFAULT_PRIORITY = listOf(
        "YouTube", "Netflix", "Prime Video", "Disney+", "Apple TV", "HBO Max", "Hulu", "Spotify",
        "Plex", "Twitch", "Paramount+", "Peacock", "Crunchyroll", "Tubi", "Pluto TV", "YouTube Music",
    )
}
