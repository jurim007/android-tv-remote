package com.jurim007.androidtvremote.data

import android.content.Context
import android.graphics.Bitmap
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
            val bytes = context.contentResolver.openInputStream(sourceUri)?.use(::readImageBytes)
                ?: throw IOException("Could not open the selected image.")
            parseSvg(bytes)
            storeImage(context, bytes)
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
                            if (contentLength > MAX_IMAGE_BYTES) {
                                throw IOException("The image is larger than the 1 MB limit.")
                            }
                            val bytes = connection.inputStream.use(::readImageBytes)
                            parseSvg(bytes)
                            return@withContext storeImage(context, bytes)
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

    private fun storeImage(context: Context, bytes: ByteArray): String {
        val directory = File(context.filesDir, ICON_DIRECTORY)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Could not save the selected icon.")
        }
        val imageFile = File(directory, "${UUID.randomUUID()}.svg")
        imageFile.outputStream().use { it.write(bytes) }
        return Uri.fromFile(imageFile).toString()
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
                throw IOException("The image is larger than the 1 MB limit.")
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
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
