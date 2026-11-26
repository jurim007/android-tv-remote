package com.jurim007.androidtvremote.data.webos

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.jurim007.androidtvremote.data.protocol.TvRemoteProtocol
import com.jurim007.androidtvremote.domain.RemoteCommand
import com.jurim007.androidtvremote.domain.RemoteController
import com.jurim007.androidtvremote.domain.TvAppCatalog
import com.jurim007.androidtvremote.domain.TvConnectionPhase
import com.jurim007.androidtvremote.domain.TvConnectionState
import com.jurim007.androidtvremote.domain.TvDevice
import com.jurim007.androidtvremote.domain.TvInstalledApp
import com.jurim007.androidtvremote.domain.TvPlatform
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Controls LG webOS TVs, including webOS Hub TVs from other brands, over LG's SSAP API. */
class WebOsRemoteController(context: Context) : RemoteController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val discovery = WebOsDiscovery(context, scope)
    private val outgoing = Channel<OutgoingAction>(Channel.BUFFERED)

    private val mutableConnectionState = MutableStateFlow(TvConnectionState())
    override val connectionState: StateFlow<TvConnectionState> = mutableConnectionState.asStateFlow()
    override val discoveredDevices: StateFlow<List<TvDevice>> = discovery.devices
    private val mutableMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val messages: SharedFlow<String> = mutableMessages.asSharedFlow()

    private val lock = Any()
    private var session: Session? = null
    private val connectingSockets = mutableListOf<WebOsSocket>()
    private var connectionGeneration = 0L
    private var reconnectJob: Job? = null
    private var holdingJob: Job? = null
    @Volatile
    private var activeHoldId: Long? = null
    @Volatile
    private var lastDevice: TvDevice? = null
    @Volatile
    private var powerOffRequestedAt = 0L
    /** Whether [reconnectJob] is a Wake-on-LAN attempt rather than a plain reconnect. */
    @Volatile
    private var waking = false

    @Volatile
    private var pairingSocket: WebOsSocket? = null
    @Volatile
    private var pairingGeneration = 0L

    init {
        scope.launch {
            for (action in outgoing) process(action)
        }
    }

    override fun startDiscovery() {
        discovery.start()
        mutableConnectionState.update { state ->
            when (state.phase) {
                TvConnectionPhase.DISCONNECTED, TvConnectionPhase.ERROR -> TvConnectionState(TvConnectionPhase.DISCOVERING)
                else -> state
            }
        }
    }

    override fun stopDiscovery() {
        discovery.stop()
        mutableConnectionState.update { state ->
            if (state.phase == TvConnectionPhase.DISCOVERING) TvConnectionState() else state
        }
    }

    override suspend fun beginPairing(device: TvDevice): TvDevice = withContext(Dispatchers.IO) {
        stopDiscovery()
        disconnect()
        closePairing()
        val attempt = ++pairingGeneration
        val target = device.copy(port = TvDevice.WEBOS_PORT, platform = TvPlatform.WEBOS)
        mutableConnectionState.value = TvConnectionState(
            phase = TvConnectionPhase.PAIRING,
            device = target,
            message = "Connecting to ${target.name}…",
        )
        val trustManager = WebOsTrustManager(expectedFingerprint = null)
        val socket = WebOsSocket(webOsHttpClient(WebOsSocket.baseClient, trustManager), url(target))
        pairingSocket = socket
        try {
            socket.awaitOpen()
            // Newer firmware expects the system information request before registration.
            requestSystemInfo(socket)
            val clientKey = register(socket, clientKey = null) {
                if (attempt == pairingGeneration) {
                    mutableConnectionState.value = TvConnectionState(
                        phase = TvConnectionPhase.PAIRING,
                        device = target,
                        message = "Accept the connection request on ${target.name} using the TV’s remote.",
                    )
                }
            }
            val name = readDeviceName(socket) ?: target.name
            val paired = target.copy(
                name = name,
                serverCertificateFingerprint = trustManager.receivedFingerprint
                    ?: throw IOException("The TV certificate was not received."),
                clientKey = clientKey,
                macAddresses = readMacAddresses(socket),
            )
            if (attempt != pairingGeneration) throw CancellationException("TV pairing was cancelled.")
            mutableConnectionState.value = TvConnectionState(
                phase = TvConnectionPhase.DISCONNECTED,
                device = paired,
                message = "TV pairing completed.",
            )
            paired
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (attempt != pairingGeneration) throw CancellationException("TV pairing was cancelled.")
            setError("Could not pair with ${target.name}: ${error.message ?: "connection failed"}", target)
            throw error
        } finally {
            socket.close()
            if (pairingSocket === socket) pairingSocket = null
        }
    }

    override suspend fun completePairing(pin: String, deviceName: String): TvDevice =
        throw IllegalStateException("webOS TVs are paired by accepting the request on the TV.")

    override suspend fun cancelPendingPairing() {
        pairingGeneration += 1
        closePairing()
        if (mutableConnectionState.value.phase == TvConnectionPhase.PAIRING) {
            mutableConnectionState.value = TvConnectionState()
        }
    }

    private fun closePairing() {
        pairingSocket?.close()
        pairingSocket = null
    }

    override suspend fun connect(device: TvDevice) = withContext(Dispatchers.IO) {
        requireNotNull(device.clientKey) { "Pair this TV before connecting." }
        lastDevice = device
        val (generation, sockets) = synchronized(lock) {
            val sockets = resetLocked()
            mutableConnectionState.value = TvConnectionState(
                phase = TvConnectionPhase.CONNECTING,
                device = device,
                message = "Connecting to ${device.name}…",
            )
            connectionGeneration to sockets
        }
        sockets.forEach(WebOsSocket::close)
        try {
            openSession(device, generation)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            synchronized(lock) {
                if (generation != connectionGeneration) return@withContext
                Log.w(TAG, "Could not connect to ${device.name}", error)
                if (error.requiresNewPairing()) {
                    setPairingRequired(device)
                } else {
                    setError("Could not connect to ${device.name}: ${error.message ?: "connection failed"}", device)
                }
            }
            throw error
        }
    }

    private suspend fun openSession(device: TvDevice, generation: Long) {
        val client = webOsHttpClient(WebOsSocket.baseClient, WebOsTrustManager(device.serverCertificateFingerprint))
        val main = WebOsSocket(client, url(device), ::handleConnectionLoss)
        var input: WebOsSocket? = null
        var connected = false
        try {
            synchronized(lock) {
                if (generation != connectionGeneration) return
                connectingSockets += main
            }
            main.awaitOpen()
            requestSystemInfo(main)
            register(main, device.clientKey) {}
            val socketPath = main.request(POINTER_INPUT_SOCKET).optString("socketPath")
                .ifBlank { throw IOException("The TV did not open its remote input.") }
            val inputSocket = WebOsSocket(client, inputUrl(socketPath, device), ::handleConnectionLoss)
            input = inputSocket
            synchronized(lock) {
                if (generation != connectionGeneration) return
                connectingSockets += inputSocket
            }
            inputSocket.awaitOpen()
            val apps = readInstalledApps(main)
            synchronized(lock) {
                connectingSockets -= main
                connectingSockets -= inputSocket
                if (generation != connectionGeneration) return
                session = Session(device, main, inputSocket, apps)
                connected = true
                reconnectJob = null
                Log.i(TAG, "Connected to ${device.name}")
                mutableConnectionState.value = TvConnectionState(
                    phase = TvConnectionPhase.CONNECTED,
                    device = device,
                    supportedFeatures = SUPPORTED_FEATURES,
                    installedApps = apps,
                )
            }
        } finally {
            if (!connected) {
                synchronized(lock) {
                    connectingSockets -= main
                    input?.let { connectingSockets -= it }
                }
                main.close()
                input?.close()
            }
        }
    }

    override suspend fun disconnect() {
        val sockets = synchronized(lock) { resetLocked() }
        sockets.forEach(WebOsSocket::close)
    }

    private fun resetLocked(): List<WebOsSocket> {
        connectionGeneration += 1
        waking = false
        clearHoldLocked()
        reconnectJob?.cancel()
        reconnectJob = null
        val sockets = connectingSockets.toList() + listOfNotNull(session?.main, session?.input)
        connectingSockets.clear()
        session = null
        mutableConnectionState.value = TvConnectionState()
        return sockets
    }

    override fun checkConnection() {
        val current = synchronized(lock) {
            session ?: run {
                val device = mutableConnectionState.value.device
                if (device != null && !waking && reconnectJob?.isActive == true) {
                    reconnectJob?.cancel()
                    val generation = connectionGeneration
                    reconnectJob = scope.launch { reconnect(device, generation, MAX_RECONNECT_ATTEMPTS, immediate = true) }
                }
                return
            }
        }
        scope.launch {
            // Any reply, even an error, shows the connection still works.
            val alive = try {
                current.main.request(POWER_STATE, timeoutMs = LIVENESS_TIMEOUT_MS)
                true
            } catch (error: WebOsCommandException) {
                true
            } catch (error: IOException) {
                false
            }
            if (!alive) handleConnectionLoss(current.main, IOException("The TV stopped responding."), immediate = true)
        }
    }

    private fun handleConnectionLoss(socket: WebOsSocket, error: Throwable, immediate: Boolean = false) {
        val lost = synchronized(lock) {
            val current = session ?: return
            if (current.main !== socket && current.input !== socket) return
            session = null
            clearHoldLocked()
            val device = current.device
            Log.w(TAG, "Connection to ${device.name} dropped", error)
            when {
                error.requiresNewPairing() -> setPairingRequired(device)
                SystemClock.elapsedRealtime() - powerOffRequestedAt < POWER_OFF_GRACE_MS -> {
                    connectionGeneration += 1
                    mutableConnectionState.value = TvConnectionState(
                        phase = TvConnectionPhase.DISCONNECTED,
                        device = device,
                        message = "${device.name} is off.",
                    )
                }
                else -> {
                    val generation = connectionGeneration
                    mutableConnectionState.value = TvConnectionState(
                        phase = TvConnectionPhase.CONNECTING,
                        device = device,
                        message = "Reconnecting to ${device.name}…",
                    )
                    reconnectJob = scope.launch { reconnect(device, generation, MAX_RECONNECT_ATTEMPTS, immediate) }
                }
            }
            current
        }
        lost.main.close()
        lost.input.close()
    }

    private suspend fun reconnect(device: TvDevice, generation: Long, attempts: Int, immediate: Boolean = false) {
        for (attempt in 1..attempts) {
            if (!immediate || attempt > 1) delay(RECONNECT_DELAY_MS * minOf(attempt, 3))
            try {
                openSession(device, generation)
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Reconnect attempt $attempt failed", error)
                if (error.requiresNewPairing()) {
                    synchronized(lock) {
                        if (generation == connectionGeneration) {
                            reconnectJob = null
                            setPairingRequired(device)
                        }
                    }
                    return
                }
            }
        }
        synchronized(lock) {
            if (generation == connectionGeneration) {
                reconnectJob = null
                setError("Could not reconnect to ${device.name}.", device)
            }
        }
    }

    override fun send(command: RemoteCommand) {
        val current = session
        if (current == null) {
            val device = lastDevice
            if (command == RemoteCommand.Power && device != null) {
                wake(device)
            } else {
                emitMessage("Connect to a paired TV before sending commands.")
            }
            return
        }
        if (outgoing.trySend(OutgoingAction(current, command)).isFailure) {
            emitMessage("The command could not be queued.")
        }
    }

    override fun startHolding(command: RemoteCommand, holdId: Long) {
        if (buttonName(command) == null) {
            emitMessage("That action cannot be held.")
            return
        }
        synchronized(lock) {
            val current = session ?: run {
                emitMessage("Connect to a paired TV before sending commands.")
                return
            }
            clearHoldLocked()
            if (outgoing.trySend(OutgoingAction(current, command)).isFailure) {
                emitMessage("The button press could not be queued.")
                return
            }
            activeHoldId = holdId
            holdingJob = scope.launch {
                delay(HOLD_INITIAL_DELAY_MS)
                while (activeHoldId == holdId) {
                    outgoing.trySend(OutgoingAction(current, command, holdId))
                    delay(HOLD_REPEAT_INTERVAL_MS)
                }
            }
        }
    }

    override fun stopHolding(holdId: Long) {
        synchronized(lock) {
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
        closePairing()
        discovery.stop()
        val sockets = synchronized(lock) {
            scope.cancel()
            resetLocked()
        }
        sockets.forEach(WebOsSocket::close)
        outgoing.close()
    }

    private suspend fun process(action: OutgoingAction) {
        synchronized(lock) {
            if (action.session !== session) return
            if (action.repeatHoldId != null && action.repeatHoldId != activeHoldId) return
        }
        try {
            perform(action.session, action.command)
        } catch (error: CancellationException) {
            throw error
        } catch (error: WebOsCommandException) {
            Log.w(TAG, "The TV rejected ${action.command}: ${error.message}")
            emitMessage(rejectionMessage(action.command, action.session.device))
        } catch (error: IOException) {
            // A dropped connection is reported by the socket listener, which also reconnects.
            Log.w(TAG, "Could not send ${action.command}", error)
        }
    }

    private suspend fun perform(session: Session, command: RemoteCommand) {
        buttonName(command)?.let { name ->
            session.input.sendText("type:button\nname:$name\n\n")
            return
        }
        when (command) {
            RemoteCommand.Power -> if (!turnOnFromStandby(session)) turnOff(session)
            RemoteCommand.Source -> try {
                session.main.request(LAUNCH, JSONObject().put("id", INPUT_PICKER_APP))
            } catch (error: WebOsCommandException) {
                // Some webOS versions don't expose the input picker as an app.
                Log.d(TAG, "Input picker app unavailable, asking the surface manager", error)
                lunaRequest(session, SHOW_INPUT_PICKER)
            }
            RemoteCommand.Delete -> if (canType(session)) {
                session.main.request(DELETE_CHARACTERS, JSONObject().put("count", 1))
            }
            is RemoteCommand.SendText -> if (canType(session)) insertText(session, command.text)
            is RemoteCommand.Enter -> if (canType(session)) {
                if (command.text.isNotBlank()) insertText(session, command.text)
                session.main.request(SEND_ENTER)
            }
            is RemoteCommand.LaunchApp -> {
                val appId = TvAppCatalog.resolveWebOsId(command.packageName, session.installedApps)
                session.main.request(LAUNCH, JSONObject().put("id", appId))
            }
            else -> emitMessage("This TV does not support that remote command.")
        }
    }

    /** Sends the power-off request, then lets go of the TV so the next press wakes it. */
    private fun turnOff(session: Session) {
        powerOffRequestedAt = SystemClock.elapsedRealtime()
        // The TV often shuts down before it answers, so don't wait for a reply.
        session.main.sendText(
            JSONObject().put("id", "power_off").put("type", "request").put("uri", "ssap://$TURN_OFF").toString(),
        )
        val released = synchronized(lock) {
            if (this.session !== session) return
            this.session = null
            clearHoldLocked()
            connectionGeneration += 1
            mutableConnectionState.value = TvConnectionState(
                phase = TvConnectionPhase.DISCONNECTED,
                device = session.device,
                message = "${session.device.name} is off.",
            )
            true
        }
        if (released) {
            session.main.closeGracefully()
            session.input.closeGracefully()
        }
    }

    /**
     * Turns the TV on when it answers while in standby, as TVs with Quick Start+ do.
     * Returns false when the TV is already on or can't report its power state.
     */
    private suspend fun turnOnFromStandby(session: Session): Boolean = when (readPower(session)) {
        TvPower.SCREEN_OFF -> {
            session.main.request(TURN_ON_SCREEN)
            true
        }
        TvPower.STANDBY -> {
            session.main.request(TURN_ON)
            true
        }
        TvPower.ON, TvPower.SHUTTING_DOWN, TvPower.UNKNOWN -> false
    }

    /**
     * YouTube on webOS draws its own keyboard, which ignores the TV's text input service, so
     * typing there would silently do nothing.
     */
    private suspend fun canType(session: Session): Boolean {
        val appId = try {
            session.main.request(FOREGROUND_APP).optString("appId")
        } catch (error: IOException) {
            return true
        }
        if (appId.startsWith(YOUTUBE_APP_PREFIX)) {
            emitMessage("YouTube on webOS uses its own keyboard, which can’t receive typing from your phone. Use the D-pad to type.")
            return false
        }
        return true
    }

    /**
     * Calls an internal webOS service that SSAP doesn't expose, through an alert whose close
     * action makes the call. The alert closes immediately.
     */
    private suspend fun lunaRequest(session: Session, uri: String, params: JSONObject = JSONObject()) {
        val action = JSONObject().put("uri", uri).put("params", params)
        val alert = session.main.request(
            CREATE_ALERT,
            JSONObject()
                .put("message", " ")
                .put("buttons", JSONArray().put(JSONObject().put("label", "").put("onClick", uri).put("params", params)))
                .put("onclose", action)
                .put("onfail", action),
        )
        val alertId = alert.optString("alertId").ifBlank { throw WebOsCommandException("The TV did not create the alert.") }
        session.main.request(CLOSE_ALERT, JSONObject().put("alertId", alertId))
    }

    private suspend fun insertText(session: Session, text: String) {
        session.main.request(INSERT_TEXT, JSONObject().put("text", text).put("replace", false))
    }

    private fun rejectionMessage(command: RemoteCommand, device: TvDevice): String = when (command) {
        is RemoteCommand.LaunchApp -> "That app isn’t installed on ${device.name}, or the TV couldn’t open it."
        RemoteCommand.Source -> "${device.name} didn’t open its input list."
        RemoteCommand.Delete, is RemoteCommand.SendText, is RemoteCommand.Enter ->
            "Select a text field on the TV first."
        else -> "The TV reported that a remote command could not be handled."
    }

    /** Sends a Wake-on-LAN packet and reconnects. Needs the TV's "Turn on via Wi-Fi" setting. */
    private fun wake(device: TvDevice) {
        if (device.macAddresses.isEmpty()) {
            emitMessage("Turn the TV on with its own remote, then connect again.")
            return
        }
        val generation = synchronized(lock) {
            val sockets = resetLocked()
            sockets.forEach(WebOsSocket::close)
            mutableConnectionState.value = TvConnectionState(
                phase = TvConnectionPhase.CONNECTING,
                device = device,
                message = "Turning on ${device.name}…",
            )
            waking = true
            connectionGeneration
        }
        powerOffRequestedAt = 0L
        val job = scope.launch {
            try {
                wakeAndConnect(device, generation)
            } finally {
                synchronized(lock) { if (generation == connectionGeneration) waking = false }
            }
        }
        synchronized(lock) {
            if (generation == connectionGeneration) reconnectJob = job else job.cancel()
        }
    }

    /**
     * Sends Wake-on-LAN on every attempt, since a TV that is still shutting down ignores it,
     * and lets go of a TV that answers while it is still shutting down.
     */
    private suspend fun wakeAndConnect(device: TvDevice, generation: Long) {
        repeat(WAKE_ATTEMPTS) { attempt ->
            try {
                sendWakePackets(device)
            } catch (error: IOException) {
                Log.w(TAG, "Could not send Wake-on-LAN", error)
            }
            delay(WAKE_ATTEMPT_DELAY_MS)
            try {
                openSession(device, generation)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.d(TAG, "Wake attempt ${attempt + 1} did not connect", error)
                if (error.requiresNewPairing()) {
                    synchronized(lock) {
                        if (generation == connectionGeneration) {
                            reconnectJob = null
                            setPairingRequired(device)
                        }
                    }
                    return
                }
                return@repeat
            }
            val current = synchronized(lock) { session?.takeIf { generation == connectionGeneration } } ?: return
            when (readPower(current)) {
                TvPower.SHUTTING_DOWN -> releaseWhileWaking(current, generation)
                TvPower.STANDBY -> return requestQuietly(current, TURN_ON)
                TvPower.SCREEN_OFF -> return requestQuietly(current, TURN_ON_SCREEN)
                TvPower.ON, TvPower.UNKNOWN -> return
            }
        }
        synchronized(lock) {
            if (generation == connectionGeneration) {
                reconnectJob = null
                setError("Couldn’t turn on ${device.name}. Check that Turn on via Wi-Fi is enabled in the TV’s settings.", device)
            }
        }
    }

    private fun releaseWhileWaking(current: Session, generation: Long) {
        synchronized(lock) {
            if (session !== current || generation != connectionGeneration) return
            session = null
            mutableConnectionState.value = TvConnectionState(
                phase = TvConnectionPhase.CONNECTING,
                device = current.device,
                message = "Turning on ${current.device.name}…",
            )
        }
        current.main.close()
        current.input.close()
    }

    private suspend fun requestQuietly(session: Session, uri: String) {
        try {
            session.main.request(uri)
        } catch (error: IOException) {
            Log.w(TAG, "The TV did not accept $uri", error)
        }
    }

    private suspend fun readPower(session: Session): TvPower = try {
        classifyPower(session.main.request(POWER_STATE))
    } catch (error: IOException) {
        Log.d(TAG, "The TV did not report its power state", error)
        TvPower.UNKNOWN
    }

    private fun sendWakePackets(device: TvDevice) {
        val targets = buildList {
            add(InetAddress.getByName("255.255.255.255"))
            IPV4.matchEntire(device.host)?.let { add(InetAddress.getByName(device.host.substringBeforeLast('.') + ".255")) }
        }
        DatagramSocket().use { socket ->
            socket.broadcast = true
            device.macAddresses.forEach { mac ->
                val packet = magicPacket(mac) ?: return@forEach
                repeat(3) {
                    targets.forEach { target ->
                        listOf(7, 9).forEach { port -> socket.send(DatagramPacket(packet, packet.size, target, port)) }
                    }
                }
            }
        }
    }

    private suspend fun register(socket: WebOsSocket, clientKey: String?, onPrompt: () -> Unit): String {
        var signed = true
        while (true) {
            val channel = socket.exchange(WebOsRegistration.MESSAGE_ID, WebOsRegistration.message(clientKey, signed))
            try {
                var reply = WebOsSocket.receive(channel, REGISTER_TIMEOUT_MS)
                if (reply.optString("type") == "error" && signed) {
                    signed = false
                    continue
                }
                if (reply.optString("type") == "response" &&
                    reply.optJSONObject("payload")?.optString("pairingType") == "PROMPT"
                ) {
                    // A saved key that still needs a prompt is no longer accepted by the TV.
                    if (clientKey != null) throw WebOsPairingRequiredException()
                    onPrompt()
                    reply = WebOsSocket.receive(channel, PROMPT_TIMEOUT_MS)
                }
                when (reply.optString("type")) {
                    "registered" -> return reply.optJSONObject("payload")?.optString("client-key")
                        ?.takeIf(String::isNotBlank) ?: clientKey
                        ?: throw IOException("The TV did not return a pairing key.")
                    "error" -> throw registrationError(reply.optString("error"))
                    else -> throw IOException("The TV sent an unexpected pairing reply.")
                }
            } finally {
                socket.release(WebOsRegistration.MESSAGE_ID)
            }
        }
    }

    private fun registrationError(error: String): IOException {
        val declined = listOf("denied", "reject", "cancel", "403").any { error.contains(it, ignoreCase = true) }
        return IOException(if (declined) "The TV declined the connection." else error.ifBlank { "Pairing failed." })
    }

    private suspend fun requestSystemInfo(socket: WebOsSocket) {
        try {
            socket.request(SYSTEM_INFO)
        } catch (error: WebOsCommandException) {
            Log.d(TAG, "The TV did not share its system information", error)
        }
    }

    private suspend fun readDeviceName(socket: WebOsSocket): String? = try {
        socket.request(
            SYSTEM_SETTINGS,
            JSONObject().put("category", "network").put("keys", JSONArray().put("deviceName")),
        ).optJSONObject("settings")?.optString("deviceName")?.trim()?.takeIf(String::isNotEmpty)
    } catch (error: IOException) {
        null
    }

    private suspend fun readMacAddresses(socket: WebOsSocket): List<String> = try {
        collectMacAddresses(socket.request(CONNECTION_INFO))
    } catch (error: IOException) {
        Log.d(TAG, "The TV did not report its network adapters", error)
        emptyList()
    }

    private suspend fun readInstalledApps(socket: WebOsSocket): List<TvInstalledApp>? = try {
        parseLaunchPoints(socket.request(LIST_LAUNCH_POINTS))
    } catch (error: IOException) {
        Log.w(TAG, "The TV did not list its apps", error)
        null
    }

    private fun setPairingRequired(device: TvDevice) {
        setError("Pair with ${device.name} again to reconnect.", device, needsPairing = true)
    }

    private fun setError(message: String, device: TvDevice? = null, needsPairing: Boolean = false) {
        mutableConnectionState.value = TvConnectionState(
            phase = TvConnectionPhase.ERROR,
            device = device ?: mutableConnectionState.value.device,
            message = message,
            needsPairing = needsPairing,
        )
        emitMessage(message)
    }

    private fun emitMessage(message: String) {
        mutableMessages.tryEmit(message)
    }

    private fun Throwable.requiresNewPairing(): Boolean = generateSequence(this) { it.cause }.any {
        it is WebOsPairingRequiredException || it is WebOsCertificateChangedException
    }

    private class Session(
        val device: TvDevice,
        val main: WebOsSocket,
        val input: WebOsSocket,
        val installedApps: List<TvInstalledApp>?,
    )

    private data class OutgoingAction(
        val session: Session,
        val command: RemoteCommand,
        val repeatHoldId: Long? = null,
    )

    private class WebOsPairingRequiredException : IOException("The TV no longer accepts this remote.")

    internal companion object {
        private const val TAG = "WebOsRemote"
        private const val SYSTEM_INFO = "system/getSystemInfo"
        private const val SYSTEM_SETTINGS = "settings/getSystemSettings"
        private const val CONNECTION_INFO = "com.webos.service.connectionmanager/getinfo"
        private const val POINTER_INPUT_SOCKET = "com.webos.service.networkinput/getPointerInputSocket"
        private const val LIST_LAUNCH_POINTS = "com.webos.applicationManager/listLaunchPoints"
        private const val LAUNCH = "system.launcher/launch"
        private const val TURN_OFF = "system/turnOff"
        private const val INSERT_TEXT = "com.webos.service.ime/insertText"
        private const val DELETE_CHARACTERS = "com.webos.service.ime/deleteCharacters"
        private const val SEND_ENTER = "com.webos.service.ime/sendEnterKey"
        private const val INPUT_PICKER_APP = "com.webos.app.inputpicker"
        private const val SHOW_INPUT_PICKER = "luna://com.webos.surfacemanager/showInputPicker"
        private const val CREATE_ALERT = "system.notifications/createAlert"
        private const val CLOSE_ALERT = "system.notifications/closeAlert"
        private const val POWER_STATE = "com.webos.service.tvpower/power/getPowerState"
        private const val TURN_ON = "system/turnOn"
        private const val TURN_ON_SCREEN = "com.webos.service.tvpower/power/turnOnScreen"
        private const val FOREGROUND_APP = "com.webos.applicationManager/getForegroundAppInfo"
        private const val YOUTUBE_APP_PREFIX = "youtube.leanback"

        private const val REGISTER_TIMEOUT_MS = 10_000L
        private const val PROMPT_TIMEOUT_MS = 90_000L
        private const val RECONNECT_DELAY_MS = 2_000L
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val WAKE_ATTEMPT_DELAY_MS = 3_000L
        private const val WAKE_ATTEMPTS = 10
        private const val POWER_OFF_GRACE_MS = 15_000L
        private const val LIVENESS_TIMEOUT_MS = 2_500L
        private const val HOLD_INITIAL_DELAY_MS = 420L
        private const val HOLD_REPEAT_INTERVAL_MS = 145L

        const val SUPPORTED_FEATURES =
            TvRemoteProtocol.FEATURE_KEY or
                TvRemoteProtocol.FEATURE_IME or
                TvRemoteProtocol.FEATURE_POWER or
                TvRemoteProtocol.FEATURE_VOLUME or
                TvRemoteProtocol.FEATURE_APP_LINK

        private val IPV4 = Regex("""\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}""")
        private val MAC_ADDRESS = Regex("""[0-9A-Fa-f]{2}([:-][0-9A-Fa-f]{2}){5}""")

        fun url(device: TvDevice): String = "wss://${hostForUrl(device.host)}:${device.port}"

        /** Uses the TV's pointer socket path, keeping it on the encrypted port. */
        fun inputUrl(socketPath: String, device: TvDevice): String {
            val uri = URI(socketPath)
            if (uri.scheme.equals("wss", ignoreCase = true)) return socketPath
            val path = (uri.rawPath ?: "") + (uri.rawQuery?.let { "?$it" } ?: "")
            return "wss://${hostForUrl(uri.host ?: device.host)}:${device.port}$path"
        }

        private fun hostForUrl(host: String) = if (':' in host && !host.startsWith("[")) "[$host]" else host

        /** Maps remote buttons to the names the TV's pointer input socket accepts. */
        fun buttonName(command: RemoteCommand): String? = when (command) {
            RemoteCommand.Up -> "UP"
            RemoteCommand.Down -> "DOWN"
            RemoteCommand.Left -> "LEFT"
            RemoteCommand.Right -> "RIGHT"
            RemoteCommand.Select -> "ENTER"
            RemoteCommand.Back -> "BACK"
            RemoteCommand.Home -> "HOME"
            RemoteCommand.VolumeUp -> "VOLUMEUP"
            RemoteCommand.VolumeDown -> "VOLUMEDOWN"
            RemoteCommand.Mute -> "MUTE"
            RemoteCommand.ChannelUp -> "CHANNELUP"
            RemoteCommand.ChannelDown -> "CHANNELDOWN"
            else -> null
        }

        internal enum class TvPower { ON, SCREEN_OFF, STANDBY, SHUTTING_DOWN, UNKNOWN }

        /** Reads `getPowerState`, whose `processing` field is set while the TV changes state. */
        fun classifyPower(payload: JSONObject): TvPower {
            val state = payload.optString("state")
            val processing = payload.optString("processing")
            return when {
                listOf("Off", "Suspend", "Standby").any { processing.contains(it, ignoreCase = true) } ->
                    TvPower.SHUTTING_DOWN
                state.equals("Power Off", ignoreCase = true) -> TvPower.SHUTTING_DOWN
                state.equals("Screen Off", ignoreCase = true) -> TvPower.SCREEN_OFF
                state.contains("Standby", ignoreCase = true) || state.equals("Suspend", ignoreCase = true) ->
                    TvPower.STANDBY
                state.equals("Active", ignoreCase = true) || state.equals("Screen Saver", ignoreCase = true) ->
                    TvPower.ON
                else -> TvPower.UNKNOWN
            }
        }

        fun parseLaunchPoints(payload: JSONObject): List<TvInstalledApp>? {
            val launchPoints = payload.optJSONArray("launchPoints") ?: return null
            return List(launchPoints.length()) { launchPoints.optJSONObject(it) }
                .mapNotNull { point ->
                    val id = point?.optString("id")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                    TvInstalledApp(id, point.optString("title").ifBlank { id })
                }
                .distinctBy(TvInstalledApp::id)
        }

        fun collectMacAddresses(payload: JSONObject): List<String> {
            val found = linkedSetOf<String>()
            fun visit(value: Any?) {
                when (value) {
                    is JSONObject -> value.keys().forEach { key ->
                        val item = value.opt(key)
                        if (key.equals("macAddress", ignoreCase = true) && item is String && MAC_ADDRESS.matches(item)) {
                            found += item.uppercase().replace('-', ':')
                        } else {
                            visit(item)
                        }
                    }
                    is JSONArray -> for (index in 0 until value.length()) visit(value.opt(index))
                }
            }
            visit(payload)
            return found.filterNot { it == "00:00:00:00:00:00" }
        }

        fun magicPacket(mac: String): ByteArray? {
            if (!MAC_ADDRESS.matches(mac)) return null
            val address = mac.split(':', '-').map { it.toInt(16).toByte() }
            return ByteArray(6) { 0xFF.toByte() } + List(16) { address }.flatten().toByteArray()
        }
    }
}
