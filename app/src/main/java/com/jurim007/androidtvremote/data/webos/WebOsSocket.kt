package com.jurim007.androidtvremote.data.webos

import java.io.EOFException
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONException
import org.json.JSONObject

internal class WebOsCommandException(message: String) : IOException(message)

internal class WebOsCertificateChangedException :
    CertificateException("The TV certificate changed after pairing.")

/**
 * Accepts the TV's self-signed certificate on first contact, records its fingerprint, and
 * requires the same certificate on later connections.
 */
internal class WebOsTrustManager(private val expectedFingerprint: String?) : X509TrustManager {
    @Volatile
    var receivedFingerprint: String? = null
        private set

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val certificate = chain?.firstOrNull() ?: throw CertificateException("The TV did not provide a certificate.")
        val fingerprint = fingerprint(certificate)
        if (expectedFingerprint != null && !fingerprint.equals(expectedFingerprint, ignoreCase = true)) {
            throw WebOsCertificateChangedException()
        }
        receivedFingerprint = fingerprint
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    companion object {
        fun fingerprint(certificate: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
                .joinToString(":") { "%02X".format(it) }
    }
}

internal fun webOsHttpClient(base: OkHttpClient, trustManager: WebOsTrustManager): OkHttpClient {
    val sslContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), SecureRandom()) }
    return base.newBuilder()
        .sslSocketFactory(sslContext.socketFactory, trustManager)
        // TV certificates name the manufacturer, not the TV's address; the fingerprint pins it instead.
        .hostnameVerifier { _, _ -> true }
        .build()
}

/** One WebSocket to the TV, with replies matched to requests by message ID. */
internal class WebOsSocket(
    client: OkHttpClient,
    url: String,
    private val onClosed: (WebOsSocket, Throwable) -> Unit = { _, _ -> },
) {
    private val opened = CompletableDeferred<Unit>()
    private val replies = ConcurrentHashMap<String, Channel<JSONObject>>()
    private val nextId = AtomicLong()
    private val closed = AtomicBoolean(false)

    private val webSocket: WebSocket = client.newWebSocket(
        Request.Builder().url(url).build(),
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = try {
                    JSONObject(text)
                } catch (_: JSONException) {
                    return
                }
                replies[message.optString("id")]?.trySend(message)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(NORMAL_CLOSURE, null)
                fail(EOFException("The TV closed the connection."))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                fail(EOFException("The TV closed the connection."))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                fail(t)
            }
        },
    )

    val isOpen: Boolean
        get() = opened.isCompleted && !closed.get()

    suspend fun awaitOpen(timeoutMs: Long = CONNECT_TIMEOUT_MS) {
        withTimeoutOrNull(timeoutMs) { opened.await() }
            ?: throw IOException("The TV did not answer.").also { close() }
    }

    fun sendText(text: String) {
        if (closed.get() || !webSocket.send(text)) throw IOException("The TV connection is closed.")
    }

    /** Sends [message] and returns a channel that receives every reply with [id]. */
    fun exchange(id: String, message: JSONObject): Channel<JSONObject> {
        val channel = Channel<JSONObject>(Channel.UNLIMITED)
        replies[id] = channel
        try {
            sendText(message.toString())
        } catch (error: IOException) {
            replies.remove(id)
            throw error
        }
        return channel
    }

    fun release(id: String) {
        replies.remove(id)?.close()
    }

    /** Calls an SSAP endpoint such as `audio/volumeUp` and returns the reply payload. */
    suspend fun request(
        uri: String,
        payload: JSONObject = JSONObject(),
        timeoutMs: Long = REQUEST_TIMEOUT_MS,
    ): JSONObject {
        val id = "request_${nextId.incrementAndGet()}"
        val channel = exchange(
            id,
            JSONObject().put("id", id).put("type", "request").put("uri", "ssap://$uri").put("payload", payload),
        )
        try {
            val reply = receive(channel, timeoutMs)
            if (reply.optString("type") == "error") {
                throw WebOsCommandException(reply.optString("error").ifBlank { "The TV rejected the request." })
            }
            val result = reply.optJSONObject("payload") ?: JSONObject()
            if (!result.optBoolean("returnValue", true)) {
                throw WebOsCommandException(result.optString("errorText").ifBlank { "The TV rejected the request." })
            }
            return result
        } finally {
            release(id)
        }
    }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            webSocket.cancel()
            closeReplies(EOFException("The TV connection is closed."))
        }
    }

    private fun fail(error: Throwable) {
        opened.completeExceptionally(error)
        if (closed.compareAndSet(false, true)) {
            closeReplies(error)
            onClosed(this, error)
        }
    }

    private fun closeReplies(error: Throwable) {
        replies.values.forEach { it.close(error) }
        replies.clear()
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 8_000L
        const val REQUEST_TIMEOUT_MS = 8_000L
        private const val NORMAL_CLOSURE = 1000

        suspend fun receive(channel: Channel<JSONObject>, timeoutMs: Long): JSONObject =
            withTimeoutOrNull(timeoutMs) { channel.receive() }
                ?: throw IOException("The TV did not respond in time.")

        val baseClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .pingInterval(15, TimeUnit.SECONDS)
                .build()
        }
    }
}
