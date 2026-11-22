package com.jurim007.androidtvremote.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
import android.util.Xml
import com.caverock.androidsvg.SVG
import com.caverock.androidsvg.SVGParseException
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URL
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ShortcutImageDownloader {
    suspend fun copyFromUri(context: Context, sourceUri: Uri): String =
        withContext(Dispatchers.IO) {
            val bytes = context.contentResolver.openInputStream(sourceUri)?.use { readImageBytes(it, MAX_LOCAL_IMAGE_BYTES) }
                ?: throw IOException("Could not open the selected image.")
            validateAndStore(context, bytes)
        }

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
                            if (contentLength > MAX_LOCAL_IMAGE_BYTES) {
                                throw IOException("The image is larger than the 16 MB limit.")
                            }
                            val bytes = connection.inputStream.use { readImageBytes(it, MAX_LOCAL_IMAGE_BYTES) }
                            return@withContext validateAndStore(context, bytes)
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

    private fun validateAndStore(context: Context, bytes: ByteArray): String {
        val format = rasterBounds(bytes).outMimeType?.let(rasterFormats::get)
        if (format == null) {
            if (bytes.size > MAX_IMAGE_BYTES) throw IOException("The SVG is larger than 1 MB.")
            parseSvg(bytes)
        } else {
            decodeRaster(bytes).recycle()
        }
        return storeImage(context, bytes, format ?: "svg")
    }

    private fun storeImage(context: Context, bytes: ByteArray, extension: String): String {
        val directory = File(context.filesDir, ICON_DIRECTORY)
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Could not save the selected icon.")
        }
        val filename = "${UUID.randomUUID()}.$extension"
        val imageFile = File(directory, filename)
        try {
            imageFile.outputStream().use { it.write(bytes) }
        } catch (error: IOException) {
            imageFile.delete()
            throw error
        }
        return "$ICON_DIRECTORY/$filename"
    }

    private fun readImageBytes(input: java.io.InputStream, limit: Int = MAX_IMAGE_BYTES): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        var totalBytes = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            totalBytes += count
            if (totalBytes > limit) {
                throw IOException("The image is larger than ${limit / (1024 * 1024)} MB.")
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    internal fun openStoredImage(context: Context, reference: String): java.io.InputStream? {
        val uri = Uri.parse(reference)
        val filename = when {
            uri.scheme == null && reference.startsWith("$ICON_DIRECTORY/") ->
                reference.removePrefix("$ICON_DIRECTORY/")
            uri.scheme == "file" && uri.path?.let(::File)?.parentFile?.name == ICON_DIRECTORY ->
                uri.lastPathSegment
            uri.scheme == "content" -> return context.contentResolver.openInputStream(uri)
            else -> null
        }
        if (filename == null || !storedFilename.matches(filename)) return null
        return File(File(context.filesDir, ICON_DIRECTORY), filename).inputStream()
    }

    internal fun isRasterReference(reference: String): Boolean =
        reference.substringAfterLast('.').lowercase() in rasterFormats.values

    internal fun renderRaster(input: java.io.InputStream): Bitmap =
        decodeRaster(readImageBytes(input, MAX_LOCAL_IMAGE_BYTES))

    private fun rasterBounds(bytes: ByteArray) =
        BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, this)
        }

    private fun decodeRaster(bytes: ByteArray): Bitmap {
        val bounds = rasterBounds(bytes)
        if (bounds.outMimeType !in rasterFormats || bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("Choose a valid PNG, JPEG, or WebP image.")
        }
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest > MAX_SOURCE_DIMENSION) throw IOException("The image dimensions are too large.")
        val sample = Integer.highestOneBit(maxOf(1, longest / ICON_PIXELS))
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: throw IOException("Could not decode the image.")
    }

    fun renderSvg(input: java.io.InputStream): Bitmap {
        val svg = parseSvg(readImageBytes(input))
        val aspect = svg.documentAspectRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
        val width = (ICON_PIXELS * minOf(aspect, 1f)).roundToInt().coerceAtLeast(1)
        val height = (ICON_PIXELS / maxOf(aspect, 1f)).roundToInt().coerceAtLeast(1)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            svg.renderToCanvas(Canvas(bitmap), RectF(0f, 0f, width.toFloat(), height.toFloat()))
        }
    }

    private fun parseSvg(bytes: ByteArray): SVG {
        try {
            val parser = Xml.newPullParser()
            parser.setInput(bytes.inputStream(), null)
            while (parser.eventType != XmlPullParser.START_TAG) {
                when (parser.nextToken()) {
                    XmlPullParser.DOCDECL, XmlPullParser.END_DOCUMENT ->
                        throw IOException("Select an SVG without a document type declaration.")
                }
            }
            if (parser.name != "svg" || parser.namespace != "http://www.w3.org/2000/svg") {
                throw IOException("The file must be an SVG image.")
            }
            return SVG.getFromInputStream(bytes.inputStream())
        } catch (error: XmlPullParserException) {
            throw IOException("The file is not valid SVG.", error)
        } catch (error: SVGParseException) {
            throw IOException("The file is not valid SVG.", error)
        }
    }

    private val rasterFormats = mapOf("image/png" to "png", "image/jpeg" to "jpg", "image/webp" to "webp")
    private val storedFilename = Regex("[A-Za-z0-9_-]+[.](svg|png|jpg|webp)")
    private const val MAX_LOCAL_IMAGE_BYTES = 16 * 1024 * 1024
    private const val MAX_SOURCE_DIMENSION = 16_384
    private const val ICON_DIRECTORY = "shortcut-icons"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000
    private const val MAX_REDIRECTS = 4
    private const val MAX_IMAGE_BYTES = 1024 * 1024
    private const val ICON_PIXELS = 192
    private const val BUFFER_SIZE = 8 * 1024
    private const val HTTP_SEE_OTHER = 303
    private const val HTTP_TEMP_REDIRECT = 307
    private const val HTTP_PERM_REDIRECT = 308
}
