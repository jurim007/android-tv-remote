package com.jurim007.androidtvremote.data

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import com.jurim007.androidtvremote.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.util.Base64

internal class UpdateSignatureMismatch : IOException("The APK uses a different signing key.")
internal class UpdateVerificationFailed(message: String) : IOException(message)

internal data class AppUpdate(
    val version: String,
    val downloadUrl: String,
    val size: Long,
    val sha256: String?,
)

internal data class VerifiedUpdate(val release: AppUpdate, val file: File, val checksum: String)

internal class GitHubAppUpdates(
    private val context: Context,
    private val openConnection: ((URL) -> HttpURLConnection)? = null,
) {
    fun canDownloadAutomatically(): Boolean = unmeteredNetwork() != null

    private fun unmeteredNetwork(): Network? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork ?: return null
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return null
        return network.takeIf {
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }
    }

    suspend fun latest(): AppUpdate? = withContext(Dispatchers.IO) {
        val response = connect("https://api.github.com/repos/$REPOSITORY/releases/latest")
        try {
            if (response.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return@withContext null
            response.requireSuccess()
            val release = JSONObject(response.inputStream.readBounded(1024 * 1024))
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

    suspend fun download(update: AppUpdate, automatic: Boolean = false): VerifiedUpdate = withContext(Dispatchers.IO) {
        val network = if (automatic) unmeteredNetwork() ?: throw IOException("No unmetered connection.") else null
        val checksum = trustedChecksum(update, network)
        val folder = File(context.cacheDir, "updates").apply { mkdirs() }
        val apk = File(folder, "TVControl-v${update.version}.apk")
        val partial = File(folder, "${apk.name}.$checksum.part.apk")
        folder.listFiles()?.filter { it != apk && it != partial }?.forEach { it.delete() }
        if (apk.isFile) {
            try {
                verifyArchive(apk, update, checksum)
                return@withContext VerifiedUpdate(update, apk, checksum)
            } catch (_: UpdateVerificationFailed) {
                apk.delete()
            } catch (_: UpdateSignatureMismatch) {
                apk.delete()
            }
        }
        if (partial.length() > update.size) partial.delete()
        if (partial.length() != update.size) transfer(update, partial, network)
        try {
            verifyArchive(partial, update, checksum)
        } catch (error: IOException) {
            partial.delete()
            throw error
        }
        if (!partial.renameTo(apk)) throw IOException("Couldn't save the update.")
        VerifiedUpdate(update, apk, checksum)
    }

    private suspend fun transfer(update: AppUpdate, partial: File, network: Network?) = withContext(Dispatchers.IO) {
        val offset = partial.length()
        val response = connect(update.downloadUrl, offset, network)
        try {
            val append = response.responseCode == HttpURLConnection.HTTP_PARTIAL
            if (append) {
                if (offset == 0L || response.getHeaderField("Content-Range") !=
                    "bytes $offset-${update.size - 1}/${update.size}"
                ) {
                    partial.delete()
                    throw IOException("GitHub returned an unexpected download range.")
                }
            } else {
                if (offset > 0 && response.responseCode == 416) partial.delete()
                response.requireSuccess()
            }
            var written = if (append) offset else 0L
            response.inputStream.use { source ->
                FileOutputStream(partial, append).buffered().use { sink ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        ensureActive()
                        val count = source.read(buffer)
                        if (count == -1) break
                        written += count
                        if (written > update.size) throw UpdateVerificationFailed("The APK has an unexpected size.")
                        sink.write(buffer, 0, count)
                    }
                }
            }
            if (written != update.size) throw IOException("The update download was interrupted.")
        } catch (error: UpdateVerificationFailed) {
            partial.delete()
            throw error
        } finally {
            response.disconnect()
        }
    }

    private fun trustedChecksum(update: AppUpdate, network: Network?): String {
        val response = connect("${update.downloadUrl}.integrity", network = network)
        try {
            if (response.responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                throw UpdateVerificationFailed("This release is missing its signed checksum.")
            }
            response.requireSuccess()
            val lines = response.inputStream.readBounded(4096).trim().lines()
            val checksum = lines.firstOrNull().orEmpty()
            val releaseDigest = update.sha256
            if (lines.size != 2 || !checksum.matches(Regex("[0-9a-f]{64}")) ||
                (releaseDigest != null && releaseDigest != checksum)
            ) throw UpdateVerificationFailed("The release checksum doesn’t match.")
            val signature = try {
                Base64.getDecoder().decode(lines[1])
            } catch (_: IllegalArgumentException) {
                throw UpdateVerificationFailed("The signed release checksum is invalid.")
            }
            val certificateFactory = CertificateFactory.getInstance("X.509")
            val keys = installedSigners().map {
                certificateFactory.generateCertificate(it.toByteArray().inputStream()).publicKey
            }
            val payload = updateIntegrityPayload(context.packageName, update, checksum)
            if (!keys.any { verifyUpdateIntegrity(it, payload, signature) }) {
                throw UpdateVerificationFailed("The release checksum wasn’t signed with this app’s key.")
            }
            return checksum
        } finally {
            response.disconnect()
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyArchive(apk: File, update: AppUpdate, checksum: String) {
        if (apk.length() != update.size || apk.sha256() != checksum) {
            throw UpdateVerificationFailed("The APK is incomplete or its checksum doesn’t match.")
        }
        val archive = context.packageManager.getPackageArchiveInfo(apk.absolutePath, signingFlags)
            ?: throw UpdateVerificationFailed("Android couldn’t read the downloaded APK.")
        val versionCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        if (archive.packageName != context.packageName || archive.versionName != update.version ||
            versionCode <= BuildConfig.VERSION_CODE.toLong()
        ) throw UpdateVerificationFailed("This APK isn’t a newer version of the installed TVControl app.")
        if ((archive.applicationInfo?.minSdkVersion ?: 0) > Build.VERSION.SDK_INT) {
            throw UpdateVerificationFailed("This update requires a newer version of Android.")
        }
        val signers = archive.signers()
        if (signers.isEmpty() || signers != installedSigners()) throw UpdateSignatureMismatch()
    }

    @Suppress("DEPRECATION")
    private val signingFlags: Int
        get() = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun PackageInfo.signers() =
        (if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners else signatures).orEmpty().toSet()

    @Suppress("DEPRECATION")
    private fun installedSigners() = context.packageManager.getPackageInfo(context.packageName, signingFlags).signers()

    private fun connect(address: String, offset: Long = 0, network: Network? = null): HttpURLConnection {
        var url = URL(address)
        repeat(5) {
            if (url.protocol != "https" || url.userInfo != null || url.port !in listOf(-1, 443) ||
                url.host !in DOWNLOAD_HOSTS
            ) throw IOException("Unexpected update download destination.")
            val connection = openConnection?.invoke(url) ?: (network?.openConnection(url) ?: url.openConnection())
            val response = (connection as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "TVControl/${BuildConfig.VERSION_NAME}")
                setRequestProperty("Accept-Encoding", "identity")
                if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
            }
            try {
                if (response.responseCode !in listOf(301, 302, 303, 307, 308)) return response
                val location = response.getHeaderField("Location") ?: throw IOException("Missing update redirect.")
                url = URL(url, location)
            } catch (error: Exception) {
                response.disconnect()
                throw error
            }
            response.disconnect()
        }
        throw IOException("Too many update redirects.")
    }

    private fun HttpURLConnection.requireSuccess() {
        if (responseCode != HttpURLConnection.HTTP_OK) throw IOException("GitHub returned HTTP $responseCode.")
    }

    private companion object {
        const val REPOSITORY = "jurim007/android-tv-remote"
        const val MAX_APK_BYTES = 100L * 1024 * 1024
        val DOWNLOAD_HOSTS = setOf(
            "api.github.com", "github.com", "release-assets.githubusercontent.com",
            "objects.githubusercontent.com", "github-releases.githubusercontent.com",
        )
    }
}

private fun InputStream.readBounded(maxBytes: Int): String = use { source ->
    val bytes = source.readNBytesCompat(maxBytes + 1)
    if (bytes.size > maxBytes) throw IOException("GitHub returned too much update metadata.")
    bytes.toString(Charsets.UTF_8)
}

private fun InputStream.readNBytesCompat(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (output.size() < limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit - output.size()))
        if (count == -1) break
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

internal fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { source ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = source.read(buffer)
            if (count == -1) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun updateIntegrityPayload(packageName: String, update: AppUpdate, checksum: String): ByteArray =
    "TVControl update v1\n$packageName\n${update.version}\n${update.size}\n$checksum\n".toByteArray(Charsets.UTF_8)

internal fun verifyUpdateIntegrity(key: PublicKey, payload: ByteArray, signed: ByteArray): Boolean =
    runCatching {
        Signature.getInstance("SHA256withRSA").run {
            initVerify(key)
            update(payload)
            verify(signed)
        }
    }.getOrDefault(false)
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
