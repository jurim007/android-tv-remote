package com.jurim007.androidtvremote.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URL
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ShortcutImageDownloader {
    suspend fun download(context: Context, imageUrl: String): String =
        withContext(Dispatchers.IO) {
            var currentUrl = URL(imageUrl)
            require(currentUrl.protocol.equals("https", ignoreCase = true)) {
                "Only HTTPS image links are supported."
            }

            repeat(MAX_REDIRECTS + 1) { redirect ->
                val connection = currentUrl.openConnection() as HttpsURLConnection
                try {
                    connection.connectTimeout = CONNECT_TIMEOUT_MS
                    connection.readTimeout = READ_TIMEOUT_MS
                    connection.instanceFollowRedirects = false

                    when (val status = connection.responseCode) {
                        HttpsURLConnection.HTTP_OK -> {
                            val contentLength = connection.contentLengthLong
                            if (contentLength > MAX_IMAGE_BYTES) {
                                throw IOException("The image is larger than the 5 MB limit.")
                            }
                            val bytes = connection.inputStream.use(::readImageBytes)
                            validateImage(bytes)

                            val directory = File(context.filesDir, ICON_DIRECTORY)
                            if (!directory.exists() && !directory.mkdirs()) {
                                throw IOException("Could not save the selected icon.")
                            }
                            val imageFile = File(directory, "${UUID.randomUUID()}.image")
                            imageFile.outputStream().use { it.write(bytes) }
                            return@withContext Uri.fromFile(imageFile).toString()
                        }

                        HttpsURLConnection.HTTP_MOVED_PERM,
                        HttpsURLConnection.HTTP_MOVED_TEMP,
                        HTTP_SEE_OTHER,
                        HTTP_TEMP_REDIRECT,
                        HTTP_PERM_REDIRECT,
                        -> {
                            if (redirect == MAX_REDIRECTS) {
                                throw IOException("The image link redirected too many times.")
                            }
                            val location = connection.getHeaderField("Location")
                                ?: throw IOException("The image link returned an invalid redirect.")
                            val nextUrl = URL(currentUrl, location)
                            if (!nextUrl.protocol.equals("https", ignoreCase = true)) {
                                throw IOException("Image links must remain on HTTPS.")
                            }
                            currentUrl = nextUrl
                        }

                        else -> throw IOException("The image link returned HTTP $status.")
                    }
                } finally {
                    connection.disconnect()
                }
            }
            throw IOException("Could not download the image.")
        }

    private fun readImageBytes(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        var totalBytes = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            totalBytes += count
            if (totalBytes > MAX_IMAGE_BYTES) {
                throw IOException("The image is larger than the 5 MB limit.")
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun validateImage(bytes: ByteArray) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        val pixels = options.outWidth.toLong() * options.outHeight.toLong()
        if (options.outWidth <= 0 || options.outHeight <= 0 || pixels > MAX_IMAGE_PIXELS) {
            throw IOException("The link does not contain a supported image.")
        }
    }

    private const val ICON_DIRECTORY = "shortcut-icons"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000
    private const val MAX_REDIRECTS = 4
    private const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
    private const val MAX_IMAGE_PIXELS = 40_000_000L
    private const val BUFFER_SIZE = 8 * 1024
    private const val HTTP_SEE_OTHER = 303
    private const val HTTP_TEMP_REDIRECT = 307
    private const val HTTP_PERM_REDIRECT = 308
}
