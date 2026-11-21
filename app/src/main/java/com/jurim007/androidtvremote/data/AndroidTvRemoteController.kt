package com.jurim007.androidtvremote.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.jurim007.androidtvremote.data.protocol.ProtoWire
import com.jurim007.androidtvremote.data.protocol.TvRemoteProtocol
import com.jurim007.androidtvremote.data.protocol.TvImeField
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
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class AndroidTvRemoteController(context: Context) : RemoteController {
    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(NsdManager::class.java)
    private val identity = AndroidTvIdentity(appContext)
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

    private val connectionLock = Any()
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
    private var connectingSocket: SSLSocket? = null
    private var readerJob: Job? = null
    private var voiceRequest: VoiceRequest? = null
    private var reconnectJob: Job? = null
    private var holdingJob: Job? = null
    @Volatile
    private var activeFeatures = 0
    @Volatile
    private var activeHoldId: Long? = null
    @Volatile
    private var pairingGeneration = 0L
    private var connectionGeneration = 0L
    @Volatile
    private var imeField: TvImeField? = null

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
                    mutableConnectionState.update { state ->
                        when (state.phase) {
                            TvConnectionPhase.DISCONNECTED, TvConnectionPhase.ERROR ->
                                TvConnectionState(TvConnectionPhase.DISCOVERING)
                            else -> state
                        }
                    }
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    synchronized(nsdLock) { discoveryListener = null }
                    reportDiscoveryFailure("TV discovery could not start (error $errorCode).")
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
                reportDiscoveryFailure("TV discovery failed: ${error.message ?: "unknown error"}")
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
        mutableConnectionState.update { state ->
            if (state.phase == TvConnectionPhase.DISCOVERING) TvConnectionState() else state
        }
    }

    private fun reportDiscoveryFailure(message: String) {
        mutableConnectionState.update { state ->
            when (state.phase) {
                TvConnectionPhase.DISCONNECTED, TvConnectionPhase.DISCOVERING, TvConnectionPhase.ERROR ->
                    state.copy(phase = TvConnectionPhase.ERROR, message = message)
                else -> state
            }
        }
        emitMessage(message)
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
            val certificate = socket.session.peerCertificates.firstOrNull() as? X509Certificate
                ?: throw CertificateException("The TV did not provide an X.509 certificate.")
            val serverKey = certificate.publicKey as? RSAPublicKey
                ?: throw CertificateException("The TV certificate does not contain an RSA key.")

            pendingPairingSocket = socket
            pendingPairingDevice = device
            pendingServerKey = serverKey
            pendingCertificateFingerprint = fingerprint(certificate)

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
                    port = if (originalDevice.port > 0 && originalDevice.port != TvDevice.PAIRING_PORT) {
                        originalDevice.port
                    } else {
                        TvDevice.REMOTE_PORT
                    },
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
        requireNotNull(device.serverCertificateFingerprint) { "Pair this TV before connecting." }
        val (generation, sockets) = synchronized(connectionLock) {
            val sockets = resetConnectionLocked()
            mutableConnectionState.value = TvConnectionState(
                phase = TvConnectionPhase.CONNECTING,
                device = device,
                message = "Connecting to ${device.name}…",
            )
            connectionGeneration to sockets
        }
        sockets.forEach { it.closeQuietly() }

        try {
            openRemoteSession(device, generation)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            synchronized(connectionLock) {
                if (generation != connectionGeneration) return@withContext
                Log.w(TAG, "Could not connect to ${device.name}", error)
                setError("Could not connect to ${device.name}: ${error.message ?: "connection failed"}", device)
            }
            throw error
        }
    }

    private suspend fun openRemoteSession(device: TvDevice, generation: Long) {
        val context = coroutineContext
        context.ensureActive()
        val socket = openTlsSocket(device, device.port, device.serverCertificateFingerprint)
        var connected = false
        try {
            synchronized(connectionLock) {
                if (generation != connectionGeneration) return
                connectingSocket = socket
            }

            var initialImeField: TvImeField? = null
            val trackIme: (ByteArray) -> Unit = {
                initialImeField = TvRemoteProtocol.updateImeField(it, initialImeField)
            }
            val configure = readRemoteUntil(socket, 1, trackIme)
            val advertisedFeatures = TvRemoteProtocol.remoteFeatures(configure)
                ?: throw IOException("The TV did not advertise remote-control capabilities.")
            val negotiatedFeatures = advertisedFeatures and SUPPORTED_FEATURES
            writeMessage(socket, TvRemoteProtocol.remoteConfigure(negotiatedFeatures))

            readRemoteUntil(socket, 2, trackIme)
            writeMessage(socket, TvRemoteProtocol.remoteSetActive(negotiatedFeatures))
            readRemoteUntil(socket, 40, trackIme)

            // The handshake timeout would also expire during normal gaps in TV traffic.
            socket.soTimeout = 0
            synchronized(connectionLock) {
                context.ensureActive()
                if (generation != connectionGeneration) return

                connectingSocket = null
                remoteSocket = socket
                activeFeatures = negotiatedFeatures
                imeField = initialImeField
                connected = true
                reconnectJob = null
                Log.i(TAG, "Connected to ${device.name}")
                mutableConnectionState.value = TvConnectionState(
                    phase = TvConnectionPhase.CONNECTED,
                    device = device,
                    supportedFeatures = negotiatedFeatures,
                )
                readerJob = scope.launch { readRemoteMessages(socket, device) }
            }
        } finally {
            if (!connected) {
                synchronized(connectionLock) {
                    if (connectingSocket === socket) connectingSocket = null
                }
                socket.closeQuietly()
            }
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
        val sockets = synchronized(connectionLock) { resetConnectionLocked() }
        withContext(Dispatchers.IO) {
            sockets.forEach { it.closeQuietly() }
        }
    }

    private fun resetConnectionLocked(): List<SSLSocket> {
        connectionGeneration += 1
        clearVoiceLocked()
        clearHoldLocked()
        reconnectJob?.cancel()
        reconnectJob = null
        readerJob?.cancel()
        readerJob = null
        val sockets = listOfNotNull(connectingSocket, remoteSocket)
        connectingSocket = null
        remoteSocket = null
        activeFeatures = 0
        imeField = null
        mutableConnectionState.value = TvConnectionState()
        return sockets
    }

    internal suspend fun sendMicrophoneAudio(
        stop: Deferred<Unit>,
        onListening: () -> Unit,
        onFinishing: () -> Unit,
    ) =
        withContext(Dispatchers.IO) {
            val (voice, device) = synchronized(connectionLock) {
                val socket = remoteSocket ?: throw IOException("Connect to a TV to use the microphone.")
                if (activeFeatures and TvRemoteProtocol.VOICE_FEATURES != TvRemoteProtocol.VOICE_FEATURES) {
                    throw IOException("This TV does not support voice through the remote.")
                }
                check(voiceRequest == null) { "A microphone session is already active." }
                val request = VoiceRequest(socket)
                voiceRequest = request
                request to checkNotNull(connectionState.value.device)
            }

            var sessionId: Int? = null
            try {
                writeVoiceMessage(voice.socket, TvRemoteProtocol.requestVoice())
                val ready = withTimeoutOrNull(VOICE_RESPONSE_TIMEOUT_MS) {
                    select<Boolean> {
                        voice.sessionId.onAwait { true }
                        stop.onAwait { false }
                        voice.finished.onAwait { false }
                    }
                } ?: throw IOException("The TV did not start voice search.")
                if (!ready) return@withContext
                sessionId = voice.sessionId.await()
                writeVoiceMessage(voice.socket, TvRemoteProtocol.beginVoice(sessionId))
                Log.d(TAG, "TV voice session opened")
                var sentPackets = 0
                if (!stop.isCompleted && !voice.finished.isCompleted) {
                    recordVoice(
                        shouldStop = {
                            stop.isCompleted || voice.finished.isCompleted || voice.speechEnded.isCompleted
                        },
                        onListening = onListening,
                        sendAudio = { samples ->
                            if (!voice.finished.isCompleted && !voice.speechEnded.isCompleted) {
                                voice.hasSentAudio = true
                                writeVoiceMessage(voice.socket, TvRemoteProtocol.voiceAudio(sessionId, samples))
                                sentPackets++
                            }
                        },
                    )
                }
                if (sentPackets > 0 && !voice.finished.isCompleted) {
                    onFinishing()
                    if (!voice.speechEnded.isCompleted) {
                        val silence = ByteArray(VOICE_PACKET_BYTES)
                        writeVoiceMessage(voice.socket, TvRemoteProtocol.voiceAudio(sessionId, silence))
                        withTimeoutOrNull(VOICE_ENDPOINT_TIMEOUT_MS) {
                            select<Unit> {
                                voice.finished.onAwait { }
                                voice.speechEnded.onAwait { }
                            }
                        }
                    }
                    if (voice.speechEnded.isCompleted && !voice.finished.isCompleted) {
                        withTimeoutOrNull(VOICE_RESULT_GRACE_MS) { voice.finished.await() }
                    }
                }
                Log.d(TAG, "TV voice packets sent: $sentPackets, endpoint signaled: ${voice.speechEnded.isCompleted}")
            } catch (error: VoiceWriteException) {
                handleConnectionLoss(voice.socket, device, error)
                throw error
            } finally {
                try {
                    if (sessionId != null && !voice.finished.isCompleted) {
                        writeVoiceMessage(voice.socket, TvRemoteProtocol.endVoice(sessionId))
                    }
                } catch (error: VoiceWriteException) {
                    handleConnectionLoss(voice.socket, device, error)
                } finally {
                    synchronized(connectionLock) {
                        if (voiceRequest === voice) voiceRequest = null
                    }
                }
            }
        }

    private fun writeVoiceMessage(socket: SSLSocket, message: ByteArray) {
        try {
            writeMessage(socket, message)
        } catch (error: IOException) {
            throw VoiceWriteException(error)
        }
    }

    private fun clearVoiceLocked() {
        voiceRequest?.finished?.complete(Unit)
        voiceRequest = null
    }

    private fun handleVoiceMessage(socket: SSLSocket, message: ByteArray): Boolean {
        val begin = TvRemoteProtocol.voiceSessionId(message, 30)
        val speechEnded = TvRemoteProtocol.voiceSessionId(message, 31)
        val end = TvRemoteProtocol.voiceSessionId(message, 32)
        if (begin == null && speechEnded == null && end == null) return false
        synchronized(connectionLock) {
            voiceRequest?.takeIf { it.socket === socket }?.let { voice ->
                if (begin != null && voice.receivedSessionId == null) {
                    voice.receivedSessionId = begin
                    voice.sessionId.complete(begin)
                }
                if (speechEnded != null && voice.hasSentAudio && voice.receivedSessionId == speechEnded) {
                    voice.speechEnded.complete(Unit)
                }
                if (end != null && voice.receivedSessionId == end) voice.finished.complete(Unit)
            }
        }
        return true
    }

    override fun send(command: RemoteCommand) {
        val socket = remoteSocket ?: run {
            emitMessage("Connect to a paired TV before sending commands.")
            return
        }
        if (outgoing.trySend(OutgoingAction(socket, command)).isFailure) {
            emitMessage("The command could not be queued.")
        }
    }

    override fun startHolding(command: RemoteCommand, holdId: Long) {
        if (TvRemoteProtocol.keyCode(command) == null) {
            emitMessage("That action cannot be held.")
            return
        }
        synchronized(connectionLock) {
            val socket = remoteSocket ?: run {
                emitMessage("Connect to a paired TV before sending commands.")
                return
            }
            if (!supportsCommand(command)) {
                emitMessage("This TV does not support that remote command.")
                return
            }

            clearHoldLocked()
            if (outgoing.trySend(OutgoingAction(socket, command)).isFailure) {
                emitMessage("The button press could not be queued.")
                return
            }
            activeHoldId = holdId
            holdingJob = scope.launch {
                delay(HOLD_INITIAL_DELAY_MS)
                var queueOverflowReported = false
                while (activeHoldId == holdId) {
                    val repeat = OutgoingAction(socket, command, holdId)
                    if (outgoing.trySend(repeat).isFailure && !queueOverflowReported) {
                        emitMessage("The TV is not responding quickly; held-command repeats are being skipped.")
                        queueOverflowReported = true
                    }
                    delay(HOLD_REPEAT_INTERVAL_MS)
                }
            }
        }
    }

    override fun stopHolding(holdId: Long) {
        synchronized(connectionLock) {
            if (activeHoldId == holdId) clearHoldLocked()
        }
    }

    private fun clearHoldLocked() {
        activeHoldId = null
        holdingJob?.cancel()
        holdingJob = null
    }

    override fun close() {
        pairingGeneration += 1
        stopDiscovery()
        closePendingPairing()
        val sockets = synchronized(connectionLock) {
            scope.cancel()
            resetConnectionLocked()
        }
        sockets.forEach { it.closeQuietly() }
        outgoing.close()
    }

    private fun processOutgoingAction(action: OutgoingAction) {
        val device = synchronized(connectionLock) {
            if (action.socket !== remoteSocket) return
            if (action.repeatHoldId != null && action.repeatHoldId != activeHoldId) return
            mutableConnectionState.value.device ?: return
        }

        try {
            sendCommand(action.socket, action.command)
        } catch (error: IOException) {
            handleConnectionLoss(action.socket, device, error)
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
                        TvRemoteProtocol.textEdit(command.text, imeField),
                    )
                }
            }

            is RemoteCommand.LaunchApp -> {
                if (activeFeatures and TvRemoteProtocol.FEATURE_APP_LINK == 0) {
                    emitMessage("This TV does not support app links.")
                } else {
                    writeMessage(socket, TvRemoteProtocol.launchApp(command))
                }
            }

            else -> {
                val keyCode = TvRemoteProtocol.keyCode(command) ?: return
                if (!supportsCommand(command)) {
                    emitMessage("This TV does not support that remote command.")
                } else if (command == RemoteCommand.Source) {
                    writeMessage(socket, TvRemoteProtocol.keyCommand(keyCode, TvRemoteProtocol.DIRECTION_START_LONG))
                    writeMessage(socket, TvRemoteProtocol.keyCommand(keyCode, TvRemoteProtocol.DIRECTION_END_LONG))
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

    private fun readRemoteMessages(socket: SSLSocket, device: TvDevice) {
        try {
            while (socket === remoteSocket) {
                val message = readDelimited(socket)
                    ?: throw EOFException("The TV closed the connection.")
                val response = TvRemoteProtocol.pingResponse(message)
                if (response != null) {
                    writeMessage(socket, response)
                } else if (handleVoiceMessage(socket, message)) {
                    continue
                } else if (TvRemoteProtocol.hasRemoteError(message)) {
                    val rejected = TvRemoteProtocol.rejectedRemoteCommand(message)
                    val appLink = rejected?.let { TvRemoteProtocol.remoteField(it, 90) }
                    val uri = appLink?.let { TvRemoteProtocol.remoteField(it, 1)?.toString(Charsets.UTF_8) }
                    val feedback = when {
                        uri?.startsWith("market://launch?id=") == true ->
                            "The TV rejected this package launch. Set up a package-launch handler such as AVA Connect on the TV."
                        appLink != null -> "The TV rejected this app shortcut."
                        else -> "The TV reported that a remote command could not be handled."
                    }
                    Log.w(TAG, feedback)
                    emitMessage(feedback)
                } else {
                    synchronized(connectionLock) {
                        if (socket !== remoteSocket) return
                        imeField = TvRemoteProtocol.updateImeField(message, imeField)
                    }
                }
            }
        } catch (error: IOException) {
            handleConnectionLoss(socket, device, error)
        }
    }

    private fun handleConnectionLoss(socket: SSLSocket, device: TvDevice, error: IOException) {
        synchronized(connectionLock) {
            if (socket !== remoteSocket) return
            remoteSocket = null
            activeFeatures = 0
            imeField = null
            clearVoiceLocked()
            clearHoldLocked()
            readerJob?.cancel()
            readerJob = null
            Log.w(TAG, "Connection to ${device.name} dropped", error)
            val generation = connectionGeneration
            mutableConnectionState.value = TvConnectionState(
                phase = TvConnectionPhase.CONNECTING,
                device = device,
                message = "Reconnecting to ${device.name}…",
            )
            reconnectJob = scope.launch { reconnect(device, generation) }
        }
        socket.closeQuietly()
    }

    private suspend fun reconnect(device: TvDevice, generation: Long) {
        for (attempt in 1..MAX_RECONNECT_ATTEMPTS) {
            delay(RECONNECT_DELAY_MS * attempt)
            try {
                openRemoteSession(device, generation)
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Reconnect attempt $attempt failed", error)
            }
        }
        synchronized(connectionLock) {
            if (generation == connectionGeneration) {
                reconnectJob = null
                setError("Could not reconnect to ${device.name}.", device)
            }
        }
    }

    private fun Socket.closeQuietly() {
        try {
            close()
        } catch (error: IOException) {
            Log.d(TAG, "Socket close failed", error)
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
        var tlsSocket: SSLSocket? = null
        try {
            plainSocket.tcpNoDelay = true
            plainSocket.keepAlive = true
            plainSocket.connect(InetSocketAddress(device.host, port), CONNECT_TIMEOUT_MS)
            val socket = sslContext.socketFactory.createSocket(
                plainSocket,
                device.host,
                port,
                true,
            ) as SSLSocket
            tlsSocket = socket
            socket.soTimeout = SOCKET_TIMEOUT_MS
            socket.startHandshake()
            return socket
        } catch (error: Exception) {
            tlsSocket?.closeQuietly()
            plainSocket.closeQuietly()
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

    private fun readRemoteUntil(
        socket: SSLSocket,
        fieldNumber: Int,
        onMessage: (ByteArray) -> Unit,
    ): ByteArray {
        repeat(MAX_HANDSHAKE_MESSAGES) {
            val message = readDelimited(socket)
                ?: throw EOFException("The TV closed the remote connection.")
            if (TvRemoteProtocol.hasRemoteError(message)) {
                throw IOException("The TV rejected the remote handshake.")
            }
            TvRemoteProtocol.pingResponse(message)?.let { writeMessage(socket, it) }
            onMessage(message)
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

    private data class OutgoingAction(
        val socket: SSLSocket,
        val command: RemoteCommand,
        val repeatHoldId: Long? = null,
    )

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

    private class VoiceRequest(val socket: SSLSocket) {
        val sessionId = CompletableDeferred<Int>()
        val finished = CompletableDeferred<Unit>()
        val speechEnded = CompletableDeferred<Unit>()
        var receivedSessionId: Int? = null
        @Volatile
        var hasSentAudio = false
    }

    private class VoiceWriteException(cause: IOException) : IOException("The TV voice connection was interrupted.", cause)

    private companion object {
        const val TAG = "AndroidTvRemote"
        const val SERVICE_TYPE_BASE = "_androidtvremote2._tcp"
        const val SERVICE_TYPE = "$SERVICE_TYPE_BASE."
        const val CONNECT_TIMEOUT_MS = 6_000
        const val SOCKET_TIMEOUT_MS = 12_000
        const val PAIRING_PIN_TIMEOUT_MS = 120_000
        const val HOLD_INITIAL_DELAY_MS = 420L
        const val HOLD_REPEAT_INTERVAL_MS = 145L
        const val MAX_HANDSHAKE_MESSAGES = 12
        const val RECONNECT_DELAY_MS = 2_000L
        const val MAX_RECONNECT_ATTEMPTS = 5
        const val VOICE_RESPONSE_TIMEOUT_MS = 2_500L
        const val VOICE_ENDPOINT_TIMEOUT_MS = 3_000L
        const val VOICE_RESULT_GRACE_MS = 2_000L
        const val SUPPORTED_FEATURES =
            TvRemoteProtocol.FEATURE_PING or
                TvRemoteProtocol.FEATURE_KEY or
                TvRemoteProtocol.FEATURE_IME or
                TvRemoteProtocol.FEATURE_VOICE or
                TvRemoteProtocol.FEATURE_POWER or
                TvRemoteProtocol.FEATURE_VOLUME or
                TvRemoteProtocol.FEATURE_APP_LINK
    }
}
