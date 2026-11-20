package com.jurim007.androidtvremote.domain

sealed interface RemoteCommand {
    data object Power : RemoteCommand
    data object Back : RemoteCommand
    data object Home : RemoteCommand
    data object Select : RemoteCommand
    data object Up : RemoteCommand
    data object Down : RemoteCommand
    data object Left : RemoteCommand
    data object Right : RemoteCommand
    data object VolumeUp : RemoteCommand
    data object VolumeDown : RemoteCommand
    data object ChannelUp : RemoteCommand
    data object ChannelDown : RemoteCommand
    data object Mute : RemoteCommand
    data object Source : RemoteCommand
    data class SendText(val text: String) : RemoteCommand
    data class LaunchApp(val packageName: String) : RemoteCommand
}

