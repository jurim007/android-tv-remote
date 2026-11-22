package com.jurim007.androidtvremote.ui

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jurim007.androidtvremote.data.AndroidTvRemoteController
import com.jurim007.androidtvremote.data.AppShortcutPreferences
import com.jurim007.androidtvremote.data.AppUpdate
import com.jurim007.androidtvremote.data.GitHubAppUpdates
import com.jurim007.androidtvremote.data.UpdateSignatureMismatch
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
import java.io.File

class RemoteViewModel(application: Application) : AndroidViewModel(application) {
    private val remoteController = AndroidTvRemoteController(application)
    private val pressAndHoldController =
        PressAndHoldController(remoteController)
    private val shortcutPreferences = AppShortcutPreferences(application)
    private val operationMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private val appUpdates = GitHubAppUpdates(application)
    private val mutableUpdateState = MutableStateFlow(AppUpdateState())
    internal val updateState = mutableUpdateState.asStateFlow()
    private var updateJob: Job? = null
    private var lastUpdateCheck: Long? = null
    private var manualUpdateCheck = false
    private var dismissedUpdate: String? = null

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
    val discoveredDevices = remoteController.discoveredDevices
    val connectionState = remoteController.connectionState
    val tclInputsTvIds = shortcutPreferences.tclInputsTvIds.stateIn(
        viewModelScope, SharingStarted.Eagerly, emptySet(),
    )
    val messages = merge(remoteController.messages, operationMessages)

    init {
        viewModelScope.launch {
            val saved = shortcutPreferences.savedTvs.first()
            val selectedId = shortcutPreferences.selectedTvId.first()
            saved.firstOrNull { it.id == selectedId }?.let(::connect)
        }
    }

    fun checkForUpdates(manual: Boolean = false) {
        val state = mutableUpdateState.value
        if (state.release != null) {
            if (manual) mutableUpdateState.value = state.copy(manual = true)
            return
        }
        if (manual) manualUpdateCheck = true
        if (updateJob?.isActive == true) return
        if (!manual && lastUpdateCheck?.let { SystemClock.elapsedRealtime() - it < 300_000 } == true) return

        updateJob = viewModelScope.launch {
            try {
                val automaticChecks = RemoteOption.AUTOMATIC_UPDATE_CHECKS in shortcutPreferences.remoteOptions.first()
                if (!manualUpdateCheck && !automaticChecks) return@launch
                lastUpdateCheck = SystemClock.elapsedRealtime()
                mutableUpdateState.value = AppUpdateState(checking = true)
                val release = appUpdates.latest()
                val showAutomaticPrompt = release != null && release.version != dismissedUpdate &&
                    RemoteOption.AUTOMATIC_UPDATE_CHECKS in shortcutPreferences.remoteOptions.first()
                if (manualUpdateCheck) {
                    mutableUpdateState.value = AppUpdateState(
                        release = release,
                        manual = true,
                        status = if (release == null) "TVControl is up to date." else null,
                    )
                } else if (showAutomaticPrompt) {
                    mutableUpdateState.value = AppUpdateState(release = release)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Update check failed", error)
                if (manualUpdateCheck) {
                    mutableUpdateState.value = AppUpdateState(status = "Couldn’t check for updates. Try again later.")
                }
            } finally {
                manualUpdateCheck = false
                if (mutableUpdateState.value.checking) mutableUpdateState.value = AppUpdateState()
            }
        }
    }

    fun downloadUpdate() {
        val state = mutableUpdateState.value
        val release = state.release ?: return
        if (state.downloading || state.apk != null) return
        mutableUpdateState.value = state.copy(downloading = true, error = null)
        viewModelScope.launch {
            try {
                val apk = appUpdates.download(release)
                mutableUpdateState.value = state.copy(apk = apk, error = null)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Update download failed", error)
                val message = if (error is UpdateSignatureMismatch) {
                    "This APK uses a different signing key from the installed app and can’t update it in place."
                } else {
                    "Couldn’t download or verify the update. Try again."
                }
                mutableUpdateState.value = state.copy(error = message)
            }
        }
    }

    fun dismissUpdate() {
        if (mutableUpdateState.value.downloading) return
        dismissedUpdate = mutableUpdateState.value.release?.version
        mutableUpdateState.value = AppUpdateState()
    }

    fun send(command: RemoteCommand) {
        val deviceId = connectionState.value.device?.id
        val tvCommand = if (command == RemoteCommand.Source && deviceId in tclInputsTvIds.value) {
            RemoteCommand.LaunchApp("com.tcl.suspension")
        } else {
            command
        }
        remoteController.send(tvCommand)
    }

    fun setTclInputsEnabled(deviceId: String, enabled: Boolean) {
        viewModelScope.launch {
            try {
                shortcutPreferences.setTclInputsEnabled(deviceId, enabled)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                reportFailure("Could not save the Source setting", error)
            }
        }
    }

    fun saveShortcuts(tvId: String?, shortcuts: List<TvAppShortcut>) {
        viewModelScope.launch {
            try {
                shortcutPreferences.saveShortcuts(tvId, shortcuts)
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
                if (option == RemoteOption.AUTOMATIC_UPDATE_CHECKS && !enabled &&
                    !mutableUpdateState.value.manual && !mutableUpdateState.value.downloading &&
                    mutableUpdateState.value.apk == null
                ) dismissUpdate()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                reportFailure("Could not save the remote setting", error)
            }
        }
    }

    fun restoreRemoteDefaults(tvId: String?) {
        viewModelScope.launch {
            try {
                shortcutPreferences.restoreRemoteDefaults(tvId)
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
                remoteController.sendMicrophoneAudio(
                    stop,
                    onListening = { mutableVoiceState.value = VoiceState.LISTENING },
                    onFinishing = { mutableVoiceState.value = VoiceState.FINISHING },
                )
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

internal data class AppUpdateState(
    val checking: Boolean = false,
    val release: AppUpdate? = null,
    val manual: Boolean = false,
    val status: String? = null,
    val downloading: Boolean = false,
    val apk: File? = null,
    val error: String? = null,
)


internal enum class VoiceState { IDLE, STARTING, LISTENING, FINISHING }
