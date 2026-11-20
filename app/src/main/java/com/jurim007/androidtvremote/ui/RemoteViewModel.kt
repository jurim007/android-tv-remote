package com.jurim007.androidtvremote.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jurim007.androidtvremote.data.AppShortcutPreferences
import com.jurim007.androidtvremote.data.MockRemoteController
import com.jurim007.androidtvremote.domain.PressAndHoldController
import com.jurim007.androidtvremote.domain.RemoteCommand
import com.jurim007.androidtvremote.domain.TvAppShortcut
import kotlinx.coroutines.launch

class RemoteViewModel(application: Application) : AndroidViewModel(application) {
    private val remoteController = MockRemoteController()
    private val pressAndHoldController =
        PressAndHoldController(viewModelScope, remoteController)
    private val shortcutPreferences = AppShortcutPreferences(application)

    val shortcuts = shortcutPreferences.shortcuts
    val touchpadEnabled = shortcutPreferences.touchpadEnabled

    fun send(command: RemoteCommand) {
        remoteController.send(command)
    }

    fun saveShortcuts(shortcuts: List<TvAppShortcut>) {
        viewModelScope.launch {
            shortcutPreferences.saveShortcuts(shortcuts)
        }
    }

    fun setTouchpadEnabled(enabled: Boolean) {
        viewModelScope.launch {
            shortcutPreferences.setTouchpadEnabled(enabled)
        }
    }

    fun startHolding(command: RemoteCommand) {
        pressAndHoldController.start(command)
    }

    fun stopHolding() {
        pressAndHoldController.stop()
    }

    override fun onCleared() {
        pressAndHoldController.stop()
        super.onCleared()
    }
}

