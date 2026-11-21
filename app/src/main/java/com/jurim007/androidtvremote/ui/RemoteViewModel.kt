package com.jurim007.androidtvremote.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jurim007.androidtvremote.data.AndroidTvRemoteController
import com.jurim007.androidtvremote.data.AppShortcutPreferences
import com.jurim007.androidtvremote.domain.PressAndHoldController
import com.jurim007.androidtvremote.domain.RemoteCommand
import com.jurim007.androidtvremote.domain.RemoteOption
import com.jurim007.androidtvremote.data.protocol.TvRemoteProtocol
import com.jurim007.androidtvremote.domain.TvAppShortcut
import com.jurim007.androidtvremote.domain.TvConnectionPhase
import com.jurim007.androidtvremote.domain.TvDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RemoteViewModel(application: Application) : AndroidViewModel(application) {
    private val remoteController = AndroidTvRemoteController(application)
    private val pressAndHoldController =
        PressAndHoldController(remoteController)
    private val shortcutPreferences = AppShortcutPreferences(application)
    private val operationMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    private var voiceJob: Job? = null
    private var stopRecording: CompletableDeferred<Unit>? = null
    private val mutableVoiceState = MutableStateFlow(VoiceState.IDLE)
    internal val voiceState = mutableVoiceState.asStateFlow()

    val shortcuts = shortcutPreferences.shortcuts
    val touchpadEnabled = shortcutPreferences.touchpadEnabled
    val remoteOptions = shortcutPreferences.remoteOptions.stateIn(
        viewModelScope, SharingStarted.Eagerly, RemoteOption.defaults,
    )
    val savedTvs = shortcutPreferences.savedTvs.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )
    val selectedTvId = shortcutPreferences.selectedTvId.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        null,
    )
    val discoveredDevices = remoteController.discoveredDevices
    val connectionState = remoteController.connectionState
    val messages = merge(remoteController.messages, operationMessages)

    init {
        viewModelScope.launch {
            val saved = shortcutPreferences.savedTvs.first()
            val selectedId = shortcutPreferences.selectedTvId.first()
            saved.firstOrNull { it.id == selectedId }?.let(::connect)
        }
    }

    fun send(command: RemoteCommand) {
        remoteController.send(command)
    }

    fun saveShortcuts(shortcuts: List<TvAppShortcut>) {
        viewModelScope.launch {
            try {
                shortcutPreferences.saveShortcuts(shortcuts)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                reportFailure("Could not save TV app shortcuts", error)
            }
        }
    }

    fun setTouchpadEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                shortcutPreferences.setTouchpadEnabled(enabled)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                reportFailure("Could not save the remote setting", error)
            }
        }
    }

    fun setRemoteOption(option: RemoteOption, enabled: Boolean) {
        viewModelScope.launch {
            try {
                shortcutPreferences.setRemoteOption(option, enabled)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                reportFailure("Could not save the remote setting", error)
            }
        }
    }

    fun restoreRemoteDefaults() {
        viewModelScope.launch {
            try {
                shortcutPreferences.restoreRemoteDefaults()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                reportFailure("Could not restore remote defaults", error)
            }
        }
    }

    fun canUsePhoneVolume(): Boolean {
        val state = connectionState.value
        val required = TvRemoteProtocol.FEATURE_KEY or TvRemoteProtocol.FEATURE_VOLUME
        return RemoteOption.PHONE_VOLUME in remoteOptions.value &&
            state.phase == TvConnectionPhase.CONNECTED &&
            state.supportedFeatures and required == required
    }

    fun startVoice() {
        if (voiceJob?.isCompleted == false || RemoteOption.MICROPHONE !in remoteOptions.value) return
        val stop = CompletableDeferred<Unit>()
        stopRecording = stop
        mutableVoiceState.value = VoiceState.STARTING
        voiceJob = viewModelScope.launch {
            try {
                pressAndHoldController.stop()
                remoteController.sendMicrophoneAudio(stop) {
                    mutableVoiceState.value = VoiceState.LISTENING
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                reportFailure("Microphone unavailable", error)
            } finally {
                stopRecording = null
                mutableVoiceState.value = VoiceState.IDLE
            }
        }
    }

    fun stopVoice() {
        stopRecording?.complete(Unit)
    }

    fun cancelVoice() {
        voiceJob?.cancel()
    }

    fun microphonePermissionDenied() {
        operationMessages.tryEmit("Allow microphone access to use voice search.")
    }

    fun startHolding(command: RemoteCommand) {
        pressAndHoldController.start(command)
    }

    fun stopHolding() {
        pressAndHoldController.stop()
    }

    fun startDiscovery() {
        remoteController.startDiscovery()
    }

    fun stopDiscovery() {
        remoteController.stopDiscovery()
    }

    fun beginPairing(device: TvDevice) {
        viewModelScope.launch {
            try {
                remoteController.beginPairing(device)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (connectionState.value.phase != TvConnectionPhase.ERROR) {
                    reportFailure("Could not start TV pairing", error)
                }
            }
        }
    }

    fun completePairing(pin: String, deviceName: String) {
        viewModelScope.launch {
            try {
                val device = remoteController.completePairing(pin, deviceName)
                shortcutPreferences.saveTv(device)
                remoteController.connect(device)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (connectionState.value.phase != TvConnectionPhase.ERROR) {
                    reportFailure("Could not save or connect to the TV", error)
                }
            }
        }
    }

    fun cancelPairing() {
        viewModelScope.launch {
            remoteController.cancelPendingPairing()
        }
    }

    fun connect(device: TvDevice) {
        viewModelScope.launch {
            try {
                if (shortcutPreferences.selectTv(device.id)) {
                    remoteController.connect(device)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (connectionState.value.phase != TvConnectionPhase.ERROR) {
                    reportFailure("Could not connect to the TV", error)
                }
            }
        }
    }

    fun removeTv(deviceId: String) {
        viewModelScope.launch {
            try {
                shortcutPreferences.removeTv(deviceId)
                if (connectionState.value.device?.id == deviceId) {
                    remoteController.disconnect()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                reportFailure("Could not remove the saved TV", error)
            }
        }
    }

    private fun reportFailure(action: String, error: Exception) {
        Log.e(TAG, action, error)
        operationMessages.tryEmit("$action: ${error.message ?: "unknown error"}")
    }

    override fun onCleared() {
        pressAndHoldController.stop()
        remoteController.close()
        super.onCleared()
    }

    private companion object {
        const val TAG = "TVControl"
    }
}


internal enum class VoiceState { IDLE, STARTING, LISTENING }
