package com.jurim007.androidtvremote.ui

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import com.jurim007.androidtvremote.domain.MAX_TV_SHORTCUTS
import com.jurim007.androidtvremote.domain.RemoteOption
import com.jurim007.androidtvremote.domain.TvAppShortcut
import com.jurim007.androidtvremote.domain.defaultTvShortcuts
import com.jurim007.androidtvremote.domain.TvConnectionPhase
import com.jurim007.androidtvremote.domain.TvConnectionState
import com.jurim007.androidtvremote.data.ShortcutImageDownloader
import com.jurim007.androidtvremote.ui.theme.RemoteAccent
import com.jurim007.androidtvremote.ui.theme.RemoteBorder
import com.jurim007.androidtvremote.ui.theme.RemoteMuted
import com.jurim007.androidtvremote.ui.theme.RemoteSurface
import com.jurim007.androidtvremote.ui.theme.RemoteText
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RemoteSettingsDialog(
    shortcuts: List<TvAppShortcut>,
    touchpadEnabled: Boolean,
    onTouchpadEnabledChange: (Boolean) -> Unit,
    options: Set<RemoteOption>,
    onOptionChange: (RemoteOption, Boolean) -> Unit,
    onRestoreDefaults: () -> Unit,
    onManageTvs: () -> Unit,
    onShortcutsChange: (List<TvAppShortcut>) -> Unit,
    connectionState: TvConnectionState,
    onDismiss: () -> Unit,
) {
    var restoreTarget by rememberSaveable { mutableStateOf<RestoreTarget?>(null) }
    var showShortcuts by rememberSaveable { mutableStateOf(false) }
    var addingShortcut by rememberSaveable { mutableStateOf(false) }
    var editingPackageName by rememberSaveable { mutableStateOf<String?>(null) }
    val editingShortcut = shortcuts.firstOrNull { it.packageName == editingPackageName }
    val scrollState = rememberScrollState()
    var viewport by remember { mutableStateOf(Rect.Zero) }
    val editing = addingShortcut || editingShortcut != null
    val goBack = {
        when {
            editing -> {
                addingShortcut = false
                editingPackageName = null
            }
            showShortcuts -> showShortcuts = false
            else -> onDismiss()
        }
    }
    LaunchedEffect(showShortcuts, editing) { scrollState.scrollTo(0) }
    Dialog(
        onDismissRequest = goBack,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
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
                .padding(22.dp)
                .onGloballyPositioned { coordinates ->
                    val topLeft = coordinates.positionInRoot()
                    viewport = Rect(
                        offset = topLeft,
                        size = androidx.compose.ui.geometry.Size(
                            coordinates.size.width.toFloat(), coordinates.size.height.toFloat(),
                        ),
                    )
                }
                .verticalScroll(scrollState),
        ) {
            Text(
                text = when {
                    editingShortcut != null -> "Edit TV app"
                    addingShortcut -> "Add TV app"
                    showShortcuts -> "TV app shortcuts"
                    else -> "Remote settings"
                },
                color = RemoteText,
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(16.dp))
            if (addingShortcut || editingShortcut != null) {
                AddTvAppForm(
                    existingShortcuts = shortcuts,
                    initialShortcut = editingShortcut,
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
            } else if (showShortcuts) {
                Text(
                    text = "Add up to eight shortcuts by app name or package ID.",
                    color = RemoteMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                if (shortcuts.isEmpty()) {
                    Text(
                        text = "No TV apps added yet.",
                        color = RemoteMuted,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    if (shortcuts.size > 1) {
                        Text("Hold and drag to reorder.", color = RemoteMuted,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    ReorderableShortcuts(
                        shortcuts = shortcuts,
                        scrollState = scrollState,
                        viewport = viewport,
                        onReorder = onShortcutsChange,
                        onEdit = { editingPackageName = it.packageName },
                        onRemove = { onShortcutsChange(shortcuts - it) },
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
                if (shortcuts != defaultTvShortcuts) {
                    TextButton(onClick = { restoreTarget = RestoreTarget.SHORTCUTS }) {
                        Text("Restore default shortcuts")
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = goBack, modifier = Modifier.align(Alignment.End)) {
                    Text("Back")
                }
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
                SettingsSection("Devices") {
                    SettingsLink("Manage TVs", connectionDescription, onManageTvs)
                }
                SettingsSection("Remote layout") {
                    NavigationStylePicker(
                        touchpadEnabled = touchpadEnabled,
                        onChange = onTouchpadEnabledChange,
                    )
                    CONTROL_OPTIONS.forEach { (option, label) ->
                        SettingsToggle(label, enabled = option in options) { onOptionChange(option, it) }
                    }
                    SettingsLink("TV app shortcuts", "${shortcuts.size} of $MAX_TV_SHORTCUTS shortcuts", {
                        showShortcuts = true
                    })
                }
                SettingsSection("Button behavior") {
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
    restoreTarget?.let { target ->
        val resetRemote = target == RestoreTarget.REMOTE
        AlertDialog(
            onDismissRequest = { restoreTarget = null },
            title = { Text(if (resetRemote) "Restore remote defaults?" else "Restore default shortcuts?") },
            text = {
                Text(
                    if (resetRemote) {
                        "This resets the layout and button behavior and replaces your shortcuts with the four defaults. Your paired TVs stay saved."
                    } else {
                        "This replaces your shortcuts with the four default apps."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    restoreTarget = null
                    if (resetRemote) onRestoreDefaults() else onShortcutsChange(defaultTvShortcuts)
                }) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = { restoreTarget = null }) { Text("Cancel") }
            },
        )
    }
}

private enum class RestoreTarget { REMOTE, SHORTCUTS }

@Composable
private fun NavigationStylePicker(touchpadEnabled: Boolean, onChange: (Boolean) -> Unit) {
    Text("Navigation style", color = RemoteText, style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        NavigationStyleCard(
            title = "Button D-pad",
            detail = "Use arrows and OK",
            icon = Icons.Filled.Gamepad,
            selected = !touchpadEnabled,
            onClick = { onChange(false) },
            modifier = Modifier.weight(1f),
        )
        NavigationStyleCard(
            title = "Swipe pad",
            detail = "Swipe to move, tap to select",
            icon = Icons.Filled.TouchApp,
            selected = touchpadEnabled,
            onClick = { onChange(true) },
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun NavigationStyleCard(
    title: String,
    detail: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(shape)
            .background(if (selected) RemoteAccent.copy(alpha = 0.13f) else RemoteSurface)
            .border(if (selected) 2.dp else 1.dp, if (selected) RemoteAccent else RemoteBorder, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) RemoteAccent else RemoteMuted,
            modifier = Modifier.size(27.dp))
        Spacer(Modifier.height(8.dp))
        Text(title, color = RemoteText, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(detail, color = RemoteMuted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    }
}

private val CONTROL_OPTIONS = listOf(
    RemoteOption.VOLUME to "Show volume buttons",
    RemoteOption.CHANNEL to "Show channel buttons",
    RemoteOption.MUTE to "Show Mute",
    RemoteOption.SOURCE to "Show Source",
    RemoteOption.SHORTCUTS to "Show app shortcuts",
)

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Text(title, color = RemoteAccent, style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    content()
    HorizontalDivider(color = RemoteBorder.copy(alpha = 0.55f))
    Spacer(Modifier.height(14.dp))
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
            .padding(vertical = 6.dp),
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
    val selectedIconBitmap = rememberShortcutIconBitmap(iconUri, packageField.text.trim()).value

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
        shortcutApps.asSequence()
            .filter { it.name.contains(query, ignoreCase = true) }
            .filterNot { app -> app.packageIds.any { it in otherPackageIds } }
            .sortedByDescending { it.name.startsWith(query, ignoreCase = true) }
            .take(4)
            .forEach { app ->
                TextButton(
                    onClick = {
                        name = app.name
                        val packageId = app.packageIds.firstOrNull { it == packageField.text.trim() }
                            ?: app.packageIds.first()
                        packageField = TextFieldValue(packageId)
                        showNameSuggestions = false
                        validationMessage = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ShortcutIcon(TvAppShortcut(app.name, app.packageIds.first()), Modifier.size(22.dp))
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
        label = { Text("TV package ID") },
        placeholder = { Text("com.example.tvapp") },
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
                if (entered.text.isNotBlank()) iconUri = null
                validationMessage = null
            }
            iconLinkField = entered
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("HTTPS SVG icon link (optional)") },
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
                    bitmap = selectedIconBitmap,
                    contentDescription = "Selected app icon",
                    colorFilter = ColorFilter.tint(RemoteText),
                    modifier = Modifier
                        .size(46.dp)
                        .padding(3.dp),
                )
            }
        }
        OutlinedButton(onClick = { imagePicker.launch(arrayOf("image/svg+xml", "application/xml", "text/xml")) }) {
            Text(if (iconUri == null) "Choose SVG" else "Change SVG")
        }
    }
    if (iconLoadError) {
        Text(
            text = "Could not load that SVG. Choose a valid SVG file.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Text(
        text = "Use the package ID shown in the TV app's information screen. com.example.tvapp is only a format example. Use an HTTPS SVG link or choose an SVG file.",
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
                    !PACKAGE_ID.matches(normalizedPackage) ->
                        validationMessage = "Enter a valid package ID, such as com.example.tvapp."
                    existingShortcuts.any {
                        it.packageName == normalizedPackage &&
                            it.packageName != initialShortcut?.packageName
                    } ->
                        validationMessage = "That package ID is already in your shortcuts."
                    normalizedImageUrl != null && !imageUrlIsHttps ->
                        validationMessage = "Use a complete HTTPS link to an SVG."
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
                                "Couldn't load a valid SVG from that HTTPS link."
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
    val rowHeight = with(LocalDensity.current) { ShortcutRowHeight.roundToPx().toFloat() }
    val edgeSize = with(LocalDensity.current) { 44.dp.toPx() }
    var listTop by remember { mutableStateOf(0f) }
    var drag by remember(shortcuts) { mutableStateOf<ShortcutDrag?>(null) }
    val target = drag?.let { (it.origin + (it.distance / rowHeight).roundToInt()).coerceIn(shortcuts.indices) }
    val displayed = drag?.let { shortcuts.moveShortcut(it.origin, checkNotNull(target)) } ?: shortcuts
    val direction = drag?.let {
        when {
            it.pointerY < viewport.top + edgeSize -> -1
            it.pointerY > viewport.bottom - edgeSize -> 1
            else -> 0
        }
    } ?: 0
    LaunchedEffect(direction, scrollState) {
        if (direction == 0) return@LaunchedEffect
        while (true) {
            val scrolled = scrollState.scrollBy(direction * edgeSize / 5)
            val active = drag ?: break
            if (scrolled == 0f) break
            drag = active.copy(distance = active.distance + scrolled)
            delay(16)
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { listTop = it.positionInRoot().y }
            .pointerInput(shortcuts, rowHeight) {
                if (shortcuts.size < 2) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDragStart = { position ->
                        val index = (position.y / rowHeight).toInt().coerceIn(shortcuts.indices)
                        drag = ShortcutDrag(index, 0f, position.y + listTop)
                    },
                    onDrag = { change, amount ->
                        drag = drag?.let {
                            it.copy(distance = it.distance + amount.y, pointerY = change.position.y + listTop)
                        }
                    },
                    onDragCancel = { drag = null },
                    onDragEnd = {
                        drag?.let {
                            val destination = (it.origin + (it.distance / rowHeight).roundToInt())
                                .coerceIn(shortcuts.indices)
                            if (destination != it.origin) {
                                currentOnReorder(shortcuts.moveShortcut(it.origin, destination))
                            }
                        }
                        drag = null
                    },
                )
            },
    ) {
        displayed.forEachIndexed { index, shortcut ->
            key(shortcut.packageName) {
                val moveActions = buildList {
                    if (index > 0) add(CustomAccessibilityAction("Move up") {
                        onReorder(shortcuts.moveShortcut(index, index - 1))
                        true
                    })
                    if (index < displayed.lastIndex) add(CustomAccessibilityAction("Move down") {
                        onReorder(shortcuts.moveShortcut(index, index + 1))
                        true
                    })
                }
                ShortcutSettingsRow(
                    shortcut = shortcut,
                    modifier = Modifier
                        .background(if (index == target) RemoteSurface else Color.Transparent)
                        .semantics { customActions = moveActions },
                    onEdit = { onEdit(shortcut) },
                    onRemove = { onRemove(shortcut) },
                )
            }
        }
    }
}

private data class ShortcutDrag(val origin: Int, val distance: Float, val pointerY: Float)

private fun List<TvAppShortcut>.moveShortcut(from: Int, to: Int): List<TvAppShortcut> =
    toMutableList().apply { add(to, removeAt(from)) }

private val ShortcutRowHeight = 58.dp

@Composable
private fun ShortcutSettingsRow(
    shortcut: TvAppShortcut,
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
        Icon(Icons.Filled.DragHandle, contentDescription = null, tint = RemoteMuted,
            modifier = Modifier.size(22.dp))
        ShortcutIcon(shortcut, Modifier.size(36.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(shortcut.name, color = RemoteText, maxLines = 1)
            Text(
                shortcut.packageName,
                color = RemoteMuted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
        }
        Row {
            TextButton(onClick = onEdit) {
                Text("Edit")
            }
            TextButton(onClick = onRemove) {
                Text("Remove")
            }
        }
    }
}

@Composable
fun ShortcutIcon(
    shortcut: TvAppShortcut,
    modifier: Modifier = Modifier,
) {
    val bitmap by rememberShortcutIconBitmap(shortcut.iconUri, shortcut.packageName)
    val icon = bitmap
    if (icon == null) {
        Icon(Icons.Filled.Tv, contentDescription = null, modifier = modifier, tint = RemoteText)
    } else {
        Image(
            bitmap = icon,
            contentDescription = null,
            modifier = modifier,
            colorFilter = ColorFilter.tint(RemoteText),
        )
    }
}

private val PACKAGE_ID = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

@Composable
private fun rememberShortcutIconBitmap(iconUri: String?, packageName: String): State<ImageBitmap?> {
    val context = LocalContext.current
    val asset = shortcutIconAssets[packageName]
    return produceState<ImageBitmap?>(initialValue = null, iconUri, asset, context) {
        value = withContext(Dispatchers.IO) {
            val customIcon = iconUri?.let { uri -> loadIconBitmap { openIconStream(context, uri.toUri()) } }
            customIcon ?: asset?.let { loadIconBitmap { context.assets.open("shortcut-icons/$it") } }
        }
    }
}

private fun loadIconBitmap(openStream: () -> java.io.InputStream?): ImageBitmap? =
    try {
        openStream()?.use { ShortcutImageDownloader.renderSvg(it).asImageBitmap() }
    } catch (_: IOException) {
        null
    } catch (error: SecurityException) {
        Log.w("RemoteSettings", "Shortcut icon permission is no longer available", error)
        null
    }

private fun openIconStream(context: Context, uri: Uri) =
    if (uri.scheme == "file") {
        uri.path?.let(::File)?.inputStream()
    } else {
        context.contentResolver.openInputStream(uri)
    }
