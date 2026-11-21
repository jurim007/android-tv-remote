package com.jurim007.androidtvremote.domain

data class TvAppShortcut(
    val name: String,
    val packageName: String,
    val iconUri: String? = null,
)

internal val defaultTvShortcuts = listOf(
    TvAppShortcut("YouTube", "com.google.android.youtube.tv"),
    TvAppShortcut("Netflix", "com.netflix.ninja"),
    TvAppShortcut("Prime Video", "com.amazon.amazonvideo.livingroom"),
    TvAppShortcut("Disney+", "com.disney.disneyplus"),
)
