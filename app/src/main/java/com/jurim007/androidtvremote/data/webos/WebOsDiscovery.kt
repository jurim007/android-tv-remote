package com.jurim007.androidtvremote.data.webos

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.jurim007.androidtvremote.domain.TvDevice
import com.jurim007.androidtvremote.domain.TvPlatform
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Finds webOS TVs on the local network with SSDP, the discovery LG's own remote app uses. */
internal class WebOsDiscovery(context: Context, private val scope: CoroutineScope) {
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
    private val mutableDevices = MutableStateFlow<List<TvDevice>>(emptyList())
    val devices: StateFlow<List<TvDevice>> = mutableDevices.asStateFlow()

    private val lock = Any()
    private var job: Job? = null
    private var socket: DatagramSocket? = null

    fun start() {
        synchronized(lock) {
            if (job != null) return
            mutableDevices.value = emptyList()
            job = scope.launch { search() }
        }
    }

    fun stop() {
        synchronized(lock) {
            job?.cancel()
            job = null
            socket?.close()
            socket = null
        }
    }

    private suspend fun search() {
        val multicastLock = wifiManager?.createMulticastLock("TVControl webOS discovery")?.apply {
            setReferenceCounted(false)
        }
        val found = linkedMapOf<String, TvDevice>()
        try {
            multicastLock?.acquire()
            val datagramSocket = DatagramSocket().apply { soTimeout = RECEIVE_TIMEOUT_MS }
            synchronized(lock) { socket = datagramSocket }
            val query = SEARCH_REQUEST.toByteArray(Charsets.US_ASCII)
            val group = InetAddress.getByName(SSDP_ADDRESS)
            val buffer = ByteArray(2048)
            var nextSearch = 0L
            while (currentCoroutineContext().isActive && synchronized(lock) { socket === datagramSocket }) {
                val now = System.currentTimeMillis()
                if (now >= nextSearch) {
                    datagramSocket.send(DatagramPacket(query, query.size, group, SSDP_PORT))
                    nextSearch = now + SEARCH_INTERVAL_MS
                }
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    datagramSocket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val host = packet.address?.hostAddress ?: continue
                if (host in found) continue
                val response = parseSearchResponse(String(packet.data, 0, packet.length, Charsets.UTF_8)) ?: continue
                val name = response.location?.let(::readFriendlyName) ?: DEFAULT_NAME
                found[host] = TvDevice(name = name, host = host, port = TvDevice.WEBOS_PORT, platform = TvPlatform.WEBOS)
                mutableDevices.value = found.values.sortedBy(TvDevice::name)
            }
        } catch (error: IOException) {
            if (synchronized(lock) { socket } != null) Log.w(TAG, "webOS discovery stopped", error)
        } finally {
            synchronized(lock) {
                socket?.close()
                socket = null
            }
            multicastLock?.takeIf { it.isHeld }?.release()
        }
    }

    /** Reads the TV's display name from its UPnP description without an HTTP client. */
    private fun readFriendlyName(location: String): String? = try {
        val uri = URI(location)
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 80
        Socket().use { socket ->
            socket.soTimeout = DESCRIPTION_TIMEOUT_MS
            socket.connect(InetSocketAddress(host, port), DESCRIPTION_TIMEOUT_MS)
            val path = uri.rawPath?.ifEmpty { "/" } ?: "/"
            socket.getOutputStream().write(
                "GET $path HTTP/1.0\r\nHost: $host:$port\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII),
            )
            val body = readLimited(socket.getInputStream()).toString(Charsets.UTF_8)
            FRIENDLY_NAME.find(body)?.groupValues?.get(1)?.let(::decodeXml)?.trim()?.takeIf(String::isNotEmpty)
        }
    } catch (error: Exception) {
        Log.d(TAG, "Could not read the webOS TV name", error)
        null
    }

    internal data class SearchResponse(val location: String?)

    companion object {
        private const val TAG = "WebOsDiscovery"
        private const val SSDP_ADDRESS = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val SERVICE_TYPE = "urn:lge-com:service:webos-second-screen:1"
        private const val SEARCH_INTERVAL_MS = 3_000L
        private const val RECEIVE_TIMEOUT_MS = 1_000
        private const val DESCRIPTION_TIMEOUT_MS = 2_000
        private const val MAX_DESCRIPTION_BYTES = 64 * 1024
        private const val DEFAULT_NAME = "LG webOS TV"
        private val FRIENDLY_NAME = Regex("<friendlyName>([^<]*)</friendlyName>")

        private val SEARCH_REQUEST = "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 2\r\n" +
            "ST: $SERVICE_TYPE\r\n\r\n"

        /** Returns the response when it comes from a webOS second-screen service. */
        internal fun parseSearchResponse(response: String): SearchResponse? {
            val lines = response.split("\r\n", "\n")
            if (lines.firstOrNull()?.startsWith("HTTP/1.1 200", ignoreCase = true) != true) return null
            val headers = lines.drop(1).mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) null else line.substring(0, separator).trim().uppercase() to line.substring(separator + 1).trim()
            }.toMap()
            val serviceType = headers["ST"].orEmpty() + " " + headers["USN"].orEmpty()
            if (!serviceType.contains(SERVICE_TYPE, ignoreCase = true)) return null
            return SearchResponse(headers["LOCATION"]?.takeIf { it.startsWith("http://", ignoreCase = true) })
        }

        private fun readLimited(input: java.io.InputStream): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (out.size() < MAX_DESCRIPTION_BYTES) {
                val read = input.read(buffer, 0, minOf(buffer.size, MAX_DESCRIPTION_BYTES - out.size()))
                if (read < 0) break
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }

        private fun decodeXml(text: String): String = text
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&amp;", "&")
    }
}
