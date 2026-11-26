package com.jurim007.androidtvremote.ui

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jurim007.androidtvremote.data.AppShortcutPreferences
import com.jurim007.androidtvremote.data.AppUpdate
import com.jurim007.androidtvremote.data.AppUpdateInstaller
import com.jurim007.androidtvremote.data.GitHubAppUpdates
import com.jurim007.androidtvremote.data.TvRemoteRouter
import com.jurim007.androidtvremote.data.UpdateSignatureMismatch
import com.jurim007.androidtvremote.data.UpdateVerificationFailed
import com.jurim007.androidtvremote.data.VerifiedUpdate
import com.jurim007.androidtvremote.domain.PressAndHoldController
import com.jurim007.androidtvremote.domain.RemoteCommand
import com.jurim007.androidtvremote.domain.RemoteOption
import com.jurim007.androidtvremote.data.protocol.TvRemoteProtocol
import com.jurim007.androidtvremote.domain.TvAppCatalog
import com.jurim007.androidtvremote.domain.TvAppShortcut
import com.jurim007.androidtvremote.domain.TvConnectionPhase
import com.jurim007.androidtvremote.domain.TvConnectionState
import com.jurim007.androidtvremote.domain.TvDevice
import com.jurim007.androidtvremote.domain.TvPlatform
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
    private val remoteController = TvRemoteRouter(application)
    private val pressAndHoldController =
        PressAndHoldController(remoteController)
    private val shortcutPreferences = AppShortcutPreferences(application)
    private val operationMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private val appUpdates = GitHubAppUpdates(application)
    private val updateInstaller = AppUpdateInstaller(application)
    internal val updateInstallation = updateInstaller.state
    private var downloadJob: Job? = null
    private val mutableUpdateState = MutableStateFlow(AppUpdateState())
    internal val updateState = mutableUpdateState.asStateFlow()
    private var updateJob: Job? = null
    private var lastUpdateCheck: Long? = null
    private var manualUpdateCheck = false

    private var voiceJob: Job? = null
    private var stopRecording: CompletableDeferred<Unit>? = null
    private val mutableVoiceState = MutableStateFlow(VoiceState.IDLE)
    internal val voiceState = mutableVoiceState.asStateFlow()

    internal val remoteLayout = shortcutPreferences.remoteLayout.stateIn(
        viewModelScope, SharingStarted.Eagerly, null,
    )
    val discoveredDevices = remoteController.discoveredDevices
    val connectionState = remoteController.connectionState
    val tclInputsTvIds = shortcutPreferences.tclInputsTvIds.stateIn(
        viewModelScope, SharingStarted.Eagerly, emptySet(),
    )
    val messages = merge(remoteController.messages, operationMessages)

    init {
        updateInstaller.restore()
        viewModelScope.launch {
            val saved = shortcutPreferences.savedTvs.first()
            val selectedId = shortcutPreferences.selectedTvId.first()
            saved.firstOrNull { it.id == selectedId }?.let(::connect)
        }
        viewModelScope.launch {
            connectionState.collect { state ->
                saveDetectedShortcuts(state)
                saveDetectedGoogleTv(state)
            }
        }
    }

    /** The shortcuts "Restore defaults" uses for a TV, detected from its apps when possible. */
    internal fun defaultShortcuts(device: TvDevice?, state: TvConnectionState): List<TvAppShortcut> =
        TvAppCatalog.defaultShortcuts(
            device?.platform ?: TvPlatform.ANDROID_TV,
            state.installedApps?.takeIf { state.device?.id == device?.id },
        )

    /** Remembers whether a connected Android-based TV is Google TV, for its badge. */
    private suspend fun saveDetectedGoogleTv(state: TvConnectionState) {
        val device = state.device ?: return
        val googleTv = state.detectedGoogleTv ?: return
        if (device.platform != TvPlatform.ANDROID_TV) return
        try {
            shortcutPreferences.setGoogleTv(device.id, googleTv)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Could not save the TV type", error)
        }
    }

    /** Gives a newly connected webOS TV shortcuts for the apps it actually has. */
    private suspend fun saveDetectedShortcuts(state: TvConnectionState) {
        val device = state.device ?: return
        if (state.phase != TvConnectionPhase.CONNECTED || device.platform != TvPlatform.WEBOS) return
        if (state.installedApps == null) return
        try {
            if (!shortcutPreferences.hasSavedShortcuts(device.id)) {
                shortcutPreferences.saveShortcuts(device.id, defaultShortcuts(device, state))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            reportFailure("Could not save the TV’s app shortcuts", error)
        }
    }

    fun checkForUpdates(manual: Boolean = false) {
        val state = mutableUpdateState.value
        if (state.release != null) {
            if (manual) mutableUpdateState.value = state.copy(manual = true)
            if (state.waitingForUnmetered && appUpdates.canDownloadAutomatically()) {
                viewModelScope.launch { startAutomaticDownload() }
            }
            return
        }
        if (manual) manualUpdateCheck = true
        if (updateJob?.isActive == true) return
        if (!manual && lastUpdateCheck?.let { SystemClock.elapsedRealtime() - it < 300_000 } == true) return

        updateJob = viewModelScope.launch {
            try {
                if (!manualUpdateCheck &&
                    RemoteOption.AUTOMATIC_UPDATE_CHECKS !in shortcutPreferences.remoteOptions.first()
                ) return@launch
                lastUpdateCheck = SystemClock.elapsedRealtime()
                mutableUpdateState.value = AppUpdateState(checking = true)
                val release = appUpdates.latest()
                val manualRequest = manualUpdateCheck
                val automatic = RemoteOption.AUTOMATIC_UPDATE_CHECKS in shortcutPreferences.remoteOptions.first()
                if (manualRequest || release != null && release.version != updateInstaller.dismissedVersion && automatic) {
                    mutableUpdateState.value = AppUpdateState(
                        release = release,
                        manual = manualRequest,
                        status = if (release == null) "TVControl is up to date." else null,
                    )
                    startAutomaticDownload()
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

    private suspend fun startAutomaticDownload() {
        val options = shortcutPreferences.remoteOptions.first()
        val state = mutableUpdateState.value
        if (!automaticDownloadEnabled(options) || state.release == null || state.apk != null ||
            updateInstallation.value?.takeIf { it.version == state.release.version }?.running == true
        ) return
        if (!appUpdates.canDownloadAutomatically()) {
            mutableUpdateState.value = state.copy(waitingForUnmetered = true)
            return
        }
        downloadUpdate(automatic = true)
    }

    fun downloadUpdate(automatic: Boolean = false) {
        val state = mutableUpdateState.value
        val release = state.release ?: return
        if (state.downloading || state.apk != null || downloadJob?.isActive == true) return
        mutableUpdateState.value = state.copy(
            downloading = true,
            backgroundDownload = automatic,
            waitingForUnmetered = false,
            error = null,
        )
        downloadJob = viewModelScope.launch {
            try {
                val apk = appUpdates.download(release, automatic)
                val options = shortcutPreferences.remoteOptions.first()
                val installation = updateInstallation.value?.takeIf { it.version == release.version }
                val request = when {
                    installation?.running == true -> null
                    !automatic -> false
                    automaticInstallEnabled(options) && installation?.error == null -> true
                    else -> null
                }
                mutableUpdateState.value = mutableUpdateState.value.copy(apk = apk, installRequest = request)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Update download failed", error)
                mutableUpdateState.value = mutableUpdateState.value.copy(error = updateErrorMessage(error))
            } finally {
                mutableUpdateState.value = mutableUpdateState.value.copy(
                    downloading = false,
                    backgroundDownload = false,
                )
            }
        }
    }

    fun installUpdate(automatic: Boolean) {
        val state = mutableUpdateState.value
        val apk = state.apk ?: return
        if (state.installing || updateInstallation.value?.running == true) return
        mutableUpdateState.value = state.copy(installRequest = null, installing = true, error = null)
        viewModelScope.launch {
            try {
                if (!automatic || automaticInstallEnabled(shortcutPreferences.remoteOptions.first())) {
                    updateInstaller.install(apk, automatic)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Update installation failed to start", error)
                mutableUpdateState.value = mutableUpdateState.value.copy(
                    apk = apk.takeUnless { error is UpdateVerificationFailed },
                    error = if (error is UpdateVerificationFailed) error.message else
                        "Couldn’t start Android’s installer. Check the install permission and available storage.",
                )
            } finally {
                mutableUpdateState.value = mutableUpdateState.value.copy(installing = false)
            }
        }
    }

    fun updateConfirmationOpened() = updateInstaller.confirmationOpened()

    fun updateConfirmationUnavailable() = updateInstaller.confirmationUnavailable()

    fun dismissUpdate(rememberChoice: Boolean = true) {
        val state = mutableUpdateState.value
        if (state.downloading || state.installing || updateInstallation.value?.running == true) return
        if (rememberChoice) state.release?.version?.let(updateInstaller::dismissOffer)
        mutableUpdateState.value = AppUpdateState()
    }

    private fun updateErrorMessage(error: Exception): String = when (error) {
        is UpdateSignatureMismatch -> "This APK uses a different signing key and can’t update the installed app."
        is UpdateVerificationFailed -> error.message ?: "Couldn’t verify the update."
        else -> "Couldn’t finish the download. Try again to resume it."
    }

    fun send(command: RemoteCommand) {
        val device = connectionState.value.device
        val tvCommand = if (command == RemoteCommand.Source && device?.platform == TvPlatform.ANDROID_TV &&
            device.id in tclInputsTvIds.value
        ) {
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
                if (!enabled && option in setOf(
                        RemoteOption.AUTOMATIC_UPDATE_CHECKS,
                        RemoteOption.AUTOMATIC_UPDATE_DOWNLOADS,
                        RemoteOption.AUTOMATIC_UPDATE_INSTALLS,
                    )
                ) {
                    if (option != RemoteOption.AUTOMATIC_UPDATE_INSTALLS &&
                        mutableUpdateState.value.backgroundDownload
                    ) {
                        downloadJob?.cancel()
                        downloadJob?.join()
                    }
                    val state = mutableUpdateState.value
                    mutableUpdateState.value = state.copy(
                        installRequest = state.installRequest.takeUnless { it == true },
                        waitingForUnmetered = state.waitingForUnmetered &&
                            option == RemoteOption.AUTOMATIC_UPDATE_INSTALLS,
                    )
                    if (option == RemoteOption.AUTOMATIC_UPDATE_CHECKS && !state.manual) dismissUpdate(rememberChoice = false)
                }
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
                saveDetectedShortcuts(connectionState.value)
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
        return RemoteOption.PHONE_VOLUME in remoteLayout.value?.options.orEmpty() &&
            state.phase == TvConnectionPhase.CONNECTED &&
            state.supportedFeatures and required == required
    }

    fun startVoice() {
        if (voiceJob?.isCompleted == false || RemoteOption.MICROPHONE !in remoteLayout.value?.options.orEmpty()) return
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

    /** Restores the connection right away when the app returns from the background. */
    fun onAppForeground() {
        viewModelScope.launch {
            val state = connectionState.value
            if (state.phase == TvConnectionPhase.ERROR && !state.needsPairing) {
                // Reconnecting gave up while the phone was locked; try the selected TV again now.
                val selectedId = shortcutPreferences.selectedTvId.first()
                shortcutPreferences.savedTvs.first().firstOrNull { it.id == selectedId }?.let(::connect)
            } else {
                remoteController.checkConnection()
            }
        }
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
                val paired = remoteController.beginPairing(device)
                if (paired != null) {
                    // webOS TVs approve the connection on screen, so there's no code to enter.
                    shortcutPreferences.saveTv(paired)
                    remoteController.connect(paired)
                }
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

    fun renameTv(deviceId: String, name: String) {
        viewModelScope.launch {
            try {
                shortcutPreferences.renameTv(deviceId, name)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                reportFailure("Could not rename the TV", error)
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
    val apk: VerifiedUpdate? = null,
    val backgroundDownload: Boolean = false,
    val waitingForUnmetered: Boolean = false,
    val installRequest: Boolean? = null,
    val installing: Boolean = false,
    val error: String? = null,
)


internal fun automaticDownloadEnabled(options: Set<RemoteOption>): Boolean =
    RemoteOption.AUTOMATIC_UPDATE_CHECKS in options && RemoteOption.AUTOMATIC_UPDATE_DOWNLOADS in options

internal fun automaticInstallEnabled(options: Set<RemoteOption>): Boolean =
    automaticDownloadEnabled(options) && RemoteOption.AUTOMATIC_UPDATE_INSTALLS in options

internal enum class VoiceState { IDLE, STARTING, LISTENING, FINISHING }
