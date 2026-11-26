package com.jurim007.androidtvremote.data

import android.content.Context
import com.jurim007.androidtvremote.data.webos.WebOsRemoteController
import com.jurim007.androidtvremote.domain.RemoteCommand
import com.jurim007.androidtvremote.domain.RemoteController
import com.jurim007.androidtvremote.domain.TvConnectionState
import com.jurim007.androidtvremote.domain.TvDevice
import com.jurim007.androidtvremote.domain.TvPlatform
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** Sends each TV to the controller for its platform and merges what both report. */
class TvRemoteRouter(context: Context) : RemoteController {
    private val androidTv = AndroidTvRemoteController(context)
    private val webOs = WebOsRemoteController(context)
    // Unconfined keeps the combined state in step with the controllers' own updates.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val activePlatform = MutableStateFlow(TvPlatform.ANDROID_TV)

    override val connectionState: StateFlow<TvConnectionState> =
        combine(activePlatform, androidTv.connectionState, webOs.connectionState) { platform, android, webos ->
            if (platform == TvPlatform.WEBOS) webos else android
        }.stateIn(scope, SharingStarted.Eagerly, androidTv.connectionState.value)

    override val discoveredDevices: StateFlow<List<TvDevice>> =
        combine(androidTv.discoveredDevices, webOs.discoveredDevices) { android, webos ->
            (android + webos).distinctBy(TvDevice::id).sortedBy(TvDevice::name)
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val messages: SharedFlow<String> =
        merge(androidTv.messages, webOs.messages).shareIn(scope, SharingStarted.Eagerly)

    private val active: RemoteController
        get() = controller(activePlatform.value)

    private fun controller(platform: TvPlatform): RemoteController = when (platform) {
        TvPlatform.ANDROID_TV -> androidTv
        TvPlatform.WEBOS -> webOs
    }

    private suspend fun activate(platform: TvPlatform) {
        if (activePlatform.value == platform) return
        val previous = active
        previous.cancelPendingPairing()
        previous.disconnect()
        activePlatform.value = platform
    }

    override fun startDiscovery() {
        androidTv.startDiscovery()
        webOs.startDiscovery()
    }

    override fun stopDiscovery() {
        androidTv.stopDiscovery()
        webOs.stopDiscovery()
    }

    override suspend fun beginPairing(device: TvDevice): TvDevice? {
        val platform = when {
            device.platform == TvPlatform.WEBOS -> TvPlatform.WEBOS
            androidTv.discoveredDevices.value.any { it.id == device.id } -> TvPlatform.ANDROID_TV
            device.serverCertificateFingerprint != null -> device.platform
            // Typed in by address: check which kind of TV answers there.
            else -> detectPlatform(device.host)
        }
        activate(platform)
        return controller(platform).beginPairing(
            if (platform == TvPlatform.WEBOS) {
                device.copy(port = TvDevice.WEBOS_PORT, platform = TvPlatform.WEBOS)
            } else {
                device
            },
        )
    }

    override suspend fun completePairing(pin: String, deviceName: String): TvDevice =
        active.completePairing(pin, deviceName)

    override suspend fun connect(device: TvDevice) {
        activate(device.platform)
        controller(device.platform).connect(device)
    }

    override suspend fun cancelPendingPairing() {
        androidTv.cancelPendingPairing()
        webOs.cancelPendingPairing()
    }

    override suspend fun disconnect() {
        active.disconnect()
    }

    override fun send(command: RemoteCommand) = active.send(command)

    override fun startHolding(command: RemoteCommand, holdId: Long) = active.startHolding(command, holdId)

    override fun stopHolding(holdId: Long) = active.stopHolding(holdId)

    internal suspend fun sendMicrophoneAudio(
        stop: Deferred<Unit>,
        onListening: () -> Unit,
        onFinishing: () -> Unit,
    ) {
        if (activePlatform.value != TvPlatform.ANDROID_TV) {
            throw IOException("This TV does not support voice through the remote.")
        }
        androidTv.sendMicrophoneAudio(stop, onListening, onFinishing)
    }

    override fun close() {
        androidTv.close()
        webOs.close()
        scope.cancel()
    }

    /** Android TV answers on its pairing port and webOS on its remote port. */
    private suspend fun detectPlatform(host: String): TvPlatform = withContext(Dispatchers.IO) {
        coroutineScope {
            val android = async { isPortOpen(host, TvDevice.PAIRING_PORT) }
            val webos = async { isPortOpen(host, TvDevice.WEBOS_PORT) }
            if (!android.await() && webos.await()) TvPlatform.WEBOS else TvPlatform.ANDROID_TV
        }
    }

    private fun isPortOpen(host: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS) }
        true
    } catch (_: IOException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    private companion object {
        const val PROBE_TIMEOUT_MS = 2_500
    }
}
