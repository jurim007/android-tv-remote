package com.jurim007.androidtvremote.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.Input
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jurim007.androidtvremote.domain.RemoteCommand
import com.jurim007.androidtvremote.domain.TvAppShortcut
import com.jurim007.androidtvremote.domain.TvConnectionPhase
import com.jurim007.androidtvremote.domain.TvConnectionState
import com.jurim007.androidtvremote.domain.TvDevice
import com.jurim007.androidtvremote.ui.theme.RemoteAccent
import com.jurim007.androidtvremote.ui.theme.RemoteBorder
import com.jurim007.androidtvremote.ui.theme.RemoteMuted
import com.jurim007.androidtvremote.ui.theme.RemoteSurface
import com.jurim007.androidtvremote.ui.theme.RemoteSurfaceHighlight
import com.jurim007.androidtvremote.ui.theme.RemoteText
import kotlin.math.abs

private val SurfaceShape = RoundedCornerShape(34.dp)

@Composable
fun RemoteScreen(
    modifier: Modifier = Modifier,
    viewModel: RemoteViewModel = viewModel(),
) {
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var connectionDialogMode by rememberSaveable { mutableStateOf<String?>(null) }
    val shortcuts by viewModel.shortcuts.collectAsStateWithLifecycle(initialValue = emptyList())
    val touchpadEnabled by viewModel.touchpadEnabled.collectAsStateWithLifecycle(initialValue = false)
    val savedTvs by viewModel.savedTvs.collectAsStateWithLifecycle()
    val selectedTvId by viewModel.selectedTvId.collectAsStateWithLifecycle()
    val discoveredDevices by viewModel.discoveredDevices.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val selectedTv = savedTvs.firstOrNull { it.id == selectedTvId } ?: connectionState.device
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel, connectionDialogMode) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    viewModel.stopHolding()
                    viewModel.stopDiscovery()
                    viewModel.cancelPairing()
                }

                Lifecycle.Event.ON_START -> {
                    if (connectionDialogMode == "add") viewModel.startDiscovery()
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stopHolding()
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF08080F),
                        Color(0xFF05050A),
                        Color(0xFF080811),
                    ),
                ),
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 4.dp),
    ) {
        // Keep IME-driven height changes from reflowing the controls behind dialogs.
        val layoutHeight = remember(maxWidth) { maxHeight }
        val sideWidth = (maxWidth * 0.18f).coerceIn(56.dp, 72.dp)
        val dpadHeight = (maxWidth * 0.73f)
            .coerceAtMost(layoutHeight * 0.36f)
            .coerceAtLeast(150.dp)
        val compact = layoutHeight < 740.dp
        val headerHeight = if (layoutHeight < 620.dp) 56.dp else 66.dp
        val topGap = if (compact) (layoutHeight * 0.03f).coerceAtLeast(10.dp) else 24.dp
        val remoteGap = if (compact) {
            (layoutHeight * 0.045f).coerceAtLeast(12.dp)
        } else {
            54.dp
        }
        val utilityGap = if (compact) (layoutHeight * 0.025f).coerceAtLeast(10.dp) else 24.dp
        val utilityHeight = if (layoutHeight < 620.dp) 60.dp else 72.dp
        val bottomButtonSize = (sideWidth + 4.dp).coerceAtMost(
            if (layoutHeight < 620.dp) 64.dp else 76.dp,
        )

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DeviceHeader(
                selectedTv = selectedTv,
                savedTvs = savedTvs,
                connectionState = connectionState,
                onSelectTv = viewModel::connect,
                onAddTv = { connectionDialogMode = "add" },
                onManageTvs = { connectionDialogMode = "manage" },
                onCommand = viewModel::send,
                onOpenSettings = { settingsOpen = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(headerHeight),
            )
            Spacer(Modifier.height(topGap))
            AppShortcutsRow(
                shortcuts = shortcuts,
                onLaunchApp = { shortcut ->
                    viewModel.send(
                        RemoteCommand.LaunchApp(
                            packageName = shortcut.packageName,
                            launchUri = shortcut.launchUri,
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(remoteGap))
            RemoteControls(
                viewModel = viewModel,
                touchpadEnabled = touchpadEnabled,
                sideWidth = sideWidth,
                dpadHeight = dpadHeight,
                bottomButtonSize = bottomButtonSize,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(utilityGap))
            UtilityBar(
                onCommand = viewModel::send,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(utilityHeight),
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp),
        )
    }
    if (settingsOpen) {
        RemoteSettingsDialog(
            shortcuts = shortcuts,
            touchpadEnabled = touchpadEnabled,
            onTouchpadEnabledChange = viewModel::setTouchpadEnabled,
            onShortcutsChange = viewModel::saveShortcuts,
            connectionState = connectionState,
            onDismiss = { settingsOpen = false },
        )
    }
    connectionDialogMode?.let { mode ->
        TvConnectionDialog(
            savedTvs = savedTvs,
            discoveredDevices = discoveredDevices,
            connectionState = connectionState,
            manageOnly = mode == "manage",
            onStartDiscovery = viewModel::startDiscovery,
            onStopDiscovery = viewModel::stopDiscovery,
            onBeginPairing = viewModel::beginPairing,
            onCompletePairing = viewModel::completePairing,
            onConnect = viewModel::connect,
            onRemove = viewModel::removeTv,
            onCancelPairing = viewModel::cancelPairing,
            onDismiss = { connectionDialogMode = null },
        )
    }
}

@Composable
private fun DeviceHeader(
    selectedTv: TvDevice?,
    savedTvs: List<TvDevice>,
    connectionState: TvConnectionState,
    onSelectTv: (TvDevice) -> Unit,
    onAddTv: () -> Unit,
    onManageTvs: () -> Unit,
    onCommand: (RemoteCommand) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val isSelectedConnected =
        selectedTv != null &&
            connectionState.device?.id == selectedTv.id &&
            connectionState.phase == TvConnectionPhase.CONNECTED
    val connectionColor = when {
        isSelectedConnected -> Color(0xFF52D18A)
        selectedTv != null &&
            connectionState.device?.id == selectedTv.id &&
            connectionState.phase in setOf(TvConnectionPhase.CONNECTING, TvConnectionPhase.PAIRING) ->
            Color(0xFFFFC857)
        selectedTv != null &&
            connectionState.device?.id == selectedTv.id &&
            connectionState.phase == TvConnectionPhase.ERROR ->
            Color(0xFFFF7373)
        else -> Color(0xFF777887)
    }

    Row(
        modifier = modifier
            .clip(SurfaceShape)
            .background(surfaceBrush())
            .border(1.dp, RemoteBorder.copy(alpha = 0.48f), SurfaceShape)
            .padding(start = 18.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Tv,
            contentDescription = null,
            tint = RemoteText,
            modifier = Modifier.size(34.dp),
        )
        Spacer(Modifier.width(18.dp))
        Box {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .combinedClickable(
                        onClick = { menuExpanded = true },
                        onLongClick = { menuExpanded = true },
                    )
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = selectedTv?.name ?: "Select a TV",
                    color = RemoteText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(9.dp))
                Box(
                    Modifier
                        .size(9.dp)
                        .background(connectionColor, CircleShape),
                )
                Spacer(Modifier.width(12.dp))
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = "Choose TV",
                    tint = RemoteAccent,
                    modifier = Modifier.size(22.dp),
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                modifier = Modifier
                    .widthIn(min = 220.dp),
                shape = RoundedCornerShape(24.dp),
                containerColor = Color(0xE611121E),
                tonalElevation = 0.dp,
                shadowElevation = 18.dp,
                border = BorderStroke(1.dp, RemoteBorder.copy(alpha = 0.74f)),
            ) {
                if (savedTvs.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("No saved TVs") },
                        onClick = {},
                        enabled = false,
                    )
                } else {
                    savedTvs.forEach { device ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier
                                            .size(8.dp)
                                            .background(
                                                if (
                                                    isSelectedConnected &&
                                                    connectionState.device.id == device.id
                                                ) {
                                                    Color(0xFF52D18A)
                                                } else {
                                                    Color(0xFF777887)
                                                },
                                                CircleShape,
                                            ),
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(device.name)
                                }
                            },
                            onClick = {
                                menuExpanded = false
                                onSelectTv(device)
                            },
                        )
                    }
                }
                HorizontalDivider(color = RemoteBorder.copy(alpha = 0.55f))
                DropdownMenuItem(
                    text = { Text("Add TV") },
                    onClick = {
                        menuExpanded = false
                        onAddTv()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Manage TVs") },
                    onClick = {
                        menuExpanded = false
                        onManageTvs()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Settings") },
                    onClick = {
                        menuExpanded = false
                        onOpenSettings()
                    },
                )
            }
        }
        Spacer(Modifier.weight(1f))
        RemoteTapTarget(
            description = "Power",
            onClick = { onCommand(RemoteCommand.Power) },
            modifier = Modifier.size(54.dp),
            shape = CircleShape,
            background = Brush.linearGradient(
                listOf(
                    RemoteSurfaceHighlight.copy(alpha = 0.62f),
                    RemoteSurface.copy(alpha = 0.96f),
                ),
            ),
            border = RemoteBorder.copy(alpha = 0.32f),
        ) {
            Icon(
                imageVector = Icons.Filled.PowerSettingsNew,
                contentDescription = null,
                tint = Color(0xFFFF3448),
                modifier = Modifier.size(34.dp),
            )
        }
    }
}

@Composable
private fun AppShortcutsRow(
    shortcuts: List<TvAppShortcut>,
    onLaunchApp: (TvAppShortcut) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (shortcuts.isEmpty()) {
        Box(
            modifier = modifier.height(82.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = "Add TV app shortcuts in Settings",
                color = RemoteMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    } else {
        Row(
            modifier = modifier.height(82.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            shortcuts.take(4).forEach { shortcut ->
                RemoteTapTarget(
                    description = shortcut.name,
                    onClick = { onLaunchApp(shortcut) },
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f),
                    shape = RoundedCornerShape(19.dp),
                    background = surfaceBrush(),
                    border = RemoteBorder.copy(alpha = 0.78f),
                ) {
                    ShortcutIcon(
                        shortcut = shortcut,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp),
                    )
                }
            }
            repeat((4 - shortcuts.size).coerceAtLeast(0)) {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RemoteControls(
    viewModel: RemoteViewModel,
    touchpadEnabled: Boolean,
    sideWidth: androidx.compose.ui.unit.Dp,
    dpadHeight: androidx.compose.ui.unit.Dp,
    bottomButtonSize: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(dpadHeight),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SideControl(
                width = sideWidth,
                repeatCommands = listOf(RemoteCommand.VolumeUp, RemoteCommand.VolumeDown),
                viewModel = viewModel,
                centerIcon = Icons.AutoMirrored.Filled.VolumeUp,
                topIcon = Icons.Filled.Add,
                bottomIcon = Icons.Filled.Remove,
                groupDescription = "Volume",
                modifier = Modifier.fillMaxHeight(),
            )
            if (touchpadEnabled) {
                Touchpad(
                    viewModel = viewModel,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            } else {
                DPad(
                    viewModel = viewModel,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
            SideControl(
                width = sideWidth,
                repeatCommands = listOf(RemoteCommand.ChannelUp, RemoteCommand.ChannelDown),
                viewModel = viewModel,
                centerLabel = "CH",
                topIcon = Icons.Filled.KeyboardArrowUp,
                bottomIcon = Icons.Filled.KeyboardArrowDown,
                groupDescription = "Channel",
                modifier = Modifier.fillMaxHeight(),
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RemoteTapTarget(
                description = "Back",
                onClick = { viewModel.send(RemoteCommand.Back) },
                modifier = Modifier.size(bottomButtonSize),
                shape = CircleShape,
                background = surfaceBrush(),
                border = RemoteBorder.copy(alpha = 0.7f),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = RemoteText,
                    modifier = Modifier.size(34.dp),
                )
            }
            RemoteTapTarget(
                description = "Home",
                onClick = { viewModel.send(RemoteCommand.Home) },
                modifier = Modifier.size(bottomButtonSize),
                shape = CircleShape,
                background = surfaceBrush(),
                border = RemoteBorder.copy(alpha = 0.7f),
            ) {
                Icon(
                    imageVector = Icons.Filled.Home,
                    contentDescription = null,
                    tint = RemoteText,
                    modifier = Modifier.size(34.dp),
                )
            }
        }
    }
}

@Composable
private fun SideControl(
    width: androidx.compose.ui.unit.Dp,
    repeatCommands: List<RemoteCommand>,
    viewModel: RemoteViewModel,
    modifier: Modifier = Modifier,
    centerIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    centerLabel: String? = null,
    topIcon: androidx.compose.ui.graphics.vector.ImageVector,
    bottomIcon: androidx.compose.ui.graphics.vector.ImageVector,
    groupDescription: String,
) {
    Column(
        modifier = modifier
            .width(width)
            .clip(RoundedCornerShape(42.dp))
            .background(surfaceBrush())
            .border(1.dp, RemoteBorder.copy(alpha = 0.78f), RoundedCornerShape(42.dp))
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        listOf(topIcon, centerIcon, bottomIcon).forEachIndexed { index, icon ->
            if (icon == null) {
                Text(
                    text = centerLabel.orEmpty(),
                    color = RemoteText,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.semantics {
                        contentDescription = "$groupDescription indicator"
                    },
                )
            } else if (index == 1 && centerIcon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = "$groupDescription indicator",
                    tint = RemoteText,
                    modifier = Modifier.size(34.dp),
                )
            } else {
                val command = if (index == 0) repeatCommands[0] else repeatCommands[1]
                HoldTarget(
                    description = if (index == 0) {
                        "$groupDescription up"
                    } else {
                        "$groupDescription down"
                    },
                    onStart = { viewModel.startHolding(command) },
                    onStop = viewModel::stopHolding,
                    onAccessibleClick = { viewModel.send(command) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(68.dp),
                ) { pressed ->
                    ControlGlyph(
                        icon = icon,
                        modifier = Modifier.size(34.dp),
                        pressed = pressed,
                    )
                }
            }
        }
    }
}

@Composable
private fun DPad(
    viewModel: RemoteViewModel,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(36.dp))
            .background(surfaceBrush())
            .border(1.5.dp, RemoteBorder.copy(alpha = 0.9f), RoundedCornerShape(36.dp)),
    ) {
        val centerSize = (maxWidth * 0.55f).coerceAtMost(116.dp)
        val arrowWidth = (maxWidth * 0.34f).coerceAtLeast(52.dp)
        HoldTarget(
            description = "Navigate up",
            onStart = { viewModel.startHolding(RemoteCommand.Up) },
            onStop = viewModel::stopHolding,
            onAccessibleClick = { viewModel.send(RemoteCommand.Up) },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp)
                .width(arrowWidth)
                .height(62.dp),
        ) { pressed ->
            ControlGlyph(
                icon = Icons.Filled.KeyboardArrowUp,
                modifier = Modifier.size(40.dp),
                pressed = pressed,
            )
        }
        HoldTarget(
            description = "Navigate left",
            onStart = { viewModel.startHolding(RemoteCommand.Left) },
            onStop = viewModel::stopHolding,
            onAccessibleClick = { viewModel.send(RemoteCommand.Left) },
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(arrowWidth)
                .height(76.dp),
        ) { pressed ->
            ControlGlyph(
                icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                modifier = Modifier.size(40.dp),
                pressed = pressed,
            )
        }
        HoldTarget(
            description = "Navigate right",
            onStart = { viewModel.startHolding(RemoteCommand.Right) },
            onStop = viewModel::stopHolding,
            onAccessibleClick = { viewModel.send(RemoteCommand.Right) },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(arrowWidth)
                .height(76.dp),
        ) { pressed ->
            ControlGlyph(
                icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                modifier = Modifier.size(40.dp),
                pressed = pressed,
            )
        }
        HoldTarget(
            description = "Navigate down",
            onStart = { viewModel.startHolding(RemoteCommand.Down) },
            onStop = viewModel::stopHolding,
            onAccessibleClick = { viewModel.send(RemoteCommand.Down) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp)
                .width(arrowWidth)
                .height(62.dp),
        ) { pressed ->
            ControlGlyph(
                icon = Icons.Filled.KeyboardArrowDown,
                modifier = Modifier.size(40.dp),
                pressed = pressed,
            )
        }
        RemoteTapTarget(
            description = "OK",
            onClick = { viewModel.send(RemoteCommand.Select) },
            modifier = Modifier
                .size(centerSize)
                .align(Alignment.Center),
            shape = CircleShape,
            background = surfaceBrush(),
            border = RemoteBorder.copy(alpha = 0.78f),
        ) {
            Text(
                text = "OK",
                color = RemoteText,
                fontSize = 23.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun Touchpad(
    viewModel: RemoteViewModel,
    modifier: Modifier = Modifier,
) {
    val thresholdPx = with(androidx.compose.ui.platform.LocalDensity.current) { 48.dp.toPx() }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(36.dp))
            .background(surfaceBrush())
            .border(1.5.dp, RemoteBorder.copy(alpha = 0.9f), RoundedCornerShape(36.dp))
            .pointerInput(viewModel, thresholdPx) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    var holding = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.isConsumed) break
                            change.consume()

                            if (!holding) {
                                val delta = change.position - down.position
                                val horizontal = abs(delta.x) >= abs(delta.y)
                                val distance = if (horizontal) abs(delta.x) else abs(delta.y)
                                if (distance >= thresholdPx) {
                                    val command = if (horizontal) {
                                        if (delta.x > 0) RemoteCommand.Right else RemoteCommand.Left
                                    } else {
                                        if (delta.y > 0) RemoteCommand.Down else RemoteCommand.Up
                                    }
                                    viewModel.startHolding(command)
                                    holding = true
                                }
                            }

                            if (!change.pressed) {
                                if (!holding && change.position.isInside(size)) {
                                    viewModel.send(RemoteCommand.Select)
                                }
                                break
                            }
                        }
                    } finally {
                        if (holding) viewModel.stopHolding()
                    }
                }
            }
            .semantics {
                role = Role.Button
                contentDescription = "Touchpad. Swipe and hold to repeat, tap to select."
                onClick(label = "Select") {
                    viewModel.send(RemoteCommand.Select)
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Filled.TouchApp,
                contentDescription = null,
                tint = RemoteText,
                modifier = Modifier.size(38.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Swipe and hold to repeat",
                color = RemoteText,
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Tap to select",
                color = RemoteMuted,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun UtilityBar(
    onCommand: (RemoteCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    var typeDialogOpen by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .clip(SurfaceShape)
            .background(surfaceBrush())
            .border(1.dp, RemoteBorder.copy(alpha = 0.7f), SurfaceShape)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UtilityAction(
            description = "Mute",
            onClick = { onCommand(RemoteCommand.Mute) },
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.VolumeOff,
                contentDescription = null,
                tint = RemoteText,
                modifier = Modifier.size(30.dp),
            )
        }
        UtilityDivider()
        UtilityAction(
            description = "Source",
            onClick = { onCommand(RemoteCommand.Source) },
            modifier = Modifier.weight(1.2f),
            label = "Source",
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Input,
                contentDescription = null,
                tint = RemoteText,
                modifier = Modifier.size(28.dp),
            )
        }
        UtilityDivider()
        UtilityAction(
            description = "Backspace",
            onClick = { onCommand(RemoteCommand.Delete) },
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Backspace,
                contentDescription = null,
                tint = RemoteText,
                modifier = Modifier.size(26.dp),
            )
        }
        UtilityDivider()
        UtilityAction(
            description = "Type text",
            onClick = { typeDialogOpen = true },
            modifier = Modifier.weight(1f),
            label = "Type",
        ) {
            Icon(
                imageVector = Icons.Filled.Keyboard,
                contentDescription = null,
                tint = RemoteText,
                modifier = Modifier.size(28.dp),
            )
        }
    }

    if (typeDialogOpen) {
        TypeTextDialog(
            onSend = { text ->
                onCommand(RemoteCommand.SendText(text))
                typeDialogOpen = false
            },
            onDismiss = { typeDialogOpen = false },
        )
    }
}

@Composable
private fun UtilityAction(
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    icon: @Composable () -> Unit,
) {
    RemoteTapTarget(
        description = description,
        onClick = onClick,
        modifier = modifier.fillMaxHeight(),
        shape = RoundedCornerShape(22.dp),
        background = Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)),
        border = Color.Transparent,
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            if (label != null) {
                Spacer(Modifier.width(10.dp))
                Text(
                    text = label,
                    color = RemoteText,
                    fontSize = 14.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun UtilityDivider() {
    Box(
        Modifier
            .width(1.dp)
            .fillMaxHeight(0.72f)
            .background(RemoteBorder.copy(alpha = 0.88f)),
    )
}

@Composable
private fun TypeTextDialog(
    onSend: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by rememberSaveable { mutableStateOf("") }
    val dialogShape = RoundedCornerShape(28.dp)
    AlertDialog(
        modifier = Modifier.border(
            1.dp,
            RemoteBorder.copy(alpha = 0.88f),
            dialogShape,
        ),
        onDismissRequest = onDismiss,
        shape = dialogShape,
        containerColor = Color(0xF011121E),
        tonalElevation = 0.dp,
        title = { Text("Type to TV") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Text") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(
                    onSend = { if (value.isNotBlank()) onSend(value) },
                ),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (value.isNotBlank()) onSend(value) },
                enabled = value.isNotBlank(),
            ) {
                Text("Send")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun MessageDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("OK")
            }
        },
    )
}

@Composable
private fun HoldTarget(
    description: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onAccessibleClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (pressed: Boolean) -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val currentOnStart by rememberUpdatedState(onStart)
    val currentOnStop by rememberUpdatedState(onStop)
    Box(
        modifier = modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var started = false
                    try {
                        pressed = true
                        started = true
                        currentOnStart()
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed || !change.position.isInside(size)) break
                            change.consume()
                        }
                    } finally {
                        pressed = false
                        if (started) currentOnStop()
                    }
                }
            }
            .semantics {
                role = Role.Button
                contentDescription = description
                onClick(label = description) {
                    onAccessibleClick()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        content(pressed)
    }
}

@Composable
private fun ControlGlyph(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    pressed: Boolean,
) {
    val glowAlpha by animateFloatAsState(
        targetValue = if (pressed) 0.14f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "remoteControlGlow",
    )
    val tint by animateColorAsState(
        targetValue = if (pressed) Color.White else RemoteText,
        label = "remoteControlTint",
    )
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(RemoteAccent.copy(alpha = glowAlpha)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = modifier,
        )
    }
}

@Composable
private fun RemoteTapTarget(
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(24.dp),
    background: Brush,
    border: Color,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val glowAlpha by animateFloatAsState(
        targetValue = if (pressed) 0.12f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "remoteTapGlow",
    )
    Box(
        modifier = modifier
            .clip(shape)
            .background(background)
            .background(RemoteAccent.copy(alpha = glowAlpha), shape)
            .border(1.dp, border, shape)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClickLabel = description,
                onClick = onClick,
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

private fun surfaceBrush(): Brush = Brush.linearGradient(
    colors = listOf(
        RemoteSurfaceHighlight.copy(alpha = 0.92f),
        RemoteSurface.copy(alpha = 0.98f),
        Color(0xFF0D0E19),
    ),
)

private fun Offset.isInside(size: IntSize): Boolean =
    x >= 0f && y >= 0f && x < size.width && y < size.height

