package com.jurim007.androidtvremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jurim007.androidtvremote.domain.TvConnectionPhase
import com.jurim007.androidtvremote.domain.TvConnectionState
import com.jurim007.androidtvremote.domain.TvDevice
import com.jurim007.androidtvremote.ui.theme.RemoteBorder
import com.jurim007.androidtvremote.ui.theme.RemoteMuted
import com.jurim007.androidtvremote.ui.theme.RemoteText

@Composable
fun TvConnectionDialog(
    savedTvs: List<TvDevice>,
    discoveredDevices: List<TvDevice>,
    connectionState: TvConnectionState,
    manageOnly: Boolean,
    onStartDiscovery: () -> Unit,
    onStopDiscovery: () -> Unit,
    onBeginPairing: (TvDevice) -> Unit,
    onCompletePairing: (pin: String, name: String) -> Unit,
    onConnect: (TvDevice) -> Unit,
    onRemove: (String) -> Unit,
    onCancelPairing: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pendingName by rememberSaveable { mutableStateOf("") }
    var pin by rememberSaveable { mutableStateOf("") }
    var manualHost by rememberSaveable { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    var pairingStarted by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(manageOnly) {
        if (!manageOnly) onStartDiscovery()
    }
    Dialog(
        onDismissRequest = {
            onStopDiscovery()
            if (connectionState.phase == TvConnectionPhase.PAIRING) onCancelPairing()
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .heightIn(max = 620.dp)
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xF01B1D30), Color(0xF011121E), Color(0xF00D0E19)),
                    ),
                    RoundedCornerShape(28.dp),
                )
                .border(1.dp, RemoteBorder, RoundedCornerShape(28.dp))
                .padding(22.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = if (manageOnly) "Manage TVs" else "Connect to a TV",
                color = RemoteText,
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(8.dp))
            if (manageOnly) {
                if (savedTvs.isEmpty()) {
                    Text("No paired TVs saved.", color = RemoteMuted)
                } else {
                    savedTvs.forEach { device ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(device.name, color = RemoteText)
                                Text(device.host, color = RemoteMuted)
                            }
                            TextButton(onClick = { onConnect(device) }) { Text("Connect") }
                            TextButton(onClick = { onRemove(device.id) }) { Text("Remove") }
                        }
                    }
                }
            } else if (connectionState.phase == TvConnectionPhase.PAIRING) {
                Text(
                    text = if (connectionState.pairingReady) {
                        "Enter the 6-character code shown on the TV."
                    } else {
                        connectionState.message ?: "Starting secure pairing…"
                    },
                    color = RemoteMuted,
                )
                Spacer(Modifier.height(12.dp))
                if (connectionState.pairingReady) {
                    OutlinedTextField(
                        value = pendingName,
                        onValueChange = { pendingName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("TV name") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it.filter(Char::isLetterOrDigit).take(6) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("TV pairing code") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Done,
                        ),
                    )
                } else {
                    CircularProgressIndicator()
                }
                connectionState.message
                    ?.takeIf { connectionState.pairingReady.not() }
                    ?.let { Text(it, color = RemoteMuted) }
            } else if (
                connectionState.phase == TvConnectionPhase.CONNECTING &&
                pairingStarted
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(connectionState.message ?: "Connecting…", color = RemoteMuted)
            } else if (
                connectionState.phase == TvConnectionPhase.CONNECTED &&
                pairingStarted
            ) {
                Text(
                    "Connected to ${connectionState.device?.name ?: "the TV"}.",
                    color = RemoteText,
                )
            } else {
                if (connectionState.phase == TvConnectionPhase.ERROR) {
                    Text(
                        connectionState.message ?: "Could not connect to that TV.",
                        color = Color(0xFFFF9A9A),
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text("Nearby TVs", color = RemoteMuted)
                if (discoveredDevices.isEmpty()) {
                    Text("Searching for Android TV devices…", color = RemoteMuted)
                } else {
                    discoveredDevices.forEach { device ->
                        TvDeviceRow(
                            device = device,
                            actionLabel = "Pair",
                            onClick = {
                                localError = null
                                pairingStarted = true
                                pendingName = device.name
                                pin = ""
                                onBeginPairing(device)
                            },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = manualHost,
                    onValueChange = { manualHost = it; localError = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("TV IP address or host name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                )
                localError?.let { Text(it, color = Color(0xFFFF9A9A)) }
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        onStopDiscovery()
                        if (connectionState.phase == TvConnectionPhase.PAIRING) {
                            onCancelPairing()
                        }
                        onDismiss()
                    },
                ) { Text("Close") }

                if (connectionState.phase == TvConnectionPhase.PAIRING &&
                    connectionState.pairingReady
                ) {
                    Button(
                        onClick = { onCompletePairing(pin, pendingName) },
                        enabled = pin.length == 6,
                    ) { Text("Pair and connect") }
                } else if (
                    connectionState.phase == TvConnectionPhase.CONNECTING &&
                    pairingStarted
                ) {
                    Text("Connecting…", color = RemoteMuted)
                } else if (!manageOnly) {
                    Button(
                        onClick = {
                            val host = manualHost.trim()
                            if (!HOST_PATTERN.matches(host)) {
                                localError = "Enter a valid IP address or host name."
                            } else {
                                localError = null
                                pairingStarted = true
                                pendingName = host
                                pin = ""
                                onBeginPairing(TvDevice(name = host, host = host))
                            }
                        },
                        enabled = manualHost.isNotBlank(),
                    ) { Text("Add by IP") }
                }
            }
        }
    }
}

@Composable
private fun TvDeviceRow(
    device: TvDevice,
    actionLabel: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(device.name, color = RemoteText)
            Text(device.host, color = RemoteMuted)
        }
        OutlinedButton(onClick = onClick) { Text(actionLabel) }
    }
}

private val HOST_PATTERN =
    Regex("(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?")
