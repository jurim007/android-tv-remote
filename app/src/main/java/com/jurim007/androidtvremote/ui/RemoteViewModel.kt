package com.jurim007.androidtvremote.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jurim007.androidtvremote.data.AppShortcutPreferences
import com.jurim007.androidtvremote.data.MockRemoteController
import com.jurim007.androidtvremote.domain.PressAndHoldController
import com.jurim007.androidtvremote.domain.RemoteCommand
import kotlinx.coroutines.launch

class RemoteViewModel(application: Application) : AndroidViewModel(application) {
    private val remoteController = MockRemoteController()
    private val pressAndHoldController =
        PressAndHoldController(viewModelScope, remoteController)
    private val shortcutPreferences = AppShortcutPreferences(application)

    val shortcutPackages = shortcutPreferences.packageNames

    fun send(command: RemoteCommand) {
        remoteController.send(command)
    }

    fun saveShortcutPackages(packageNames: List<String>) {
        viewModelScope.launch {
            shortcutPreferences.savePackageNames(packageNames)
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

