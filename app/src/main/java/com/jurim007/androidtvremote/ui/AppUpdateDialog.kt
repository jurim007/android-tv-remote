package com.jurim007.androidtvremote.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File

@Composable
internal fun AppUpdateDialog(
    state: AppUpdateState,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    val release = checkNotNull(state.release)
    val context = LocalContext.current
    var installError by rememberSaveable(release.version) { mutableStateOf<String?>(null) }

    fun openInstaller(apk: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(intent)
        }.onSuccess {
            onDismiss()
        }.onFailure {
            installError = "Couldn’t open Android’s installer. Try again."
        }
    }

    val permissionSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val apk = state.apk ?: return@rememberLauncherForActivityResult
        if (context.packageManager.canRequestPackageInstalls()) {
            openInstaller(apk)
        } else {
            installError = "Allow TVControl to install apps in Android settings to continue."
        }
    }

    fun install(apk: File) {
        installError = null
        if (context.packageManager.canRequestPackageInstalls()) {
            openInstaller(apk)
        } else {
            val settings = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                "package:${context.packageName}".toUri(),
            )
            runCatching { permissionSettings.launch(settings) }.onFailure {
                installError = "Couldn’t open Android’s install permission settings."
            }
        }
    }

    var startedInstaller by rememberSaveable(release.version) { mutableStateOf(false) }
    LaunchedEffect(state.apk) {
        state.apk?.let { apk ->
            if (!startedInstaller) {
                startedInstaller = true
                install(apk)
            }
        }
    }

    AlertDialog(
        title = { Text("TVControl ${release.version} is available") },
        text = {
            Column {
                Text(
                    installError ?: state.error ?: when {
                        state.downloading -> "Downloading the update…"
                        state.apk != null -> "The update is ready to install."
                        else -> "Download the new version from GitHub and install it with Android’s installer?"
                    },
                )
                if (state.downloading) LinearProgressIndicator()
            }
        },
        onDismissRequest = { if (!state.downloading) onDismiss() },
        confirmButton = {
            Button(
                onClick = {
                    val apk = state.apk
                    if (apk == null) onDownload() else install(apk)
                },
                enabled = !state.downloading,
            ) {
                Text(if (state.apk == null) "Update" else "Install update")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.downloading) { Text("Later") }
        },
    )
}
