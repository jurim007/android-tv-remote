package com.jurim007.androidtvremote.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.jurim007.androidtvremote.data.protocol.ProtoWire
import com.jurim007.androidtvremote.data.protocol.TvRemoteProtocol
import com.jurim007.androidtvremote.domain.RemoteCommand
import com.jurim007.androidtvremote.domain.RemoteController
import com.jurim007.androidtvremote.domain.TvConnectionPhase
import com.jurim007.androidtvremote.domain.TvConnectionState
import com.jurim007.androidtvremote.domain.TvDevice
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AndroidTvRemoteController(context: Context) : RemoteController {
    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(NsdManager::class.java)
    private val identity = AndroidTvIdentity()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val outgoing = Channel<OutgoingAction>(Channel.BUFFERED)

    private val mutableConnectionState = MutableStateFlow(TvConnectionState())
    override val connectionState: StateFlow<TvConnectionState> =
        mutableConnectionState.asStateFlow()

    private val mutableDiscoveredDevices = MutableStateFlow<List<TvDevice>>(emptyList())
    override val discoveredDevices: StateFlow<List<TvDevice>> =
        mutableDiscoveredDevices.asStateFlow()

    private val mutableMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val messages: SharedFlow<String> = mutableMessages.asSharedFlow()

    private val nsdLock = Any()
    private val servicesToResolve = ArrayDeque<NsdServiceInfo>()
    private val discoveredByServiceName = mutableMapOf<String, TvDevice>()
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var resolvingService = false
    @Volatile
    private var pendingPairingSocket: SSLSocket? = null
    @Volatile
    private var pendingPairingDevice: TvDevice? = null
    @Volatile
    private var pendingServerKey: RSAPublicKey? = null
    @Volatile
    private var pendingCertificateFingerprint: String? = null
    @Volatile
    private var remoteSocket: SSLSocket? = null
    @Volatile
    private var connectingSocket: SSLSocket? = null
    private var readerJob: Job? = null
    @Volatile
    private var holdingJob: Job? = null
    @Volatile
    private var activeFeatures = 0
    @Volatile
    private var activeHoldId: Long? = null
    @Volatile
    private var pairingGeneration = 0L
    @Volatile
    private var connectionGeneration = 0L
    @Volatile
    private var imeCounter = 0
    @Volatile
    private var fieldCounter = 0

    init {
        scope.launch {
            for (action in outgoing) {
                processOutgoingAction(action)
            }
        }
    }

    override fun startDiscovery() {
        synchronized(nsdLock) {
            if (discoveryListener != null) return
            mutableDiscoveredDevices.value = emptyList()
            discoveredByServiceName.clear()
            servicesToResolve.clear()
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {
                    if (mutableConnectionState.value.phase != TvConnectionPhase.CONNECTED) {
                        mutableConnectionState.value =
                            TvConnectionState(TvConnectionPhase.DISCOVERING)
                    }
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    synchronized(nsdLock) { discoveryListener = null }
                    setError("TV discovery could not start (error $errorCode).")
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (serviceInfo.serviceType.contains(SERVICE_TYPE_BASE, ignoreCase = true)) {
                        synchronized(nsdLock) {
                            servicesToResolve.addLast(serviceInfo)
                            resolveNextServiceLocked()
                        }
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    synchronized(nsdLock) {
                        discoveredByServiceName.remove(serviceInfo.serviceName)
                        publishDiscoveredDevicesLocked()
                    }
                }

                override fun onDiscoveryStopped(serviceType: String) = Unit

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    emitMessage("TV discovery could not stop (error $errorCode).")
                }
            }
            discoveryListener = listener
            try {
                nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (error: RuntimeException) {
                discoveryListener = null
                setError("TV discovery failed: ${error.message ?: "unknown error"}")
            }
        }
    }

    override fun stopDiscovery() {
        val listener = synchronized(nsdLock) {
            discoveryListener.also {
                discoveryListener = null
                servicesToResolve.clear()
                resolvingService = false
            }
        } ?: return
        try {
            nsdManager.stopServiceDiscovery(listener)
        } catch (error: IllegalArgumentException) {
            emitMessage("TV discovery had already stopped.")
        }
        if (mutableConnectionState.value.phase == TvConnectionPhase.DISCOVERING) {
            mutableConnectionState.value = TvConnectionState()
        }
    }

    override suspend fun beginPairing(device: TvDevice) = withContext(Dispatchers.IO) {
        stopDiscovery()
        disconnect()
        closePendingPairing()
        val pairingAttempt = pairingGeneration + 1
        pairingGeneration = pairingAttempt
        mutableConnectionState.value = TvConnectionState(
            phase = TvConnectionPhase.PAIRING,
            device = device,
            message = "Connecting securely to ${device.name}…",
        )

        var socket: SSLSocket? = null
        try {
            socket = openTlsSocket(device, TvDevice.PAIRING_PORT, expectedFingerprint = null)
            pendingPairingSocket = socket
            if (pairingAttempt != pairingGeneration) {
                closePendingPairing()
                return@withContext
            }
            writeMessage(socket, TvRemoteProtocol.pairingRequest())
            readPairingUntil(socket, 11)

            writeMessage(socket, TvRemoteProtocol.pairingOptions())
            readPairingUntil(socket, 20)

            writeMessage(socket, TvRemoteProtocol.pairingConfiguration())
            readPairingUntil(socket, 31)

            if (pairingAttempt != pairingGeneration) {
                closePendingPairing()
                return@withContext
            }
            val certificate = socket.session.peerCertificates.firstOrNull() as? X509Certificate
                ?: throw CertificateException("The TV did not provide an X.509 certificate.")
            val serverKey = certificate.publicKey as? RSAPublicKey
                ?: throw CertificateException("The TV certificate does not contain an RSA key.")

            pendingPairingSocket = socket
            pendingPairingDevice = device
            pendingServerKey = serverKey
            pendingCertificateFingerprint = fingerprint(certificate)
            socket.soTimeout = PAIRING_PIN_TIMEOUT_MS
            mutableConnectionState.value = TvConnectionState(
                phase = TvConnectionPhase.PAIRING,
                device = device,
                message = "Enter the 6-character code shown on the TV.",
                pairingReady = true,
            )
        } catch (error: Exception) {
            socket?.close()
            if (pairingAttempt != pairingGeneration) return@withContext
            setError("Could not start pairing with ${device.name}: ${error.message ?: "connection failed"}")
            throw error
        }
    }

    override suspend fun completePairing(pin: String, deviceName: String): TvDevice =
        withContext(Dispatchers.IO) {
            val pairingAttempt = pairingGeneration
            val socket = pendingPairingSocket
                ?: throw IllegalStateException("Start TV pairing before entering the code.")
            val originalDevice = pendingPairingDevice
                ?: throw IllegalStateException("The pending TV pairing was lost.")
            try {
                val clientCertificate = identity.clientCertificate()
                val clientKey = clientCertificate.publicKey as? RSAPublicKey
                    ?: throw CertificateException("The client certificate does not contain an RSA key.")
                val serverKey = pendingServerKey
                    ?: throw IllegalStateException("The TV certificate was not received.")
                val secret = TvRemoteProtocol.pairingSecret(pin.trim(), clientKey, serverKey)
                writeMessage(socket, TvRemoteProtocol.pairingSecret(secret))
                val acknowledgement = readPairingUntil(socket, 41)
                TvRemoteProtocol.pairingStatus(acknowledgement)?.let { status ->
                    if (status != 200L) throw IOException("The TV rejected pairing (status $status).")
                }
                val pairedDevice = TvDevice(
                    name = deviceName.trim().ifBlank { originalDevice.name },
                    host = originalDevice.host,
                    port = TvDevice.REMOTE_PORT,
                    serverCertificateFingerprint = pendingCertificateFingerprint
                        ?: throw IllegalStateException("The TV certificate fingerprint was not captured."),
                )
                mutableConnectionState.value = TvConnectionState(
                    phase = TvConnectionPhase.DISCONNECTED,
                    device = pairedDevice,
                    message = "TV pairing completed.",
                )
                pairedDevice
            } catch (error: Exception) {
                if (pairingAttempt != pairingGeneration) {
                    throw CancellationException("TV pairing was cancelled.")
                }
                mutableConnectionState.value = TvConnectionState(
                    phase = TvConnectionPhase.ERROR,
                    device = originalDevice,
                    message = error.message ?: "TV pairing failed.",
                )
                throw error
            } finally {
                closePendingPairing()
            }
        }

    override suspend fun connect(device: TvDevice) = withContext(Dispatchers.IO) {
        val expectedFingerprint = device.serverCertificateFingerprint
            ?: throw IllegalArgumentException("Pair this TV before connecting.")
        disconnect()
        val connectionAttempt = connectionGeneration
        mutableConnectionState.value = TvConnectionState(
            phase = TvConnectionPhase.CONNECTING,
            device = device,
            message = "Connecting to ${device.name}…",
        )
        var socket: SSLSocket? = null
        try {
            socket = openTlsSocket(device, device.port, expectedFingerprint)
            connectingSocket = socket
            if (connectionAttempt != connectionGeneration) {
                if (connectingSocket === socket) connectingSocket = null
                socket.close()
                return@withContext
            }
            val configure = readRemoteUntil(socket, 1)
            val advertisedFeatures = TvRemoteProtocol.remoteFeatures(configure)
                ?: throw IOException("The TV did not advertise remote-control capabilities.")
            val requestedFeatures = advertisedFeatures and SUPPORTED_FEATURES
            writeMessage(socket, TvRemoteProtocol.remoteConfigure(requestedFeatures))

            readRemoteUntil(socket, 2)
            val negotiatedFeatures = requestedFeatures
            writeMessage(socket, TvRemoteProtocol.remoteSetActive(negotiatedFeatures))
            readRemoteUntil(socket, 40)

            if (connectionAttempt != connectionGeneration) {
                if (connectingSocket === socket) connectingSocket = null
                socket.close()
                return@withContext
            }
            remoteSocket = socket
            connectingSocket = null
            if (connectionAttempt != connectionGeneration) {
                if (remoteSocket === socket) remoteSocket = null
                socket.close()
                return@withContext
            }
            activeFeatures = negotiatedFeatures
            mutableConnectionState.value = TvConnectionState(
                phase = TvConnectionPhase.CONNECTED,
                device = device,
                supportedFeatures = negotiatedFeatures,
            )
            readerJob = scope.launch { readRemoteMessages(socket) }
        } catch (error: Exception) {
            socket?.close()
            if (connectingSocket === socket) connectingSocket = null
            if (connectionAttempt != connectionGeneration) return@withContext
            setError("Could not connect to ${device.name}: ${error.message ?: "connection failed"}", device)
            throw error
        }
    }

    override suspend fun cancelPendingPairing() {
        pairingGeneration += 1
        closePendingPairing()
        if (mutableConnectionState.value.phase == TvConnectionPhase.PAIRING) {
            mutableConnectionState.value = TvConnectionState()
        }
    }

    override suspend fun disconnect() {
        connectionGeneration += 1
        activeHoldId = null
        holdingJob?.cancel()
        holdingJob = null
        readerJob?.cancel()
        readerJob = null
        connectingSocket?.close()
        connectingSocket = null
        remoteSocket?.close()
        remoteSocket = null
        activeFeatures = 0
        mutableConnectionState.value = TvConnectionState()
    }

    override fun send(command: RemoteCommand) {
        if (outgoing.trySend(OutgoingAction.Command(command)).isFailure) {
            emitMessage("The command could not be queued.")
        }
    }

    override fun startHolding(command: RemoteCommand, holdId: Long) {
        val keyCode = TvRemoteProtocol.keyCode(command)
        if (keyCode == null) {
            emitMessage("That action cannot be held.")
            return
        }
        if (mutableConnectionState.value.phase != TvConnectionPhase.CONNECTED) {
            emitMessage(
                "Connect to a paired TV before sending commands.",
            )
            return
        }
        if (!supportsCommand(command)) {
            emitMessage("This TV does not support that remote command.")
            return
        }

        activeHoldId = holdId
        if (
            outgoing.trySend(
                OutgoingAction.HeldCommand(command, holdId, isInitialPress = true),
            ).isFailure
        ) {
            activeHoldId = null
            emitMessage("The button press could not be queued.")
            return
        }
        holdingJob?.cancel()
        holdingJob = scope.launch {
            delay(HOLD_INITIAL_DELAY_MS)
            var queueOverflowReported = false
            while (activeHoldId == holdId) {
                if (
                    outgoing.trySend(
                        OutgoingAction.HeldCommand(command, holdId, isInitialPress = false),
                    ).isFailure
                ) {
                    if (!queueOverflowReported) {
                        emitMessage("The TV is not responding quickly; held-command repeats are being skipped.")
                        queueOverflowReported = true
                    }
                }
                delay(HOLD_REPEAT_INTERVAL_MS)
            }
        }
    }

    override fun stopHolding(holdId: Long) {
        if (activeHoldId == holdId) {
            activeHoldId = null
            holdingJob?.cancel()
            holdingJob = null
        }
    }

    override fun close() {
        pairingGeneration += 1
        connectionGeneration += 1
        stopDiscovery()
        closePendingPairing()
        connectingSocket?.close()
        connectingSocket = null
        remoteSocket?.close()
        remoteSocket = null
        scope.cancel()
        outgoing.close()
    }

    private suspend fun processOutgoingAction(action: OutgoingAction) {
        if (
            action is OutgoingAction.HeldCommand &&
            !action.isInitialPress &&
            action.holdId != activeHoldId
        ) return
        val socket = remoteSocket
        if (socket == null || mutableConnectionState.value.phase != TvConnectionPhase.CONNECTED) {
            emitMessage("Connect to a paired TV before sending commands.")
            return
        }

        try {
            when (action) {
                is OutgoingAction.Command -> sendCommand(socket, action.command)
                is OutgoingAction.HeldCommand -> {
                    if (action.isInitialPress || action.holdId == activeHoldId) {
                        sendCommand(socket, action.command)
                    }
                }
            }
        } catch (error: IOException) {
            activeHoldId = null
            holdingJob?.cancel()
            holdingJob = null
            setError("The TV connection was interrupted: ${error.message ?: "I/O error"}")
            socket.close()
            if (remoteSocket === socket) remoteSocket = null
        } catch (error: IllegalArgumentException) {
            emitMessage("The TV command could not be encoded: ${error.message ?: "invalid command"}")
        }
    }

    private fun sendCommand(socket: SSLSocket, command: RemoteCommand) {
        when (command) {
            is RemoteCommand.SendText -> {
                if (activeFeatures and TvRemoteProtocol.FEATURE_IME == 0) {
                    emitMessage("Text input is not supported by this TV right now.")
                } else {
                    writeMessage(
                        socket,
                        TvRemoteProtocol.textEdit(command.text, imeCounter, fieldCounter),
                    )
                }
            }

            is RemoteCommand.LaunchApp -> {
                val uri = command.launchUri?.trim()?.takeIf(String::isNotEmpty)
                if (uri == null) {
                    emitMessage("Add a TV app link in Settings; package IDs alone cannot launch apps.")
                } else if (activeFeatures and TvRemoteProtocol.FEATURE_APP_LINK == 0) {
                    emitMessage("This TV does not support app links.")
                } else {
                    writeMessage(socket, TvRemoteProtocol.appLink(uri))
                }
            }

            else -> {
                val keyCode = TvRemoteProtocol.keyCode(command) ?: return
                if (!supportsCommand(command)) {
                    emitMessage("This TV does not support that remote command.")
                } else {
                    writeMessage(
                        socket,
                        TvRemoteProtocol.keyCommand(keyCode, TvRemoteProtocol.DIRECTION_SHORT),
                    )
                }
            }
        }
    }

    private fun supportsCommand(command: RemoteCommand): Boolean {
        val requiredFeatures = when (command) {
            RemoteCommand.Power ->
                TvRemoteProtocol.FEATURE_KEY or TvRemoteProtocol.FEATURE_POWER
            RemoteCommand.VolumeUp,
            RemoteCommand.VolumeDown,
            RemoteCommand.Mute,
            -> TvRemoteProtocol.FEATURE_KEY or TvRemoteProtocol.FEATURE_VOLUME
            else -> TvRemoteProtocol.FEATURE_KEY
        }
        return activeFeatures and requiredFeatures == requiredFeatures
    }

    private suspend fun readRemoteMessages(socket: SSLSocket) {
        try {
            while (socket === remoteSocket && !socket.isClosed) {
                val message = readDelimited(socket)
                if (message == null) throw EOFException("The TV closed the connection.")
                val response = TvRemoteProtocol.pingResponse(message)
                if (response != null) {
                    writeMessage(socket, response)
                } else if (TvRemoteProtocol.hasRemoteError(message)) {
                    emitMessage("The TV reported that a remote command could not be handled.")
                } else {
                    val (newImeCounter, newFieldCounter) = TvRemoteProtocol.imeCounters(message)
                    if (newImeCounter != null) imeCounter = newImeCounter
                    if (newFieldCounter != null) fieldCounter = newFieldCounter
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            if (socket === remoteSocket) {
                activeHoldId = null
                holdingJob?.cancel()
                holdingJob = null
                setError("The TV disconnected: ${error.message ?: "connection closed"}")
                socket.close()
                remoteSocket = null
                activeFeatures = 0
            }
        }
    }

    private fun openTlsSocket(
        device: TvDevice,
        port: Int,
        expectedFingerprint: String?,
    ): SSLSocket {
        val trustManager = PinnedTvTrustManager(expectedFingerprint)
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(
            identity.keyManagers(),
            arrayOf<TrustManager>(trustManager),
            SecureRandom(),
        )
        val plainSocket = Socket()
        try {
            plainSocket.connect(InetSocketAddress(device.host, port), CONNECT_TIMEOUT_MS)
            val tlsSocket = sslContext.socketFactory.createSocket(
                plainSocket,
                device.host,
                port,
                true,
            ) as SSLSocket
            tlsSocket.soTimeout = SOCKET_TIMEOUT_MS
            tlsSocket.startHandshake()
            return tlsSocket
        } catch (error: Exception) {
            plainSocket.close()
            throw error
        }
    }

    private fun readPairingUntil(socket: SSLSocket, fieldNumber: Int): ByteArray {
        repeat(MAX_HANDSHAKE_MESSAGES) {
            val message = readDelimited(socket)
                ?: throw EOFException("The TV closed pairing before responding.")
            val status = TvRemoteProtocol.pairingStatus(message)
            if (status != null && status != 200L) {
                throw IOException("TV pairing returned status $status.")
            }
            if (TvRemoteProtocol.hasPairingPayload(message, fieldNumber)) return message
        }
        throw IOException("The TV pairing handshake did not complete.")
    }

    private fun readRemoteUntil(socket: SSLSocket, fieldNumber: Int): ByteArray {
        repeat(MAX_HANDSHAKE_MESSAGES) {
            val message = readDelimited(socket)
                ?: throw EOFException("The TV closed the remote connection.")
            val status = ProtoWire.parse(message).firstOrNull { it.number == 3 }?.varint
            if (status != null && status != 0L) {
                throw IOException("The TV remote handshake returned status $status.")
            }
            if (TvRemoteProtocol.remoteField(message, fieldNumber) != null) return message
        }
        throw IOException("The TV remote handshake did not complete.")
    }

    private fun readDelimited(socket: SSLSocket): ByteArray? =
        ProtoWire.readDelimited(socket.inputStream)

    private fun writeMessage(socket: SSLSocket, message: ByteArray) {
        synchronized(socket) {
            ProtoWire.writeDelimited(socket.outputStream, message)
        }
    }

    private fun closePendingPairing() {
        pendingPairingSocket?.close()
        pendingPairingSocket = null
        pendingPairingDevice = null
        pendingServerKey = null
        pendingCertificateFingerprint = null
    }

    private fun setError(message: String, device: TvDevice? = null) {
        mutableConnectionState.value = TvConnectionState(
            phase = TvConnectionPhase.ERROR,
            device = device ?: mutableConnectionState.value.device,
            message = message,
        )
        emitMessage(message)
    }

    private fun emitMessage(message: String) {
        mutableMessages.tryEmit(message)
    }

    private fun fingerprint(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString(":") { byte -> "%02X".format(byte) }

    private fun resolveNextServiceLocked() {
        if (resolvingService || servicesToResolve.isEmpty()) return
        val serviceInfo = servicesToResolve.removeFirst()
        resolvingService = true
        @Suppress("DEPRECATION")
        nsdManager.resolveService(
            serviceInfo,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    synchronized(nsdLock) {
                        resolvingService = false
                        resolveNextServiceLocked()
                    }
                }

                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    synchronized(nsdLock) {
                        val host = resolved.host?.hostAddress
                        if (!host.isNullOrBlank() && resolved.port > 0) {
                            discoveredByServiceName[resolved.serviceName] = TvDevice(
                                name = resolved.serviceName.removeSuffix("._androidtvremote2._tcp"),
                                host = host,
                                port = resolved.port,
                            )
                            publishDiscoveredDevicesLocked()
                        }
                        resolvingService = false
                        resolveNextServiceLocked()
                    }
                }
            },
        )
    }

    private fun publishDiscoveredDevicesLocked() {
        mutableDiscoveredDevices.value = discoveredByServiceName.values
            .distinctBy(TvDevice::id)
            .sortedBy(TvDevice::name)
    }

    private sealed interface OutgoingAction {
        data class Command(val command: RemoteCommand) : OutgoingAction
        data class HeldCommand(
            val command: RemoteCommand,
            val holdId: Long,
            val isInitialPress: Boolean,
        ) : OutgoingAction
    }

    private class PinnedTvTrustManager(
        private val expectedFingerprint: String?,
    ) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val certificate = chain?.firstOrNull()
                ?: throw CertificateException("The TV did not provide a certificate.")
            if (expectedFingerprint != null) {
                val received = MessageDigest.getInstance("SHA-256")
                    .digest(certificate.encoded)
                    .joinToString(":") { byte -> "%02X".format(byte) }
                if (!received.equals(expectedFingerprint, ignoreCase = true)) {
                    throw CertificateException("The TV certificate changed after pairing.")
                }
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private companion object {
        const val SERVICE_TYPE_BASE = "_androidtvremote2._tcp"
        const val SERVICE_TYPE = "$SERVICE_TYPE_BASE."
        const val CONNECT_TIMEOUT_MS = 6_000
        const val SOCKET_TIMEOUT_MS = 12_000
        const val PAIRING_PIN_TIMEOUT_MS = 120_000
        const val HOLD_INITIAL_DELAY_MS = 420L
        const val HOLD_REPEAT_INTERVAL_MS = 145L
        const val MAX_HANDSHAKE_MESSAGES = 12
        const val SUPPORTED_FEATURES =
            TvRemoteProtocol.FEATURE_PING or
                TvRemoteProtocol.FEATURE_KEY or
                TvRemoteProtocol.FEATURE_IME or
                TvRemoteProtocol.FEATURE_POWER or
                TvRemoteProtocol.FEATURE_VOLUME or
                TvRemoteProtocol.FEATURE_APP_LINK
    }
}
