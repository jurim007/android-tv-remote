package com.jurim007.androidtvremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import com.jurim007.androidtvremote.domain.tvTypeLabel
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
import androidx.compose.ui.text.input.KeyboardCapitalization
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
    repairDevice: TvDevice?,
    onStartDiscovery: () -> Unit,
    onStopDiscovery: () -> Unit,
    onBeginPairing: (TvDevice) -> Unit,
    onCompletePairing: (pin: String, name: String) -> Unit,
    onConnect: (TvDevice) -> Unit,
    onRename: (deviceId: String, name: String) -> Unit,
    onRemove: (String) -> Unit,
    onCancelPairing: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pendingName by rememberSaveable { mutableStateOf(repairDevice?.name.orEmpty()) }
    var pin by rememberSaveable { mutableStateOf("") }
    var manualHost by rememberSaveable { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    var pairingStarted by rememberSaveable { mutableStateOf(false) }
    var renamingId by rememberSaveable { mutableStateOf<String?>(null) }
    var removingId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(manageOnly, repairDevice?.id) {
        if (repairDevice != null && !pairingStarted) {
            pairingStarted = true
            onBeginPairing(repairDevice)
        } else if (repairDevice == null && !manageOnly) {
            onStartDiscovery()
        }
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
                    Text("Tap a TV to connect to it.", color = RemoteMuted, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    savedTvs.forEach { device ->
                        SavedTvRow(
                            device = device,
                            connectionState = connectionState,
                            onConnect = { onConnect(device) },
                            onRename = { renamingId = device.id },
                            onRemove = { removingId = device.id },
                        )
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
                        onValueChange = { entered ->
                            pin = entered.uppercase().filter { it in "0123456789ABCDEF" }.take(6)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("TV pairing code") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            capitalization = KeyboardCapitalization.Characters,
                            autoCorrectEnabled = false,
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
                    if (repairDevice != null) {
                        TextButton(onClick = {
                            pin = ""
                            onBeginPairing(repairDevice)
                        }) { Text("Retry pairing") }
                    }
                }
                Text("Nearby TVs", color = RemoteMuted)
                if (discoveredDevices.isEmpty()) {
                    Text("Searching for TVs…", color = RemoteMuted)
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
    savedTvs.firstOrNull { it.id == renamingId }?.let { device ->
        RenameTvDialog(device, onDismiss = { renamingId = null }) { name ->
            onRename(device.id, name)
            renamingId = null
        }
    }
    savedTvs.firstOrNull { it.id == removingId }?.let { device ->
        AlertDialog(
            onDismissRequest = { removingId = null },
            title = { Text("Remove ${device.name}?") },
            text = { Text("You’ll need to pair this TV again to use it.") },
            confirmButton = {
                TextButton(onClick = {
                    onRemove(device.id)
                    removingId = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removingId = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SavedTvRow(
    device: TvDevice,
    connectionState: TvConnectionState,
    onConnect: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
) {
    val connected = connectionState.phase == TvConnectionPhase.CONNECTED && connectionState.device?.id == device.id
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClickLabel = "Connect to ${device.name}", onClick = onConnect)
            .padding(start = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = device.name,
                    color = RemoteText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (connected) {
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(8.dp).background(Color(0xFF52D18A), CircleShape))
                }
            }
            Text(
                text = listOfNotNull(tvTypeLabel(device, connectionState), device.host).joinToString(" · "),
                color = RemoteMuted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRename) {
            Icon(Icons.Outlined.Edit, contentDescription = "Rename ${device.name}", tint = RemoteMuted)
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Outlined.Delete, contentDescription = "Remove ${device.name}", tint = RemoteMuted)
        }
    }
}

@Composable
private fun RenameTvDialog(device: TvDevice, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by rememberSaveable(device.id) { mutableStateOf(device.name) }
    val trimmed = name.trim()
    val canSave = trimmed.isNotEmpty() && trimmed != device.name
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename TV") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(MAX_TV_NAME_LENGTH) },
                label = { Text("TV name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { if (canSave) onRename(trimmed) }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onRename(trimmed) }, enabled = canSave) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private const val MAX_TV_NAME_LENGTH = 40

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
