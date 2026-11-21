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
    enum class Hdmi(val port: Int) : RemoteCommand {
        HDMI_1(1), HDMI_2(2), HDMI_3(3), HDMI_4(4),
    }
    data object Delete : RemoteCommand
    data class SendText(val text: String) : RemoteCommand
    data class LaunchApp(
        val packageName: String,
        val launchUri: String?,
    ) : RemoteCommand
}

