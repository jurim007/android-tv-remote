package com.jurim007.androidtvremote.domain

enum class RemoteOption(val preferenceKey: String, val enabledByDefault: Boolean = true) {
    VOLUME("show_volume"),
    CHANNEL("show_channel"),
    MUTE("show_mute"),
    SOURCE("show_source"),
    SHORTCUTS("show_shortcuts"),
    NAVIGATION_SELECTOR("show_navigation_mode_selector"),
    MICROPHONE("show_microphone"),
    PHONE_VOLUME("phone_controls_tv_volume", enabledByDefault = false),
    VIBRATION("remote_button_vibration"),
    AUTOMATIC_UPDATE_CHECKS("automatic_update_checks"),
    ;

    companion object {
        val defaults = entries.filter { it.enabledByDefault }.toSet()
    }
}
