package com.jurim007.androidtvremote.ui

import android.content.Context
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import com.jurim007.androidtvremote.BuildConfig
import com.jurim007.androidtvremote.R
import com.jurim007.androidtvremote.domain.MAX_TV_SHORTCUTS
import com.jurim007.androidtvremote.domain.RemoteOption
import com.jurim007.androidtvremote.domain.TvAppShortcut
import com.jurim007.androidtvremote.domain.TvAppCatalog
import com.jurim007.androidtvremote.domain.TvInstalledApp
import com.jurim007.androidtvremote.domain.TvPlatform
import com.jurim007.androidtvremote.domain.TvConnectionPhase
import com.jurim007.androidtvremote.domain.TvConnectionState
import com.jurim007.androidtvremote.data.ShortcutImageDownloader
import com.jurim007.androidtvremote.ui.theme.RemoteAccent
import com.jurim007.androidtvremote.ui.theme.RemoteBorder
import com.jurim007.androidtvremote.ui.theme.RemoteMuted
import com.jurim007.androidtvremote.ui.theme.RemoteSurface
import com.jurim007.androidtvremote.ui.theme.RemoteText
import java.io.IOException
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RemoteSettingsDialog(
    shortcuts: List<TvAppShortcut>,
    options: Set<RemoteOption>,
    onOptionChange: (RemoteOption, Boolean) -> Unit,
    checkingForUpdates: Boolean,
    updateCheckStatus: String?,
    onCheckForUpdates: () -> Unit,
    tclInputsEnabled: Boolean,
    onTclInputsChange: (String, Boolean) -> Unit,
    onRestoreDefaults: () -> Unit,
    onManageTvs: () -> Unit,
    onShortcutsChange: (List<TvAppShortcut>) -> Unit,
    connectionState: TvConnectionState,
    platform: TvPlatform,
    defaultShortcuts: List<TvAppShortcut>,
    installedApps: List<TvInstalledApp>?,
    onDismiss: () -> Unit,
) {
    var removeShortcut by remember { mutableStateOf<TvAppShortcut?>(null) }
    var restoreTarget by rememberSaveable { mutableStateOf<RestoreTarget?>(null) }
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    var addingShortcut by rememberSaveable { mutableStateOf(false) }
    var editingPackageName by rememberSaveable { mutableStateOf<String?>(null) }
    val editingShortcut = shortcuts.firstOrNull { it.packageName == editingPackageName }
    val overviewScroll = rememberScrollState()
    val scrollState = if (page == null) overviewScroll else key(page, addingShortcut, editingPackageName) {
        rememberScrollState()
    }
    var viewport by remember { mutableStateOf(Rect.Zero) }
    val editing = addingShortcut || editingShortcut != null
    val goBack = {
        when {
            editing -> {
                addingShortcut = false
                editingPackageName = null
            }
            page != null -> page = null
            else -> onDismiss()
        }
    }
    Dialog(
        onDismissRequest = goBack,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        ResizeDialogForKeyboard()
        Column(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .heightIn(max = 680.dp)
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color(0xF01B1D30),
                            Color(0xF011121E),
                            Color(0xF00D0E19),
                        ),
                    ),
                    RoundedCornerShape(28.dp),
                )
                .border(1.dp, RemoteBorder, RoundedCornerShape(28.dp))
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                if (page != null) {
                    IconButton(onClick = goBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = RemoteAccent)
                    }
                }
                Text(
                    text = when {
                        editingShortcut != null -> "Edit TV app"
                        addingShortcut -> "Add TV app"
                        else -> page?.title ?: "Remote settings"
                    },
                    color = RemoteText,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier.weight(1f, fill = false)
                    .onGloballyPositioned { coordinates ->
                        val origin = coordinates.positionInRoot()
                        viewport = Rect(origin, androidx.compose.ui.geometry.Size(
                            coordinates.size.width.toFloat(), coordinates.size.height.toFloat(),
                        ))
                    }
                    .verticalScroll(scrollState),
            ) {
                if (addingShortcut || editingShortcut != null) {
                    AddTvAppForm(
                        existingShortcuts = shortcuts,
                        initialShortcut = editingShortcut,
                        platform = platform,
                        installedApps = installedApps,
                        onSave = { shortcut ->
                            val oldPackageName = editingShortcut?.packageName
                            onShortcutsChange(
                                if (oldPackageName == null) {
                                    shortcuts + shortcut
                                } else {
                                    shortcuts.map { existing ->
                                        if (existing.packageName == oldPackageName) shortcut else existing
                                    }
                                },
                            )
                            addingShortcut = false
                            editingPackageName = null
                        },
                        onCancel = {
                            addingShortcut = false
                            editingPackageName = null
                        },
                    )
                } else {
                    val connectionDescription = when (connectionState.phase) {
                        TvConnectionPhase.CONNECTED ->
                            "Connected to ${connectionState.device?.name ?: "TV"}"
                        TvConnectionPhase.DISCOVERING -> "Searching for TVs"
                        TvConnectionPhase.CONNECTING -> "Connecting to TV"
                        TvConnectionPhase.PAIRING -> "Pairing with TV"
                        TvConnectionPhase.ERROR -> connectionState.message ?: "Connection error"
                        TvConnectionPhase.DISCONNECTED -> "No TV connected"
                    }
                    when (page) {
                        null -> SettingsOverview(shortcuts.size, connectionDescription) { page = it }
                        SettingsPage.SHORTCUTS -> {
                            Text(
                                text = "Add up to eight shortcuts by app name or package ID.",
                                color = RemoteMuted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (shortcuts.isNotEmpty()) {
                                Text("Tap a shortcut to edit or remove it.", color = RemoteMuted,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            if (shortcuts.size > 1) {
                                Text("Touch and hold, then drag to reorder.", color = RemoteMuted,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(Modifier.height(12.dp))
                            if (shortcuts.isEmpty()) {
                                Text(
                                    text = "No TV apps added yet.",
                                    color = RemoteMuted,
                                    modifier = Modifier.padding(vertical = 8.dp),
                                )
                            } else {
                                ReorderableShortcuts(
                                    shortcuts = shortcuts,
                                    scrollState = scrollState,
                                    viewport = viewport,
                                    onReorder = onShortcutsChange,
                                    onEdit = { editingPackageName = it.packageName },
                                    onRemove = { removeShortcut = it },
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = { addingShortcut = true },
                                enabled = shortcuts.size < MAX_TV_SHORTCUTS,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = null)
                                Text(if (shortcuts.size < MAX_TV_SHORTCUTS) "Add TV app" else "Eight app limit reached")
                            }
                            if (shortcuts != defaultShortcuts) {
                                TextButton(onClick = { restoreTarget = RestoreTarget.SHORTCUTS }) {
                                    Text("Restore default shortcuts")
                                }
                            }
                        }
                        SettingsPage.DEVICES -> {
                            SettingsLink("Manage TVs", connectionDescription, onManageTvs)
                            if (connectionState.phase == TvConnectionPhase.CONNECTED) {
                                connectionState.device?.takeIf { it.platform == TvPlatform.ANDROID_TV }?.let { tv ->
                                    SettingsToggle(
                                        title = "TCL Inputs via AVA Connect",
                                        detail = "Open the native Inputs panel on ${tv.name}. Requires AVA Connect on the TV.",
                                        enabled = tclInputsEnabled,
                                    ) { onTclInputsChange(tv.id, it) }
                                }
                            }
                        }
                        SettingsPage.LAYOUT -> {
                            SettingsToggle(
                                title = "Show Navigation Mode Selector",
                                detail = "Show or hide the D-pad and Touchpad selector on the home screen.",
                                enabled = RemoteOption.NAVIGATION_SELECTOR in options,
                            ) { onOptionChange(RemoteOption.NAVIGATION_SELECTOR, it) }
                            CONTROL_OPTIONS
                                .filterNot { (option, _) ->
                                    platform == TvPlatform.WEBOS && option == RemoteOption.MICROPHONE
                                }
                                .forEach { (option, label) ->
                                    SettingsToggle(label, enabled = option in options) { onOptionChange(option, it) }
                                }
                        }
                        SettingsPage.BEHAVIOR -> {
                            SettingsToggle(
                                title = "Phone volume buttons",
                                detail = "Adjust TV volume with the phone’s volume keys when connected.",
                                enabled = RemoteOption.PHONE_VOLUME in options,
                            ) { onOptionChange(RemoteOption.PHONE_VOLUME, it) }
                            SettingsToggle(
                                title = "Vibrate on button press",
                                enabled = RemoteOption.VIBRATION in options,
                            ) { onOptionChange(RemoteOption.VIBRATION, it) }
                        }
                        SettingsPage.ABOUT -> {
                            AboutRow("Version") {
                                Text(BuildConfig.VERSION_NAME, color = RemoteText)
                            }
                            if (BuildConfig.GITHUB_AUTHOR.isNotEmpty()) {
                                AboutRow("Author") { GitHubAuthorBadge(BuildConfig.GITHUB_AUTHOR) }
                            }
                        }
                        SettingsPage.UPDATES -> {
                            SettingsToggle(
                                title = "Automatic Updates",
                                detail = "Check GitHub when TVControl opens and allow automatic downloads and installation.",
                                enabled = RemoteOption.AUTOMATIC_UPDATE_CHECKS in options,
                            ) { onOptionChange(RemoteOption.AUTOMATIC_UPDATE_CHECKS, it) }
                            SettingsToggle(
                                title = "Automatic Downloads",
                                detail = "Download updates on unmetered connections.",
                                enabled = RemoteOption.AUTOMATIC_UPDATE_DOWNLOADS in options,
                            ) { onOptionChange(RemoteOption.AUTOMATIC_UPDATE_DOWNLOADS, it) }
                            SettingsToggle(
                                title = "Automatic Installation",
                                detail = "Install automatically downloaded updates. Android may still ask for confirmation.",
                                enabled = RemoteOption.AUTOMATIC_UPDATE_INSTALLS in options,
                            ) { onOptionChange(RemoteOption.AUTOMATIC_UPDATE_INSTALLS, it) }
                            SettingsLink(
                                title = "Check for updates",
                                detail = when {
                                    checkingForUpdates -> "Checking GitHub…"
                                    updateCheckStatus != null -> updateCheckStatus
                                    else -> "Look for a new release now."
                                },
                                onClick = onCheckForUpdates,
                            )
                        }
                    }
                }
            }
            if (page == null) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = { restoreTarget = RestoreTarget.REMOTE }) {
                        Text("Restore defaults")
                    }
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
            }
        }
    }
    removeShortcut?.let { shortcut ->
        ConfirmShortcutRemoval(shortcut, { removeShortcut = null }) {
            onShortcutsChange(shortcuts.filterNot { it.packageName == shortcut.packageName })
            removeShortcut = null
        }
    }
    restoreTarget?.let { target ->
        val resetRemote = target == RestoreTarget.REMOTE
        AlertDialog(
            onDismissRequest = { restoreTarget = null },
            title = { Text(if (resetRemote) "Restore remote defaults?" else "Restore default shortcuts?") },
            text = {
                Text(
                    if (resetRemote) {
                        "This resets the layout and button behavior and replaces the current TV’s shortcuts with its defaults. Your paired TVs stay saved."
                    } else {
                        "This replaces your shortcuts with ${defaultShortcuts.joinToString { it.name }}."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    restoreTarget = null
                    if (resetRemote) onRestoreDefaults() else onShortcutsChange(defaultShortcuts)
                }) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = { restoreTarget = null }) { Text("Cancel") }
            },
        )
    }
}

private enum class RestoreTarget { REMOTE, SHORTCUTS }

private val CONTROL_OPTIONS = listOf(
    RemoteOption.TV_TYPE_BADGE to "Show TV type badge",
    RemoteOption.VOLUME to "Show volume buttons",
    RemoteOption.CHANNEL to "Show channel buttons",
    RemoteOption.MUTE to "Show Mute",
    RemoteOption.SOURCE to "Show Source",
    RemoteOption.SHORTCUTS to "Show app shortcuts",
    RemoteOption.MICROPHONE to "Show microphone",
)

@Composable
private fun SettingsOverview(shortcutCount: Int, connectionDescription: String, onOpen: (SettingsPage) -> Unit) {
    val summaries = mapOf(
        SettingsPage.LAYOUT to "Choose which controls appear",
        SettingsPage.SHORTCUTS to "$shortcutCount of $MAX_TV_SHORTCUTS shortcuts",
        SettingsPage.BEHAVIOR to "Phone volume keys and vibration",
        SettingsPage.DEVICES to connectionDescription,
        SettingsPage.UPDATES to "Automatic updates and manual checks",
        SettingsPage.ABOUT to "Version ${BuildConfig.VERSION_NAME}",
    )
    SettingsPage.entries.chunked(3).forEachIndexed { group, pages ->
        if (group > 0) Spacer(Modifier.height(12.dp))
        Column(
            Modifier.clip(RoundedCornerShape(18.dp))
                .background(RemoteSurface)
                .border(1.dp, RemoteBorder.copy(alpha = 0.55f), RoundedCornerShape(18.dp)),
        ) {
            pages.forEachIndexed { index, destination ->
                if (index > 0) HorizontalDivider(
                    modifier = Modifier.padding(start = 54.dp, end = 14.dp),
                    color = RemoteBorder.copy(alpha = 0.38f),
                )
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(destination) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(destination.icon, null, Modifier.size(23.dp), tint = RemoteAccent)
                    Column(Modifier.weight(1f)) {
                        Text(destination.title, color = RemoteText, style = MaterialTheme.typography.titleMedium)
                        Text(summaries.getValue(destination), color = RemoteMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = RemoteMuted)
                }
            }
        }
    }
}

private enum class SettingsPage(val title: String, val icon: ImageVector) {
    LAYOUT("Remote layout", Icons.Filled.Gamepad),
    SHORTCUTS("TV app shortcuts", Icons.Filled.Apps),
    BEHAVIOR("Button behavior", Icons.Filled.Tune),
    DEVICES("Devices", Icons.Filled.Tv),
    UPDATES("Updates", Icons.Filled.SystemUpdate),
    ABOUT("About", Icons.Filled.Info),
}

@Composable
private fun AboutRow(label: String, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = RemoteMuted, style = MaterialTheme.typography.bodyMedium)
        content()
    }
}

@Composable
private fun GitHubAuthorBadge(username: String) {
    Row(
        modifier = Modifier
            .padding(start = 12.dp)
            .clip(CircleShape)
            .background(RemoteAccent.copy(alpha = 0.10f))
            .border(1.dp, RemoteAccent.copy(alpha = 0.34f), CircleShape)
            .padding(start = 6.dp, top = 5.dp, end = 12.dp, bottom = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(26.dp).background(RemoteAccent.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_github),
                contentDescription = "GitHub",
                modifier = Modifier.size(16.dp),
                tint = RemoteAccent,
            )
        }
        Text(
            text = "@$username",
            color = RemoteText,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SettingsToggle(
    title: String,
    enabled: Boolean,
    detail: String? = null,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = enabled, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = 48.dp)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = RemoteText)
            if (detail != null) {
                Text(detail, color = RemoteMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
        Switch(checked = enabled, onCheckedChange = null)
    }
}

@Composable
private fun SettingsLink(title: String, detail: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, color = RemoteText)
            Text(detail, color = RemoteMuted, style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = RemoteMuted)
    }
}

@Composable
private fun AddTvAppForm(
    existingShortcuts: List<TvAppShortcut>,
    initialShortcut: TvAppShortcut?,
    platform: TvPlatform,
    installedApps: List<TvInstalledApp>?,
    onSave: (TvAppShortcut) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    var name by rememberSaveable(initialShortcut?.packageName) {
        mutableStateOf(initialShortcut?.name.orEmpty())
    }
    var packageField by rememberSaveable(initialShortcut?.packageName, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initialShortcut?.packageName.orEmpty()))
    }
    var iconLinkField by rememberSaveable(initialShortcut?.packageName, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue())
    }
    var iconUri by rememberSaveable(initialShortcut?.packageName) {
        mutableStateOf(initialShortcut?.iconUri)
    }
    var nameFocused by remember { mutableStateOf(false) }
    var showNameSuggestions by rememberSaveable { mutableStateOf(false) }
    var validationMessage by remember { mutableStateOf<String?>(null) }
    var iconLoadError by remember { mutableStateOf(false) }
    var processingImage by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                processingImage = true
                try {
                    iconUri = ShortcutImageDownloader.copyFromUri(context, uri)
                    iconLinkField = TextFieldValue()
                    iconLoadError = false
                } catch (_: IOException) {
                    iconLoadError = true
                } catch (_: SecurityException) {
                    iconLoadError = true
                } finally {
                    processingImage = false
                }
            }
        }
    }
    val selectedIconBitmap = rememberShortcutIconBitmap(iconUri, packageField.text.trim(), name.trim()).value

    OutlinedTextField(
        value = name,
        onValueChange = {
            name = it
            showNameSuggestions = true
            validationMessage = null
        },
        modifier = Modifier.fillMaxWidth().onFocusChanged { nameFocused = it.isFocused },
        label = { Text("App name") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
    )
    val query = name.trim()
    if (nameFocused && showNameSuggestions && query.isNotEmpty()) {
        val otherPackageIds = existingShortcuts
            .filterNot { it.packageName == initialShortcut?.packageName }
            .map { it.packageName }
            .toSet()
        shortcutSuggestions(platform, installedApps, packageField.text.trim())
            .filter { suggestion -> suggestion.matchesQuery(query) }
            .filterNot { suggestion -> suggestion.allIds.any { it in otherPackageIds } }
            .sortedByDescending { it.name.startsWith(query, ignoreCase = true) }
            .take(4)
            .forEach { app ->
                TextButton(
                    onClick = {
                        name = app.name
                        packageField = TextFieldValue(app.id)
                        showNameSuggestions = false
                        validationMessage = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ShortcutIcon(TvAppShortcut(app.name, app.id), Modifier.size(22.dp))
                    Text(app.name, modifier = Modifier.padding(start = 10.dp))
                }
            }
    }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = packageField,
        onValueChange = { entered ->
            if (entered.text != packageField.text) validationMessage = null
            packageField = entered
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(if (platform == TvPlatform.WEBOS) "webOS app ID" else "TV package ID") },
        placeholder = { Text(if (platform == TvPlatform.WEBOS) "netflix" else "com.example.tvapp") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Next,
        ),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = iconLinkField,
        onValueChange = { entered ->
            if (entered.text != iconLinkField.text) {
                validationMessage = null
            }
            iconLinkField = entered
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("HTTPS SVG / image link (optional)") },
        placeholder = { Text("https://example.com/icon.svg") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done,
        ),
    )
    Spacer(Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .background(RemoteSurface, RoundedCornerShape(14.dp))
                .border(1.dp, RemoteBorder, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (selectedIconBitmap == null) {
                Icon(Icons.Filled.Tv, contentDescription = null, tint = RemoteMuted)
            } else {
                Image(
                    bitmap = selectedIconBitmap.bitmap,
                    contentDescription = "Selected app icon",
                    colorFilter = selectedIconBitmap.tint,
                    modifier = Modifier
                        .size(46.dp)
                        .padding(3.dp),
                )
            }
        }
        Column {
            OutlinedButton(
                enabled = !processingImage,
                onClick = {
                    imagePicker.launch(arrayOf("image/png", "image/jpeg", "image/webp", "image/svg+xml", "application/xml", "text/xml"))
                },
            ) {
                Text(if (iconUri == null) "Choose image" else "Change image")
            }
            if (iconUri != null) {
                TextButton(enabled = !processingImage, onClick = {
                    iconUri = null
                    iconLinkField = TextFieldValue()
                    iconLoadError = false
                }) { Text("Remove image") }
            }
        }
    }
    if (iconLoadError) {
        Text(
            text = "Could not load the image. Choose a valid PNG, JPEG, WebP, or SVG file.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Text(
        text = if (platform == TvPlatform.WEBOS) {
            "Type the app’s name and pick it from the suggestions to fill in its webOS app ID. Apps installed on the connected TV are suggested. Choose a PNG, JPEG, WebP, or SVG file, or paste a direct HTTPS link to one."
        } else {
            "Use the package ID shown in the TV app's information screen. com.example.tvapp is only a format example. Choose a PNG, JPEG, WebP, or SVG file, or paste a direct HTTPS link to one."
        },
        color = RemoteMuted,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 8.dp),
    )
    validationMessage?.let { message ->
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    Spacer(Modifier.height(16.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onCancel) {
            Text("Cancel")
        }
        Button(
            onClick = {
                val normalizedPackage = packageField.text.trim()
                val normalizedImageUrl = iconLinkField.text.trim().takeIf(String::isNotEmpty)
                val imageUri = iconUri
                val imageUrlIsHttps = normalizedImageUrl?.let { value ->
                    val uri = value.toUri()
                    uri.scheme.equals("https", ignoreCase = true) &&
                        !uri.host.isNullOrBlank() &&
                        uri.userInfo == null
                } ?: true
                when {
                    name.isBlank() -> validationMessage = "Enter an app name."
                    platform == TvPlatform.ANDROID_TV && !PACKAGE_ID.matches(normalizedPackage) ->
                        validationMessage = "Enter a valid package ID, such as com.example.tvapp."
                    platform == TvPlatform.WEBOS && !WEBOS_APP_ID.matches(normalizedPackage) ->
                        validationMessage = "Enter a valid webOS app ID, such as netflix."
                    existingShortcuts.any {
                        it.packageName == normalizedPackage &&
                            it.packageName != initialShortcut?.packageName
                    } ->
                        validationMessage = if (platform == TvPlatform.WEBOS) {
                            "That app ID is already in your shortcuts."
                        } else {
                            "That package ID is already in your shortcuts."
                        }
                    normalizedImageUrl != null && !imageUrlIsHttps ->
                        validationMessage = "Use a complete HTTPS link to an image."
                    normalizedImageUrl != null -> scope.launch {
                        processingImage = true
                        validationMessage = null
                        try {
                            val downloadedImage =
                                ShortcutImageDownloader.download(context, normalizedImageUrl)
                            onSave(
                                TvAppShortcut(
                                    name = name.trim(),
                                    packageName = normalizedPackage,
                                    iconUri = downloadedImage,
                                ),
                            )
                        } catch (_: IOException) {
                            validationMessage =
                                "Couldn’t load a PNG, JPEG, WebP, or SVG from that HTTPS link."
                        } finally {
                            processingImage = false
                        }
                    }
                    else -> onSave(
                        TvAppShortcut(
                            name = name.trim(),
                            packageName = normalizedPackage,
                            iconUri = imageUri,
                        ),
                    )
                }
            },
            enabled = !processingImage,
        ) {
            Text(
                when {
                    !processingImage -> if (initialShortcut == null) "Add app" else "Save changes"
                    iconLinkField.text.isNotBlank() -> "Downloading…"
                    else -> "Loading image…"
                },
            )
        }
    }
}

@Composable
private fun ReorderableShortcuts(
    shortcuts: List<TvAppShortcut>,
    scrollState: ScrollState,
    viewport: Rect,
    onReorder: (List<TvAppShortcut>) -> Unit,
    onEdit: (TvAppShortcut) -> Unit,
    onRemove: (TvAppShortcut) -> Unit,
) {
    val currentOnReorder by rememberUpdatedState(onReorder)
    val density = LocalDensity.current
    val rowHeight = with(density) { ShortcutRowHeight.roundToPx().toFloat() }
    val edgeSize = with(density) { 48.dp.toPx() }
    var listTop by remember { mutableStateOf(0f) }
    var pendingOrder by remember(shortcuts) { mutableStateOf<List<TvAppShortcut>?>(null) }
    val order = pendingOrder ?: shortcuts
    var drag by remember(order) { mutableStateOf<ShortcutDrag?>(null) }
    val draggedTop = drag?.top(rowHeight, scrollState.value, order.lastIndex)
    val target = draggedTop?.let { (it / rowHeight).roundToInt() }
    val projectedOrder = drag?.let { order.moveShortcut(it.origin, checkNotNull(target)) } ?: order
    val direction = drag?.let {
        when {
            it.pointerY < viewport.top + edgeSize -> -1
            it.pointerY > viewport.bottom - edgeSize -> 1
            else -> 0
        }
    } ?: 0

    LaunchedEffect(direction, scrollState) {
        if (direction == 0) return@LaunchedEffect
        while (drag != null && scrollState.scrollBy(direction * edgeSize / 8) != 0f) {
            delay(16)
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { listTop = it.positionInRoot().y }
            .pointerInput(order, rowHeight) {
                if (order.size < 2) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val origin = (down.position.y / rowHeight).toInt()
                    if (origin !in order.indices) return@awaitEachGesture
                    val startY = down.position.y + listTop
                    val startScroll = scrollState.value
                    val firstDrag = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                    var active = ShortcutDrag(origin, startY, firstDrag.position.y + listTop, startScroll)
                    drag = active
                    try {
                        val dropped = verticalDrag(firstDrag.id) { change ->
                            active = active.copy(pointerY = change.position.y + listTop)
                            drag = active
                            change.consume()
                        }
                        if (dropped) {
                            val destination = (active.top(rowHeight, scrollState.value, order.lastIndex) / rowHeight)
                                .roundToInt()
                            if (destination != origin) {
                                val reordered = order.moveShortcut(origin, destination)
                                pendingOrder = reordered
                                currentOnReorder(reordered)
                            }
                        }
                    } finally {
                        drag = null
                    }
                }
            },
    ) {
        order.forEachIndexed { index, shortcut ->
            key(shortcut.packageName) {
                val liftedTop = draggedTop.takeIf { drag?.origin == index }
                val isDragged = liftedTop != null
                val destination = projectedOrder.indexOf(shortcut) * rowHeight
                val rowTop by animateFloatAsState(
                    targetValue = liftedTop ?: destination,
                    animationSpec = if (isDragged) snap() else spring(dampingRatio = 0.86f, stiffness = 500f),
                    label = "shortcut position",
                )
                val moveActions = if (drag != null) emptyList() else buildList {
                    if (index > 0) add(CustomAccessibilityAction("Move up") {
                        val reordered = order.moveShortcut(index, index - 1)
                        pendingOrder = reordered
                        onReorder(reordered)
                        true
                    })
                    if (index < order.lastIndex) add(CustomAccessibilityAction("Move down") {
                        val reordered = order.moveShortcut(index, index + 1)
                        pendingOrder = reordered
                        onReorder(reordered)
                        true
                    })
                }
                ShortcutSettingsRow(
                    shortcut = shortcut,
                    canOpenMenu = { drag == null },
                    modifier = Modifier
                        .zIndex(if (isDragged) 1f else 0f)
                        .graphicsLayer {
                            translationY = (liftedTop ?: rowTop) - index * rowHeight
                        }
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isDragged) RemoteSurface else Color.Transparent)
                        .semantics { customActions = moveActions },
                    onEdit = { onEdit(shortcut) },
                    onRemove = { onRemove(shortcut) },
                )
            }
        }
    }
}

private data class ShortcutDrag(
    val origin: Int,
    val startY: Float,
    val pointerY: Float,
    val startScroll: Int,
) {
    fun top(rowHeight: Float, scroll: Int, lastIndex: Int): Float =
        (origin * rowHeight + pointerY - startY + scroll - startScroll).coerceIn(0f, lastIndex * rowHeight)
}

private fun List<TvAppShortcut>.moveShortcut(from: Int, to: Int): List<TvAppShortcut> =
    toMutableList().apply { add(to, removeAt(from)) }

private val ShortcutRowHeight = 58.dp

@Composable
private fun ShortcutSettingsRow(
    shortcut: TvAppShortcut,
    canOpenMenu: () -> Boolean,
    modifier: Modifier,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(ShortcutRowHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        var menuOpen by remember { mutableStateOf(false) }
        var tapPosition by remember { mutableStateOf<Offset?>(null) }
        var rowSize by remember { mutableStateOf(IntSize.Zero) }
        Box(Modifier.weight(1f).onSizeChanged { rowSize = it }) {
            Row(
                modifier = Modifier.fillMaxWidth().fillMaxHeight()
                    .pointerInput(shortcut.packageName) {
                        awaitEachGesture {
                            tapPosition = awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial,
                            ).position
                        }
                    }
                    .clickable(
                        onClickLabel = "Manage ${shortcut.name}",
                        onClick = { if (canOpenMenu()) menuOpen = true },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShortcutIcon(shortcut, Modifier.size(36.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(shortcut.name, color = RemoteText, maxLines = 1)
                    Text(shortcut.packageName, color = RemoteMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
            if (menuOpen) {
                Box(Modifier.absoluteOffset {
                    val point = tapPosition ?: Offset(rowSize.width / 2f, rowSize.height / 2f)
                    IntOffset(point.x.roundToInt(), point.y.roundToInt())
                }.size(0.dp)) {
                    ShortcutActionsMenu(menuOpen, {
                        menuOpen = false
                        tapPosition = null
                    }, onEdit, onRemove)
                }
            }
        }
    }
}

@Composable
internal fun ShortcutActionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(16.dp),
        containerColor = RemoteSurface,
        border = BorderStroke(1.dp, RemoteBorder),
        tonalElevation = 0.dp,
    ) {
        DropdownMenuItem(text = { Text("Edit") }, onClick = { onDismiss(); onEdit() })
        DropdownMenuItem(text = { Text("Remove") }, onClick = { onDismiss(); onRemove() })
    }
}

@Composable
internal fun ConfirmShortcutRemoval(shortcut: TvAppShortcut, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove ${shortcut.name}?") },
        text = { Text("This removes the shortcut from this TV.") },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Remove") } },
    )
}

@Composable
fun ShortcutIcon(
    shortcut: TvAppShortcut,
    modifier: Modifier = Modifier,
) {
    val bitmap by rememberShortcutIconBitmap(shortcut.iconUri, shortcut.packageName, shortcut.name)
    val icon = bitmap
    if (icon == null) {
        Icon(Icons.Filled.Tv, contentDescription = null, modifier = modifier, tint = RemoteText)
    } else {
        Image(
            bitmap = icon.bitmap,
            contentDescription = null,
            modifier = modifier,
            colorFilter = icon.tint,
        )
    }
}

private val PACKAGE_ID = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
private val WEBOS_APP_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

private class ShortcutSuggestion(
    val name: String,
    val id: String,
    val title: String = name,
    val allIds: List<String> = listOf(id),
) {
    fun matchesQuery(query: String) =
        name.contains(query, ignoreCase = true) || title.contains(query, ignoreCase = true)
}

/**
 * Apps to suggest for the selected TV. A connected webOS TV suggests only the apps it has;
 * otherwise known apps with an ID on the TV's platform are suggested.
 */
private fun shortcutSuggestions(
    platform: TvPlatform,
    installedApps: List<TvInstalledApp>?,
    currentId: String,
): List<ShortcutSuggestion> {
    if (platform == TvPlatform.WEBOS && installedApps != null) {
        return installedApps.map { app ->
            ShortcutSuggestion(TvAppCatalog.match(app.id, app.title)?.name ?: app.title, app.id, app.title)
        }
    }
    return TvAppCatalog.apps.mapNotNull { app ->
        val ids = app.ids(platform)
        val id = ids.firstOrNull { it == currentId } ?: ids.firstOrNull() ?: return@mapNotNull null
        ShortcutSuggestion(app.name, id, allIds = ids)
    }
}

private data class ShortcutArtwork(val bitmap: ImageBitmap, val tint: ColorFilter?)

@Composable
private fun rememberShortcutIconBitmap(iconUri: String?, packageName: String, name: String?): State<ShortcutArtwork?> {
    val context = LocalContext.current
    val asset = shortcutIconAsset(packageName, name)
    return produceState<ShortcutArtwork?>(initialValue = null, iconUri, asset, context) {
        value = withContext(Dispatchers.IO) {
            val customIcon = iconUri?.let { reference ->
                loadIconBitmap(ShortcutImageDownloader.isRasterReference(reference)) {
                    ShortcutImageDownloader.openStoredImage(context, reference)
                }
            }
            customIcon ?: asset?.let { loadIconBitmap(false) { context.assets.open("shortcut-icons/$it") } }
        }
    }
}

private fun loadIconBitmap(raster: Boolean, openStream: () -> java.io.InputStream?): ShortcutArtwork? =
    try {
        openStream()?.use {
            val bitmap = if (raster) ShortcutImageDownloader.renderRaster(it) else ShortcutImageDownloader.renderSvg(it)
            ShortcutArtwork(bitmap.asImageBitmap(), if (raster) null else ColorFilter.tint(RemoteText))
        }
    } catch (_: IOException) {
        null
    } catch (error: SecurityException) {
        Log.w("RemoteSettings", "Shortcut icon permission is no longer available", error)
        null
    }
