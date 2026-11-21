package com.jurim007.androidtvremote.ui

internal class ShortcutApp(val name: String, val iconAsset: String, vararg val packageIds: String)

internal val shortcutApps = listOf(
    ShortcutApp("Apple TV", "appletv.svg", "com.apple.atve.androidtv.appletv"),
    ShortcutApp(
        "CloudStream",
        "cloudstream.svg",
        "com.lagradost.cloudstream3",
        "com.lagradost.cloudstream3.debug",
        "com.lagradost.cloudstream3.prerelease",
    ),
    ShortcutApp("Crunchyroll", "crunchyroll.svg", "com.crunchyroll.crunchyroid"),
    ShortcutApp("DAZN", "dazn.svg", "com.dazn"),
    ShortcutApp("Disney+", "disneyplus.svg", "com.disney.disneyplus"),
    ShortcutApp("ESPN", "espn.svg", "com.espn.score_center"),
    ShortcutApp("Fubo", "fubo.svg", "com.fubo.firetv"),
    ShortcutApp("HBO GO", "hbo.svg", "eu.hbogo.androidtv.production", "com.hbo.hbonow"),
    ShortcutApp("HBO Max", "hbomax.svg", "com.wbd.stream", "com.hbo.hbomax"),
    ShortcutApp("Hulu", "hulu.svg", "com.hulu.livingroomplus", "com.hulu.plus"),
    ShortcutApp("iHeartRadio", "iheartradio.svg", "com.clearchannel.iheartradio.controller"),
    ShortcutApp("Jellyfin", "jellyfin.svg", "org.jellyfin.androidtv"),
    ShortcutApp("Kick", "kick.svg", "com.kick.mobile"),
    ShortcutApp("Kodi", "kodi.svg", "org.xbmc.kodi"),
    ShortcutApp("Netflix", "netflix.svg", "com.netflix.ninja"),
    ShortcutApp("Paramount+", "paramountplus.svg", "com.cbs.ott", "com.cbs.ca"),
    ShortcutApp("Peacock", "peacock.svg", "com.peacocktv.peacockandroid"),
    ShortcutApp("Plex", "plex.svg", "com.plexapp.android"),
    ShortcutApp("Pluto TV", "plutotv.svg", "tv.pluto.android"),
    ShortcutApp("Prime Video", "primevideo.svg", "com.amazon.amazonvideo.livingroom"),
    ShortcutApp("Rakuten Viki", "viki.svg", "com.viki.android"),
    ShortcutApp("SoundCloud", "soundcloud.svg", "com.soundcloud.android"),
    ShortcutApp("Spotify", "spotify.svg", "com.spotify.tv.android", "com.spotify.music"),
    ShortcutApp("Stremio", "stremio.svg", "com.stremio.one"),
    ShortcutApp("Tubi", "tubi.svg", "com.tubitv", "com.tubitv.ott"),
    ShortcutApp("Twitch", "twitch.svg", "tv.twitch.android.app", "tv.twitch.android.viewer"),
    ShortcutApp("Vimeo", "vimeo.svg", "com.vimeo.android.videoapp"),
    ShortcutApp("YouTube", "youtube.svg", "com.google.android.youtube.tv"),
    ShortcutApp(
        "YouTube Music",
        "youtubemusic.svg",
        "com.google.android.youtube.tvmusic",
        "com.google.android.apps.youtube.music",
    ),
)

internal val shortcutIconAssets = shortcutApps.flatMap { app ->
    app.packageIds.map { packageId -> packageId to app.iconAsset }
}.toMap()
