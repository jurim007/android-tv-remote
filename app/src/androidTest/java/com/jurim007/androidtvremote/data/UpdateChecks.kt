package com.jurim007.androidtvremote.data

import android.app.Instrumentation
import android.content.Intent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

internal class UpdateChecks(private val test: Instrumentation) {
    private val context = test.targetContext
    private val fixtures = File(context.cacheDir, "updater-fixtures")
    private val apk = File(fixtures, "TVControl-v1.3.1.apk")
    private val initialChecksum = apk.sha256()
    private var responseVersion = "1.3.1"
    private var responseChecksum = initialChecksum
    private var proof = File(fixtures, "${apk.name}.integrity")
    private var candidate = apk
    private var interrupt = false
    private var corrupt = false
    private var missingProof = false
    private var resumed = false

    private val github = GitHubAppUpdates(context) { url ->
        object : HttpURLConnection(url) {
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            private val range get() = getRequestProperty("Range")
                ?.removePrefix("bytes=")?.removeSuffix("-")?.toLongOrNull() ?: 0
            override fun getResponseCode() = when {
                url.path.endsWith(".integrity") && missingProof -> 404
                url.path.endsWith(".apk") && range > 0 -> 206
                else -> 200
            }
            override fun getHeaderField(name: String): String? =
                if (name == "Content-Range" && range > 0) "bytes ${range}-${candidate.length() - 1}/${candidate.length()}" else null

            override fun getInputStream(): InputStream = when {
                url.path.endsWith("/latest") -> metadata().byteInputStream()
                url.path.endsWith(".integrity") -> proof.inputStream()
                else -> {
                    val bytes = candidate.readBytes()
                    if (corrupt) bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
                    if (range > 0) resumed = true
                    bytes.inputStream(range.toInt(), if (interrupt) 8192 else bytes.size - range.toInt())
                }
            }
        }
    }

    suspend fun verifyDownloads() {
        val folder = File(context.cacheDir, "updates")
        try {
            folder.deleteRecursively()
            responseVersion = "1.3.0"
            check(github.latest() == null)
            responseVersion = "1.3.1"
            val release = checkNotNull(github.latest())
            check(release.version == "1.3.1")
            missingProof = true
            check(runCatching { github.download(release) }.exceptionOrNull() is UpdateVerificationFailed)
            missingProof = false
            interrupt = true
            check(runCatching { github.download(release) }.exceptionOrNull() is IOException)
            check(folder.listFiles()?.single()?.length() == 8192L)
            interrupt = false
            val verified = github.download(release)
            check(resumed && verified.file.sha256() == initialChecksum)
            verified.file.delete()
            corrupt = true
            check(runCatching { github.download(release) }.exceptionOrNull() is UpdateVerificationFailed)
            check(folder.listFiles()?.isEmpty() == true)
            corrupt = false

            candidate = File(fixtures, "wrong-key/${apk.name}")
            proof = File(candidate.parentFile, "${apk.name}.integrity")
            responseChecksum = candidate.sha256()
            val wrongKeyRelease = checkNotNull(github.latest())
            check(runCatching { github.download(wrongKeyRelease) }.exceptionOrNull() is UpdateSignatureMismatch)
        } finally {
            folder.deleteRecursively()
        }
    }

    suspend fun install(automatic: Boolean) {
        val release = checkNotNull(github.latest())
        val verified = github.download(release)
        val installer = AppUpdateInstaller(context)
        installer.restore()
        installer.install(verified, automatic)
        val result = withTimeout(45_000) {
            installer.state.first { it?.confirmation != null || it?.error != null }
        }
        if (automatic) error("Expected Android to complete the self-update, but got ${result?.error ?: "confirmation"}.")
        val confirmation = checkNotNull(result?.confirmation)
        context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val failure = withTimeout(45_000) { installer.state.first { it?.error != null } }
        check(failure?.error == installationFailureMessage(android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED))
        check(verified.file.isFile)
    }

    private fun metadata(): String = JSONObject()
        .put("draft", false)
        .put("prerelease", false)
        .put("tag_name", "v$responseVersion")
        .put("assets", JSONArray().put(
            JSONObject()
                .put("name", "TVControl-v$responseVersion.apk")
                .put("state", "uploaded")
                .put("browser_download_url", "https://github.com/jurim007/android-tv-remote/releases/download/v$responseVersion/TVControl-v$responseVersion.apk")
                .put("size", candidate.length())
                .put("digest", "sha256:$responseChecksum"),
        )).toString()
}
