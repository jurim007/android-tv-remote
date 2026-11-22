package com.jurim007.androidtvremote.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import androidx.core.content.IntentCompat
import com.jurim007.androidtvremote.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

internal data class UpdateInstallation(
    val version: String,
    val running: Boolean = false,
    val confirmation: Intent? = null,
    val confirmationOpened: Boolean = false,
    val error: String? = null,
)

internal class AppUpdateInstaller(private val context: Context) {
    val state = current.asStateFlow()
    private val installer get() = context.packageManager.packageInstaller
    private val record get() = context.getSharedPreferences("update_installation", Context.MODE_PRIVATE)

    val dismissedVersion: String? get() = record.getString("dismissed_version", null)

    fun dismissOffer(version: String) {
        record.edit().putString("dismissed_version", version).apply()
    }

    fun restore() {
        if (current.value != null) return
        val version = record.getString("version", null) ?: return
        val id = record.getInt("session", -1)
        if (!isNewerRelease(version, BuildConfig.VERSION_NAME)) {
            cleanUp(version)
            return
        }
        val previousError = record.getString("error", null)
        if (previousError != null) {
            current.value = UpdateInstallation(version, error = previousError)
        } else if (!record.getBoolean("confirmation", false) && hasCommittedSession(id)) {
            current.value = UpdateInstallation(version, running = true)
        } else {
            abandon(id)
            fail(version, "Installation was interrupted. Tap Install update to try again.")
        }
    }

    suspend fun install(update: VerifiedUpdate, automatic: Boolean) = withContext(Dispatchers.IO) {
        check(context.packageManager.canRequestPackageInstalls())
        check(current.value?.running != true)
        val version = update.release.version
        if (!update.file.isFile || update.file.length() != update.release.size || update.file.sha256() != update.checksum) {
            throw UpdateVerificationFailed("The verified APK is missing or damaged. Download it again.")
        }

        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(update.release.size)
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(
                    if (automatic) PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                    else PackageInstaller.SessionParams.USER_ACTION_REQUIRED,
                )
            }
        }
        val id = installer.createSession(params)
        try {
            if (!record.edit().resetSession().putString("version", version).putInt("session", id).commit()) {
                throw IOException("Couldn’t save the update session.")
            }
            current.value = UpdateInstallation(version, running = true)
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, update.release.size).use { sink ->
                    update.file.inputStream().use { it.copyTo(sink) }
                    session.fsync(sink)
                }
                val callback = PendingIntent.getBroadcast(
                    context,
                    id,
                    Intent(context, UpdateInstallReceiver::class.java)
                        .setData(Uri.parse("tvcontrol-update:$id")),
                    PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
                )
                session.commit(callback.intentSender)
            }
        } catch (error: Exception) {
            abandon(id)
            fail(version, "Couldn’t start Android’s installer. Try again.")
            throw error
        }
    }

    fun onStatus(intent: Intent) {
        val id = intent.data?.schemeSpecificPart?.toIntOrNull() ?: return
        if (id != record.getInt("session", -1)) return
        val version = record.getString("version", null) ?: return
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirmation == null) {
                    abandon(id)
                    fail(version, "Android’s confirmation screen was unavailable. Try again.")
                    return
                }
                record.edit().putBoolean("confirmation", true).commit()
                current.value = UpdateInstallation(version, running = true, confirmation = confirmation)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                cleanUp(version)
                current.value = null
            }
            else -> fail(version, installationFailureMessage(status))
        }
    }

    fun confirmationOpened() {
        current.value?.let { current.value = it.copy(confirmationOpened = true) }
    }

    fun confirmationUnavailable() {
        val installation = current.value ?: return
        abandon(record.getInt("session", -1))
        fail(installation.version, "Couldn’t open Android’s confirmation screen. Try again.")
    }

    private fun cleanUp(version: String) {
        File(context.cacheDir, "updates/TVControl-v$version.apk").delete()
        record.edit().resetSession().commit()
    }

    private fun fail(version: String, message: String) {
        record.edit().resetSession().putString("version", version).putString("error", message).commit()
        current.value = UpdateInstallation(version, error = message)
    }

    private fun SharedPreferences.Editor.resetSession(): SharedPreferences.Editor =
        remove("session").remove("version").remove("error").remove("confirmation")

    private fun hasCommittedSession(id: Int): Boolean {
        val session = if (id >= 0) installer.getSessionInfo(id) else null
        return session != null && if (Build.VERSION.SDK_INT >= 31) session.isCommitted else session.isActive
    }

    private fun abandon(id: Int) {
        if (id >= 0) runCatching { installer.abandonSession(id) }
    }

    private companion object {
        val current = MutableStateFlow<UpdateInstallation?>(null)
    }
}

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppUpdateInstaller(context).onStatus(intent)
    }
}

internal fun installationFailureMessage(status: Int): String = when (status) {
    PackageInstaller.STATUS_FAILURE_ABORTED -> "Installation was canceled. You can try again."
    PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android blocked the installation. Check Android’s install settings."
    PackageInstaller.STATUS_FAILURE_STORAGE -> "There isn’t enough space to install the update."
    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "This update isn’t compatible with your Android device."
    PackageInstaller.STATUS_FAILURE_CONFLICT -> "Android found an incompatible app or signing certificate."
    PackageInstaller.STATUS_FAILURE_INVALID -> "Android rejected the APK as invalid."
    else -> "Android couldn’t install the update. Try again."
}
