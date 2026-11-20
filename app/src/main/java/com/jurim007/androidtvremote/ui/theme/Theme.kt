package com.jurim007.androidtvremote.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val RemoteColorScheme = darkColorScheme(
    primary = RemoteAccent,
    onPrimary = RemoteBackground,
    background = RemoteBackground,
    onBackground = RemoteText,
    surface = RemoteSurface,
    onSurface = RemoteText,
    secondary = RemoteMuted,
)

@Composable
fun AndroidTvRemoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RemoteColorScheme,
        content = content,
    )
}

