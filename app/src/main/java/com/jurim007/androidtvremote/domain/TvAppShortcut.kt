package com.jurim007.androidtvremote.domain

data class TvAppShortcut(
    val name: String,
    val packageName: String,
    val iconUri: String? = null,
    val launchUri: String? = null,
)
