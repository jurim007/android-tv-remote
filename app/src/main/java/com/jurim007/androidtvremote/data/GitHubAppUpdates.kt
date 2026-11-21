package com.jurim007.androidtvremote.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import com.jurim007.androidtvremote.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class UpdateFileProvider : FileProvider()

internal class UpdateSignatureMismatch : IOException()

internal data class AppUpdate(
    val version: String,
    val downloadUrl: String,
    val size: Long,
    val sha256: String?,
)

internal class GitHubAppUpdates(private val context: Context) {
    suspend fun latest(): AppUpdate? = withContext(Dispatchers.IO) {
        val response = open("https://api.github.com/repos/$REPOSITORY/releases/latest")
        try {
            response.setRequestProperty("Accept", "application/vnd.github+json")
            if (response.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return@withContext null
            response.requireSuccess()

            val release = JSONObject(response.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            if (release.getBoolean("draft") || release.getBoolean("prerelease")) return@withContext null
            val tag = release.getString("tag_name")
            val version = tag.removePrefix("v")
            if (!isNewerRelease(version, BuildConfig.VERSION_NAME)) return@withContext null

            val name = "TVControl-v$version.apk"
            val downloadUrl = "https://github.com/$REPOSITORY/releases/download/$tag/$name"
            val assets = release.getJSONArray("assets")
            for (index in 0 until assets.length()) {
                val asset = assets.getJSONObject(index)
                val size = asset.getLong("size")
                if (asset.getString("name") == name &&
                    asset.getString("state") == "uploaded" &&
                    asset.getString("browser_download_url") == downloadUrl &&
                    size in 1..MAX_APK_BYTES
                ) {
                    val digest = asset.optString("digest").takeIf { it.startsWith("sha256:") }
                    return@withContext AppUpdate(version, downloadUrl, size, digest?.removePrefix("sha256:"))
                }
            }
            throw IOException("The latest release doesn’t have an installable TVControl APK yet.")
        } finally {
            response.disconnect()
        }
    }

    @Suppress("DEPRECATION")
    suspend fun download(update: AppUpdate): File = withContext(Dispatchers.IO) {
        val folder = File(context.cacheDir, "updates").apply { mkdirs() }
        val apk = File(folder, "TVControl-v${update.version}.apk")
        val partial = File.createTempFile("update-", ".apk", folder)
        val response = open(update.downloadUrl)
        try {
            response.requireSuccess()
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            response.inputStream.use { source ->
                partial.outputStream().buffered().use { sink ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        ensureActive()
                        val count = source.read(buffer)
                        if (count == -1) break
                        written += count
                        if (written > update.size) throw IOException("The update download has an unexpected size.")
                        digest.update(buffer, 0, count)
                        sink.write(buffer, 0, count)
                    }
                }
            }
            val checksum = digest.digest().joinToString("") { "%02x".format(it) }
            if (written != update.size || (update.sha256 != null && checksum != update.sha256)) {
                throw IOException("The update download couldn't be verified.")
            }
            val packages = context.packageManager
            val archive = packages.getPackageArchiveInfo(partial.absolutePath, PackageManager.GET_SIGNATURES)
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                archive?.longVersionCode
            } else {
                archive?.versionCode?.toLong()
            }
            if (archive?.packageName != context.packageName || archive.versionName != update.version ||
                versionCode == null || versionCode <= BuildConfig.VERSION_CODE.toLong()
            ) {
                throw IOException("The downloaded APK isn’t a newer version of TVControl.")
            }
            val installed = packages.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            val signers = archive.signatures.orEmpty().toSet()
            if (signers.isEmpty() || signers != installed.signatures.orEmpty().toSet()) {
                throw UpdateSignatureMismatch()
            }
            if (!partial.renameTo(apk)) throw IOException("Couldn't save the update.")
            folder.listFiles()?.filter { it != apk && it.extension == "apk" }?.forEach { it.delete() }
            apk
        } finally {
            partial.delete()
            response.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 15_000
        setRequestProperty("User-Agent", "TVControl/${BuildConfig.VERSION_NAME}")
    }

    private fun HttpURLConnection.requireSuccess() {
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw IOException("GitHub returned HTTP $responseCode.")
        }
    }

    private companion object {
        const val REPOSITORY = "jurim007/android-tv-remote"
        const val MAX_APK_BYTES = 100L * 1024 * 1024
    }
}

private val stableVersion = Regex("""v?((?:0|[1-9]\d*))\.((?:0|[1-9]\d*))\.((?:0|[1-9]\d*))""")

internal fun isNewerRelease(candidate: String, installed: String): Boolean {
    val newer = stableVersion.matchEntire(candidate)?.groupValues?.drop(1) ?: return false
    val current = stableVersion.matchEntire(installed)?.groupValues?.drop(1) ?: return false
    for ((left, right) in newer.zip(current)) {
        if (left.length != right.length) return left.length > right.length
        if (left != right) return left > right
    }
    return false
}
