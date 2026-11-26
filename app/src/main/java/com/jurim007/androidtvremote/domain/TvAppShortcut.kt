package com.jurim007.androidtvremote.domain

data class TvAppShortcut(
    val name: String,
    val packageName: String,
    val iconUri: String? = null,
)

internal const val MAX_TV_SHORTCUTS = 8

internal val defaultTvShortcuts = listOf(
    TvAppShortcut("YouTube", "com.google.android.youtube.tv"),
    TvAppShortcut("Netflix", "com.netflix.ninja"),
    TvAppShortcut("Prime Video", "com.amazon.amazonvideo.livingroom"),
    TvAppShortcut("Disney+", "com.disney.disneyplus"),
)

/** Used for webOS TVs until the TV reports its installed apps. */
internal val defaultWebOsShortcuts = listOf(
    TvAppShortcut("YouTube", "youtube.leanback.v4"),
    TvAppShortcut("Netflix", "netflix"),
    TvAppShortcut("Prime Video", "amazon"),
    TvAppShortcut("Disney+", "com.disney.disneyplus-prod"),
)
