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
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.jurim007.androidtvremote.data.UpdateInstallation

@Composable
internal fun AppUpdateDialog(
    state: AppUpdateState,
    installation: UpdateInstallation?,
    onDownload: () -> Unit,
    onInstall: (Boolean) -> Unit,
    onConfirmationOpened: () -> Unit,
    onConfirmationUnavailable: () -> Unit,
    onDismiss: () -> Unit,
) {
    val version = state.release?.version ?: checkNotNull(installation).version
    val context = LocalContext.current
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    var canInstall by rememberSaveable(version) { mutableStateOf(context.packageManager.canRequestPackageInstalls()) }
    var permissionError by rememberSaveable(version) { mutableStateOf<String?>(null) }

    val permissionSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        canInstall = context.packageManager.canRequestPackageInstalls()
        if (canInstall) {
            permissionError = null
            onInstall(state.installRequest == true)
        } else {
            permissionError = "To install updates, allow TVControl as a source in Android settings."
        }
    }

    fun requestInstall() {
        permissionError = null
        canInstall = context.packageManager.canRequestPackageInstalls()
        if (canInstall) {
            onInstall(state.installRequest == true)
        } else {
            val settings = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                "package:${context.packageName}".toUri(),
            )
            runCatching { permissionSettings.launch(settings) }.onFailure {
                permissionError = "Couldn’t open Android’s install permission settings."
            }
        }
    }

    fun showConfirmation(confirmation: Intent) {
        runCatching { context.startActivity(confirmation) }
            .onSuccess { onConfirmationOpened() }
            .onFailure { onConfirmationUnavailable() }
    }

    LaunchedEffect(lifecycleState, state.installRequest, installation) {
        if (lifecycleState != Lifecycle.State.RESUMED) return@LaunchedEffect
        canInstall = context.packageManager.canRequestPackageInstalls()
        val confirmation = installation?.confirmation
        if (confirmation != null && !installation.confirmationOpened) {
            showConfirmation(confirmation)
        } else if (state.installRequest != null && canInstall && installation?.running != true) {
            onInstall(state.installRequest)
        }
    }

    val working = state.downloading || state.installing || installation?.running == true
    val confirmation = installation?.confirmation
    AlertDialog(
        title = { Text("TVControl $version is available") },
        text = {
            Column {
                Text(
                    permissionError ?: state.error ?: installation?.error ?: when {
                        confirmation != null -> "Android requires confirmation to finish installing this update."
                        state.downloading -> "Downloading and verifying the update…"
                        state.installing || installation?.running == true -> "Android is preparing the update…"
                        state.apk != null && !canInstall ->
                            "To install the verified update, allow TVControl as an installation source in Android settings."
                        state.apk != null -> "The verified update is ready to install."
                        state.waitingForUnmetered ->
                            "Automatic download is waiting for an unmetered connection. You can download now."
                        else -> "Download the new version from GitHub?"
                    },
                )
                if (working && confirmation == null) LinearProgressIndicator()
            }
        },
        onDismissRequest = { if (!working) onDismiss() },
        confirmButton = {
            Button(
                onClick = {
                    when {
                        confirmation != null -> showConfirmation(confirmation)
                        state.apk != null -> requestInstall()
                        else -> onDownload()
                    }
                },
                enabled = confirmation != null || !working,
            ) {
                Text(
                    when {
                        confirmation != null -> "Continue in Android"
                        state.apk == null -> "Update"
                        !canInstall -> "Open Android settings"
                        else -> "Install update"
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !working) { Text("Later") }
        },
    )
}
