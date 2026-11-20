package com.jurim007.androidtvremote.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import com.jurim007.androidtvremote.domain.TvAppShortcut
import com.jurim007.androidtvremote.data.ShortcutImageDownloader
import com.jurim007.androidtvremote.ui.theme.RemoteBorder
import com.jurim007.androidtvremote.ui.theme.RemoteMuted
import com.jurim007.androidtvremote.ui.theme.RemoteSurface
import com.jurim007.androidtvremote.ui.theme.RemoteText
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RemoteSettingsDialog(
    shortcuts: List<TvAppShortcut>,
    touchpadEnabled: Boolean,
    onTouchpadEnabledChange: (Boolean) -> Unit,
    onShortcutsChange: (List<TvAppShortcut>) -> Unit,
    onDismiss: () -> Unit,
) {
    var addingShortcut by rememberSaveable { mutableStateOf(false) }
    var editingPackageName by rememberSaveable { mutableStateOf<String?>(null) }
    val editingShortcut = shortcuts.firstOrNull { it.packageName == editingPackageName }
    Dialog(
        onDismissRequest = onDismiss,
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
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = when {
                    editingShortcut != null -> "Edit TV app"
                    addingShortcut -> "Add TV app"
                    else -> "Settings"
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
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Touchpad mode", color = RemoteText)
                        Text(
                            text = "Swipe the pad to navigate; tap to select.",
                            color = RemoteMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = touchpadEnabled,
                        onCheckedChange = onTouchpadEnabledChange,
                    )
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "TV app shortcuts",
                    color = RemoteText,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Add up to four apps by their TV package ID. Icons are selected from this device. Launch commands remain mocked until TV pairing is implemented.",
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
                    shortcuts.forEach { shortcut ->
                        ShortcutSettingsRow(
                            shortcut = shortcut,
                            onEdit = { editingPackageName = shortcut.packageName },
                            onRemove = {
                                onShortcutsChange(shortcuts - shortcut)
                            },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { addingShortcut = true },
                    enabled = shortcuts.size < MAX_SHORTCUTS,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text(if (shortcuts.size < MAX_SHORTCUTS) "Add TV app" else "Four app limit reached")
                }
                Spacer(Modifier.height(12.dp))
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("Done")
                }
            }
        }
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
    var packageName by rememberSaveable(initialShortcut?.packageName) {
        mutableStateOf(initialShortcut?.packageName.orEmpty())
    }
    var imageUrl by rememberSaveable(initialShortcut?.packageName) { mutableStateOf("") }
    var iconUri by rememberSaveable(initialShortcut?.packageName) {
        mutableStateOf(initialShortcut?.iconUri)
    }
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
                    imageUrl = ""
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
    val iconBitmap by rememberShortcutIconBitmap(iconUri)
    val selectedIconBitmap = iconBitmap

    OutlinedTextField(
        value = name,
        onValueChange = { name = it; validationMessage = null },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("App name") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = packageName,
        onValueChange = { packageName = it; validationMessage = null },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("TV package ID") },
        placeholder = { Text("com.example.tvapp") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Next,
        ),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = imageUrl,
        onValueChange = {
            imageUrl = it
            if (it.isNotBlank()) iconUri = null
            validationMessage = null
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("HTTPS icon image link (optional)") },
        placeholder = { Text("https://example.com/icon.png") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
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
                    modifier = Modifier
                        .size(46.dp)
                        .padding(3.dp),
                )
            }
        }
        OutlinedButton(onClick = { imagePicker.launch(arrayOf("image/*")) }) {
            Text(if (iconUri == null) "Choose icon" else "Change icon")
        }
    }
    if (iconLoadError) {
        Text(
            text = "Could not copy that image. Please choose another one.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Text(
        text = "Use the package ID shown in the TV app's information screen. com.example.tvapp is only a format example. Choose an icon image by link or upload.",
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
                val normalizedPackage = packageName.trim()
                val normalizedImageUrl = imageUrl.trim().takeIf(String::isNotEmpty)
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
                                "Couldn't download that image. Check the HTTPS link and try again."
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
                    imageUrl.isNotBlank() -> "Downloading…"
                    else -> "Loading image…"
                },
            )
        }
    }
}

@Composable
private fun ShortcutSettingsRow(
    shortcut: TvAppShortcut,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShortcutIcon(shortcut, Modifier.size(42.dp))
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
    val context = LocalContext.current
    val bitmap by rememberShortcutIconBitmap(shortcut.iconUri)
    val loadedBitmap = bitmap
    if (loadedBitmap == null) {
        Box(
            modifier = modifier
                .background(RemoteSurface, CircleShape)
                .border(1.dp, RemoteBorder, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Tv, contentDescription = null, tint = RemoteText)
        }
    } else {
        Image(
            bitmap = loadedBitmap,
            contentDescription = shortcut.name,
            modifier = modifier,
        )
    }
}

private const val MAX_SHORTCUTS = 4
private val PACKAGE_ID = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

@Composable
private fun rememberShortcutIconBitmap(iconUri: String?): State<ImageBitmap?> {
    val context = LocalContext.current
    return produceState<ImageBitmap?>(initialValue = null, iconUri, context) {
        value = iconUri?.let { uri ->
            withContext(Dispatchers.IO) {
                loadIconBitmap(context, uri)
            }
        }
    }
}

private fun loadIconBitmap(context: Context, uriString: String): ImageBitmap? =
    try {
        val uri = uriString.toUri()
        val openStream = { openIconStream(context, uri) }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = openStream() ?: return null
        boundsStream.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > ICON_MAX_DIMENSION) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        openStream()
            ?.use { BitmapFactory.decodeStream(it, null, options) }
            ?.asImageBitmap()
    } catch (error: IOException) {
        Log.w(TAG, "Could not read the selected shortcut icon", error)
        null
    } catch (error: SecurityException) {
        Log.w(TAG, "Shortcut icon permission is no longer available", error)
        null
    }

private fun openIconStream(context: Context, uri: Uri) =
    if (uri.scheme == "file") {
        uri.path?.let(::File)?.inputStream()
    } else {
        context.contentResolver.openInputStream(uri)
    }

private const val TAG = "RemoteSettings"
private const val ICON_MAX_DIMENSION = 512
