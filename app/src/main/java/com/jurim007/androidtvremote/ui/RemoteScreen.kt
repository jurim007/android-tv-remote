package com.jurim007.androidtvremote.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.rememberUpdatedState
import com.jurim007.androidtvremote.domain.RemoteCommand
import com.jurim007.androidtvremote.ui.theme.RemoteAccent
import com.jurim007.androidtvremote.ui.theme.RemoteBackground
import com.jurim007.androidtvremote.ui.theme.RemoteBorder
import com.jurim007.androidtvremote.ui.theme.RemoteMuted
import com.jurim007.androidtvremote.ui.theme.RemoteSurface
import com.jurim007.androidtvremote.ui.theme.RemoteSurfaceHighlight
import com.jurim007.androidtvremote.ui.theme.RemoteText

private val SurfaceShape = RoundedCornerShape(34.dp)

@Composable
fun RemoteScreen(
    modifier: Modifier = Modifier,
    viewModel: RemoteViewModel = viewModel(),
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.stopHolding()
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
            .padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        val sideWidth = (maxWidth * 0.18f).coerceIn(56.dp, 72.dp)
        val dpadHeight = (maxWidth * 0.73f)
            .coerceAtMost(maxHeight * 0.36f)
            .coerceAtLeast(150.dp)
        val compact = maxHeight < 740.dp
        val headerHeight = if (maxHeight < 620.dp) 56.dp else 66.dp
        val topGap = if (compact) (maxHeight * 0.03f).coerceAtLeast(10.dp) else 24.dp
        val remoteGap = if (compact) {
            (maxHeight * 0.045f).coerceAtLeast(12.dp)
        } else {
            54.dp
        }
        val utilityGap = if (compact) (maxHeight * 0.025f).coerceAtLeast(10.dp) else 24.dp
        val utilityHeight = if (maxHeight < 620.dp) 60.dp else 72.dp
        val bottomButtonSize = (sideWidth + 4.dp).coerceAtMost(
            if (maxHeight < 620.dp) 64.dp else 76.dp,
        )

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DeviceHeader(
                onCommand = viewModel::send,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(headerHeight),
            )
            Spacer(Modifier.height(topGap))
            AppShortcutsRow(
                viewModel = viewModel,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(remoteGap))
            RemoteControls(
                viewModel = viewModel,
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
    }
}

@Composable
private fun DeviceHeader(
    onCommand: (RemoteCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var infoMessage by remember { mutableStateOf<String?>(null) }

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
                    text = "Select a TV",
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
                        .background(Color(0xFF777887), CircleShape),
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
                    .background(RemoteSurface)
                    .widthIn(min = 220.dp),
            ) {
                DropdownMenuItem(
                    text = { Text("No saved TVs") },
                    onClick = {},
                    enabled = false,
                )
                HorizontalDivider(color = RemoteBorder.copy(alpha = 0.55f))
                DropdownMenuItem(
                    text = { Text("Add TV") },
                    onClick = {
                        menuExpanded = false
                        infoMessage =
                            "TV discovery and pairing will be added in a future milestone. No TV is connected."
                    },
                )
                DropdownMenuItem(
                    text = { Text("Manage TVs") },
                    onClick = {
                        menuExpanded = false
                        infoMessage = "There are no saved TVs to manage."
                    },
                )
                DropdownMenuItem(
                    text = { Text("Settings") },
                    onClick = {
                        menuExpanded = false
                        infoMessage = "Settings are not part of this first milestone."
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
    infoMessage?.let { message ->
        MessageDialog(message = message, onDismiss = { infoMessage = null })
    }
}

private data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: Drawable,
)

@Composable
private fun AppShortcutsRow(
    viewModel: RemoteViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val installedApps = remember(context) {
        loadInstalledApps(context.packageManager, context.packageName)
    }
    val savedPackages by viewModel.shortcutPackages.collectAsStateWithLifecycle(
        initialValue = recommendedAppPackages,
    )
    val selectedPackages = savedPackages
        .filter { packageName -> installedApps.any { it.packageName == packageName } }
        .take(4)
    var pickerOpen by remember { mutableStateOf(false) }
    val selectedApps = selectedPackages.mapNotNull { packageName ->
        installedApps.firstOrNull { it.packageName == packageName }
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        selectedApps.forEach { app ->
            AppShortcutTile(
                app = app,
                onClick = {
                    context.packageManager.getLaunchIntentForPackage(app.packageName)?.let {
                        context.startActivity(it)
                    }
                },
                onRemove = {
                    viewModel.saveShortcutPackages(selectedPackages - app.packageName)
                },
                modifier = Modifier.weight(1f),
            )
        }
        AddShortcutTile(
            onClick = { pickerOpen = true },
            modifier = Modifier.weight(1f),
        )
        repeat((4 - selectedApps.size).coerceAtLeast(0)) {
            Spacer(Modifier.weight(1f))
        }
    }

    if (pickerOpen) {
        val availableApps = installedApps.filterNot { it.packageName in selectedPackages }
        AppPickerDialog(
            apps = availableApps,
            canAddMore = selectedPackages.size < 4,
            onSelect = { app ->
                if (selectedPackages.size < 4) {
                    viewModel.saveShortcutPackages(selectedPackages + app.packageName)
                }
                pickerOpen = false
            },
            onDismiss = { pickerOpen = false },
        )
    }
}

@Composable
private fun AppShortcutTile(
    app: InstalledApp,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(app.icon) {
        app.icon.toBitmap(width = 128, height = 128).asImageBitmap()
    }
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(19.dp))
            .background(surfaceBrush())
            .border(1.dp, RemoteBorder.copy(alpha = 0.78f), RoundedCornerShape(19.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onRemove,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = app.label,
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
        )
    }
}

@Composable
private fun AddShortcutTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RemoteTapTarget(
        description = "Add app shortcut",
        onClick = onClick,
        modifier = modifier.aspectRatio(1f),
        shape = RoundedCornerShape(19.dp),
        background = surfaceBrush(),
        border = RemoteBorder.copy(alpha = 0.7f),
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = null,
            tint = RemoteAccent,
            modifier = Modifier.size(34.dp),
        )
    }
}

@Composable
private fun AppPickerDialog(
    apps: List<InstalledApp>,
    canAddMore: Boolean,
    onSelect: (InstalledApp) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .heightIn(max = 540.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(RemoteSurface)
                .border(1.dp, RemoteBorder, RoundedCornerShape(28.dp))
                .padding(20.dp),
        ) {
            Text(
                text = "Add app shortcut",
                color = RemoteText,
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (canAddMore) {
                    "Choose an installed app. Long-press a shortcut to remove it."
                } else {
                    "You already have four shortcuts. Long-press one to remove it first."
                },
                color = RemoteMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            if (!canAddMore) {
                Text(
                    text = "Remove a shortcut before adding another.",
                    color = RemoteMuted,
                    modifier = Modifier.padding(vertical = 18.dp),
                )
            } else if (apps.isEmpty()) {
                Text(
                    text = "No more launchable apps were found.",
                    color = RemoteMuted,
                    modifier = Modifier.padding(vertical = 18.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                ) {
                    items(apps, key = InstalledApp::packageName) { app ->
                        val iconBitmap = remember(app.icon) {
                            app.icon.toBitmap(width = 96, height = 96).asImageBitmap()
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .combinedClickable(
                                    onClick = { if (canAddMore) onSelect(app) },
                                    onLongClick = {},
                                )
                                .padding(vertical = 9.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Image(
                                bitmap = iconBitmap,
                                contentDescription = null,
                                modifier = Modifier.size(38.dp),
                            )
                            Spacer(Modifier.width(14.dp))
                            Text(
                                text = app.label,
                                color = if (canAddMore) RemoteText else RemoteMuted,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Close")
            }
        }
    }
}

private fun loadInstalledApps(
    packageManager: PackageManager,
    ownPackageName: String,
): List<InstalledApp> {
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return packageManager.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
        .mapNotNull { resolveInfo ->
            val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
            val packageName = activityInfo.packageName
            if (packageName == ownPackageName) return@mapNotNull null
            InstalledApp(
                packageName = packageName,
                label = resolveInfo.loadLabel(packageManager).toString(),
                icon = resolveInfo.loadIcon(packageManager),
            )
        }
        .distinctBy(InstalledApp::packageName)
        .sortedBy { it.label.lowercase() }
}

private val recommendedAppPackages = listOf(
    "com.netflix.mediaclient",
    "com.google.android.youtube.tv",
    "com.google.android.youtube",
    "com.amazon.amazonvideo.livingroom",
    "com.disney.disneyplus",
)

@Composable
private fun RemoteControls(
    viewModel: RemoteViewModel,
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
            DPad(
                viewModel = viewModel,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
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
                    onAccessibleClick = {
                        viewModel.startHolding(command)
                        viewModel.stopHolding()
                    },
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
            onAccessibleClick = {
                viewModel.startHolding(RemoteCommand.Up)
                viewModel.stopHolding()
            },
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
            onAccessibleClick = {
                viewModel.startHolding(RemoteCommand.Left)
                viewModel.stopHolding()
            },
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
            onAccessibleClick = {
                viewModel.startHolding(RemoteCommand.Right)
                viewModel.stopHolding()
            },
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
            onAccessibleClick = {
                viewModel.startHolding(RemoteCommand.Down)
                viewModel.stopHolding()
            },
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
private fun UtilityBar(
    onCommand: (RemoteCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    var muted by rememberSaveable { mutableStateOf(false) }
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
            description = if (muted) "Unmute" else "Mute",
            onClick = {
                muted = !muted
                onCommand(RemoteCommand.Mute)
            },
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                imageVector = if (muted) {
                    Icons.AutoMirrored.Filled.VolumeUp
                } else {
                    Icons.AutoMirrored.Filled.VolumeOff
                },
                contentDescription = null,
                tint = if (muted) RemoteAccent else RemoteText,
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
    AlertDialog(
        onDismissRequest = onDismiss,
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
    val tint by animateColorAsState(
        targetValue = if (pressed) Color.White else RemoteText,
        label = "remoteControlTint",
    )
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (pressed) RemoteAccent.copy(alpha = 0.13f) else Color.Transparent),
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
    Box(
        modifier = modifier
            .clip(shape)
            .background(background)
            .border(1.dp, border, shape)
            .combinedClickable(
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

private fun androidx.compose.ui.geometry.Offset.isInside(size: IntSize): Boolean =
    x >= 0f && y >= 0f && x < size.width && y < size.height

